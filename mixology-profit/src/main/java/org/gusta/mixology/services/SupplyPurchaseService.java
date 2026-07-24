package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IEquipmentAPI;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.data.HerbSources;
import org.gusta.mixology.data.TravelItems;
import org.gusta.mixology.domain.HerbSource;
import org.gusta.mixology.domain.HopperStock;
import org.gusta.mixology.domain.PasteSourceQuote;
import org.gusta.mixology.domain.PasteType;
import org.gusta.mixology.stats.MixologyStats;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;

public class SupplyPurchaseService {
    private static final int GE_MIN_X = 3150;
    private static final int GE_MAX_X = 3190;
    private static final int GE_MIN_Y = 3465;
    private static final int GE_MAX_Y = 3505;
    private static final Tile GRAND_EXCHANGE_WALK_TILE = new Tile(3164, 3487, 0);
    private static final int GE_SLOT_BATCH_SIZE = 8;

    private final MixologySettings settings;
    private final MixologyStats stats;
    private final ProfitPlanner profitPlanner;
    private final Queue<PurchaseRequest> pendingPurchases = new ArrayDeque<>();
    private final Map<PasteType, Integer> availablePasteByType = new EnumMap<>(PasteType.class);
    private final Map<PasteType, Integer> hopperPasteByType = new EnumMap<>(PasteType.class);
    private final Map<PasteType, Integer> targetPasteByType = new EnumMap<>(PasteType.class);

    private boolean bankChecked;
    private boolean existingSuppliesFound;
    private boolean planned;
    private boolean longRestockMode;
    private PurchaseRequest activePurchase;
    private final List<PurchaseRequest> placedBatch = new ArrayList<>();
    private long nextBatchCollectAt;
    private long nextRingTeleportAttemptAt;

    public SupplyPurchaseService(
            MixologySettings settings,
            MixologyStats stats,
            ProfitPlanner profitPlanner
    ) {
        this.settings = settings;
        this.stats = stats;
        this.profitPlanner = profitPlanner;
    }

    public boolean ensureStarterSupplies(APIContext ctx) {
        if (existingSuppliesFound) {
            return true;
        }
        if (!bankChecked) {
            checkBankForExistingSupplies(ctx);
            return existingSuppliesFound;
        }

        if (!planned) {
            planPurchases(ctx);
        }

        if (activePurchase == null) {
            activePurchase = pendingPurchases.poll();
        }

        if (activePurchase == null && pendingPurchases.isEmpty() && placedBatch.isEmpty()) {
            closeGrandExchange(ctx);
            stats.setStatus("Starter Mixology supplies are ready");
            longRestockMode = false;
            return true;
        }

        return handleActivePurchase(ctx);
    }

    public void requestRestock() {
        requestRestock(null);
    }

    public void requestRestock(HopperStock hopperStock) {
        bankChecked = false;
        existingSuppliesFound = false;
        planned = false;
        longRestockMode = true;
        pendingPurchases.clear();
        availablePasteByType.clear();
        hopperPasteByType.clear();
        targetPasteByType.clear();
        activePurchase = null;
        placedBatch.clear();
        nextBatchCollectAt = 0L;
        if (hopperStock != null && hopperStock.isComplete()) {
            for (PasteType type : PasteType.values()) {
                hopperPasteByType.put(type, Math.max(0, hopperStock.amount(type)));
            }
            stats.debug("GE restock planning includes Hopper stock: " + hopperStock.summary());
        }
    }

    private boolean checkBankForExistingSupplies(APIContext ctx) {
        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return true;
        }

        if (!ctx.bank().isOpen()) {
            stats.setStatus("Checking bank for existing Mixology supplies");
            if (!ctx.bank().isReachable()) {
                ctx.webWalking().setUseTeleports(true);
                ctx.webWalking().walkToBank();
                Time.sleep(1000, 1600);
                return true;
            }
            ctx.bank().open();
            Time.sleep(1000, 1600, () -> ctx.bank().isOpen(), 100);
            return true;
        }

        snapshotAvailablePaste(ctx);
        bankChecked = true;
        if (hasEnoughTargetStock(ctx)) {
            existingSuppliesFound = true;
            stats.setStatus("Bank check OK: Mixology supplies already stocked "
                    + availablePasteSummary() + "; skipping GE buy");
        } else {
            String targetMode = longRestockMode
                    ? "restocking paste reserve for 5-6h"
                    : "targeting starter paste reserve";
            stats.setStatus("Bank check before herb buy: " + availablePasteSummary() + "; " + targetMode);
        }
        ctx.bank().close();
        Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
        return true;
    }

    private void planPurchases(APIContext ctx) {
        List<String> labels = new ArrayList<>();
        for (PasteType type : PasteType.values()) {
            int availablePaste = availablePasteByType.getOrDefault(type, 0);
            int targetPaste = restockTargetFor(type);
            PasteSourceQuote quote = profitPlanner.cheapestSource(ctx, type).orElse(null);
            if (quote == null) {
                labels.add(type.label() + "=" + availablePaste + "/" + targetPaste
                        + " paste; no GE source found");
                continue;
            }

            if (availablePaste >= targetPaste) {
                labels.add(type.label() + "=" + availablePaste + "/" + targetPaste
                        + " paste available; skip");
                continue;
            }

            int missingPaste = Math.max(0, targetPaste - availablePaste);
            int quantity = (int) Math.ceil((double) missingPaste / quote.source().pasteYield());
            String label = type.label() + "=" + availablePaste + "/" + targetPaste
                    + " paste missing=" + missingPaste + " via " + quote.source().itemName()
                    + " (yield=" + quote.source().pasteYield() + ")";
            if (quantity <= 0) {
                labels.add(type.label() + "=" + availablePaste + " available; skip");
                continue;
            }
            pendingPurchases.add(new PurchaseRequest(type, quote.source(), targetPaste, quantity, quote.unitBuyPrice()));
            labels.add(label);
        }

        planned = true;
        stats.setStatus("Planned Mixology GE supplies: " + String.join(", ", labels));
    }

    private int restockTargetFor(PasteType type) {
        Integer existing = targetPasteByType.get(type);
        if (existing != null && existing > 0) {
            return existing;
        }
        int target = randomRestockTargetPaste();
        targetPasteByType.put(type, target);
        return target;
    }

    private void snapshotAvailablePaste(APIContext ctx) {
        availablePasteByType.clear();
        for (PasteType type : PasteType.values()) {
            availablePasteByType.put(type, countAvailablePaste(ctx, type));
        }
        stats.debug("Mixology bank stock snapshot: " + availablePasteSummary());
    }

    private int countAvailablePaste(APIContext ctx, PasteType type) {
        int pasteInventory = countInventoryItemByName(ctx, type.pasteName());
        int pasteBank = countBankItemByName(ctx, type.pasteName());
        int hopperPaste = hopperPasteByType.getOrDefault(type, 0);
        int total = pasteInventory + pasteBank + hopperPaste;
        List<String> herbParts = new ArrayList<>();
        if (ctx.bank().isOpen()) {
            herbParts.add(type.pasteName() + "=" + pasteBank);
        }
        if (hopperPaste > 0) {
            herbParts.add("hopper " + type.pasteName() + "=" + hopperPaste);
        }
        if (pasteInventory > 0) {
            herbParts.add("inv " + type.pasteName() + "=" + pasteInventory);
        }

        for (HerbSource source : HerbSources.all()) {
            if (source.pasteType() != type) {
                continue;
            }
            int inventoryCount = countInventoryItemByName(ctx, source.itemName());
            int bankCount = countBankItemByName(ctx, source.itemName());
            if (bankCount > 0) {
                herbParts.add(source.itemName() + "=" + bankCount + "=>" + (bankCount * source.pasteYield()));
            }
            if (inventoryCount > 0) {
                herbParts.add("inv " + source.itemName() + "=" + inventoryCount
                        + "=>" + (inventoryCount * source.pasteYield()));
            }
            total += (inventoryCount + bankCount) * source.pasteYield();
        }
        stats.debug("Stock detail " + type.label() + ": "
                + (herbParts.isEmpty() ? "none" : String.join(", ", herbParts))
                + " totalPaste=" + total);
        return total;
    }

    private boolean hasEnoughTargetStock(APIContext ctx) {
        for (PasteType type : PasteType.values()) {
            int availablePaste = availablePasteByType.getOrDefault(type, 0);
            int requiredPaste = longRestockMode
                    ? restockTargetFor(type)
                    : MixologySettings.MIN_RESTOCK_PASTE_PER_TYPE;
            if (availablePaste < requiredPaste) {
                return false;
            }
        }
        return true;
    }

    private int randomRestockTargetPaste() {
        int min = MixologySettings.MIN_RESTOCK_PASTE_PER_TYPE;
        int max = MixologySettings.MAX_RESTOCK_PASTE_PER_TYPE;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private String availablePasteSummary() {
        List<String> parts = new ArrayList<>();
        for (PasteType type : PasteType.values()) {
            parts.add(type.label() + "=" + availablePasteByType.getOrDefault(type, 0));
        }
        return String.join(", ", parts);
    }

    private boolean handleActivePurchase(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return false;
        }

        PurchaseRequest currentOrNext = activePurchase != null ? activePurchase : pendingPurchases.peek();
        if (!isAtGrandExchange(ctx)) {
            if (tryRingOfWealthTeleport(ctx)) {
                return false;
            }
            String label = currentOrNext == null ? "GE batch collect" : currentOrNext.label();
            stats.setStatus("Walking to GE for " + label);
            ctx.webWalking().setUseTeleports(true);
            ctx.webWalking().walkTo(GRAND_EXCHANGE_WALK_TILE);
            Time.sleep(1200, 1800);
            return false;
        }

        if (!ctx.grandExchange().isOpen()) {
            String label = currentOrNext == null ? "GE batch" : currentOrNext.label();
            stats.setStatus("Opening GE for " + label);
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return false;
        }

        if (confirmHighPriceWarning(ctx)) {
            return false;
        }

        if (shouldCollectBatch(ctx)) {
            collectCompletedBatch(ctx);
            return false;
        }

        if (activePurchase == null) {
            activePurchase = pendingPurchases.poll();
            if (activePurchase == null) {
                if (!placedBatch.isEmpty()) {
                    collectCompletedBatch(ctx);
                }
                return false;
            }
        }

        stats.debug("GE open for purchase: " + activePurchase.quantity + "x "
                + activePurchase.source.itemName()
                + " unitPrice=" + activePurchase.unitPrice
                + " batchPlaced=" + placedBatch.size()
                + " pending=" + pendingPurchases.size());

        GrandExchangeSlot existingSlot = findActiveSlot(ctx, activePurchase);
        if (existingSlot != null) {
            if (!containsPlacedPurchase(activePurchase)) {
                placedBatch.add(activePurchase);
            }
            pendingPurchases.removeIf(request ->
                    request.source.itemName().equals(activePurchase.source.itemName()));
            stats.setStatus("Existing GE herb offer already open; not rebuying: "
                    + activePurchase.source.itemName() + " slot=" + existingSlot.getIndex());
            activePurchase = null;
            nextBatchCollectAt = System.currentTimeMillis() + 2_500L;
            return false;
        }

        stats.setStatus("Buying " + activePurchase.quantity + "x "
                + activePurchase.source.itemName() + " for " + activePurchase.unitPrice + " each");
        boolean placed = ctx.grandExchange().placeBuyOffer(
                activePurchase.source.itemName(),
                activePurchase.quantity,
                activePurchase.unitPrice
        );
        Time.sleep(1000, 1500);
        if (!placed) {
            if (!confirmHighPriceWarning(ctx)) {
                stats.setStatus("GE buy offer was not placed for " + activePurchase.source.itemName());
            }
            return false;
        }

        placedBatch.add(activePurchase);
        stats.setStatus("GE batch offers placed " + placedBatch.size() + "/"
                + GE_SLOT_BATCH_SIZE + ": " + activePurchase.source.itemName());
        activePurchase = null;
        nextBatchCollectAt = System.currentTimeMillis() + 2_500L;
        return false;
    }

    private boolean tryRingOfWealthTeleport(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextRingTeleportAttemptAt) {
            return false;
        }

        ItemWidget equippedRing = ctx.equipment().getItem(IEquipmentAPI.Slot.RING);
        if (equippedRing != null && TravelItems.isChargedRingOfWealth(equippedRing.getName())) {
            nextRingTeleportAttemptAt = now + 15_000L;
            stats.setStatus("Teleporting to GE with equipped Ring of wealth");
            if (interactRingTeleport(equippedRing)) {
                Time.sleep(2500, 5000,
                        () -> isAtGrandExchange(ctx)
                                || ctx.localPlayer().isMoving(),
                        100);
                return true;
            }
        }

        ItemWidget inventoryRing = ctx.inventory().getItem(item ->
                item != null && TravelItems.isChargedRingOfWealth(item.getName()));
        if (inventoryRing == null) {
            return false;
        }

        nextRingTeleportAttemptAt = now + 15_000L;
        stats.setStatus("Teleporting to GE with inventory Ring of wealth");
        if (interactRingTeleport(inventoryRing)) {
            Time.sleep(2500, 5000,
                    () -> isAtGrandExchange(ctx)
                            || ctx.localPlayer().isMoving(),
                    100);
            return true;
        }
        return false;
    }

    private boolean interactRingTeleport(ItemWidget ring) {
        String name = ring.getName();
        return ring.interact("Grand Exchange", name)
                || ring.interact("Grand Exchange")
                || ring.interact("Grand Exchange teleport", name)
                || ring.interact("Grand Exchange teleport")
                || ring.interact("Rub", name)
                || ring.interact("Rub");
    }

    private boolean isAtGrandExchange(APIContext ctx) {
        Tile tile = ctx.localPlayer().getLocation();
        if (tile == null || tile.getPlane() != 0) {
            return false;
        }
        return tile.getX() >= GE_MIN_X
                && tile.getX() <= GE_MAX_X
                && tile.getY() >= GE_MIN_Y
                && tile.getY() <= GE_MAX_Y;
    }

    private boolean shouldCollectBatch(APIContext ctx) {
        return !placedBatch.isEmpty()
                && (pendingPurchases.isEmpty()
                || placedBatch.size() >= GE_SLOT_BATCH_SIZE
                || activeGeSlotCount(ctx) >= GE_SLOT_BATCH_SIZE);
    }

    private void collectCompletedBatch(APIContext ctx) {
        if (System.currentTimeMillis() < nextBatchCollectAt) {
            Time.sleep(600, 900);
            return;
        }

        int ready = 0;
        int waiting = 0;
        for (PurchaseRequest request : placedBatch) {
            GrandExchangeSlot slot = findActiveSlot(ctx, request);
            if (slot == null || slot.isCompleted() || slot.canCollect()) {
                ready++;
            } else {
                waiting++;
            }
        }

        if (waiting > 0) {
            stats.setStatus("Waiting for GE batch before collect: ready="
                    + ready + "/" + placedBatch.size() + " waiting=" + waiting);
            nextBatchCollectAt = System.currentTimeMillis() + 4_000L;
            Time.sleep(600, 900);
            return;
        }

        stats.setStatus("Collecting GE batch to bank: " + placedBatch.size() + " offer(s)");
        try {
            ctx.grandExchange().collectToBank();
        } catch (RuntimeException ignored) {
            // Collection is harmless to retry when the offer is not quite ready.
        }
        Time.sleep(700, 1100);
        placedBatch.removeIf(request -> findActiveSlot(ctx, request) == null);
        nextBatchCollectAt = placedBatch.isEmpty() ? 0L : System.currentTimeMillis() + 4_000L;
    }

    private GrandExchangeSlot findActiveSlot(APIContext ctx, PurchaseRequest request) {
        for (GrandExchangeSlot slot : ctx.grandExchange().getSlots()) {
            if (slot == null || !slot.inUse() || slot.getOffer() == null) {
                continue;
            }
            GrandExchangeOffer offer = slot.getOffer();
            if (request.source.itemName().equals(offer.getItemName())) {
                return slot;
            }
        }
        return null;
    }

    private boolean containsPlacedPurchase(PurchaseRequest request) {
        for (PurchaseRequest placed : placedBatch) {
            if (placed.source.itemName().equals(request.source.itemName())) {
                return true;
            }
        }
        return false;
    }

    private int activeGeSlotCount(APIContext ctx) {
        int count = 0;
        for (GrandExchangeSlot slot : ctx.grandExchange().getSlots()) {
            if (slot != null && slot.inUse()) {
                count++;
            }
        }
        return count;
    }

    private boolean confirmHighPriceWarning(APIContext ctx) {
        WidgetChild confirm = ctx.widgets().query()
                .textContains("Yes")
                .results()
                .first();
        if (confirm == null || !confirm.isValid()) {
            return false;
        }

        String allText = allWidgetText(ctx).toLowerCase();
        if (!allText.contains("much higher") && !allText.contains("are you sure")) {
            return false;
        }

        String label = activePurchase == null ? "GE batch" : activePurchase.label();
        stats.setStatus("Confirming GE price warning for " + label);
        if (!confirm.interact("Continue") && !confirm.interact("Yes")) {
            confirm.click();
        }
        Time.sleep(700, 1100);
        return true;
    }

    private String allWidgetText(APIContext ctx) {
        StringBuilder text = new StringBuilder();
        for (WidgetChild widget : ctx.widgets().getAllChildren(widget -> widget != null && widget.isValid())) {
            String value = widget.getText();
            if (value == null || value.isBlank()) {
                value = widget.getRawText();
            }
            if (value != null && !value.isBlank()) {
                text.append(' ').append(value.replaceAll("<[^>]+>", " "));
            }
        }
        return text.toString();
    }

    private void closeGrandExchange(APIContext ctx) {
        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(600, 900, () -> !ctx.grandExchange().isOpen(), 100);
        }
    }

    private boolean hasAnyMixologyInput(APIContext ctx) {
        return hasInventoryMixologyInput(ctx) || (ctx.bank().isOpen() && hasBankMixologyInput(ctx));
    }

    private boolean hasInventoryMixologyInput(APIContext ctx) {
        for (PasteType type : PasteType.values()) {
            if (countInventoryItemByName(ctx, type.pasteName()) > 0) {
                return true;
            }
        }
        for (HerbSource source : HerbSources.all()) {
            if (countInventoryItemByName(ctx, source.itemName()) > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBankMixologyInput(APIContext ctx) {
        for (PasteType type : PasteType.values()) {
            if (countBankItemByName(ctx, type.pasteName()) > 0) {
                return true;
            }
        }
        for (HerbSource source : HerbSources.all()) {
            if (countBankItemByName(ctx, source.itemName()) > 0) {
                return true;
            }
        }
        return false;
    }

    private int countInventoryItemByName(APIContext ctx, String itemName) {
        return countItemsByName(ctx.inventory().getItems(), itemName, false);
    }

    private int countBankItemByName(APIContext ctx, String itemName) {
        if (!ctx.bank().isOpen()) {
            return 0;
        }
        return countItemsByName(ctx.bank().getItems(), itemName, true);
    }

    private int countItemsByName(Iterable<? extends ItemWidget> items, String itemName, boolean bankItems) {
        int total = 0;
        for (ItemWidget item : items) {
            if (item == null || !itemNameMatches(item.getName(), itemName)) {
                continue;
            }

            int stackSize = item.getStackSize();
            if (stackSize <= 0 && bankItems) {
                continue;
            }
            total += bankItems ? stackSize : Math.max(1, stackSize);
        }
        return total;
    }

    private boolean itemNameMatches(String actualName, String expectedName) {
        String actual = normalizeItemName(actualName);
        String expected = normalizeItemName(expectedName);
        return actual.equals(expected) || actual.equals("clean " + expected);
    }

    private String normalizeItemName(String value) {
        return value == null
                ? ""
                : value.replaceAll("<[^>]+>", " ")
                .toLowerCase()
                .replaceAll("[^a-z0-9() ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static class PurchaseRequest {
        private final PasteType pasteType;
        private final HerbSource source;
        private final int targetPaste;
        private final int quantity;
        private final int unitPrice;

        private PurchaseRequest(
                PasteType pasteType,
                HerbSource source,
                int targetPaste,
                int quantity,
                int unitPrice
        ) {
            this.pasteType = pasteType;
            this.source = source;
            this.targetPaste = targetPaste;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
        }

        private String label() {
            return pasteType.label() + " target=" + targetPaste + " via " + source.itemName();
        }
    }
}
