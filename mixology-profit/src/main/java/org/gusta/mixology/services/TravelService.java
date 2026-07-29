package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.stats.MixologyStats;

import java.util.Arrays;

public class TravelService {
    private static final String[] MIXOLOGY_ROOM_OBJECT_NAMES = {
            "Hopper",
            "Mixing vessel",
            "Vessel",
            "Mixer",
            "Retort",
            "Agitator",
            "Alembic",
            "Conveyor",
            "Mox",
            "Aga",
            "Lye"
    };
    private static final String[] MIXOLOGY_ENTRY_NAMES = {
            "Gate",
            "Barrier",
            "Door",
            "Entrance",
            "Curtain",
            "Room",
            "Mixology",
            "Laboratory"
    };
    private static final String[] MIXOLOGY_ENTRY_ACTIONS = {
            "Enter",
            "Open",
            "Pass",
            "Walk-through",
            "Start",
            "Use"
    };
    private static final String[] SOCIETY_LAB_OBJECT_NAMES = {
            "Bank chest",
            "Bank",
            "Refiner"
    };

    private final MixologySettings settings;
    private final MixologyStats stats;
    private final CharterShipService charterShip;
    private final SocietyEntranceService societyEntrance;
    private long nextFallbackLogAt;
    private long nextRoomEntryDiagnosticAt;

    public TravelService(MixologySettings settings, MixologyStats stats) {
        this.settings = settings;
        this.stats = stats;
        this.charterShip = new CharterShipService(settings, stats);
        this.societyEntrance = new SocietyEntranceService(stats);
    }

    public boolean isAtSociety(APIContext ctx) {
        return isInSocietyLabContext(ctx);
    }

    public boolean isInMixingRoom(APIContext ctx) {
        return settings.isMixingRoomTile(ctx.localPlayer().getLocation())
                || hasReachableMixologyRoomObject(ctx);
    }

    public boolean isInMixologyContext(APIContext ctx) {
        return isInSocietyLabContext(ctx);
    }

    public boolean isInSocietyLabContext(APIContext ctx) {
        return societyEntrance.isInsideLabStaircase(ctx)
                || settings.isAlchemicalSocietyTile(ctx.localPlayer().getLocation())
                || hasReachableMixologyRoomObject(ctx)
                || hasReachableSocietyLabObject(ctx);
    }

    public boolean isAtSocietyEntrance(APIContext ctx) {
        if (isInMixologyContext(ctx)) {
            return false;
        }
        return societyEntrance.isEntranceContext(ctx);
    }

    public boolean canResumeTravelCheckpoint(APIContext ctx) {
        return charterShip.canResumeFromCheckpoint(ctx);
    }

    public boolean isObservedAldarinDockCheckpoint(APIContext ctx) {
        return charterShip.isObservedAldarinDockCheckpoint(ctx);
    }

    public boolean completeCheckpointRouteToBank(APIContext ctx) {
        if (!isInSocietyLabContext(ctx)) {
            goToSociety(ctx);
            return false;
        }
        if (ctx.bank().isOpen()) {
            stats.setStatus("Travel checkpoint complete: minigame bank is open");
            return true;
        }
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus("Checkpoint lock: approaching minigame bank");
            Time.sleep(650, 1000);
            return false;
        }

        SceneObject bank = ctx.objects()
                .query()
                .nameContains("Bank chest", "Bank")
                .actions("Bank")
                .within(settings.alchemicalSocietyArea())
                .results()
                .nearest();
        if (bank != null && bank.isValid()) {
            if (bank.tileDistanceTo(ctx) > 4) {
                stats.setStatus("Checkpoint lock: walking to minigame bank " + bank.getLocation());
                ctx.walking().walkTo(bank.getLocation());
                Time.sleep(800, 1300,
                        () -> bank.tileDistanceTo(ctx) <= 4 || ctx.localPlayer().isMoving(), 100);
                return false;
            }
            stats.setStatus("Checkpoint lock: opening minigame bank");
            bank.interact("Bank");
            Time.sleep(900, 1500, () -> ctx.bank().isOpen(), 100);
            return ctx.bank().isOpen();
        }

        stats.setStatus("Checkpoint lock: locating minigame bank");
        if (ctx.bank().isReachable()) {
            ctx.bank().open();
            Time.sleep(900, 1500, () -> ctx.bank().isOpen(), 100);
        } else {
            Time.sleep(700, 1100);
        }
        return ctx.bank().isOpen();
    }

    public boolean goToSociety(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return false;
        }
        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(500, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }
        if (isInMixologyContext(ctx)) {
            stats.setStatus("At Alchemical Society lab");
            return true;
        }
        if (societyEntrance.isWaitingForStaircaseTransition(ctx)) {
            stats.setStatus("Waiting for Society lab transition");
            Time.sleep(700, 1100);
            return false;
        }
        if (societyEntrance.isEntranceContext(ctx)) {
            societyEntrance.handleEntrance(ctx);
            return false;
        }
        if (settings.isSocietySurfaceTile(ctx.localPlayer().getLocation())) {
            stats.setStatus("At Society surface; walking to laboratory entrance");
            societyEntrance.handleEntrance(ctx);
            return false;
        }
        if (ctx.localPlayer().isMoving()) {
            stats.setStatus("Walking to Mastering Mixology");
            Time.sleep(700, 1100);
            return false;
        }

        stats.setStatus("Traveling via Ring of wealth, Trader Stan and Aldarin charter");
        if (charterShip.tryCharterToAldarin(ctx)) {
            return isInSocietyLabContext(ctx);
        }

        long now = System.currentTimeMillis();
        if (now >= nextFallbackLogAt) {
            stats.setStatus("Waiting to resume Ring of wealth charter route");
            nextFallbackLogAt = now + 45_000L;
        }
        Time.sleep(700, 1100);
        return false;
    }

    public boolean enterMixingRoom(APIContext ctx) {
        if (isAtOrderReadingTile(ctx)) {
            stats.setStatus("At Mixology order reading tile");
            return true;
        }

        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return false;
        }
        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(500, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus("Moving to Mixology order reading tile "
                    + tileText(settings.mixingRoomCenterTile())
                    + " dist=" + settings.mixingRoomCenterTile().tileDistanceTo(ctx));
            Time.sleep(650, 1000);
            return false;
        }

        if (isInSocietyLabContext(ctx)) {
            stats.setStatus("Ground-clicking Mixology order reading tile "
                    + tileText(settings.mixingRoomCenterTile())
                    + " dist=" + settings.mixingRoomCenterTile().tileDistanceTo(ctx));
            boolean walking = localGroundWalk(ctx, settings.mixingRoomCenterTile());
            if (!walking) {
                stats.setStatus("Local ground click failed for Mixology order reading tile; minimap fallback "
                        + tileText(settings.mixingRoomCenterTile()));
                ctx.walking().walkTo(settings.mixingRoomCenterTile());
                Time.sleep(900, 1500,
                        () -> isAtOrderReadingTile(ctx) || ctx.localPlayer().isMoving(), 100);
                return isAtOrderReadingTile(ctx);
            }
            Time.sleep(900, 1500,
                    () -> isAtOrderReadingTile(ctx) || ctx.localPlayer().isMoving(), 100);
            return walking && isAtOrderReadingTile(ctx);
        }

        SceneObject entry = findMixologyEntry(ctx);
        if (entry != null && entry.isValid()) {
            stats.setStatus("Entering active Mixology room via " + entry.getName()
                    + " id=" + entry.getId()
                    + " tile=" + entry.getLocation()
                    + " actions=" + entry.getActions());
            for (String action : MIXOLOGY_ENTRY_ACTIONS) {
                if (entry.hasAction(action) && entry.interact(action)) {
                    Time.sleep(1000, 1800,
                            () -> isAtOrderReadingTile(ctx) || ctx.localPlayer().isMoving(), 100);
                    return isAtOrderReadingTile(ctx);
                }
            }
        }

        logRoomEntryDiagnostic(ctx);
        stats.setStatus("Walking to active Mixology room");
        ctx.webWalking().setUseTeleports(false);
        ctx.webWalking().walkTo(settings.mixingRoomCenterTile());
        Time.sleep(1000, 1600, () -> isAtOrderReadingTile(ctx) || ctx.localPlayer().isMoving(), 100);
        return isAtOrderReadingTile(ctx);
    }

    public boolean moveToMixingRoomCenter(APIContext ctx, String reason) {
        if (isAtLeverCenterTile(ctx)) {
            stats.setStatus(reason + ": already on lever work tile");
            return true;
        }

        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return false;
        }
        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(500, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus(reason + ": moving to lever center "
                    + tileText(nearestLeverReturnTile(ctx))
                    + " dist=" + nearestLeverReturnDistance(ctx));
            Time.sleep(650, 1000);
            return false;
        }

        Tile[] fallbackTiles = settings.leverReturnTiles();
        for (int i = 0; i < fallbackTiles.length; i++) {
            Tile target = fallbackTiles[i];
            stats.setStatus(reason + ": ground-clicking lever work tile "
                    + (i + 1) + "/" + fallbackTiles.length
                    + " " + tileText(target)
                    + " dist=" + target.tileDistanceTo(ctx));
            boolean walking = localGroundWalk(ctx, target);
            Time.sleep(900, 1500,
                    () -> isAtLeverCenterTile(ctx) || ctx.localPlayer().isMoving(), 100);
            if (isAtLeverCenterTile(ctx)) {
                return true;
            }
            if (walking && ctx.localPlayer().isMoving()) {
                return false;
            }
        }

        Tile finalFallback = fallbackTiles[fallbackTiles.length - 1];
        if (settings.isMixingRoomTile(ctx.localPlayer().getLocation())) {
            stats.setStatus(reason + ": ground-click retries failed; minimap walking to lever fallback "
                    + tileText(finalFallback));
            ctx.walking().walkTo(finalFallback);
            Time.sleep(900, 1500,
                    () -> isAtLeverCenterTile(ctx) || ctx.localPlayer().isMoving(), 100);
            return isAtLeverCenterTile(ctx);
        }
        stats.setStatus(reason + ": webwalking to lever fallback after ground-click retries " + tileText(finalFallback));
        ctx.webWalking().setUseTeleports(false);
        ctx.webWalking().walkTo(finalFallback);
        Time.sleep(900, 1500,
                () -> isAtLeverCenterTile(ctx) || ctx.localPlayer().isMoving(), 100);
        return isAtLeverCenterTile(ctx);
    }

    private boolean isAtOrderReadingTile(APIContext ctx) {
        return settings.mixingRoomCenterTile().tileDistanceTo(ctx) <= 2;
    }

    private boolean isAtLeverCenterTile(APIContext ctx) {
        Tile playerTile = ctx.localPlayer().getLocation();
        if (!settings.isMixingRoomTile(playerTile)) {
            return false;
        }
        for (Tile tile : settings.leverReturnTiles()) {
            if (isSameTile(playerTile, tile)) {
                return true;
            }
        }
        return false;
    }

    private boolean localGroundWalk(APIContext ctx, Tile target) {
        return ctx.walking().walkOnScreen(target)
                || target.interact("Walk here")
                || target.click(true);
    }

    private boolean isSameTile(Tile left, Tile right) {
        return left != null
                && right != null
                && left.getX() == right.getX()
                && left.getY() == right.getY()
                && left.getPlane() == right.getPlane();
    }

    private Tile nearestLeverReturnTile(APIContext ctx) {
        Tile nearest = settings.leverCenterTile();
        int nearestDistance = Integer.MAX_VALUE;
        for (Tile tile : settings.leverReturnTiles()) {
            int distance = tile.tileDistanceTo(ctx);
            if (distance < nearestDistance) {
                nearest = tile;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private int nearestLeverReturnDistance(APIContext ctx) {
        int nearestDistance = Integer.MAX_VALUE;
        for (Tile tile : settings.leverReturnTiles()) {
            nearestDistance = Math.min(nearestDistance, tile.tileDistanceTo(ctx));
        }
        return nearestDistance;
    }

    private boolean hasReachableMixologyRoomObject(APIContext ctx) {
        SceneObject roomObject = ctx.objects()
                .query()
                .nameContains(MIXOLOGY_ROOM_OBJECT_NAMES)
                .within(settings.alchemicalSocietyArea())
                .reachable()
                .results()
                .nearest();
        return roomObject != null && roomObject.isValid();
    }

    private boolean hasReachableSocietyLabObject(APIContext ctx) {
        SceneObject labObject = ctx.objects()
                .query()
                .nameContains(SOCIETY_LAB_OBJECT_NAMES)
                .within(settings.alchemicalSocietyArea())
                .reachable()
                .results()
                .nearest();
        return labObject != null && labObject.isValid();
    }

    private SceneObject findMixologyEntry(APIContext ctx) {
        SceneObject entry = ctx.objects()
                .query()
                .nameContains(MIXOLOGY_ENTRY_NAMES)
                .actions(MIXOLOGY_ENTRY_ACTIONS)
                .reachable()
                .results()
                .nearest();
        if (entry != null && entry.isValid()) {
            return entry;
        }

        return ctx.objects()
                .query()
                .nameContains(MIXOLOGY_ENTRY_NAMES)
                .actions(MIXOLOGY_ENTRY_ACTIONS)
                .within(settings.alchemicalSocietyArea())
                .results()
                .nearest();
    }

    private void logRoomEntryDiagnostic(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextRoomEntryDiagnosticAt) {
            return;
        }
        nextRoomEntryDiagnosticAt = now + 10_000L;
        StringBuilder nearby = new StringBuilder();
        int count = 0;
        for (SceneObject object : ctx.objects()
                .query()
                .within(settings.alchemicalSocietyArea())
                .results()
                .nearestList()) {
            if (object == null || !object.isValid()) {
                continue;
            }
            if (count >= 10) {
                break;
            }
            if (nearby.length() > 0) {
                nearby.append(" | ");
            }
            nearby.append(object.getId())
                    .append(':')
                    .append(object.getName())
                    .append('@')
                    .append(object.getLocation())
                    .append(" actions=")
                    .append(object.getActions());
            count++;
        }
        stats.debug("Mixology room entry not found. playerLoc=" + ctx.localPlayer().getLocation()
                + " entryNames=" + Arrays.toString(MIXOLOGY_ENTRY_NAMES)
                + " entryActions=" + Arrays.toString(MIXOLOGY_ENTRY_ACTIONS)
                + " nearbyObjects=" + nearby);
    }

    private String tileText(com.epicbot.api.shared.model.Tile tile) {
        return tile.getX() + "," + tile.getY() + "," + tile.getPlane();
    }
}
