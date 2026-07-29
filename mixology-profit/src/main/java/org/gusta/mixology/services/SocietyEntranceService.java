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
import java.util.concurrent.ThreadLocalRandom;

public class SocietyEntranceService {
    private static final int STAIRCASE_ID = 54946;
    private static final Tile STAIRCASE_TILE = new Tile(1389, 2916, 0);
    private static final Tile[] SOCIETY_APPROACH_PATH = {
            new Tile(1388, 2920, 0),
            new Tile(1390, 2919, 0)
    };
    private static final Area STAIRCASE_AREA = new Area(1383, 2910, 1395, 2922, 0);
    private static final long STAIRCASE_RETRY_MIN_MILLIS = 3_000L;
    private static final long STAIRCASE_RETRY_MAX_MILLIS = 4_000L;

    private final MixologyStats stats;
    private long nextDiagnosticAt;
    private long staircaseTransitionUntil;
    private Tile staircaseClickOrigin;
    private int approachWaypointIndex;

    public SocietyEntranceService(MixologyStats stats) {
        this.stats = stats;
    }

    public boolean isEntranceContext(APIContext ctx) {
        SceneObject staircase = findStaircase(ctx);
        if (isExitStaircase(staircase) || isWaitingForStaircaseTransition(ctx)) {
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

    public boolean isWaitingForStaircaseTransition(APIContext ctx) {
        if (staircaseTransitionConfirmed(ctx)) {
            staircaseTransitionUntil = 0L;
            staircaseClickOrigin = null;
            return false;
        }
        if (System.currentTimeMillis() < staircaseTransitionUntil) {
            return true;
        }
        staircaseTransitionUntil = 0L;
        staircaseClickOrigin = null;
        return false;
    }

    public boolean handleEntrance(APIContext ctx) {
        if (handleEntranceDialogue(ctx)) {
            return true;
        }

        if (isWaitingForStaircaseTransition(ctx)) {
            stats.setStatus("Waiting 3-4s for Society staircase location change");
            Time.sleep(700, 1100);
            return true;
        }

        if (walkSocietyApproachPath(ctx)) {
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
        staircaseClickOrigin = ctx.localPlayer().getLocation();
        boolean clicked = staircase.interact("Climb-down");
        long retryDelay = ThreadLocalRandom.current().nextLong(
                STAIRCASE_RETRY_MIN_MILLIS,
                STAIRCASE_RETRY_MAX_MILLIS + 1L
        );
        staircaseTransitionUntil = System.currentTimeMillis() + retryDelay;
        stats.debug("Society staircase single-click result=" + clicked
                + " retryBlockedFor=" + retryDelay + "ms"
                + " origin=" + staircaseClickOrigin);
        Time.sleep(500, 800);
        return true;
    }

    private boolean staircaseTransitionConfirmed(APIContext ctx) {
        if (staircaseClickOrigin == null || ctx.localPlayer().getLocation() == null) {
            return false;
        }
        Tile current = ctx.localPlayer().getLocation();
        return current.getPlane() != staircaseClickOrigin.getPlane()
                || Math.abs(current.getY() - staircaseClickOrigin.getY()) >= 1_000;
    }

    private boolean walkSocietyApproachPath(APIContext ctx) {
        Tile finalWaypoint = SOCIETY_APPROACH_PATH[SOCIETY_APPROACH_PATH.length - 1];
        if (finalWaypoint.tileDistanceTo(ctx) <= 2) {
            approachWaypointIndex = SOCIETY_APPROACH_PATH.length;
            return false;
        }
        if (approachWaypointIndex >= SOCIETY_APPROACH_PATH.length) {
            return false;
        }

        Tile target = SOCIETY_APPROACH_PATH[approachWaypointIndex];
        if (target.tileDistanceTo(ctx) <= 2) {
            approachWaypointIndex++;
            if (approachWaypointIndex >= SOCIETY_APPROACH_PATH.length) {
                return false;
            }
            target = SOCIETY_APPROACH_PATH[approachWaypointIndex];
        }
        if (ctx.localPlayer().isMoving()) {
            stats.setStatus("Following Society approach path "
                    + (approachWaypointIndex + 1) + "/" + SOCIETY_APPROACH_PATH.length
                    + " to " + tileText(target));
            Time.sleep(650, 1000);
            return true;
        }

        stats.setStatus("Walking Society approach path "
                + (approachWaypointIndex + 1) + "/" + SOCIETY_APPROACH_PATH.length
                + " to " + tileText(target));
        Tile pathTarget = target;
        ctx.walking().walkTo(pathTarget);
        Time.sleep(800, 1300,
                () -> pathTarget.tileDistanceTo(ctx) <= 2 || ctx.localPlayer().isMoving(), 100);
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
