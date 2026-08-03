package org.gusta.mixology.stats;

import com.epicbot.api.gameval.InterfaceID;
import com.epicbot.api.gameval.VarPlayerID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Tile;
import org.gusta.mixology.domain.PotionProcess;
import org.gusta.mixology.domain.PotionRecipe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MixologyStats {
    private static final boolean VERBOSE_LOGGING = false;
    private static final long SAME_STATUS_LOG_INTERVAL_MILLIS = 30_000L;
    private static final int ALDARIUM_MOX_COST = 80;
    private static final int ALDARIUM_AGA_COST = 60;
    private static final int ALDARIUM_LYE_COST = 90;
    private static final int ALDARIUM_FALLBACK_PRICE = 6_000;
    private static final int ALDARIUM_LYE_TRIGGER_MIN = 4_500;
    private static final int ALDARIUM_LYE_TRIGGER_MAX = 5_000;
    private static final int CHATBOX_SCAN_ROW_Y_TOLERANCE = 6;
    private static final int CHATBOX_SCAN_RECENT_LIMIT = 40;

    private final long startedAt = System.currentTimeMillis();
    private final Consumer<String> logger;
    private final AtomicInteger ordersCompleted = new AtomicInteger();
    private final AtomicInteger tripsStarted = new AtomicInteger();
    private final AtomicInteger potionsMixed = new AtomicInteger();
    private final AtomicLong estimatedProfit = new AtomicLong();
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final ArrayDeque<String> recentResinChatboxTexts = new ArrayDeque<>();
    private volatile boolean xpStarted;
    private volatile int startingHerbloreXp;
    private volatile String state = "STARTING";
    private volatile String status = "Starting";
    private volatile String targetReward = "-";
    private volatile String lastOrder = "-";
    private volatile String lastLoggedStatus = "";
    private volatile String lastPotionFinalizerFinishedMessage = "";
    private volatile String lastPotionCollectedMessage = "";
    private volatile int lastMoxResin = -1;
    private volatile int lastAgaResin = -1;
    private volatile int lastLyeResin = -1;
    private volatile int aldariumLyeTrigger = randomAldariumLyeTrigger();
    private volatile int bankedAldarium;
    private volatile int aldariumUnitPrice = ALDARIUM_FALLBACK_PRICE;
    private volatile boolean pendingAldariumClaim;
    private volatile String pendingAldariumClaimReason = "-";
    private volatile long pendingAldariumClaimAt;
    private volatile long lastStatusLoggedAt;
    private volatile long lastPotionFinalizerFinishedAt;
    private volatile long lastPotionCollectedAt;
    private volatile long lastResinBalanceAt;
    private volatile long nextChatboxScanDiagnosticAt;

    public MixologyStats() {
        this(null);
    }

    public MixologyStats(Consumer<String> logger) {
        this.logger = logger;
    }

    public long runtimeMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    public String runtimeText() {
        long seconds = runtimeMillis() / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainingSeconds = seconds % 60L;
        return String.format("%02d:%02d:%02d", hours, minutes, remainingSeconds);
    }

    public void startExperienceIfNeeded(APIContext ctx) {
        if (xpStarted) {
            return;
        }
        synchronized (this) {
            if (!xpStarted) {
                startingHerbloreXp = ctx.skills().get(Skill.Skills.HERBLORE).getExperience();
                xpStarted = true;
            }
        }
    }

    public int herbloreXpGained(APIContext ctx) {
        startExperienceIfNeeded(ctx);
        return Math.max(0, ctx.skills().get(Skill.Skills.HERBLORE).getExperience() - startingHerbloreXp);
    }

    public long herbloreXpPerHour(APIContext ctx) {
        return perHour(herbloreXpGained(ctx));
    }

    public void setState(String state) {
        String sanitized = sanitize(state, "UNKNOWN");
        if (!sanitized.equals(this.state)) {
            debug("State transition: " + this.state + " -> " + sanitized);
        }
        this.state = sanitized;
    }

    public void setStatus(String status) {
        String sanitized = sanitize(status, "Unknown");
        this.status = sanitized;
        recordEvent(sanitized);
        logStatusIfUseful(sanitized);
    }

    public void debug(String message) {
        log("[DEBUG] " + sanitize(message, "debug"));
    }

    public void recordChatMessage(String message) {
        recordChatText(message, "chat");
    }

    public void scanVisibleChatbox(APIContext ctx) {
        if (ctx == null || ctx.widgets() == null) {
            return;
        }

        List<ChatboxTextWidget> widgets = chatboxTextWidgets(ctx);
        boolean foundCandidate = false;
        for (ChatboxTextWidget widget : widgets) {
            if (recordChatboxCandidate(widget.text())) {
                foundCandidate = true;
            }
        }

        StringBuilder row = new StringBuilder();
        int rowY = Integer.MIN_VALUE;
        for (ChatboxTextWidget widget : widgets) {
            if (rowY == Integer.MIN_VALUE || Math.abs(widget.y() - rowY) > CHATBOX_SCAN_ROW_Y_TOLERANCE) {
                if (recordChatboxCandidate(row.toString())) {
                    foundCandidate = true;
                }
                row.setLength(0);
                rowY = widget.y();
            }
            if (row.length() > 0) {
                row.append(' ');
            }
            row.append(widget.text());
        }
        if (recordChatboxCandidate(row.toString())) {
            foundCandidate = true;
        }

        if (!foundCandidate) {
            logChatboxScanDiagnostic(widgets);
        }
    }

    public void scanResinVarps(APIContext ctx) {
        if (ctx == null || ctx.vars() == null) {
            return;
        }

        int mox = safeVarp(ctx, VarPlayerID.MIXOLOGY_MOX_POINTS);
        int aga = safeVarp(ctx, VarPlayerID.MIXOLOGY_AGA_POINTS);
        int lye = safeVarp(ctx, VarPlayerID.MIXOLOGY_LYE_POINTS);
        if (mox < 0 || aga < 0 || lye < 0) {
            return;
        }
        recordResinBalance(mox, aga, lye, "varp");
    }

    private void recordChatText(String message, String source) {
        String sanitized = sanitize(stripMarkup(message), "");
        if (sanitized.isBlank()) {
            return;
        }

        String normalized = sanitized.toLowerCase(Locale.ROOT);
        if (normalized.contains("you now have")) {
            debug("Resin " + source + " candidate: " + sanitized);
        }
        if (normalized.contains("you finish") && isPotionFinalizerMessage(normalized)) {
            lastPotionFinalizerFinishedAt = System.currentTimeMillis();
            lastPotionFinalizerFinishedMessage = sanitized;
            debug("Potion finalizer chat: " + sanitized);
        }
        if (normalized.contains("you collect some") && normalized.contains("from the mixing vessel")) {
            lastPotionCollectedAt = System.currentTimeMillis();
            lastPotionCollectedMessage = sanitized;
            debug("Potion vessel collect chat: " + sanitized);
        }
        recordResinBalanceFromChat(sanitized, source);
    }

    public void recordResinBalance(int mox, int aga, int lye, String source) {
        boolean updated = false;
        boolean changed = false;
        if (mox >= 0) {
            changed = changed || lastMoxResin != mox;
            lastMoxResin = mox;
            updated = true;
        }
        if (aga >= 0) {
            changed = changed || lastAgaResin != aga;
            lastAgaResin = aga;
            updated = true;
        }
        if (lye >= 0) {
            changed = changed || lastLyeResin != lye;
            lastLyeResin = lye;
            updated = true;
        }
        if (!updated) {
            return;
        }

        lastResinBalanceAt = System.currentTimeMillis();
        if (changed) {
            debug("Resin balance from " + sanitize(source, "unknown") + ": " + resinBalanceText());
        }
        updatePendingAldariumClaim(source);
    }

    public boolean hasRecentLyeResinAbove(int threshold, long maxAgeMillis) {
        return lastLyeResin > threshold
                && lastResinBalanceAt > 0L
                && System.currentTimeMillis() - lastResinBalanceAt <= maxAgeMillis;
    }

    public boolean hasRecentLyeResinAboveAldariumTrigger(long maxAgeMillis) {
        return hasRecentLyeResinAbove(aldariumLyeTrigger, maxAgeMillis);
    }

    public boolean hasPendingAldariumClaim(int threshold) {
        return pendingAldariumClaim && lastLyeResin > threshold;
    }

    public boolean hasPendingAldariumClaim() {
        return hasPendingAldariumClaim(aldariumLyeTrigger);
    }

    public String pendingAldariumClaimText() {
        return pendingAldariumClaimReason;
    }

    public void clearPendingAldariumClaim(String reason) {
        if (!pendingAldariumClaim) {
            return;
        }
        pendingAldariumClaim = false;
        pendingAldariumClaimReason = "-";
        pendingAldariumClaimAt = 0L;
        debug("Aldarium claim trigger cleared: " + sanitize(reason, "complete"));
    }

    public int lastLyeResin() {
        return lastLyeResin;
    }

    public void recordBankedAldarium(int count, String source) {
        int normalized = Math.max(0, count);
        if (bankedAldarium != normalized) {
            bankedAldarium = normalized;
            debug("Banked Aldarium from " + sanitize(source, "bank") + ": " + bankedAldarium);
        }
    }

    public void scanOpenBankInventory(APIContext ctx) {
        if (ctx == null || !ctx.bank().isOpen()) {
            return;
        }
        recordBankedAldarium(countBankItem(ctx, "Aldarium"), "open bank scan");
    }

    public int bankedAldarium() {
        return bankedAldarium;
    }

    public String bankedAldariumText() {
        return String.valueOf(bankedAldarium);
    }

    public void setAldariumUnitPrice(int price) {
        if (price > 0) {
            aldariumUnitPrice = price;
        }
    }

    public int aldariumUnitPrice() {
        return aldariumUnitPrice;
    }

    public int estimatedClaimableAldarium() {
        if (lastLyeResin < 0) {
            return 0;
        }
        int byLye = lastLyeResin / ALDARIUM_LYE_COST;
        int byMox = lastMoxResin < 0 ? byLye : lastMoxResin / ALDARIUM_MOX_COST;
        int byAga = lastAgaResin < 0 ? byLye : lastAgaResin / ALDARIUM_AGA_COST;
        return Math.max(0, Math.min(byLye, Math.min(byMox, byAga)));
    }

    public int estimatedTotalAldarium() {
        return Math.max(0, bankedAldarium) + estimatedClaimableAldarium();
    }

    public long estimatedAldariumProfit() {
        return (long) estimatedTotalAldarium() * aldariumUnitPrice;
    }

    public int aldariumLyeTrigger() {
        return aldariumLyeTrigger;
    }

    public String aldariumTriggerText() {
        return "Lye > " + aldariumLyeTrigger;
    }

    public void rerollAldariumLyeTrigger(String reason) {
        int previous = aldariumLyeTrigger;
        aldariumLyeTrigger = randomAldariumLyeTrigger();
        debug("New Aldarium Lye trigger=" + aldariumLyeTrigger
                + " range=" + ALDARIUM_LYE_TRIGGER_MIN + "-" + ALDARIUM_LYE_TRIGGER_MAX
                + " previous=" + previous
                + " reason=" + sanitize(reason, "unknown"));
    }

    public String resinBalanceText() {
        return "Mox=" + resinValueText(lastMoxResin)
                + ", Aga=" + resinValueText(lastAgaResin)
                + ", Lye=" + resinValueText(lastLyeResin);
    }

    public boolean hasPotionFinalizerFinishedSince(long sinceMillis) {
        return lastPotionFinalizerFinishedAt >= sinceMillis;
    }

    public boolean hasPotionFinalizerFinishedSince(long sinceMillis, PotionRecipe recipe) {
        return hasPotionFinalizerFinishedSince(sinceMillis)
                && chatMessageContainsRecipe(lastPotionFinalizerFinishedMessage, recipe);
    }

    public boolean hasPotionFinalizerFinishedSince(long sinceMillis, PotionRecipe recipe, PotionProcess process) {
        return hasPotionFinalizerFinishedSince(sinceMillis, recipe)
                && chatMessageContainsProcess(lastPotionFinalizerFinishedMessage, process);
    }

    public String lastPotionFinalizerFinishedMessage() {
        return lastPotionFinalizerFinishedMessage;
    }

    public long lastPotionFinalizerFinishedAt() {
        return lastPotionFinalizerFinishedAt;
    }

    public boolean hasPotionCollectedSince(long sinceMillis) {
        return lastPotionCollectedAt >= sinceMillis;
    }

    public boolean hasPotionCollectedSince(long sinceMillis, PotionRecipe recipe) {
        return hasPotionCollectedSince(sinceMillis)
                && chatMessageContainsRecipe(lastPotionCollectedMessage, recipe);
    }

    public String lastPotionCollectedMessage() {
        return lastPotionCollectedMessage;
    }

    public void snapshot(APIContext ctx, String reason) {
        if (ctx == null) {
            debug("Snapshot(" + reason + "): ctx=null");
            return;
        }
        debug("Snapshot(" + reason + "): state=" + state
                + " status='" + status + "'"
                + " loc=" + locationText(ctx)
                + " moving=" + ctx.localPlayer().isMoving()
                + " anim=" + ctx.localPlayer().isAnimating()
                + " bankOpen=" + ctx.bank().isOpen()
                + " geOpen=" + ctx.grandExchange().isOpen()
                + " menuOpen=" + ctx.menu().isOpen()
                + " itemSelected=" + ctx.inventory().isItemSelected()
                + " dialogueOpen=" + ctx.dialogues().isDialogueOpen()
                + " chatOpen=" + ctx.dialogues().isChatOpen()
                + " canContinue=" + ctx.dialogues().canContinue()
                + " invCount=" + ctx.inventory().getCount()
                + " inv=" + itemSummary(ctx.inventory().getItems(), 8));
    }

    public void setTargetReward(String targetReward) {
        this.targetReward = sanitize(targetReward, "-");
    }

    public void setLastOrder(String lastOrder) {
        this.lastOrder = sanitize(lastOrder, "-");
    }

    public void recordTripStarted() {
        tripsStarted.incrementAndGet();
    }

    public void recordPotionMixed() {
        potionsMixed.incrementAndGet();
    }

    public void recordOrdersCompleted(int count) {
        ordersCompleted.addAndGet(Math.max(1, count));
    }

    public void addEstimatedProfit(long value) {
        estimatedProfit.addAndGet(Math.max(0L, value));
    }

    public String state() {
        return state;
    }

    public String status() {
        return status;
    }

    public String targetReward() {
        return targetReward;
    }

    public String lastOrder() {
        return lastOrder;
    }

    public int ordersCompleted() {
        return ordersCompleted.get();
    }

    public int tripsStarted() {
        return tripsStarted.get();
    }

    public int potionsMixed() {
        return potionsMixed.get();
    }

    public long estimatedProfit() {
        return estimatedProfit.get() + estimatedAldariumProfit();
    }

    public long estimatedProfitPerHour() {
        return perHour(estimatedProfit());
    }

    public long progressScore() {
        return ordersCompleted.get()
                + tripsStarted.get()
                + potionsMixed.get()
                + estimatedProfit.get();
    }

    public List<String> recentEvents(int max) {
        synchronized (events) {
            int limit = Math.max(0, max);
            int skip = Math.max(0, events.size() - limit);
            List<String> result = new ArrayList<>(Math.min(limit, events.size()));
            int index = 0;
            for (String event : events) {
                if (index++ >= skip) {
                    result.add(event);
                }
            }
            return result;
        }
    }

    private long perHour(long amount) {
        long runtime = Math.max(1L, runtimeMillis());
        return Math.round(amount * 3_600_000.0D / runtime);
    }

    private boolean isPotionFinalizerMessage(String normalized) {
        return normalized.contains("concentrat")
                || normalized.contains("homogenis")
                || normalized.contains("homogeniz")
                || normalized.contains("crystallis")
                || normalized.contains("crystalliz");
    }

    private void recordResinBalanceFromChat(String message, String source) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (recordSlashResinBalanceFromChat(message, source)) {
            return;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        if (!normalized.contains("resin")
                || (!normalized.contains("mox")
                && !normalized.contains("aga")
                && !normalized.contains("lye"))) {
            return;
        }

        int mox = extractResinValue(message, "mox");
        int aga = extractResinValue(message, "aga");
        int lye = extractResinValue(message, "lye");
        if (lye < 0) {
            lye = extractResinValue(message, "red");
        }
        if (mox >= 0 || aga >= 0 || lye >= 0) {
            recordResinBalance(mox, aga, lye, source);
        }
    }

    private boolean recordSlashResinBalanceFromChat(String message, String source) {
        String stripped = stripMarkup(message);
        String normalized = stripped.toLowerCase(Locale.ROOT);
        int marker = normalized.lastIndexOf("you now have");
        if (marker < 0) {
            return false;
        }

        String tail = stripped.substring(marker);
        Matcher matcher = Pattern.compile(
                "\\+?\\s*([0-9][0-9,\\s]*)\\s*/\\s*\\+?\\s*([0-9][0-9,\\s]*)\\s*/\\s*\\+?\\s*([0-9][0-9,\\s]*)",
                Pattern.CASE_INSENSITIVE).matcher(tail);
        if (!matcher.find()) {
            debug("Resin slash chat did not match numbers: " + sanitize(tail, "unknown"));
            return false;
        }

        int mox = parseChatNumber(matcher.group(1));
        int aga = parseChatNumber(matcher.group(2));
        int lye = parseChatNumber(matcher.group(3));
        if (mox < 0 || aga < 0 || lye < 0) {
            return false;
        }

        recordResinBalance(mox, aga, lye, source);
        return true;
    }

    private boolean recordChatboxCandidate(String text) {
        String sanitized = sanitize(stripMarkup(text), "");
        if (sanitized.isBlank()
                || !sanitized.toLowerCase(Locale.ROOT).contains("you now have")) {
            return false;
        }

        String key = normalizeChatboxScanKey(sanitized);
        if (key.isBlank() || recentResinChatboxTexts.contains(key)) {
            return true;
        }

        recentResinChatboxTexts.addLast(key);
        while (recentResinChatboxTexts.size() > CHATBOX_SCAN_RECENT_LIMIT) {
            recentResinChatboxTexts.removeFirst();
        }

        recordChatText(sanitized, "chatbox");
        return true;
    }

    private List<ChatboxTextWidget> chatboxTextWidgets(APIContext ctx) {
        List<ChatboxTextWidget> texts = new ArrayList<>();
        for (WidgetChild widget : ctx.widgets().getAllChildren(widget -> widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0)) {
            if (!isChatboxTextWidget(widget)) {
                continue;
            }

            String text = widgetText(widget);
            if (text.isBlank()) {
                continue;
            }
            texts.add(new ChatboxTextWidget(text, widget.getAbsoluteX(), widget.getAbsoluteY(), widgetSummary(widget)));
        }
        texts.sort((left, right) -> {
            int y = Integer.compare(left.y(), right.y());
            return y != 0 ? y : Integer.compare(left.x(), right.x());
        });
        return texts;
    }

    private boolean isChatboxTextWidget(WidgetChild widget) {
        if (widget == null || widget.getGroup() == null) {
            return false;
        }
        if (widget.getGroup().getIndex() != InterfaceID.CHATBOX) {
            return false;
        }
        int y = widget.getAbsoluteY();
        return y >= 330 && y <= 1020;
    }

    private void logChatboxScanDiagnostic(List<ChatboxTextWidget> widgets) {
        long now = System.currentTimeMillis();
        if (now < nextChatboxScanDiagnosticAt) {
            return;
        }
        nextChatboxScanDiagnosticAt = now + 15_000L;

        StringBuilder sample = new StringBuilder();
        int included = 0;
        for (int i = widgets.size() - 1; i >= 0 && included < 8; i--) {
            ChatboxTextWidget widget = widgets.get(i);
            if (sample.length() > 0) {
                sample.append(" | ");
            }
            sample.append(shortText(widget.summary(), 140));
            included++;
        }
        debug("Resin chatbox scan: no 'You now have' widget found; widgets="
                + widgets.size()
                + " sample="
                + (sample.length() == 0 ? "none" : sample));
    }

    private String normalizeChatboxScanKey(String text) {
        return text == null ? "" : stripMarkup(text)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9/,.]", "")
                .trim();
    }

    private void updatePendingAldariumClaim(String source) {
        if (lastLyeResin < 0) {
            return;
        }
        int trigger = aldariumLyeTrigger;
        if (lastLyeResin > trigger) {
            String reason = sanitize(source, "unknown")
                    + " Lye trigger " + resinBalanceText()
                    + " threshold=" + trigger;
            boolean shouldLog = !pendingAldariumClaim || !reason.equals(pendingAldariumClaimReason);
            pendingAldariumClaim = true;
            pendingAldariumClaimAt = System.currentTimeMillis();
            pendingAldariumClaimReason = reason;
            if (shouldLog) {
                debug("Aldarium claim trigger armed: " + pendingAldariumClaimReason);
            }
            return;
        }
        clearPendingAldariumClaim("Lye resin below trigger "
                + aldariumTriggerText()
                + ": " + resinBalanceText());
    }

    private static int randomAldariumLyeTrigger() {
        return ThreadLocalRandom.current().nextInt(
                ALDARIUM_LYE_TRIGGER_MIN,
                ALDARIUM_LYE_TRIGGER_MAX + 1);
    }

    private int countBankItem(APIContext ctx, String itemName) {
        if (ctx == null || !ctx.bank().isOpen()) {
            return 0;
        }

        int total = 0;
        for (ItemWidget item : ctx.bank().getItems()) {
            if (item == null || !itemNameMatches(item.getName(), itemName)) {
                continue;
            }
            total += Math.max(0, item.getStackSize());
        }

        int apiCount = 0;
        try {
            apiCount = Math.max(0, ctx.bank().getCount(itemName));
        } catch (RuntimeException ignored) {
            // Direct item scan above is the primary path.
        }
        return Math.max(total, apiCount);
    }

    private boolean itemNameMatches(String actualName, String expectedName) {
        return normalizeItemName(actualName).equals(normalizeItemName(expectedName));
    }

    private String normalizeItemName(String value) {
        return value == null
                ? ""
                : value.replaceAll("<[^>]+>", " ")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9() ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private int safeVarp(APIContext ctx, int varpId) {
        try {
            return ctx.vars().getVarp(varpId);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private int extractResinValue(String message, String label) {
        int afterLabel = extractResinValueByRegex(message,
                "\\b" + label + "(?:\\s+resin)?\\s*(?::|=|is|are)\\s*([0-9][0-9,]*)");
        if (afterLabel >= 0) {
            return afterLabel;
        }
        int afterShortLabel = extractResinValueByRegex(message,
                "\\b" + label + "\\s+([0-9][0-9,]*)\\b");
        if (afterShortLabel >= 0) {
            return afterShortLabel;
        }
        int afterResinLabel = extractResinValueByRegex(message,
                "\\b" + label + "\\s+resin\\s+([0-9][0-9,]*)\\b");
        if (afterResinLabel >= 0) {
            return afterResinLabel;
        }
        return extractResinValueByRegex(message,
                "([0-9][0-9,]*)\\D{0,16}\\b" + label + "(?:\\s+resin)?\\b");
    }

    private int extractResinValueByRegex(String message, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(message);
        if (!matcher.find()) {
            return -1;
        }
        return parseChatNumber(matcher.group(1));
    }

    private int parseChatNumber(String value) {
        if (value == null) {
            return -1;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private String stripMarkup(String value) {
        return value == null ? "" : value.replace("<br>", " ").replaceAll("<[^>]+>", "");
    }

    private String resinValueText(int value) {
        return value < 0 ? "?" : Integer.toString(value);
    }

    private boolean chatMessageContainsRecipe(String message, PotionRecipe recipe) {
        if (recipe == null || message == null || message.isBlank()) {
            return false;
        }
        String normalizedMessage = normalizeChatToken(message);
        return normalizedMessage.contains(normalizeChatToken(recipe.displayName()))
                || normalizedMessage.contains(normalizeChatToken(recipe.code()));
    }

    private boolean chatMessageContainsProcess(String message, PotionProcess process) {
        if (process == null || message == null || message.isBlank()) {
            return false;
        }
        String normalizedMessage = normalizeChatToken(message);
        if (process == PotionProcess.CONCENTRATE) {
            return normalizedMessage.contains("concentrat");
        }
        if (process == PotionProcess.HOMOGENISE) {
            return normalizedMessage.contains("homogenis")
                    || normalizedMessage.contains("homogeniz");
        }
        if (process == PotionProcess.CRYSTALISE) {
            return normalizedMessage.contains("crystallis")
                    || normalizedMessage.contains("crystalliz");
        }
        return false;
    }

    private String normalizeChatToken(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void recordEvent(String value) {
        String event = runtimeText() + " | " + sanitize(value, "event");
        synchronized (events) {
            events.addLast(event);
            while (events.size() > 80) {
                events.removeFirst();
            }
        }
    }

    private void logStatusIfUseful(String sanitized) {
        long now = System.currentTimeMillis();
        if (!sanitized.equals(lastLoggedStatus)
                || now - lastStatusLoggedAt >= SAME_STATUS_LOG_INTERVAL_MILLIS) {
            log("[STATUS] " + sanitized);
            lastLoggedStatus = sanitized;
            lastStatusLoggedAt = now;
        }
    }

    private void log(String message) {
        if (logger != null && shouldEmitLog(message)) {
            logger.accept(message);
        }
    }

    private boolean shouldEmitLog(String message) {
        if (VERBOSE_LOGGING) {
            return true;
        }
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalized = message.toLowerCase(Locale.ROOT);

        if (containsAny(normalized,
                "state transition",
                "error",
                "failed",
                "blocked",
                "missing",
                "invalid",
                "corrupt",
                "retry",
                "watchdog",
                "logout",
                "stopped",
                "started")) {
            return true;
        }

        if (containsAny(normalized,
                "aldarium",
                "resin chat",
                "resin balance",
                "restock",
                "grand exchange",
                "ge ",
                "buying",
                "offer",
                "collecting ge",
                "price warning",
                "bank check ok",
                "bank has no",
                "banking carried",
                "no banked")) {
            return true;
        }

        if (containsAny(normalized,
                "hopper low",
                "hopper already capped",
                "hopper has no space",
                "live hopper stock confirmed",
                "final hopper load",
                "no stored paste",
                "not enough paste",
                "not enough resin")) {
            return true;
        }

        if (containsAny(normalized,
                "read 3 complete mixology order",
                "delivering",
                "depositing 3",
                "conveyor accepted",
                "rewarded",
                "orders completed",
                "order cycle paused")) {
            return true;
        }

        if (containsAny(normalized,
                "minigame teleport",
                "charter",
                "members world",
                "f2p world",
                "travel loadout ready",
                "requirements look ok",
                "requires 60 herblore")) {
            return true;
        }

        return false;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String locationText(APIContext ctx) {
        Tile tile = ctx.localPlayer().getLocation();
        if (tile == null) {
            return "unknown";
        }
        return tile.getX() + "," + tile.getY() + "," + tile.getPlane();
    }

    private String itemSummary(Iterable<ItemWidget> items, int maxItems) {
        StringBuilder summary = new StringBuilder();
        int count = 0;
        for (ItemWidget item : items) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            if (count > 0) {
                summary.append(", ");
            }
            summary.append(item.getName()).append(" x").append(Math.max(1, item.getStackSize()));
            count++;
            if (count >= maxItems) {
                summary.append(", ...");
                break;
            }
        }
        return count == 0 ? "empty" : summary.toString();
    }

    private String widgetText(WidgetChild widget) {
        if (widget == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (widget.getText() != null) {
            text.append(widget.getText());
        }
        if (widget.getRawText() != null && !widget.getRawText().equals(widget.getText())) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(widget.getRawText());
        }
        return sanitize(stripMarkup(text.toString()), "");
    }

    private String widgetSummary(WidgetChild widget) {
        if (widget == null) {
            return "null";
        }
        return "group=" + (widget.getGroup() == null ? -1 : widget.getGroup().getIndex())
                + ", child=" + widget.getChildId()
                + ", index=" + widget.getIndex()
                + ", loc=" + widget.getAbsoluteX() + "," + widget.getAbsoluteY()
                + ", size=" + widget.getWidth() + "x" + widget.getHeight()
                + ", text='" + shortText(widgetText(widget), 70) + "'";
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String compact = sanitize(value, "");
        if (compact.length() <= maxChars) {
            return compact;
        }
        return compact.substring(0, Math.max(1, maxChars - 3)) + "...";
    }

    private String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("<[^>]+>", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static class ChatboxTextWidget {
        private final String text;
        private final int x;
        private final int y;
        private final String summary;

        private ChatboxTextWidget(String text, int x, int y, String summary) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.summary = summary;
        }

        private String text() {
            return text;
        }

        private int x() {
            return x;
        }

        private int y() {
            return y;
        }

        private String summary() {
            return summary;
        }
    }
}
