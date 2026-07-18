package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.model.Area;
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
    private final MinigameTeleportService minigameTeleport;
    private final CharterShipService charterShip;
    private final SocietyEntranceService societyEntrance;
    private long nextFallbackLogAt;
    private long nextRoomEntryDiagnosticAt;

    public TravelService(MixologySettings settings, MixologyStats stats) {
        this.settings = settings;
        this.stats = stats;
        this.minigameTeleport = new MinigameTeleportService(settings, stats);
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

    public boolean goToSociety(APIContext ctx) {
        Area surfaceArea = settings.societySurfaceArea();

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
        if (societyEntrance.isWaitingForStaircaseTransition()) {
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

        stats.setStatus("Trying Mastering Mixology minigame teleport");
        if (minigameTeleport.tryTeleport(ctx)) {
            return isInSocietyLabContext(ctx) || surfaceArea.contains(ctx.localPlayer().getLocation());
        }

        stats.setStatus("Minigame teleport unavailable; trying Aldarin charter ship");
        if (charterShip.tryCharterToAldarin(ctx)) {
            return isInSocietyLabContext(ctx) || surfaceArea.contains(ctx.localPlayer().getLocation());
        }

        long now = System.currentTimeMillis();
        if (now >= nextFallbackLogAt) {
            stats.setStatus("Fallback: webwalking to Aldarin/Mastering Mixology");
            nextFallbackLogAt = now + 45_000L;
        }

        ctx.webWalking().setUseTeleports(true);
        ctx.webWalking().walkTo(settings.societyCenterTile());
        Time.sleep(1200, 1800);
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
            stats.setStatus("Walking to Mixology order reading tile "
                    + tileText(settings.mixingRoomCenterTile())
                    + " dist=" + settings.mixingRoomCenterTile().tileDistanceTo(ctx));
            boolean walking = ctx.walking().walkTo(settings.mixingRoomCenterTile())
                    || ctx.walking().walkOnMap(settings.mixingRoomCenterTile());
            if (!walking) {
                ctx.webWalking().walkTo(settings.mixingRoomCenterTile());
                walking = true;
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
            stats.setStatus(reason + ": already in lever work zone");
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
            stats.setStatus(reason + ": walking to lever fallback "
                    + (i + 1) + "/" + fallbackTiles.length
                    + " " + tileText(target)
                    + " dist=" + target.tileDistanceTo(ctx));
            boolean walking = ctx.walking().walkTo(target)
                    || ctx.walking().walkOnMap(target);
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
        stats.setStatus(reason + ": webwalking to lever fallback " + tileText(finalFallback));
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
        if (!settings.isMixingRoomTile(ctx.localPlayer().getLocation())) {
            return false;
        }
        for (Tile tile : settings.leverReturnTiles()) {
            if (tile.tileDistanceTo(ctx) <= 1) {
                return true;
            }
        }
        return false;
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
