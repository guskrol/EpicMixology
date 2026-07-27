package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.stats.MixologyStats;

import java.util.Arrays;

public class ObjectService {
    private static final int MAX_DIRECT_OBJECT_INTERACT_DISTANCE = 4;

    private final MixologyStats stats;
    private long nextMissingObjectDiagnosticAt;

    public ObjectService(MixologyStats stats) {
        this.stats = stats;
    }

    public SceneObject nearestByName(APIContext ctx, Area area, String... names) {
        SceneObject candidate = ctx.objects()
                .query()
                .nameContains(names)
                .within(area)
                .reachable()
                .results()
                .nearest();
        if (candidate != null && candidate.isValid()) {
            return candidate;
        }

        return ctx.objects()
                .query()
                .nameContains(names)
                .within(area)
                .results()
                .nearest();
    }

    public SceneObject nearestByNameAndAction(APIContext ctx, Area area, String[] names, String... actions) {
        SceneObject candidate = ctx.objects()
                .query()
                .nameContains(names)
                .actions(actions)
                .within(area)
                .reachable()
                .results()
                .nearest();
        if (candidate != null && candidate.isValid()) {
            return candidate;
        }

        return ctx.objects()
                .query()
                .nameContains(names)
                .actions(actions)
                .within(area)
                .results()
                .nearest();
    }

    public boolean interact(APIContext ctx, Area area, String[] names, String... actions) {
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            Time.sleep(450, 800);
            return true;
        }

        SceneObject object = actions.length == 0
                ? nearestByName(ctx, area, names)
                : nearestByNameAndAction(ctx, area, names, actions);
        if (object == null || !object.isValid()) {
            stats.setStatus("Missing object: " + Arrays.toString(names));
            logNearbyObjects(ctx, area, names, actions);
            Time.sleep(900, 1300);
            return false;
        }

        for (String action : actions) {
            if (object.hasAction(action) && object.interact(action)) {
                stats.setStatus("Interacting: " + object.getName() + " / " + action);
                Time.sleep(700, 1200);
                return true;
            }
        }

        if (actions.length == 0 && object.interact()) {
            stats.setStatus("Interacting: " + object.getName());
            Time.sleep(700, 1200);
            return true;
        }

        stats.setStatus("Could not interact with " + object.getName() + " actions=" + object.getActions());
        Time.sleep(900, 1300);
        return false;
    }

    public boolean interactById(APIContext ctx, Area area, int id, String label, String... actions) {
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            Time.sleep(450, 800);
            return true;
        }

        SceneObject object = nearestById(ctx, area, id, actions);
        if (object == null || !object.isValid()) {
            stats.setStatus("Missing object id=" + id + " label=" + label);
            logNearbyObjects(ctx, area, new String[]{label, "id=" + id}, actions);
            Time.sleep(900, 1300);
            return false;
        }

        for (String action : actions) {
            if (object.hasAction(action) && object.interact(action)) {
                stats.setStatus("Interacting: " + object.getName() + " id=" + id + " / " + action);
                Time.sleep(700, 1200);
                return true;
            }
        }

        stats.setStatus("Could not interact with id=" + id
                + " name=" + object.getName()
                + " actions=" + object.getActions());
        Time.sleep(900, 1300);
        return false;
    }

    public boolean interactByIdAtTile(
            APIContext ctx,
            Area area,
            int id,
            String label,
            Tile objectTile,
            String... actions
    ) {
        return interactByIdAtTile(ctx, area, id, label, objectTile, objectTile, actions);
    }

    public boolean interactByIdAtTile(
            APIContext ctx,
            Area area,
            int id,
            String label,
            Tile objectTile,
            Tile approachTile,
            String... actions
    ) {
        return interactByIdAtTile(ctx, area, id, label, objectTile, approachTile, false, actions);
    }

    public boolean interactByIdAtTileWithMinimap(
            APIContext ctx,
            Area area,
            int id,
            String label,
            Tile objectTile,
            Tile approachTile,
            String... actions
    ) {
        return interactByIdAtTile(ctx, area, id, label, objectTile, approachTile, true, actions);
    }

    private boolean interactByIdAtTile(
            APIContext ctx,
            Area area,
            int id,
            String label,
            Tile objectTile,
            Tile approachTile,
            boolean allowMinimapWalk,
            String... actions
    ) {
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus("Moving to " + label + " tile " + tileText(approachTile));
            Time.sleep(450, 800);
            return false;
        }

        SceneObject object = nearestByIdNearTile(ctx, area, id, objectTile);
        if (object != null && object.isValid()
                && object.tileDistanceTo(ctx) <= MAX_DIRECT_OBJECT_INTERACT_DISTANCE) {
            if (interactWithObject(ctx, object, id, label, actions)) {
                return true;
            }
        }

        if (approachTile != null && approachTile.tileDistanceTo(ctx) > 1) {
            if (allowMinimapWalk) {
                stats.setStatus("Minimap walking to " + label + " tile " + tileText(approachTile)
                        + " dist=" + approachTile.tileDistanceTo(ctx));
                boolean walking = ctx.walking().walkTo(approachTile);
                if (!walking) {
                    ctx.webWalking().setUseTeleports(false);
                    ctx.webWalking().walkTo(approachTile);
                }
                Time.sleep(800, 1300,
                        () -> ctx.localPlayer().isMoving() || approachTile.tileDistanceTo(ctx) <= 1,
                        100);
                return false;
            }

            stats.setStatus("Ground-clicking " + label + " tile " + tileText(approachTile)
                    + " dist=" + approachTile.tileDistanceTo(ctx));
            boolean walking = ctx.walking().walkOnScreen(approachTile)
                    || approachTile.interact("Walk here")
                    || approachTile.click(true);
            if (!walking) {
                if (area != null && area.contains(ctx.localPlayer().getLocation())) {
                    stats.setStatus("Local ground click failed for " + label
                            + " tile " + tileText(approachTile)
                            + "; minimap fallback");
                    walking = ctx.walking().walkTo(approachTile);
                    if (!walking) {
                        ctx.webWalking().setUseTeleports(false);
                        ctx.webWalking().walkTo(approachTile);
                    }
                    Time.sleep(800, 1300,
                            () -> ctx.localPlayer().isMoving() || approachTile.tileDistanceTo(ctx) <= 1,
                            100);
                    return false;
                }
                walking = ctx.walking().walkTo(approachTile);
                if (!walking) {
                    ctx.webWalking().setUseTeleports(false);
                    ctx.webWalking().walkTo(approachTile);
                }
            }
            Time.sleep(800, 1300,
                    () -> ctx.localPlayer().isMoving() || approachTile.tileDistanceTo(ctx) <= 1,
                    100);
            return false;
        }

        object = nearestByIdNearTile(ctx, area, id, objectTile);
        if (object == null || !object.isValid()) {
            stats.setStatus("Missing object id=" + id
                    + " label=" + label
                    + " tile=" + tileText(objectTile));
            logNearbyObjects(ctx, area, new String[]{label, "id=" + id, "tile=" + tileText(objectTile)}, actions);
            Time.sleep(900, 1300);
            return false;
        }

        return interactWithObject(ctx, object, id, label, actions);
    }

    private SceneObject nearestById(APIContext ctx, Area area, int id, String... actions) {
        SceneObject candidate = ctx.objects()
                .query()
                .id(id)
                .actions(actions)
                .within(area)
                .reachable()
                .results()
                .nearest();
        if (candidate != null && candidate.isValid()) {
            return candidate;
        }

        return ctx.objects()
                .query()
                .id(id)
                .within(area)
                .results()
                .nearest();
    }

    private SceneObject nearestByIdNearTile(APIContext ctx, Area area, int id, Tile objectTile) {
        SceneObject candidate = nearestMatchingTile(ctx.objects()
                .query()
                .id(id)
                .within(area)
                .results()
                .nearestList(), objectTile);
        if (candidate != null && candidate.isValid()) {
            return candidate;
        }

        return nearestMatchingTile(ctx.objects()
                .query()
                .id(id)
                .results()
                .nearestList(), objectTile);
    }

    private SceneObject nearestMatchingTile(Iterable<SceneObject> objects, Tile objectTile) {
        SceneObject fallback = null;
        int fallbackDistance = Integer.MAX_VALUE;
        for (SceneObject object : objects) {
            if (object == null || !object.isValid()) {
                continue;
            }
            if (objectTile == null || object.getLocation() == null) {
                return object;
            }

            int distance = tileDistance(object.getLocation(), objectTile);
            if (distance <= 1) {
                return object;
            }
            if (distance < fallbackDistance) {
                fallback = object;
                fallbackDistance = distance;
            }
        }

        return fallbackDistance <= 3 ? fallback : null;
    }

    private boolean interactWithObject(APIContext ctx, SceneObject object, int id, String label, String[] actions) {
        for (String action : actions) {
            if (object.hasAction(action) && object.interact(action)) {
                stats.setStatus("Interacting: " + object.getName()
                        + " id=" + id
                        + " tile=" + object.getLocation()
                        + " / " + action);
                Time.sleep(700, 1200);
                return true;
            }

            if (ctx.menu().interact(action, label, object, true)
                    || ctx.menu().interact(action, object, true)
                    || ctx.menu().interact(action, object, false)) {
                stats.setStatus("Menu interacting: " + label
                        + " id=" + id
                        + " tile=" + object.getLocation()
                        + " / " + action);
                Time.sleep(700, 1200);
                return true;
            }
        }

        if (actions.length == 0 && (object.interact() || ctx.mouse().click(object, false))) {
            stats.setStatus("Interacting: " + object.getName()
                    + " id=" + id
                    + " tile=" + object.getLocation());
            Time.sleep(700, 1200);
            return true;
        }

        stats.setStatus("Could not interact with id=" + id
                + " label=" + label
                + " name=" + object.getName()
                + " tile=" + object.getLocation()
                + " actions=" + object.getActions());
        Time.sleep(900, 1300);
        return false;
    }

    private void logNearbyObjects(APIContext ctx, Area area, String[] names, String[] actions) {
        long now = System.currentTimeMillis();
        if (now < nextMissingObjectDiagnosticAt) {
            return;
        }
        nextMissingObjectDiagnosticAt = now + 10_000L;

        StringBuilder nearby = new StringBuilder();
        int count = 0;
        for (SceneObject object : ctx.objects()
                .query()
                .within(area)
                .results()
                .nearestList()) {
            if (object == null || !object.isValid()) {
                continue;
            }
            if (count >= 12) {
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

        stats.debug("Missing object diagnostic names=" + Arrays.toString(names)
                + " actions=" + Arrays.toString(actions)
                + " playerLoc=" + ctx.localPlayer().getLocation()
                + " nearbyObjects=" + nearby);
    }

    private int tileDistance(Tile a, Tile b) {
        if (a == null || b == null || a.getPlane() != b.getPlane()) {
            return Integer.MAX_VALUE;
        }
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
    }

    private String tileText(Tile tile) {
        if (tile == null) {
            return "unknown";
        }
        return tile.getX() + "," + tile.getY() + "," + tile.getPlane();
    }
}
