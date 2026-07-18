package org.gusta.mixology.stats;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
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
import java.util.function.Consumer;

public class MixologyStats {
    private static final long SAME_STATUS_LOG_INTERVAL_MILLIS = 5_000L;

    private final long startedAt = System.currentTimeMillis();
    private final Consumer<String> logger;
    private final AtomicInteger ordersCompleted = new AtomicInteger();
    private final AtomicInteger tripsStarted = new AtomicInteger();
    private final AtomicInteger potionsMixed = new AtomicInteger();
    private final AtomicLong estimatedProfit = new AtomicLong();
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private volatile boolean xpStarted;
    private volatile int startingHerbloreXp;
    private volatile String state = "STARTING";
    private volatile String status = "Starting";
    private volatile String targetReward = "-";
    private volatile String lastOrder = "-";
    private volatile String lastLoggedStatus = "";
    private volatile String lastPotionFinalizerFinishedMessage = "";
    private volatile String lastPotionCollectedMessage = "";
    private volatile long lastStatusLoggedAt;
    private volatile long lastPotionFinalizerFinishedAt;
    private volatile long lastPotionCollectedAt;

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
        String sanitized = sanitize(message, "");
        if (sanitized.isBlank()) {
            return;
        }

        String normalized = sanitized.toLowerCase(Locale.ROOT);
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
        return estimatedProfit.get();
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
        if (logger != null) {
            logger.accept(message);
        }
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

    private String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .replaceAll("\\s+", " ");
    }
}
