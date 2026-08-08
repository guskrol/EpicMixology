package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.domain.PotionOrder;
import org.gusta.mixology.domain.PotionRecipe;
import org.gusta.mixology.stats.MixologyStats;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ConveyorService {
    private static final long ACTION_RETRY_TIMEOUT_MS = 20_000L;
    private static final int DELIVERY_TILE_READY_DISTANCE = 1;
    private static final int MIN_DELIVERY_BATCH_SIZE = 3;
    private static final int CONVEYOR_BELT_ID = 54917;
    private static final Tile CONVEYOR_BELT_TILE = new Tile(1394, 9331, 0);
    private static final Tile CONVEYOR_BELT_APPROACH_TILE = new Tile(1394, 9330, 0);

    private final MixologySettings settings;
    private final ObjectService objects;
    private final MixologyStats stats;
    private final PotionInventoryService potionInventory;

    public ConveyorService(
            MixologySettings settings,
            ObjectService objects,
            MixologyStats stats,
            PotionInventoryService potionInventory
    ) {
        this.settings = settings;
        this.objects = objects;
        this.stats = stats;
        this.potionInventory = potionInventory;
    }

    public boolean depositOrders(APIContext ctx, List<PotionOrder> orders) {
        Map<PotionRecipe, Integer> required = requiredCounts(orders);
        int expectedOrders = totalRequired(required);
        if (expectedOrders <= 0) {
            stats.setStatus("No readable order potions to deposit");
            return false;
        }

        stats.setStatus("Depositing completed potions on conveyor");
        int beforeMatching = matchingReadyCount(ctx, required);
        int beforeReady = potionInventory.readyPotionCount(ctx);
        int requiredBatch = Math.max(MIN_DELIVERY_BATCH_SIZE, expectedOrders);
        if (beforeMatching < requiredBatch) {
            stats.setStatus("Only " + beforeMatching
                    + "/" + requiredBatch
                    + " matching processed order potion(s); not using conveyor");
            stats.debug("Blocked conveyor delivery: required="
                    + requiredText(required)
                    + " inventory=" + potionInventory.allPotionDetails(ctx));
            return false;
        }

        long deliveryStartedAt = System.currentTimeMillis();
        stats.debug("Timing conveyor delivery requested: expected=" + expectedOrders
                + " matching=" + beforeMatching
                + " ready=" + beforeReady);
        int delivered = deliverBatchAtConveyor(ctx, expectedOrders, deliveryStartedAt);
        int afterReady = potionInventory.readyPotionCount(ctx);
        if (delivered < expectedOrders) {
            stats.setStatus("Conveyor accepted only " + delivered
                    + "/" + expectedOrders
                    + " expected potion(s); keeping batch state for recovery");
            stats.debug("Ready potion details after partial conveyor delivery: "
                    + potionInventory.allPotionDetails(ctx)
                    + " required=" + requiredText(required));
            return false;
        }

        stats.debug("Recorded completed conveyor batch: delivered=" + delivered
                + " expected=" + expectedOrders
                + " elapsed=" + (System.currentTimeMillis() - deliveryStartedAt) + "ms"
                + " remainingReady=" + afterReady);
        stats.recordOrdersCompleted(1);
        return true;
    }

    public boolean depositTargetOrder(APIContext ctx, PotionOrder target) {
        if (target == null || !target.isComplete()) {
            return false;
        }

        PotionRecipe recipe = target.recipe();
        int beforeTarget = potionInventory.potionCount(ctx, recipe);
        if (beforeTarget <= 0) {
            stats.setStatus("Target recovery potion is no longer in inventory: " + target.label());
            return false;
        }

        int beforeReady = potionInventory.readyPotionCount(ctx);
        stats.setStatus("Depositing isolated target potion: " + target.label());
        if (!retryDeposit(ctx, 1, 1)) {
            return false;
        }

        Time.sleep(1200, 2200,
                () -> potionInventory.potionCount(ctx, recipe) < beforeTarget
                        || potionInventory.readyPotionCount(ctx) < beforeReady,
                100);
        int afterTarget = potionInventory.potionCount(ctx, recipe);
        int afterReady = potionInventory.readyPotionCount(ctx);
        if (afterTarget >= beforeTarget) {
            stats.setStatus("Target potion deposit was not confirmed: " + target.label());
            stats.debug("Target conveyor recovery changed ready count "
                    + beforeReady + " -> " + afterReady
                    + " but target " + recipe.code() + " stayed "
                    + beforeTarget + " -> " + afterTarget);
            return false;
        }

        stats.debug("Isolated target potion delivered: " + target.label()
                + " target=" + beforeTarget + " -> " + afterTarget
                + " ready=" + beforeReady + " -> " + afterReady);
        stats.recordOrdersCompleted(1);
        return true;
    }

    public boolean depositOrders(APIContext ctx, int expectedOrders) {
        stats.setStatus("Depositing completed potions on conveyor");
        int beforeReady = potionInventory.readyPotionCount(ctx);
        int required = Math.max(MIN_DELIVERY_BATCH_SIZE, expectedOrders);
        if (beforeReady < required) {
            stats.setStatus("Only " + beforeReady
                    + "/" + required
                    + " ready potion(s); not using conveyor");
            stats.debug("Blocked partial conveyor delivery: "
                    + potionInventory.readyPotionDetails(ctx));
            return false;
        }

        boolean interacted = retryDeposit(ctx, expectedOrders, required);
        if (!interacted) {
            return false;
        }

        Time.sleep(1200, 2200,
                () -> potionInventory.readyPotionCount(ctx) < beforeReady,
                100);
        int afterReady = potionInventory.readyPotionCount(ctx);
        int delivered = Math.max(0, beforeReady - afterReady);
        if (delivered < Math.max(1, expectedOrders)) {
            stats.setStatus("Conveyor accepted only " + delivered
                    + "/" + expectedOrders
                    + " expected potion(s); keeping batch state for recovery");
            stats.debug("Ready potion details after partial conveyor delivery: "
                    + potionInventory.readyPotionDetails(ctx));
            return false;
        }

        stats.debug("Recorded completed conveyor batch: delivered=" + delivered
                + " expected=" + Math.max(1, expectedOrders)
                + " remainingReady=" + afterReady);
        stats.recordOrdersCompleted(1);
        return true;
    }

    public int depositExistingOrders(APIContext ctx) {
        int beforeReady = potionInventory.readyPotionCount(ctx);
        if (beforeReady < MIN_DELIVERY_BATCH_SIZE) {
            if (beforeReady > 0) {
                stats.debug("Skipping conveyor cleanup: only " + beforeReady
                        + "/" + MIN_DELIVERY_BATCH_SIZE
                        + " existing potion(s); details="
                        + potionInventory.readyPotionDetails(ctx));
            }
            return 0;
        }

        stats.setStatus("Trying conveyor cleanup for " + beforeReady + " existing Mixology potion(s)");
        boolean interacted = retryDeposit(ctx, MIN_DELIVERY_BATCH_SIZE, MIN_DELIVERY_BATCH_SIZE);
        if (!interacted) {
            return 0;
        }

        Time.sleep(1200, 2200,
                () -> potionInventory.readyPotionCount(ctx) < beforeReady,
                100);
        int afterReady = potionInventory.readyPotionCount(ctx);
        int delivered = Math.max(0, beforeReady - afterReady);
        if (delivered > 0) {
            stats.debug("Conveyor cleanup delivered=" + delivered
                    + " remainingReady=" + afterReady
                    + " details=" + potionInventory.readyPotionDetails(ctx));
        } else {
            stats.debug("Conveyor cleanup did not accept existing potions: "
                    + potionInventory.readyPotionDetails(ctx));
        }
        return delivered;
    }

    public boolean depositCarriedPotionsAtStartup(APIContext ctx) {
        int beforePotions = potionInventory.anyPotionCount(ctx);
        if (beforePotions <= 0) {
            return true;
        }

        stats.setStatus("Startup cleanup: depositing " + beforePotions
                + " carried Mixology potion(s) on conveyor");
        if (!retryDeposit(ctx, 1, 1)) {
            return false;
        }

        Time.sleep(1200, 2200,
                () -> potionInventory.anyPotionCount(ctx) < beforePotions,
                100);
        int remainingPotions = potionInventory.anyPotionCount(ctx);
        if (remainingPotions >= beforePotions) {
            stats.setStatus("Startup conveyor deposit was not confirmed; retrying safely");
            stats.debug("Startup conveyor cleanup kept potion count at "
                    + beforePotions + "; inventory=" + potionInventory.allPotionDetails(ctx));
            return false;
        }

        stats.debug("Startup conveyor cleanup accepted="
                + (beforePotions - remainingPotions)
                + " remaining=" + remainingPotions
                + " inventory=" + potionInventory.allPotionDetails(ctx));
        return remainingPotions <= 0;
    }

    private boolean retryDeposit(APIContext ctx, int expectedOrders, int fallbackReadyRequired) {
        long deadline = System.currentTimeMillis() + ACTION_RETRY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
                Time.sleep(450, 900);
                continue;
            }
            if (!standOnDeliveryTile(ctx)) {
                if (hasReadyDeliveryFallback(ctx, fallbackReadyRequired) && interactConveyor(ctx)) {
                    return true;
                }
                Time.sleep(250, 450);
                continue;
            }
            if (interactConveyor(ctx)) {
                return true;
            }
            Time.sleep(250, 450);
        }
        stats.setStatus("Failed to deposit completed orders after retry window");
        return false;
    }

    private int deliverBatchAtConveyor(APIContext ctx, int expectedOrders, long deliveryStartedAt) {
        int delivered = 0;
        while (delivered < expectedOrders) {
            int beforeReady = potionInventory.readyPotionCount(ctx);
            if (beforeReady <= 0 || !retryDeposit(ctx, 1, 1)) {
                break;
            }

            int deliveryNumber = delivered + 1;
            stats.debug("Timing conveyor batch click accepted: item="
                    + deliveryNumber + "/" + expectedOrders
                    + " elapsed=" + (System.currentTimeMillis() - deliveryStartedAt) + "ms");
            Time.sleep(1200, 2200,
                    () -> potionInventory.readyPotionCount(ctx) < beforeReady,
                    100);
            int afterReady = potionInventory.readyPotionCount(ctx);
            int accepted = Math.max(0, beforeReady - afterReady);
            if (accepted <= 0) {
                stats.setStatus("Conveyor batch click was not confirmed; keeping remaining potions tracked");
                break;
            }

            delivered += accepted;
            stats.debug("Timing conveyor batch inventory updated: delivered=" + delivered
                    + "/" + expectedOrders
                    + " lastAccepted=" + accepted
                    + " elapsed=" + (System.currentTimeMillis() - deliveryStartedAt) + "ms");
        }
        return Math.min(delivered, expectedOrders);
    }

    private boolean interactConveyor(APIContext ctx) {
        return objects.interactByIdAtTile(ctx, settings.mixingRoomArea(),
                CONVEYOR_BELT_ID, "Conveyor belt", CONVEYOR_BELT_TILE,
                CONVEYOR_BELT_APPROACH_TILE, "Fulfil-order");
    }

    private boolean hasReadyDeliveryFallback(APIContext ctx, int requiredReady) {
        int readyPotions = potionInventory.readyPotionCount(ctx);
        if (readyPotions < Math.max(1, requiredReady)) {
            return false;
        }

        stats.setStatus("Fallback: " + readyPotions
                + " ready potions in inventory; retrying conveyor delivery from current tile");
        return true;
    }

    private boolean standOnDeliveryTile(APIContext ctx) {
        int distance = CONVEYOR_BELT_APPROACH_TILE.tileDistanceTo(ctx);
        if (distance <= DELIVERY_TILE_READY_DISTANCE) {
            return true;
        }

        stats.setStatus("Clicking conveyor delivery tile "
                + tileText(CONVEYOR_BELT_APPROACH_TILE)
                + " before fulfil; dist=" + distance);
        boolean walking = distance <= 12
                && ctx.walking().walkOnScreen(CONVEYOR_BELT_APPROACH_TILE);
        if (!walking) {
            stats.setStatus("Local ground click failed for conveyor delivery tile; adjusting camera once "
                    + tileText(CONVEYOR_BELT_APPROACH_TILE));
            ctx.camera().turnTo(CONVEYOR_BELT_APPROACH_TILE);
            Time.sleep(350, 650);
            walking = ctx.walking().walkOnScreen(CONVEYOR_BELT_APPROACH_TILE);
            if (!walking) {
                stats.setStatus("Camera-assisted conveyor click failed; minimap fallback "
                        + tileText(CONVEYOR_BELT_APPROACH_TILE));
                ctx.walking().walkTo(CONVEYOR_BELT_APPROACH_TILE);
            }
            Time.sleep(900, 1500,
                    () -> ctx.localPlayer().isMoving()
                            || CONVEYOR_BELT_APPROACH_TILE.tileDistanceTo(ctx) <= DELIVERY_TILE_READY_DISTANCE,
                    100);
            return CONVEYOR_BELT_APPROACH_TILE.tileDistanceTo(ctx) <= DELIVERY_TILE_READY_DISTANCE;
        }
        Time.sleep(900, 1500,
                () -> ctx.localPlayer().isMoving()
                        || CONVEYOR_BELT_APPROACH_TILE.tileDistanceTo(ctx) <= DELIVERY_TILE_READY_DISTANCE,
                100);
        return CONVEYOR_BELT_APPROACH_TILE.tileDistanceTo(ctx) <= DELIVERY_TILE_READY_DISTANCE;
    }

    private String tileText(Tile tile) {
        return tile.getX() + "," + tile.getY() + "," + tile.getPlane();
    }

    private Map<PotionRecipe, Integer> requiredCounts(List<PotionOrder> orders) {
        Map<PotionRecipe, Integer> required = new EnumMap<>(PotionRecipe.class);
        if (orders == null) {
            return required;
        }
        for (PotionOrder order : orders) {
            if (order != null && order.recipe() != null) {
                required.merge(order.recipe(), 1, Integer::sum);
            }
        }
        return required;
    }

    private int matchingReadyCount(APIContext ctx, Map<PotionRecipe, Integer> required) {
        Map<PotionRecipe, Integer> ready = potionInventory.readyPotionCounts(ctx);
        int total = 0;
        for (Map.Entry<PotionRecipe, Integer> entry : required.entrySet()) {
            total += Math.min(entry.getValue(), ready.getOrDefault(entry.getKey(), 0));
        }
        return total;
    }

    private int totalRequired(Map<PotionRecipe, Integer> required) {
        int total = 0;
        for (int count : required.values()) {
            total += Math.max(0, count);
        }
        return total;
    }

    private String requiredText(Map<PotionRecipe, Integer> required) {
        if (required.isEmpty()) {
            return "none";
        }

        StringBuilder text = new StringBuilder();
        for (Map.Entry<PotionRecipe, Integer> entry : required.entrySet()) {
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(entry.getKey().code()).append('=').append(entry.getValue());
        }
        return text.toString();
    }
}
