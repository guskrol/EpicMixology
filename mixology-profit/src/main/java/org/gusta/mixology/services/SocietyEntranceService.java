package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.stats.MixologyStats;

import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale;

public class SocietyEntranceService {
    private static final int STAIRCASE_ID = 54946;
    private static final Tile STAIRCASE_TILE = new Tile(1389, 2916, 0);
    private static final Area STAIRCASE_AREA = new Area(1383, 2910, 1395, 2922, 0);
    private static final long STAIRCASE_TRANSITION_GRACE_MILLIS = 12_000L;

    private final MixologyStats stats;
    private long nextDiagnosticAt;
    private long staircaseTransitionUntil;

    public SocietyEntranceService(MixologyStats stats) {
        this.stats = stats;
    }

    public boolean isEntranceContext(APIContext ctx) {
        SceneObject staircase = findStaircase(ctx);
        if (isExitStaircase(staircase) || isWaitingForStaircaseTransition()) {
            return false;
        }

        boolean nearEntrance = isNearStaircase(ctx) || staircase != null;
        if (ctx.dialogues().isDialogueOpen() || ctx.dialogues().isChatOpen()) {
            if (!nearEntrance) {
                stats.debug("Ignoring Society entrance dialogue/chat context away from entrance; playerLoc="
                        + ctx.localPlayer().getLocation());
            }
            return nearEntrance;
        }

        return nearEntrance;
    }

    public boolean isInsideLabStaircase(APIContext ctx) {
        return isExitStaircase(findStaircase(ctx));
    }

    public boolean isWaitingForStaircaseTransition() {
        return System.currentTimeMillis() < staircaseTransitionUntil;
    }

    public boolean handleEntrance(APIContext ctx) {
        if (handleEntranceDialogue(ctx)) {
            return true;
        }

        if (isWaitingForStaircaseTransition()) {
            stats.setStatus("Waiting for Society staircase transition");
            Time.sleep(700, 1100);
            return true;
        }

        SceneObject staircase = findStaircase(ctx);
        if (isExitStaircase(staircase)) {
            stats.setStatus("Already below Society entrance staircase");
            return false;
        }
        if (staircase != null && staircase.isValid()) {
            return climbDown(ctx, staircase);
        }

        if (isNearStaircase(ctx)) {
            logMissingStaircase(ctx);
            stats.setStatus("At Society entrance; waiting for Staircase id=" + STAIRCASE_ID);
            Time.sleep(700, 1100);
            return true;
        }

        if (ctx.localPlayer().isMoving()) {
            stats.setStatus("Walking to Society Staircase " + tileText(STAIRCASE_TILE));
            Time.sleep(700, 1100);
            return true;
        }

        stats.setStatus("Walking to Society Staircase " + tileText(STAIRCASE_TILE));
        ctx.webWalking().setUseTeleports(true);
        ctx.webWalking().walkTo(STAIRCASE_TILE);
        Time.sleep(1200, 1800);
        return true;
    }

    private boolean handleEntranceDialogue(APIContext ctx) {
        if (!ctx.dialogues().isDialogueOpen() && !ctx.dialogues().isChatOpen()) {
            return false;
        }

        if (ctx.dialogues().canContinue()) {
            stats.setStatus("Continuing Society entrance dialogue");
            if (!ctx.dialogues().selectContinue()) {
                ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            }
            Time.sleep(450, 800);
            return true;
        }

        String[] preferredOptions = {
                "yes",
                "enter",
                "climb",
                "go down",
                "down",
                "continue",
                "ok",
                "okay"
        };
        for (String option : preferredOptions) {
            if (ctx.dialogues().hasOptionContaining(option)) {
                stats.setStatus("Selecting Society entrance dialogue option: " + option);
                ctx.dialogues().selectOption(text -> contains(text, option));
                Time.sleep(600, 950);
                return true;
            }
        }

        List<WidgetChild> options = ctx.dialogues().getOptions();
        if (options != null && !options.isEmpty()) {
            stats.setStatus("Selecting first non-negative Society entrance option");
            ctx.dialogues().selectOption(text -> text != null && !isNegativeOption(text));
            Time.sleep(600, 950);
            return true;
        }

        return false;
    }

    private SceneObject findStaircase(APIContext ctx) {
        SceneObject staircase = ctx.objects()
                .query()
                .id(STAIRCASE_ID)
                .results()
                .nearest();
        if (staircase != null && staircase.isValid()) {
            return staircase;
        }

        return ctx.objects()
                .query()
                .nameContains("Staircase")
                .actions("Climb-down")
                .results()
                .nearest();
    }

    private boolean climbDown(APIContext ctx, SceneObject staircase) {
        if (!staircase.hasAction("Climb-down")) {
            stats.setStatus("Society staircase is not a down entrance; actions=" + staircase.getActions());
            Time.sleep(500, 800);
            return false;
        }
        if (ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
            return true;
        }
        if (ctx.inventory().isItemSelected()) {
            ctx.inventory().deselectItem();
            Time.sleep(150, 300);
            return true;
        }

        stats.setStatus("Climbing down Society Staircase id=" + staircase.getId()
                + " tile=" + staircase.getLocation()
                + " actions=" + staircase.getActions());
        boolean clicked = staircase.interact("Climb-down", "Staircase")
                || staircase.interact("Climb-down")
                || staircase.interactMatch("Climb-down")
                || ctx.menu().interact("Climb-down", "Staircase", staircase, true)
                || ctx.menu().interact("Climb-down", staircase, true)
                || ctx.menu().interact("Climb-down", staircase, false);
        if (clicked) {
            Time.sleep(900, 1500,
                    () -> ctx.dialogues().isDialogueOpen()
                            || ctx.dialogues().isChatOpen()
                            || ctx.localPlayer().isMoving(), 100);
            if (!ctx.dialogues().isDialogueOpen() && !ctx.dialogues().isChatOpen()) {
                staircaseTransitionUntil = System.currentTimeMillis() + STAIRCASE_TRANSITION_GRACE_MILLIS;
            }
            return true;
        }

        Time.sleep(700, 1100);
        return true;
    }

    private boolean isNearStaircase(APIContext ctx) {
        return STAIRCASE_AREA.contains(ctx.localPlayer().getLocation())
                || STAIRCASE_TILE.tileDistanceTo(ctx) <= 8;
    }

    private boolean isExitStaircase(SceneObject staircase) {
        return staircase != null
                && staircase.isValid()
                && staircase.hasAction("Climb-up")
                && !staircase.hasAction("Climb-down");
    }

    private void logMissingStaircase(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextDiagnosticAt) {
            return;
        }
        nextDiagnosticAt = now + 10_000L;
        stats.debug("Society Staircase not found near " + tileText(STAIRCASE_TILE)
                + " playerLoc=" + ctx.localPlayer().getLocation());
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

    private String tileText(Tile tile) {
        return tile.getX() + "," + tile.getY() + "," + tile.getPlane();
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
