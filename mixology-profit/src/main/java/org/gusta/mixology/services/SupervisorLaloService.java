package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.stats.MixologyStats;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

public class SupervisorLaloService {
    private static final int LALO_ID = 13920;
    private static final Tile LALO_TILE = new Tile(1394, 9314, 0);
    private static final Path AUTHORISED_PLAYERS_FILE = Path.of(
            System.getProperty("user.home"),
            ".mixology-profit-lalo-authorised.txt"
    );

    private final MixologyStats stats;
    private boolean authorisedThisSession;
    private boolean awaitingDialogueCompletion;
    private boolean sawDialogueAfterInteraction;
    private long lastLaloInteractionAt;
    private long lastDialogueAt;
    private long nextDiagnosticAt;
    private long nextFoundDiagnosticAt;

    public SupervisorLaloService(MixologyStats stats) {
        this.stats = stats;
    }

    public boolean isAuthorised() {
        return authorisedThisSession;
    }

    public void assumeAuthorised(APIContext ctx, String reason) {
        if (authorisedThisSession) {
            return;
        }
        authorisedThisSession = true;
        awaitingDialogueCompletion = false;
        persistAuthorisedPlayer(ctx);
        stats.setStatus("Supervisor Lalo authorisation already complete: " + reason);
    }

    public boolean ensureAuthorised(APIContext ctx) {
        if (authorisedThisSession) {
            return true;
        }
        if (hasPersistedAuthorisation(ctx)) {
            authorisedThisSession = true;
            stats.setStatus("Supervisor Lalo authorisation remembered for this player");
            return true;
        }

        if (handleDialogue(ctx)) {
            return false;
        }

        if (awaitingDialogueCompletion) {
            long now = System.currentTimeMillis();
            if (sawDialogueAfterInteraction && !hasActiveDialogue(ctx) && now - lastDialogueAt > 900L) {
                return markAuthorised(ctx);
            }
            if (!sawDialogueAfterInteraction && now - lastLaloInteractionAt > 2400L) {
                awaitingDialogueCompletion = false;
                stats.setStatus("Supervisor Lalo did not open dialogue; retrying");
                Time.sleep(350, 600);
            } else {
                stats.setStatus("Waiting for Supervisor Lalo authorisation dialogue");
                Time.sleep(500, 800);
            }
            return false;
        }

        NPC lalo = findLalo(ctx);
        if (lalo != null && lalo.isValid() && lalo.tileDistanceTo(ctx) <= 8) {
            logFoundLalo(ctx, lalo);
            stats.setStatus("Requesting Supervisor Lalo authorisation: " + describeLalo(ctx, lalo));
            ctx.camera().turnTo(lalo);
            boolean clicked = selectTalkFromOpenMenu(ctx, lalo)
                    || lalo.interact("Talk-to", "Supervisor Lalo")
                    || lalo.interact("Talk-to")
                    || lalo.interactMatch("Talk-to")
                    || ctx.menu().interact("Talk-to", "Supervisor Lalo", lalo, true)
                    || ctx.menu().interact("Talk-to", lalo, true)
                    || ctx.menu().interact("Talk-to", lalo, false);
            if (clicked) {
                markLaloInteraction();
                Time.sleep(900, 1500,
                        () -> ctx.dialogues().isDialogueOpen()
                                || ctx.dialogues().canContinue()
                                || hasDialogueOptions(ctx)
                                || ctx.localPlayer().isMoving(), 100);
                return false;
            }

            stats.setStatus("Could not click Supervisor Lalo: " + describeLalo(ctx, lalo));
            Time.sleep(650, 950);
            return false;
        }

        if (ctx.localPlayer().isMoving()) {
            stats.setStatus("Walking to Supervisor Lalo");
            Time.sleep(700, 1000);
            return false;
        }

        logMissingLalo(ctx);
        stats.setStatus("Walking to Supervisor Lalo tile 1394,9314,0");
        ctx.walking().walkTo(LALO_TILE);
        Time.sleep(900, 1400,
                () -> LALO_TILE.tileDistanceTo(ctx) <= 6 || ctx.localPlayer().isMoving(), 100);
        return false;
    }

    private boolean handleDialogue(APIContext ctx) {
        if (ctx.dialogues().canContinue()) {
            markDialogueSeen();
            stats.setStatus("Continuing Supervisor Lalo dialogue");
            if (!ctx.dialogues().selectContinue()) {
                ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            }
            Time.sleep(450, 800);
            return true;
        }

        List<WidgetChild> options = ctx.dialogues().getOptions();
        String[] preferredOptions = {
                "work it out",
                "sure i can work",
                "i'm sure",
                "i am sure",
                "skip",
                "yes",
                "authorise",
                "authorize",
                "permission",
                "allowed",
                "start",
                "begin",
                "help",
                "mixology",
                "potion",
                "ok",
                "okay"
        };
        for (String option : preferredOptions) {
            if (selectDialogueOption(ctx, option, false, "Selecting Supervisor Lalo option")) {
                return true;
            }
        }

        if (options != null && !options.isEmpty()) {
            return selectSafeFallbackOption(ctx, options);
        }

        if (ctx.dialogues().isDialogueOpen()) {
            markDialogueSeen();
            stats.setStatus("Supervisor Lalo dialogue open without options; pressing space");
            ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            Time.sleep(450, 800);
            return true;
        }

        return false;
    }

    private boolean selectDialogueOption(APIContext ctx, String expected, boolean exact, String reason) {
        String normalizedExpected = normalizeDialogueText(expected);
        for (WidgetChild option : ctx.dialogues().getOptions()) {
            String text = normalizeDialogueText(option.getText());
            String rawText = normalizeDialogueText(option.getRawText());
            if (!matchesDialogueOption(text, normalizedExpected, exact)
                    && !matchesDialogueOption(rawText, normalizedExpected, exact)) {
                continue;
            }

            markDialogueSeen();
            stats.setStatus(reason + ": " + expected);
            if (clickWidgetCenter(ctx, option)
                    || option.click(false)
                    || ctx.dialogues().selectOption(candidate -> matchesDialogueOption(
                    normalizeDialogueText(candidate),
                    normalizedExpected,
                    exact))) {
                Time.sleep(650, 1000);
                return true;
            }

            if (normalizedExpected.contains("work it out")) {
                ctx.keyboard().sendKey(KeyEvent.VK_2);
                Time.sleep(650, 1000);
                return true;
            }
        }

        if (!exact && ctx.dialogues().hasOptionContaining(expected)) {
            markDialogueSeen();
            stats.setStatus(reason + " containing: " + expected);
            if (ctx.dialogues().selectOption(text -> matchesDialogueOption(
                    normalizeDialogueText(text),
                    normalizedExpected,
                    false))) {
                Time.sleep(550, 900);
                return true;
            }
            if (normalizedExpected.contains("work it out")) {
                ctx.keyboard().sendKey(KeyEvent.VK_2);
                Time.sleep(650, 1000);
                return true;
            }
        }

        return false;
    }

    private boolean selectSafeFallbackOption(APIContext ctx, List<WidgetChild> options) {
        for (WidgetChild option : options) {
            String text = normalizeDialogueText(widgetText(option));
            if (text.isBlank() || isNegativeOption(text) || isTutorialOption(text)) {
                continue;
            }

            markDialogueSeen();
            stats.setStatus("Selecting safe Supervisor Lalo option: " + shortText(text, 60));
            if (clickWidgetCenter(ctx, option)
                    || option.click(false)
                    || ctx.dialogues().selectOption(candidate -> !isNegativeOption(candidate)
                    && !isTutorialOption(candidate))) {
                Time.sleep(650, 1000);
                return true;
            }
        }

        markDialogueSeen();
        stats.setStatus("Supervisor Lalo options need mapping: " + optionSummary(options));
        Time.sleep(700, 1100);
        return true;
    }

    private boolean clickWidgetCenter(APIContext ctx, WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return false;
        }

        Point point = widget.getCentralPoint();
        return point != null && ctx.mouse().click(point, false);
    }

    private boolean isVisibleWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0;
    }

    private boolean matchesDialogueOption(String candidate, String expected, boolean exact) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return exact ? candidate.equals(expected) : candidate.contains(expected);
    }

    private String normalizeDialogueText(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace("<br>", " ")
                .replaceAll("<[^>]+>", "")
                .replaceAll("[^a-z0-9' ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String widgetText(WidgetChild widget) {
        if (widget == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (widget.getText() != null) {
            text.append(widget.getText());
        }
        if (widget.getRawText() != null) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(widget.getRawText());
        }
        return text.toString();
    }

    private NPC findLalo(APIContext ctx) {
        NPC lalo = ctx.npcs()
                .query()
                .id(LALO_ID)
                .results()
                .nearest();
        if (lalo != null && lalo.isValid()) {
            return lalo;
        }

        lalo = ctx.npcs()
                .query()
                .named("Supervisor Lalo")
                .actions("Talk-to")
                .results()
                .nearest();
        if (lalo != null && lalo.isValid()) {
            return lalo;
        }

        return ctx.npcs()
                .query()
                .named("Supervisor Lalo")
                .results()
                .nearest();
    }

    private boolean selectTalkFromOpenMenu(APIContext ctx, NPC lalo) {
        if (!ctx.menu().isOpen()) {
            return false;
        }
        return ctx.menu().interact("Talk-to", "Supervisor Lalo", lalo, true)
                || ctx.menu().interact("Talk-to", lalo, true)
                || ctx.menu().interact("Talk-to", lalo, false)
                || ctx.menu().interact("Talk-to", true)
                || ctx.menu().interact("Talk-to", false);
    }

    private void markLaloInteraction() {
        lastLaloInteractionAt = System.currentTimeMillis();
        lastDialogueAt = 0L;
        awaitingDialogueCompletion = true;
        sawDialogueAfterInteraction = false;
    }

    private void markDialogueSeen() {
        lastDialogueAt = System.currentTimeMillis();
        awaitingDialogueCompletion = true;
        sawDialogueAfterInteraction = true;
    }

    private boolean markAuthorised(APIContext ctx) {
        authorisedThisSession = true;
        awaitingDialogueCompletion = false;
        persistAuthorisedPlayer(ctx);
        stats.setStatus("Supervisor Lalo authorisation complete");
        return true;
    }

    private boolean hasActiveDialogue(APIContext ctx) {
        return ctx.dialogues().canContinue()
                || hasDialogueOptions(ctx)
                || ctx.dialogues().isDialogueOpen();
    }

    private boolean hasDialogueOptions(APIContext ctx) {
        List<WidgetChild> options = ctx.dialogues().getOptions();
        return options != null && !options.isEmpty();
    }

    private void logFoundLalo(APIContext ctx, NPC lalo) {
        long now = System.currentTimeMillis();
        if (now < nextFoundDiagnosticAt) {
            return;
        }
        nextFoundDiagnosticAt = now + 5_000L;
        stats.debug("Supervisor Lalo found: " + describeLalo(ctx, lalo));
    }

    private void logMissingLalo(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextDiagnosticAt) {
            return;
        }
        nextDiagnosticAt = now + 10_000L;
        stats.debug("Supervisor Lalo scan: playerLoc=" + ctx.localPlayer().getLocation()
                + " laloTile=" + LALO_TILE
                + " dist=" + LALO_TILE.tileDistanceTo(ctx));
    }

    private String describeLalo(APIContext ctx, NPC lalo) {
        if (lalo == null) {
            return "null targetTile=" + LALO_TILE;
        }
        return lalo.getName()
                + " id=" + lalo.getId()
                + " tile=" + lalo.getLocation()
                + " targetTile=" + LALO_TILE
                + " dist=" + lalo.tileDistanceTo(ctx)
                + " actions=" + lalo.getActions();
    }

    private boolean contains(String value, String needle) {
        return normalize(value).contains(normalize(needle));
    }

    private boolean isNegativeOption(String text) {
        String normalized = normalize(text);
        return normalized.contains("no")
                || normalized.contains("cancel")
                || normalized.contains("never mind")
                || normalized.contains("nevermind");
    }

    private boolean hasPersistedAuthorisation(APIContext ctx) {
        String playerKey = playerKey(ctx);
        if (playerKey.isBlank() || !Files.isRegularFile(AUTHORISED_PLAYERS_FILE)) {
            return false;
        }
        try {
            for (String line : Files.readAllLines(AUTHORISED_PLAYERS_FILE, StandardCharsets.UTF_8)) {
                if (playerKey.equals(line.trim())) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private void persistAuthorisedPlayer(APIContext ctx) {
        String playerKey = playerKey(ctx);
        if (playerKey.isBlank() || hasPersistedAuthorisation(ctx)) {
            return;
        }
        try {
            Files.writeString(
                    AUTHORISED_PLAYERS_FILE,
                    playerKey + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            stats.debug("Could not persist Supervisor Lalo authorisation for player=" + playerKey);
        }
    }

    private String playerKey(APIContext ctx) {
        if (ctx == null || ctx.localPlayer() == null || ctx.localPlayer().getName() == null) {
            return "";
        }
        return normalize(ctx.localPlayer().getName());
    }

    private boolean isTutorialOption(String text) {
        String normalized = normalize(text);
        return normalized.contains("run through")
                || normalized.contains("walkthrough")
                || normalized.contains("tutorial")
                || normalized.contains("teach")
                || normalized.contains("show me")
                || normalized.contains("explain");
    }

    private String optionSummary(List<WidgetChild> options) {
        StringBuilder summary = new StringBuilder();
        int count = 0;
        for (WidgetChild option : options) {
            if (option == null) {
                continue;
            }
            String text = normalizeDialogueText(widgetText(option));
            if (text.isBlank()) {
                continue;
            }
            if (count > 0) {
                summary.append(" | ");
            }
            summary.append(shortText(text, 45));
            count++;
            if (count >= 4) {
                summary.append(" | ...");
                break;
            }
        }
        return count == 0 ? "unknown options" : summary.toString();
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String normalized = value.replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(1, maxChars - 3)) + "...";
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
