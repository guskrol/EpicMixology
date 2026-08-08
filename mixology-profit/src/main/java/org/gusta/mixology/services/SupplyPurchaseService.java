package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IEquipmentAPI;
import com.epicbot.api.shared.methods.IGrandExchangeAPI;
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
    private static final long BUY_REPRICE_DELAY_MILLIS = 10_000L;
    private static final int MIN_INITIAL_BUY_MARKUP_PERCENT = 15;
    private static final int MAX_INITIAL_BUY_MARKUP_PERCENT = 25;
    private static final int MIN_BUY_REPRICE_PERCENT = 10;
    private static final int MAX_BUY_REPRICE_PERCENT = 25;
    private static final int MAX_BUY_REPRICE_ATTEMPTS = 1;
    private static final int MAX_GE_GUIDE_PRICE_READ_ATTEMPTS = 3;
    private static final long GE_GUIDE_SUBMIT_CONFIRM_MILLIS = 5_000L;

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
    private PurchaseRequest pendingReprice;
    private long nextRepriceCollectAt;
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
        pendingReprice = null;
        nextRepriceCollectAt = 0L;
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
            BankOpenService.open(ctx, stats, "Checking bank for existing Mixology supplies");
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
            pendingPurchases.add(new PurchaseRequest(
                    type,
                    quote.source(),
                    targetPaste,
                    quantity,
                    quote.unitBuyPrice(),
                    profitPlanner.isClientPricingInCooldown()
            ));
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

        if (handlePendingRepriceCollection(ctx)) {
            return false;
        }

        if (repriceTimedOutPurchase(ctx)) {
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
                if (activePurchase.offerPlacedAt <= 0L) {
                    activePurchase.offerPlacedAt = System.currentTimeMillis();
                }
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

        if (resolveInitialGePrice(ctx, activePurchase)) {
            return false;
        }

        applyInitialPurchaseMarkup(activePurchase);

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

        activePurchase.offerPlacedAt = System.currentTimeMillis();
        placedBatch.add(activePurchase);
        stats.setStatus("GE batch offers placed " + placedBatch.size() + "/"
                + GE_SLOT_BATCH_SIZE + ": " + activePurchase.source.itemName());
        activePurchase = null;
        nextBatchCollectAt = System.currentTimeMillis() + 2_500L;
        return false;
    }

    private boolean resolveInitialGePrice(APIContext ctx, PurchaseRequest request) {
        if (!request.useGeGuidePrice || request.repriceAttempts > 0) {
            return false;
        }

        IGrandExchangeAPI.GrandExchangeScreen screen = ctx.grandExchange().getCurrentScreen();
        long now = System.currentTimeMillis();
        if (request.offerSubmittedAt > 0L) {
            if (now - request.offerSubmittedAt < GE_GUIDE_SUBMIT_CONFIRM_MILLIS) {
                stats.setStatus("Waiting for GE guide-price offer confirmation: "
                        + request.source.itemName());
                Time.sleep(600, 900);
                return true;
            }
            request.offerSubmittedAt = 0L;
            if (screen == IGrandExchangeAPI.GrandExchangeScreen.OVERVIEW) {
                request.guidePriceResolved = false;
                request.offerPriceConfigured = false;
                request.quantityConfigured = false;
                request.initialMarkupApplied = false;
                request.initialMarkupPercent = 0;
            }
            stats.setStatus("GE guide-price offer not confirmed; retrying current setup for "
                    + request.source.itemName());
        }

        if (screen == IGrandExchangeAPI.GrandExchangeScreen.SETUP_BUY_OFFER) {
            if (!request.guidePriceResolved) {
                int displayedPrice = ctx.grandExchange().getOfferPrice();
                if (displayedPrice > 0) {
                    request.unitPrice = displayedPrice;
                    request.guidePriceResolved = true;
                    stats.setStatus("Using GE displayed price for " + request.source.itemName()
                            + ": " + displayedPrice + " each");
                } else {
                    request.guidePriceReadAttempts++;
                    if (request.guidePriceReadAttempts >= MAX_GE_GUIDE_PRICE_READ_ATTEMPTS) {
                        request.guidePriceResolved = true;
                        stats.setStatus("GE displayed price unavailable for " + request.source.itemName()
                                + "; applying planned fallback " + request.unitPrice);
                    }
                }
                Time.sleep(500, 800);
                return true;
            }

            if (!request.offerPriceConfigured) {
                applyInitialPurchaseMarkup(request);
                stats.setStatus("Setting initial GE price for " + request.source.itemName()
                        + ": " + request.unitPrice + " (" + request.initialMarkupPercent + "% higher)");
                boolean priceSet = ctx.grandExchange().setPrice(request.unitPrice);
                Time.sleep(500, 900,
                        () -> ctx.grandExchange().getOfferPrice() == request.unitPrice,
                        100);
                request.offerPriceConfigured = priceSet
                        || ctx.grandExchange().getOfferPrice() == request.unitPrice;
                return true;
            }

            if (!request.quantityConfigured) {
                stats.setStatus("Setting GE quantity for " + request.source.itemName()
                        + ": " + request.quantity);
                boolean quantitySet = ctx.grandExchange().setQuantity(request.quantity);
                Time.sleep(500, 900,
                        () -> ctx.grandExchange().getOfferQuantity() == request.quantity,
                        100);
                request.quantityConfigured = quantitySet
                        || ctx.grandExchange().getOfferQuantity() == request.quantity;
                return true;
            }

            stats.setStatus("Confirming " + request.quantity + "x "
                    + request.source.itemName() + " at GE displayed price " + request.unitPrice);
            boolean submitted = ctx.grandExchange().confirmOffer();
            request.offerSubmittedAt = System.currentTimeMillis();
            stats.debug("GE guide-price confirm requested: item=" + request.source.itemName()
                    + " result=" + submitted);
            Time.sleep(800, 1300,
                    () -> findActiveSlot(ctx, request) != null
                            || ctx.grandExchange().getCurrentScreen()
                            == IGrandExchangeAPI.GrandExchangeScreen.OVERVIEW,
                    100);
            return true;
        }

        if (screen == IGrandExchangeAPI.GrandExchangeScreen.ACTIVE_BUY_OFFER) {
            stats.setStatus("Waiting for active GE herb offer slot: " + request.source.itemName());
            Time.sleep(600, 900);
            return true;
        }

        if (screen != IGrandExchangeAPI.GrandExchangeScreen.OVERVIEW) {
            ctx.grandExchange().backToOverview();
            Time.sleep(600, 900);
            return true;
        }

        stats.setStatus("Reading GE displayed price for " + request.source.itemName());
        boolean opened = ctx.grandExchange().newBuyOffer(request.source.itemName());
        if (!opened) {
            request.guidePriceReadAttempts++;
            if (request.guidePriceReadAttempts >= MAX_GE_GUIDE_PRICE_READ_ATTEMPTS) {
                request.guidePriceResolved = true;
                stats.setStatus("Could not open GE guide price for " + request.source.itemName()
                        + "; retaining planned fallback " + request.unitPrice);
            }
        }
        Time.sleep(800, 1300,
                () -> ctx.grandExchange().getCurrentScreen()
                        == IGrandExchangeAPI.GrandExchangeScreen.SETUP_BUY_OFFER,
                100);
        return true;
    }

    private void applyInitialPurchaseMarkup(PurchaseRequest request) {
        if (request.initialMarkupApplied || request.repriceAttempts > 0) {
            return;
        }
        int markupPercent = ThreadLocalRandom.current().nextInt(
                MIN_INITIAL_BUY_MARKUP_PERCENT,
                MAX_INITIAL_BUY_MARKUP_PERCENT + 1);
        int basePrice = Math.max(1, request.unitPrice);
        double divisor = request.useGeGuidePrice ? 100.0D : 115.0D;
        request.unitPrice = (int) Math.min(Integer.MAX_VALUE,
                Math.max(1L, (long) Math.ceil(basePrice * (100 + markupPercent) / divisor)));
        request.initialMarkupPercent = markupPercent;
        request.initialMarkupApplied = true;
    }

    private boolean repriceTimedOutPurchase(APIContext ctx) {
        if (activePurchase != null) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (int index = 0; index < placedBatch.size(); index++) {
            PurchaseRequest request = placedBatch.get(index);
            if (request.repriceAttempts >= MAX_BUY_REPRICE_ATTEMPTS
                    || request.offerPlacedAt <= 0L
                    || now - request.offerPlacedAt < BUY_REPRICE_DELAY_MILLIS) {
                continue;
            }

            GrandExchangeSlot slot = findActiveSlot(ctx, request);
            if (slot == null || slot.isCompleted() || slot.canCollect() || slot.getOffer() == null) {
                continue;
            }

            GrandExchangeOffer offer = slot.getOffer();
            int remaining = offer.getRemaining();
            if (remaining <= 0) {
                continue;
            }

            int currentPrice = Math.max(request.unitPrice, offer.getPrice());
            int increasePercent = ThreadLocalRandom.current().nextInt(
                    MIN_BUY_REPRICE_PERCENT,
                    MAX_BUY_REPRICE_PERCENT + 1);
            int increasedPrice = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(1L, (long) Math.ceil(currentPrice * (100 + increasePercent) / 100.0D)));
            stats.setStatus("GE herb offer slow; repricing remaining " + remaining + "x "
                    + request.source.itemName() + " " + increasePercent + "% higher from "
                    + currentPrice + " to " + increasedPrice);
            if (!slot.abortOffer()) {
                Time.sleep(600, 900);
                return true;
            }

            placedBatch.remove(index);
            pendingReprice = new PurchaseRequest(
                    request.pasteType,
                    request.source,
                    request.targetPaste,
                    remaining,
                    increasedPrice,
                    request.repriceAttempts + 1
            );
            nextRepriceCollectAt = System.currentTimeMillis() + 1_000L;
            Time.sleep(900, 1400);
            return true;
        }
        return false;
    }

    private boolean handlePendingRepriceCollection(APIContext ctx) {
        if (pendingReprice == null) {
            return false;
        }
        if (System.currentTimeMillis() < nextRepriceCollectAt) {
            Time.sleep(500, 800);
            return true;
        }

        stats.setStatus("Collecting aborted herb offer before higher relist: "
                + pendingReprice.source.itemName());
        try {
            ctx.grandExchange().collectToBank();
        } catch (RuntimeException ignored) {
            // Collection can be retried until the aborted slot is cleared.
        }
        Time.sleep(900, 1400, () -> findActiveSlot(ctx, pendingReprice) == null, 100);
        if (findActiveSlot(ctx, pendingReprice) != null) {
            nextRepriceCollectAt = System.currentTimeMillis() + 1_500L;
            return true;
        }

        activePurchase = pendingReprice;
        pendingReprice = null;
        nextRepriceCollectAt = 0L;
        return true;
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
        private int unitPrice;
        private final int repriceAttempts;
        private final boolean useGeGuidePrice;
        private long offerPlacedAt;
        private boolean guidePriceResolved;
        private int guidePriceReadAttempts;
        private boolean offerPriceConfigured;
        private boolean quantityConfigured;
        private long offerSubmittedAt;
        private boolean initialMarkupApplied;
        private int initialMarkupPercent;

        private PurchaseRequest(
                PasteType pasteType,
                HerbSource source,
                int targetPaste,
                int quantity,
                int unitPrice,
                boolean useGeGuidePrice
        ) {
            this(pasteType, source, targetPaste, quantity, unitPrice, 0, useGeGuidePrice);
        }

        private PurchaseRequest(
                PasteType pasteType,
                HerbSource source,
                int targetPaste,
                int quantity,
                int unitPrice,
                int repriceAttempts
        ) {
            this(pasteType, source, targetPaste, quantity, unitPrice, repriceAttempts, false);
        }

        private PurchaseRequest(
                PasteType pasteType,
                HerbSource source,
                int targetPaste,
                int quantity,
                int unitPrice,
                int repriceAttempts,
                boolean useGeGuidePrice
        ) {
            this.pasteType = pasteType;
            this.source = source;
            this.targetPaste = targetPaste;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.repriceAttempts = repriceAttempts;
            this.useGeGuidePrice = useGeGuidePrice;
            this.guidePriceResolved = !useGeGuidePrice || repriceAttempts > 0;
        }

        private String label() {
            return pasteType.label() + " target=" + targetPaste + " via " + source.itemName();
        }
    }
}
