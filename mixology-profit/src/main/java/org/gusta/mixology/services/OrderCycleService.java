package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.domain.PotionOrder;
import org.gusta.mixology.domain.PotionRecipe;
import org.gusta.mixology.stats.MixologyStats;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OrderCycleService {
    private final MixerService mixer;
    private final ProcessingService processing;
    private final ConveyorService conveyor;
    private final BankService bank;
    private final MixologyStats stats;
    private final PotionInventoryService potionInventory;
    private PotionOrder pendingFinalizerOrder;
    private final List<PotionOrder> trackedReadyOrders = new ArrayList<>();
    private List<PotionOrder> activeOrderBatch = new ArrayList<>();

    public OrderCycleService(
            MixerService mixer,
            ProcessingService processing,
            ConveyorService conveyor,
            BankService bank,
            MixologyStats stats,
            PotionInventoryService potionInventory
    ) {
        this.mixer = mixer;
        this.processing = processing;
        this.conveyor = conveyor;
        this.bank = bank;
        this.stats = stats;
        this.potionInventory = potionInventory;
    }

    public boolean executeCycle(APIContext ctx, List<PotionOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            stats.setStatus("No orders ready to execute");
            return false;
        }

        syncActiveOrderBatch(orders);

        if (pendingFinalizerOrder != null) {
            stats.setStatus("Resuming pending finalizer: " + pendingFinalizerOrder.label());
            if (!processing.processPotion(ctx, pendingFinalizerOrder)) {
                return false;
            }
            recordTrackedReadyOrder(pendingFinalizerOrder);
            pendingFinalizerOrder = null;
        }

        int readyBefore = potionInventory.readyPotionCount(ctx);
        int carriedBefore = potionInventory.anyPotionCount(ctx);
        if (carriedBefore > 0) {
            stats.debug("Existing Mixology potion item(s) before order cycle: ready="
                    + potionInventory.readyPotionDetails(ctx)
                    + " all=" + potionInventory.allPotionDetails(ctx)
                    + " required=" + requiredText(requiredCounts(orders)));
            stats.setStatus("Resuming partial Mixology batch with "
                    + carriedBefore
                    + " carried potion(s); tracked="
                    + trackedOrderText());
            if (hasTrackedRequiredOrders(orders) && hasRequiredPotions(ctx, orders)) {
                stats.setStatus("Tracked batch already has 3 confirmed order potions; delivering batch");
                return conveyor.depositOrders(ctx, orders);
            }
        }

        Map<String, Integer> availableTracked = cappedTrackedCounts(orders);
        Map<PotionRecipe, Integer> availableUnfinished = cappedUnfinishedCounts(ctx, orders);
        int completedLocally = 0;
        for (PotionOrder order : orders) {
            if (!order.isComplete()) {
                stats.setStatus("Incomplete order data: " + order.label());
                return false;
            }

            if (consumeTrackedOrder(availableTracked, order)) {
                completedLocally++;
                stats.setStatus("Keeping tracked ready potion for order: " + order.label());
                continue;
            }

            if (consumePotionForOrder(availableUnfinished, order)) {
                stats.setLastOrder(order.label());
                stats.setStatus("Finishing carried unfinished potion for order: " + order.label());
                pendingFinalizerOrder = order;
                if (!processing.processPotion(ctx, order)) {
                    return false;
                }
                recordTrackedReadyOrder(order);
                pendingFinalizerOrder = null;
                completedLocally++;
                Time.sleep(450, 850);
                continue;
            }

            stats.setLastOrder(order.label());
            if (!mixer.mixBase(ctx, order)) {
                return false;
            }
            pendingFinalizerOrder = order;
            if (!processing.processPotion(ctx, order)) {
                return false;
            }
            recordTrackedReadyOrder(order);
            pendingFinalizerOrder = null;
            completedLocally++;
            Time.sleep(450, 850);
        }

        if (completedLocally <= 0) {
            stats.setStatus("No completed Mixology orders available for conveyor");
            return false;
        }

        int readyAfter = matchingReadyCount(ctx, orders);
        if (!hasTrackedRequiredOrders(orders) || !hasRequiredPotions(ctx, orders)) {
            stats.setStatus("Only " + readyAfter
                    + "/" + orders.size()
                    + " matching processed order potion(s) ready; tracked="
                    + trackedOrderText()
                    + "; keeping partial batch");
            stats.debug("Partial batch details before conveyor block: "
                    + potionInventory.allPotionDetails(ctx));
            return false;
        }

        stats.debug("Current-cycle orders processed=" + completedLocally
                + " readyBefore=" + readyBefore
                + " readyNow=" + readyAfter
                + " tracked=" + trackedOrderText()
                + " details=" + potionInventory.allPotionDetails(ctx));
        stats.setStatus("Depositing " + Math.min(orders.size(), readyAfter) + " order potions together");
        boolean delivered = conveyor.depositOrders(ctx, orders);
        if (delivered) {
            trackedReadyOrders.clear();
        } else {
            stats.debug("Conveyor delivery failed/partial; clearing tracked batch for safe rebuild");
            trackedReadyOrders.clear();
        }
        return delivered;
    }

    public boolean hasTrackedRequiredOrdersForCurrentBatch(List<PotionOrder> orders) {
        return sameOrderBatch(activeOrderBatch, orders) && hasTrackedRequiredOrders(orders);
    }

    public List<PotionOrder> remainingOrdersForCurrentBatch(List<PotionOrder> orders) {
        List<PotionOrder> remaining = new ArrayList<>();
        if (orders == null || orders.isEmpty()) {
            return remaining;
        }

        Map<String, Integer> tracked = sameOrderBatch(activeOrderBatch, orders)
                ? orderKeyCounts(trackedReadyOrders)
                : new HashMap<>();
        for (PotionOrder order : orders) {
            if (order == null || !order.isComplete()) {
                continue;
            }

            String key = orderKey(order);
            int available = tracked.getOrDefault(key, 0);
            if (available > 0) {
                if (available == 1) {
                    tracked.remove(key);
                } else {
                    tracked.put(key, available - 1);
                }
                continue;
            }
            remaining.add(copyOrder(order));
        }
        return remaining;
    }

    public void resetTrackedBatch(String reason) {
        if (!activeOrderBatch.isEmpty() || !trackedReadyOrders.isEmpty() || pendingFinalizerOrder != null) {
            stats.debug("Resetting tracked order batch: " + reason
                    + " active=" + orderBatchText(activeOrderBatch)
                    + " tracked=" + trackedOrderText()
                    + " pending=" + (pendingFinalizerOrder == null ? "none" : pendingFinalizerOrder.label()));
        }
        activeOrderBatch = new ArrayList<>();
        trackedReadyOrders.clear();
        pendingFinalizerOrder = null;
    }

    private Map<PotionRecipe, Integer> requiredCounts(List<PotionOrder> orders) {
        Map<PotionRecipe, Integer> required = new EnumMap<>(PotionRecipe.class);
        for (PotionOrder order : orders) {
            if (order != null && order.recipe() != null) {
                required.merge(order.recipe(), 1, Integer::sum);
            }
        }
        return required;
    }

    private Map<PotionRecipe, Integer> cappedUnfinishedCounts(APIContext ctx, List<PotionOrder> orders) {
        Map<PotionRecipe, Integer> required = requiredCounts(orders);
        Map<PotionRecipe, Integer> unfinished = potionInventory.unfinishedPotionCounts(ctx);
        Map<PotionRecipe, Integer> capped = new EnumMap<>(PotionRecipe.class);
        for (Map.Entry<PotionRecipe, Integer> entry : required.entrySet()) {
            int alreadyReady = trackedReadyRecipeCount(entry.getKey(), orders);
            int stillRequired = Math.max(0, entry.getValue() - alreadyReady);
            int available = unfinished.getOrDefault(entry.getKey(), 0);
            if (available > 0 && stillRequired > 0) {
                capped.put(entry.getKey(), Math.min(available, stillRequired));
            }
        }
        return capped;
    }

    private Map<String, Integer> cappedTrackedCounts(List<PotionOrder> orders) {
        Map<String, Integer> required = orderKeyCounts(orders);
        Map<String, Integer> tracked = orderKeyCounts(trackedReadyOrders);
        Map<String, Integer> capped = new HashMap<>();
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            int available = tracked.getOrDefault(entry.getKey(), 0);
            if (available > 0) {
                capped.put(entry.getKey(), Math.min(available, entry.getValue()));
            }
        }
        return capped;
    }

    private boolean consumeTrackedOrder(Map<String, Integer> availableTracked, PotionOrder order) {
        String key = orderKey(order);
        int available = availableTracked.getOrDefault(key, 0);
        if (available <= 0) {
            return false;
        }

        if (available == 1) {
            availableTracked.remove(key);
        } else {
            availableTracked.put(key, available - 1);
        }
        return true;
    }

    private boolean consumePotionForOrder(Map<PotionRecipe, Integer> availableReady, PotionOrder order) {
        if (order == null || order.recipe() == null) {
            return false;
        }

        int available = availableReady.getOrDefault(order.recipe(), 0);
        if (available <= 0) {
            return false;
        }

        if (available == 1) {
            availableReady.remove(order.recipe());
        } else {
            availableReady.put(order.recipe(), available - 1);
        }
        return true;
    }

    private boolean hasRequiredPotions(APIContext ctx, List<PotionOrder> orders) {
        Map<PotionRecipe, Integer> required = requiredCounts(orders);
        Map<PotionRecipe, Integer> ready = potionInventory.readyPotionCounts(ctx);
        for (Map.Entry<PotionRecipe, Integer> entry : required.entrySet()) {
            if (ready.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                return false;
            }
        }
        return !required.isEmpty();
    }

    private boolean hasTrackedRequiredOrders(List<PotionOrder> orders) {
        Map<String, Integer> required = orderKeyCounts(orders);
        Map<String, Integer> tracked = orderKeyCounts(trackedReadyOrders);
        for (Map.Entry<String, Integer> entry : required.entrySet()) {
            if (tracked.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                return false;
            }
        }
        return !required.isEmpty();
    }

    private void syncActiveOrderBatch(List<PotionOrder> orders) {
        if (sameOrderBatch(activeOrderBatch, orders)) {
            return;
        }
        if (!activeOrderBatch.isEmpty() || !trackedReadyOrders.isEmpty() || pendingFinalizerOrder != null) {
            stats.debug("Order batch changed; clearing tracked ready state. old="
                    + orderBatchText(activeOrderBatch)
                    + " new="
                    + orderBatchText(orders)
                    + " tracked="
                    + trackedOrderText());
        }
        activeOrderBatch = copyOrders(orders);
        trackedReadyOrders.clear();
        pendingFinalizerOrder = null;
    }

    private void recordTrackedReadyOrder(PotionOrder order) {
        if (order == null || !order.isComplete()) {
            return;
        }
        Map<String, Integer> required = orderKeyCounts(activeOrderBatch);
        String key = orderKey(order);
        int requiredCount = required.getOrDefault(key, 0);
        if (requiredCount <= 0) {
            stats.debug("Ignoring finalized potion outside current tracked batch: " + order.label());
            return;
        }
        int trackedCount = orderKeyCounts(trackedReadyOrders).getOrDefault(key, 0);
        if (trackedCount >= requiredCount) {
            stats.debug("Ignoring extra finalized potion beyond current batch requirement: " + order.label());
            return;
        }
        trackedReadyOrders.add(copyOrder(order));
        stats.debug("Tracked ready order confirmed by id+chat: "
                + order.label()
                + " tracked="
                + trackedOrderText());
    }

    private int trackedReadyRecipeCount(PotionRecipe recipe, List<PotionOrder> orders) {
        if (recipe == null) {
            return 0;
        }
        int requiredByRecipe = requiredCounts(orders).getOrDefault(recipe, 0);
        int trackedByRecipe = 0;
        for (PotionOrder order : trackedReadyOrders) {
            if (order != null && order.recipe() == recipe) {
                trackedByRecipe++;
            }
        }
        return Math.min(requiredByRecipe, trackedByRecipe);
    }

    private int matchingReadyCount(APIContext ctx, List<PotionOrder> orders) {
        Map<PotionRecipe, Integer> required = requiredCounts(orders);
        Map<PotionRecipe, Integer> ready = potionInventory.readyPotionCounts(ctx);
        int total = 0;
        for (Map.Entry<PotionRecipe, Integer> entry : required.entrySet()) {
            total += Math.min(entry.getValue(), ready.getOrDefault(entry.getKey(), 0));
        }
        return total;
    }

    private boolean sameOrderBatch(List<PotionOrder> left, List<PotionOrder> right) {
        return orderKeyCounts(left).equals(orderKeyCounts(right));
    }

    private Map<String, Integer> orderKeyCounts(List<PotionOrder> orders) {
        Map<String, Integer> counts = new HashMap<>();
        if (orders == null) {
            return counts;
        }
        for (PotionOrder order : orders) {
            if (order == null || !order.isComplete()) {
                continue;
            }
            counts.merge(orderKey(order), 1, Integer::sum);
        }
        return counts;
    }

    private String orderKey(PotionOrder order) {
        if (order == null || !order.isComplete()) {
            return "incomplete";
        }
        return order.recipe().name() + ":" + order.process().name();
    }

    private List<PotionOrder> copyOrders(List<PotionOrder> orders) {
        List<PotionOrder> copy = new ArrayList<>();
        if (orders == null) {
            return copy;
        }
        for (PotionOrder order : orders) {
            if (order != null) {
                copy.add(copyOrder(order));
            }
        }
        return copy;
    }

    private PotionOrder copyOrder(PotionOrder order) {
        return new PotionOrder(order.recipe(), order.process());
    }

    private String orderBatchText(List<PotionOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return "none";
        }
        StringBuilder text = new StringBuilder();
        for (PotionOrder order : orders) {
            if (text.length() > 0) {
                text.append(" | ");
            }
            text.append(order == null ? "null" : order.label());
        }
        return text.toString();
    }

    private String trackedOrderText() {
        return orderBatchText(trackedReadyOrders);
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
