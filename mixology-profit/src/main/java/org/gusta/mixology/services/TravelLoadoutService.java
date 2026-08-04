package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.methods.IEquipmentAPI;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.data.TravelItems;
import org.gusta.mixology.stats.MixologyStats;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class TravelLoadoutService {
    private static final int GE_MIN_X = 3150;
    private static final int GE_MAX_X = 3190;
    private static final int GE_MIN_Y = 3465;
    private static final int GE_MAX_Y = 3505;
    private static final Tile GRAND_EXCHANGE_WALK_TILE = new Tile(3164, 3487, 0);
    private static final int GE_SLOT_BATCH_SIZE = 8;
    private static final long COLLECTED_BANK_CONFIRM_TIMEOUT_MILLIS = 12_000L;
    private static final long RING_EQUIP_RETRY_MILLIS = 2_500L;
    private static final String COINS = "Coins";
    private static final int CHARTER_COINS = 3_100;
    private static final int STAMINA_ONE_DOSE_BUY_PRICE = 5_000;

    private static final GearItem[][] STARTUP_GEAR_CANDIDATES = {
            {
                    new GearItem(IEquipmentAPI.Slot.WEAPON, "Bronze sword", "Wield", 500),
                    new GearItem(IEquipmentAPI.Slot.WEAPON, "Iron scimitar", "Wield", 1_500),
                    new GearItem(IEquipmentAPI.Slot.WEAPON, "Staff of air", "Wield", 2_000),
                    new GearItem(IEquipmentAPI.Slot.WEAPON, "Shortbow", "Wield", 800)
            },
            {
                    new GearItem(IEquipmentAPI.Slot.HELMET, "Blue wizard hat", "Wear", 800),
                    new GearItem(IEquipmentAPI.Slot.HELMET, "Leather cowl", "Wear", 500)
            },
            {
                    new GearItem(IEquipmentAPI.Slot.BODY, "Blue wizard robe", "Wear", 1_200),
                    new GearItem(IEquipmentAPI.Slot.BODY, "Leather body", "Wear", 800)
            },
            {
                    new GearItem(IEquipmentAPI.Slot.NECK, "Amulet of accuracy", "Wear", 1_000),
                    new GearItem(IEquipmentAPI.Slot.NECK, "Amulet of magic", "Wear", 1_000)
            },
            {
                    new GearItem(IEquipmentAPI.Slot.HANDS, "Gold bracelet", "Wear", 1_500)
            },
            {
                    new GearItem(IEquipmentAPI.Slot.LEGS, "Leather chaps", "Wear", 800)
            },
            {
                    new GearItem(IEquipmentAPI.Slot.FEET, "Leather boots", "Wear", 500)
            },
            {
                    new GearItem(IEquipmentAPI.Slot.CAPE, "Black cape", "Wear", 1_000),
                    new GearItem(IEquipmentAPI.Slot.CAPE, "Red cape", "Wear", 1_000)
            }
    };

    private final MixologyStats stats;
    private final GePricingService pricing;
    private final Queue<LoadoutPurchase> pendingPurchases = new ArrayDeque<>();
    private final List<LoadoutPurchase> placedBatch = new ArrayList<>();
    private final List<LoadoutPurchase> missingAfterBankCheck = new ArrayList<>();
    private final List<LoadoutPurchase> awaitingCollectedBankItems = new ArrayList<>();

    private GearItem[] selectedGear;
    private boolean bankCheckedForLoadout;
    private boolean geCheckedForExistingOffers;
    private boolean purchasesPlanned;
    private boolean startupGearPrepared;
    private LoadoutPurchase activePurchase;
    private int offerFailures;
    private long activePurchaseConfirmUntil;
    private long nextBatchCollectAt;
    private long collectedBankConfirmUntil;
    private int collectedBankScanAttempts;
    private long nextRingEquipAttemptAt;
    private int ringEquipAttempts;

    public TravelLoadoutService(MixologyStats stats, GePricingService pricing) {
        this.stats = stats;
        this.pricing = pricing;
    }

    public void resetForRestock() {
        purchasesPlanned = false;
        bankCheckedForLoadout = false;
        geCheckedForExistingOffers = false;
        pendingPurchases.clear();
        placedBatch.clear();
        missingAfterBankCheck.clear();
        awaitingCollectedBankItems.clear();
        activePurchase = null;
        offerFailures = 0;
        activePurchaseConfirmUntil = 0L;
        nextBatchCollectAt = 0L;
        collectedBankConfirmUntil = 0L;
        collectedBankScanAttempts = 0;
        nextRingEquipAttemptAt = 0L;
        ringEquipAttempts = 0;
    }

    public boolean prepareForTravel(APIContext ctx) {
        if (activePurchase != null || !pendingPurchases.isEmpty() || !placedBatch.isEmpty()) {
            return handleLoadoutPurchases(ctx);
        }

        if (!purchasesPlanned) {
            return planMissingPurchases(ctx);
        }

        if (ctx.bank().isOpen()) {
            if (withdrawTravelItems(ctx)) {
                return false;
            }
            stats.setStatus("Closing bank to equip travel loadout");
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return false;
        }
        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(500, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }

        if (equipInventoryLoadout(ctx)) {
            return false;
        }

        if (needsMandatoryBankWithdrawal(ctx) && withdrawTravelItems(ctx)) {
            return false;
        }

        if (!hasChargedRingEquipped(ctx)) {
            stats.setStatus("Travel loadout missing charged Ring of wealth");
            purchasesPlanned = false;
            return false;
        }
        if (!hasOneDoseStaminaInInventory(ctx) || staminaPotionCount(ctx) != 1) {
            stats.setStatus("Travel loadout needs exactly one Stamina potion(1)");
            purchasesPlanned = false;
            return false;
        }
        if (ctx.inventory().getCount(true, COINS) != CHARTER_COINS) {
            stats.setStatus("Travel loadout needs exactly " + CHARTER_COINS + " coins");
            purchasesPlanned = false;
            return false;
        }

        if (!requiredStartupSlotsFilled(ctx)) {
            if (hasUnequippedSelectedGearInInventory(ctx)) {
                stats.setStatus("Waiting to equip missing startup slot(s): " + missingStartupSlots(ctx));
                return false;
            }
            stats.setStatus("Travel outfit still missing slot(s): " + missingStartupSlots(ctx)
                    + "; rechecking bank and GE candidates");
            selectedGear = null;
            purchasesPlanned = false;
            bankCheckedForLoadout = false;
            geCheckedForExistingOffers = false;
            return false;
        }

        stats.setStatus("Travel loadout ready: ROW equipped, outfit slots filled, 3100 coins, one Stamina potion(1)");
        return true;
    }

    public boolean prepareStartupGear(APIContext ctx) {
        if (startupGearPrepared) {
            return true;
        }

        if (ctx.bank().isOpen()) {
            if (selectedGear == null) {
                selectGearFromAvailableItems(ctx);
                stats.debug("Startup gear bank scan: gear=" + selectedGearSummary());
            }
            if (withdrawSelectedGear(ctx)) {
                return false;
            }

            stats.setStatus("Closing bank to equip random startup gear");
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return false;
        }

        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(500, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }

        if (equipSelectedGear(ctx)) {
            return false;
        }

        if (selectedGear != null) {
            startupGearPrepared = true;
            stats.setStatus("Startup gear scan complete: " + equippedSlotSummary(ctx)
                    + "; remaining slots will be completed before travel");
            return true;
        }

        if (!openBank(ctx)) {
            return false;
        }
        return false;
    }

    private boolean planMissingPurchases(APIContext ctx) {
        if (!bankCheckedForLoadout) {
            if (!openBank(ctx)) {
                return false;
            }

            missingAfterBankCheck.clear();
            selectGearFromAvailableItems(ctx);
            addMissingLoadoutPurchases(ctx, missingAfterBankCheck);

            List<LoadoutPurchase> expectedButMissing = expectedCollectedItemsStillMissing(
                    missingAfterBankCheck);
            if (!expectedButMissing.isEmpty()
                    && System.currentTimeMillis() < collectedBankConfirmUntil) {
                collectedBankScanAttempts++;
                bankCheckedForLoadout = false;
                stats.setStatus("Waiting for collected GE loadout item(s) to appear in bank: "
                        + purchaseSummary(expectedButMissing)
                        + " scan=" + collectedBankScanAttempts);
                if (collectedBankScanAttempts % 2 == 0) {
                    ctx.bank().close();
                    Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
                } else {
                    Time.sleep(700, 1100);
                }
                return false;
            }
            if (expectedButMissing.isEmpty() && !awaitingCollectedBankItems.isEmpty()) {
                stats.debug("Confirmed collected GE loadout items in bank/inventory/equipment: "
                        + purchaseSummary(awaitingCollectedBankItems));
                clearCollectedBankExpectation();
            } else if (!expectedButMissing.isEmpty()) {
                stats.debug("Collected GE loadout bank confirmation timed out; checking active GE offers "
                        + "before any rebuy: " + purchaseSummary(expectedButMissing));
                clearCollectedBankExpectation();
            }

            bankCheckedForLoadout = true;
            stats.debug("Travel loadout bank scan: row=" + firstBankItem(ctx, TravelItems.CHARGED_RING_OF_WEALTH)
                    + " stamina=" + firstBankItem(ctx, TravelItems.STAMINA_POTIONS)
                    + " gear=" + selectedGearSummary()
                    + " inventoryCoins=" + ctx.inventory().getCount(true, COINS));

            if (missingAfterBankCheck.isEmpty()) {
                purchasesPlanned = true;
                stats.setStatus("Bank check OK: travel loadout items already available");
                return false;
            }

            stats.setStatus("Bank check missing travel loadout items: "
                    + purchaseSummary(missingAfterBankCheck) + "; checking GE offers before buying");
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return false;
        }

        if (!geCheckedForExistingOffers) {
            if (ctx.bank().isOpen()) {
                ctx.bank().close();
                Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
                return false;
            }
            if (!isAtGrandExchange(ctx)) {
                stats.setStatus("Walking to GE to check existing loadout offers");
                ctx.webWalking().setUseTeleports(true);
                ctx.webWalking().walkTo(GRAND_EXCHANGE_WALK_TILE);
                Time.sleep(1200, 1800);
                return false;
            }
            if (!ctx.grandExchange().isOpen()) {
                stats.setStatus("Opening GE to check existing loadout offers");
                ctx.grandExchange().open();
                Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
                return false;
            }

            int resumed = resumeExistingLoadoutOffers(ctx);
            pendingPurchases.addAll(missingAfterBankCheck);
            missingAfterBankCheck.clear();
            geCheckedForExistingOffers = true;
            purchasesPlanned = true;

            if (resumed > 0) {
                stats.setStatus("Resumed " + resumed + " existing GE loadout offer(s); pending new buys="
                        + pendingPurchases.size());
            } else {
                stats.setStatus("Planning GE buys for travel loadout: " + pendingPurchases.size() + " item(s)");
            }
            return false;
        }

        purchasesPlanned = true;
        return false;
    }

    private void addMissingLoadoutPurchases(APIContext ctx, List<LoadoutPurchase> missing) {
        if (!hasChargedRingAnywhere(ctx)) {
            missing.add(new LoadoutPurchase(
                    TravelItems.RING_OF_WEALTH_BUY,
                    1,
                    loadoutBuyPrice(ctx, TravelItems.RING_OF_WEALTH_BUY, 20_000),
                    true
            ));
        }
        if (!hasOneDoseStaminaAnywhere(ctx)) {
            missing.add(new LoadoutPurchase(
                    TravelItems.STAMINA_BUY,
                    1,
                    STAMINA_ONE_DOSE_BUY_PRICE,
                    true
            ));
        }
        if (selectedGear != null) {
            for (GearItem item : selectedGear) {
                if (isSlotOccupied(ctx, item.slot)
                        || hasInventoryItem(ctx, item.name)
                        || firstBankItem(ctx, item.name) != null) {
                    continue;
                }
                missing.add(new LoadoutPurchase(
                        item.name,
                        1,
                        item.fallbackPrice,
                        false
                ));
            }
        }
    }

    private int resumeExistingLoadoutOffers(APIContext ctx) {
        int resumed = 0;
        List<LoadoutPurchase> matched = new ArrayList<>();
        for (GrandExchangeSlot slot : ctx.grandExchange().getSlots()) {
            if (slot == null || !slot.inUse() || slot.getOffer() == null) {
                continue;
            }
            String offerName = slot.getOffer().getItemName();
            for (LoadoutPurchase purchase : missingAfterBankCheck) {
                if (!purchaseItemMatches(purchase.itemName, offerName)) {
                    continue;
                }
                if (!containsPurchase(placedBatch, purchase.itemName)) {
                    placedBatch.add(purchase);
                    resumed++;
                }
                matched.add(purchase);
                stats.debug("Found existing GE loadout offer: " + offerName
                        + " slot=" + slot.getIndex()
                        + " completed=" + slot.isCompleted()
                        + " canCollect=" + slot.canCollect());
                break;
            }
        }

        missingAfterBankCheck.removeAll(matched);
        if (resumed > 0) {
            nextBatchCollectAt = System.currentTimeMillis() + 2_500L;
        }
        return resumed;
    }

    private boolean containsPurchase(List<LoadoutPurchase> purchases, String itemName) {
        for (LoadoutPurchase purchase : purchases) {
            if (purchaseItemMatches(purchase.itemName, itemName)) {
                return true;
            }
        }
        return false;
    }

    private boolean purchaseItemMatches(String plannedName, String actualName) {
        if (TravelItems.RING_OF_WEALTH_BUY.equals(plannedName)) {
            return TravelItems.isChargedRingOfWealth(actualName);
        }
        if (TravelItems.STAMINA_BUY.equals(plannedName)) {
            return TravelItems.isStaminaPotion(actualName);
        }
        return TravelItems.matchesAny(actualName, plannedName);
    }

    private String purchaseSummary(List<LoadoutPurchase> purchases) {
        StringBuilder summary = new StringBuilder();
        int count = 0;
        for (LoadoutPurchase purchase : purchases) {
            if (count > 0) {
                summary.append(", ");
            }
            summary.append(purchase.itemName);
            count++;
            if (count >= 8 && purchases.size() > count) {
                summary.append(", ...");
                break;
            }
        }
        return summary.length() == 0 ? "none" : summary.toString();
    }

    private void selectGearFromAvailableItems(APIContext ctx) {
        if (selectedGear != null) {
            return;
        }

        List<GearItem> selected = new ArrayList<>();
        for (GearItem[] candidates : STARTUP_GEAR_CANDIDATES) {
            if (candidates.length == 0 || isSlotOccupied(ctx, candidates[0].slot)) {
                continue;
            }

            IEquipmentAPI.Slot slot = candidates[0].slot;
            List<GearItem> available = existingGearForSlot(ctx, slot);
            for (GearItem candidate : candidates) {
                if ((hasInventoryItem(ctx, candidate.name) || firstBankItem(ctx, candidate.name) != null)
                        && !containsGearName(available, candidate.name)) {
                    available.add(candidate);
                }
            }
            List<GearItem> pool = available.isEmpty() ? List.of(candidates) : available;
            selected.add(pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
        }

        selectedGear = selected.toArray(new GearItem[0]);
        stats.debug("Selected startup gear by empty slot: selected=" + selectedGearSummary()
                + " equipped=" + equippedSlotSummary(ctx)
                + " missing=" + missingStartupSlots(ctx));
    }

    private List<GearItem> existingGearForSlot(APIContext ctx, IEquipmentAPI.Slot slot) {
        List<GearItem> available = new ArrayList<>();
        for (ItemWidget item : ctx.inventory().getItems()) {
            addExistingGearCandidate(available, item, slot);
        }
        if (ctx.bank().isOpen()) {
            for (ItemWidget item : ctx.bank().getItems()) {
                addExistingGearCandidate(available, item, slot);
            }
        }
        return available;
    }

    private void addExistingGearCandidate(
            List<GearItem> available,
            ItemWidget item,
            IEquipmentAPI.Slot slot
    ) {
        if (item == null || item.getName() == null || item.getName().isBlank() || item.isNoted()) {
            return;
        }

        String name = item.getName();
        String lowerName = name.toLowerCase();
        if (!matchesEquipmentSlot(item, lowerName, slot) || containsGearName(available, name)) {
            return;
        }

        available.add(new GearItem(
                slot,
                name,
                slot == IEquipmentAPI.Slot.WEAPON ? "Wield" : "Wear",
                fallbackPriceForSlot(slot)
        ));
    }

    private boolean matchesEquipmentSlot(
            ItemWidget item,
            String lowerName,
            IEquipmentAPI.Slot slot
    ) {
        return switch (slot) {
            case WEAPON -> item.hasAction("Wield") || containsAny(lowerName,
                    "sword", "scimitar", "dagger", "mace", "spear", "hasta", "halberd",
                    "staff", "wand", "bow", "crossbow", "whip", "maul", "warhammer",
                    "battleaxe", "claws", "sceptre");
            case HELMET -> containsAny(lowerName,
                    "helmet", "helm", "hat", "cowl", "coif", "hood", "mask", "mitre",
                    "tiara", "headband", "headpiece", "crown");
            case CAPE -> containsAny(lowerName,
                    "cape", "cloak", "accumulator", "attractor", "assembler");
            case NECK -> containsAny(lowerName,
                    "amulet", "necklace", "pendant", "symbol", "stole", "scarf");
            case BODY -> !containsAny(lowerName,
                    "bottom", "skirt", "legs", "chaps", "trousers", "leggings")
                    && containsAny(lowerName,
                    "body", "platebody", "chainbody", "chestplate", "robe", "shirt", "tunic",
                    "jacket", "torso", "apron", "hauberk", "brassard");
            case HANDS -> containsAny(lowerName,
                    "bracelet", "gloves", "gauntlets", "vambraces", "bracers");
            case LEGS -> containsAny(lowerName,
                    "platelegs", "plateskirt", "chaps", "trousers", "bottoms", "robe bottom",
                    "skirt", "leggings", "tassets", "greaves");
            case FEET -> containsAny(lowerName,
                    "boots", "shoes", "sandals", "slippers");
            default -> false;
        };
    }

    private boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private int fallbackPriceForSlot(IEquipmentAPI.Slot slot) {
        return switch (slot) {
            case WEAPON -> 2_000;
            case HANDS -> 1_500;
            case BODY -> 1_200;
            default -> 1_000;
        };
    }

    private boolean containsGearName(List<GearItem> items, String itemName) {
        for (GearItem item : items) {
            if (TravelItems.matchesAny(item.name, itemName)) {
                return true;
            }
        }
        return false;
    }

    private int loadoutBuyPrice(APIContext ctx, String itemName, int fallbackPrice) {
        int priced = pricing.quickBuyPrice(ctx, itemName, fallbackPrice);
        int floor = Math.max(1, fallbackPrice);
        int finalPrice = Math.max(priced, floor);
        if (finalPrice > priced) {
            stats.debug("Raised low GE price for " + itemName
                    + " from " + priced + " to fallback floor " + finalPrice);
        }
        return finalPrice;
    }

    private boolean handleLoadoutPurchases(APIContext ctx) {
        if (activePurchase == null && pendingPurchases.isEmpty() && placedBatch.isEmpty()) {
            return false;
        }

        if (ctx.bank().isOpen()) {
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return false;
        }

        LoadoutPurchase currentOrNext = activePurchase != null ? activePurchase : pendingPurchases.peek();
        if (!isAtGrandExchange(ctx)) {
            String itemName = currentOrNext == null ? "loadout batch collect" : currentOrNext.itemName;
            stats.setStatus("Walking to GE for loadout item: " + itemName);
            ctx.webWalking().setUseTeleports(true);
            ctx.webWalking().walkTo(GRAND_EXCHANGE_WALK_TILE);
            Time.sleep(1200, 1800);
            return false;
        }
        if (!ctx.grandExchange().isOpen()) {
            String itemName = currentOrNext == null ? "loadout batch" : currentOrNext.itemName;
            stats.setStatus("Opening GE for loadout item: " + itemName);
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return false;
        }
        if (abortDuplicateOpenLoadoutOffers(ctx)) {
            return false;
        }
        if (confirmHighPriceWarning(ctx)) {
            if (activePurchase != null) {
                activePurchaseConfirmUntil = System.currentTimeMillis() + 5_000L;
            }
            return false;
        }

        if (shouldCollectBatch(ctx)) {
            collectLoadoutBatch(ctx);
            return false;
        }

        if (activePurchase == null) {
            activePurchase = pendingPurchases.poll();
            offerFailures = 0;
            activePurchaseConfirmUntil = 0L;
            if (activePurchase == null) {
                if (!placedBatch.isEmpty()) {
                    collectLoadoutBatch(ctx);
                }
                return false;
            }
        }

        GrandExchangeSlot existingSlot = findActiveSlot(ctx, activePurchase.itemName);
        if (existingSlot != null) {
            if (!containsPurchase(placedBatch, activePurchase.itemName)) {
                placedBatch.add(activePurchase);
            }
            pendingPurchases.removeIf(purchase ->
                    purchaseItemMatches(purchase.itemName, activePurchase.itemName));
            stats.setStatus("Existing GE loadout offer already open; not rebuying: "
                    + activePurchase.itemName + " slot=" + existingSlot.getIndex());
            activePurchase = null;
            offerFailures = 0;
            activePurchaseConfirmUntil = 0L;
            nextBatchCollectAt = System.currentTimeMillis() + 2_500L;
            return false;
        }
        if (activePurchaseConfirmUntil > System.currentTimeMillis()) {
            stats.setStatus("Waiting to confirm GE loadout offer: " + activePurchase.itemName);
            Time.sleep(700, 1000);
            return false;
        }

        stats.setStatus("Buying loadout item: " + activePurchase.quantity + "x "
                + activePurchase.itemName + " for " + activePurchase.unitPrice + " each");
        boolean placed = ctx.grandExchange().placeBuyOffer(
                activePurchase.itemName,
                activePurchase.quantity,
                activePurchase.unitPrice
        );
        Time.sleep(1000, 1500);
        if (confirmHighPriceWarning(ctx)) {
            activePurchaseConfirmUntil = System.currentTimeMillis() + 5_000L;
            return false;
        }

        GrandExchangeSlot confirmedSlot = findActiveSlot(ctx, activePurchase.itemName);
        if (!placed) {
            if (confirmedSlot != null) {
                placed = true;
            } else {
                offerFailures++;
                stats.setStatus("GE loadout offer was not placed for " + activePurchase.itemName
                        + " attempt=" + offerFailures);
                if (!activePurchase.mandatory && offerFailures >= 3) {
                    stats.setStatus("Skipping optional random gear item: " + activePurchase.itemName);
                    activePurchase = null;
                    offerFailures = 0;
                    activePurchaseConfirmUntil = 0L;
                }
                return false;
            }
        }

        if (confirmedSlot == null) {
            activePurchaseConfirmUntil = System.currentTimeMillis() + 3_000L;
            stats.setStatus("GE accepted loadout offer; waiting for slot confirmation: "
                    + activePurchase.itemName);
            return false;
        }

        placedBatch.add(activePurchase);
        stats.setStatus("GE loadout batch offers placed " + placedBatch.size()
                + "/" + GE_SLOT_BATCH_SIZE + ": " + activePurchase.itemName);
        activePurchase = null;
        offerFailures = 0;
        activePurchaseConfirmUntil = 0L;
        nextBatchCollectAt = System.currentTimeMillis() + 2_500L;
        return false;
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

    private boolean confirmHighPriceWarning(APIContext ctx) {
        if (!isHighPriceWarningOpen(ctx)) {
            return false;
        }

        WidgetChild confirm = findVisibleWidgetText(ctx, "Yes");
        if (confirm == null || !confirm.isValid()) {
            stats.setStatus("GE price warning visible, waiting for Yes button");
            Time.sleep(500, 800);
            return false;
        }

        String itemName = activePurchase == null ? "loadout batch" : activePurchase.itemName;
        stats.setStatus("Confirming GE price warning for loadout item: " + itemName);
        if (!confirm.interact("Continue") && !confirm.interact("Yes")) {
            confirm.click();
        }
        Time.sleep(700, 1100,
                () -> !isHighPriceWarningOpen(ctx)
                        || (activePurchase != null && findActiveSlot(ctx, activePurchase.itemName) != null),
                100);
        return true;
    }

    private boolean isHighPriceWarningOpen(APIContext ctx) {
        String allText = allWidgetText(ctx).toLowerCase();
        return (allText.contains("much higher")
                || allText.contains("are you sure")
                || allText.contains("higher than the guide price"))
                && allText.contains("yes");
    }

    private WidgetChild findVisibleWidgetText(APIContext ctx, String needle) {
        List<WidgetChild> widgets = ctx.widgets().getAllChildren(widget -> {
            if (widget == null || !widget.isValid() || !widget.isVisible()
                    || widget.getWidth() <= 0 || widget.getHeight() <= 0) {
                return false;
            }
            String text = widget.getText();
            if (text == null || text.isBlank()) {
                text = widget.getRawText();
            }
            return text != null && text.replaceAll("<[^>]+>", " ")
                    .trim()
                    .equalsIgnoreCase(needle);
        });
        return widgets.isEmpty() ? null : widgets.get(0);
    }

    private boolean abortDuplicateOpenLoadoutOffers(APIContext ctx) {
        Set<String> seen = new HashSet<>();
        for (GrandExchangeSlot slot : ctx.grandExchange().getSlots()) {
            if (slot == null || !slot.inUse() || slot.getOffer() == null) {
                continue;
            }

            String key = loadoutOfferKey(slot.getOffer().getItemName());
            if (key == null || seen.add(key)) {
                continue;
            }
            if (slot.isCompleted() || slot.canCollect()) {
                continue;
            }

            stats.setStatus("Aborting duplicate GE loadout offer: "
                    + slot.getOffer().getItemName() + " slot=" + slot.getIndex());
            if (slot.abortOffer()) {
                Time.sleep(900, 1500);
            } else {
                Time.sleep(500, 800);
            }
            return true;
        }
        return false;
    }

    private String loadoutOfferKey(String itemName) {
        if (TravelItems.isChargedRingOfWealth(itemName)) {
            return "ring-of-wealth";
        }
        if (TravelItems.isStaminaPotion(itemName)) {
            return "stamina";
        }
        for (GearItem[] candidates : STARTUP_GEAR_CANDIDATES) {
            for (GearItem item : candidates) {
                if (TravelItems.matchesAny(itemName, item.name)) {
                    return "gear:" + item.name;
                }
            }
        }
        return null;
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

    private boolean shouldCollectBatch(APIContext ctx) {
        return !placedBatch.isEmpty()
                && (pendingPurchases.isEmpty()
                || placedBatch.size() >= GE_SLOT_BATCH_SIZE
                || activeGeSlotCount(ctx) >= GE_SLOT_BATCH_SIZE);
    }

    private void collectLoadoutBatch(APIContext ctx) {
        if (System.currentTimeMillis() < nextBatchCollectAt) {
            Time.sleep(600, 900);
            return;
        }

        if (allPlacedLoadoutOffersCleared(ctx)) {
            confirmCollectedLoadoutBatch("GE slots already clear before collect retry");
            return;
        }

        int ready = 0;
        int waiting = 0;
        for (LoadoutPurchase purchase : placedBatch) {
            GrandExchangeSlot slot = findActiveSlot(ctx, purchase.itemName);
            if (slot == null) {
                ready++;
            } else if (slot.isCompleted() || slot.canCollect()) {
                ready++;
            } else {
                waiting++;
            }
        }

        if (waiting > 0) {
            stats.setStatus("Waiting for GE loadout batch before collect: ready="
                    + ready + "/" + placedBatch.size() + " waiting=" + waiting);
            nextBatchCollectAt = System.currentTimeMillis() + 4_000L;
            Time.sleep(600, 900);
            return;
        }

        stats.setStatus("Collecting GE loadout batch to bank: " + placedBatch.size() + " offer(s)");
        boolean collectRequested = false;
        try {
            collectRequested = ctx.grandExchange().collectToBank();
        } catch (RuntimeException exception) {
            stats.debug("GE loadout collectToBank failed: "
                    + exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }

        Time.sleep(1200, 2200, () -> allPlacedLoadoutOffersCleared(ctx), 100);
        if (!allPlacedLoadoutOffersCleared(ctx)) {
            stats.setStatus("GE loadout collection not confirmed; keeping batch for retry");
            stats.debug("GE loadout collect confirmation failed: requested=" + collectRequested
                    + " remaining=" + activePlacedBatchText(ctx));
            nextBatchCollectAt = System.currentTimeMillis() + 2_500L;
            return;
        }

        confirmCollectedLoadoutBatch("GE slots cleared after collect request=" + collectRequested);
    }

    private void confirmCollectedLoadoutBatch(String reason) {
        awaitingCollectedBankItems.clear();
        awaitingCollectedBankItems.addAll(placedBatch);
        collectedBankConfirmUntil = System.currentTimeMillis()
                + COLLECTED_BANK_CONFIRM_TIMEOUT_MILLIS;
        collectedBankScanAttempts = 0;
        stats.setStatus("GE loadout batch collected; rechecking bank before any more buys");
        stats.debug("GE loadout collection confirmed: " + reason
                + " expectedBank=" + purchaseSummary(awaitingCollectedBankItems));
        clearPlannedPurchasesForBankRecheck();
    }

    private boolean allPlacedLoadoutOffersCleared(APIContext ctx) {
        for (LoadoutPurchase purchase : placedBatch) {
            if (findActiveSlot(ctx, purchase.itemName) != null) {
                return false;
            }
        }
        return !placedBatch.isEmpty();
    }

    private String activePlacedBatchText(APIContext ctx) {
        StringBuilder text = new StringBuilder();
        for (LoadoutPurchase purchase : placedBatch) {
            GrandExchangeSlot slot = findActiveSlot(ctx, purchase.itemName);
            if (slot == null) {
                continue;
            }
            if (text.length() > 0) {
                text.append(", ");
            }
            GrandExchangeOffer offer = slot.getOffer();
            text.append(purchase.itemName)
                    .append(" slot=").append(slot.getIndex())
                    .append(" state=").append(slot.getState());
            if (offer != null) {
                text.append(" remaining=").append(offer.getRemaining());
            }
        }
        return text.length() == 0 ? "none" : text.toString();
    }

    private void clearPlannedPurchasesForBankRecheck() {
        purchasesPlanned = false;
        bankCheckedForLoadout = false;
        geCheckedForExistingOffers = false;
        pendingPurchases.clear();
        missingAfterBankCheck.clear();
        placedBatch.clear();
        activePurchase = null;
        offerFailures = 0;
        activePurchaseConfirmUntil = 0L;
        nextBatchCollectAt = 0L;
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

    private boolean withdrawTravelItems(APIContext ctx) {
        if (!ctx.bank().isOpen() && !needsMandatoryBankWithdrawal(ctx)) {
            return false;
        }
        if (!openBank(ctx)) {
            return true;
        }

        if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM)) {
            stats.setStatus("Selecting item withdraw mode for travel loadout");
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
            Time.sleep(500, 900,
                    () -> ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM),
                    100);
            return true;
        }

        if (!hasChargedRingEquipped(ctx) && !hasChargedRingInInventory(ctx)) {
            String ring = firstBankItem(ctx, TravelItems.CHARGED_RING_OF_WEALTH);
            if (ring == null) {
                purchasesPlanned = false;
                stats.setStatus("Charged Ring of wealth missing after loadout purchases");
                return true;
            }
            stats.setStatus("Withdrawing charged Ring of wealth");
            withdrawOne(ctx, ring);
            Time.sleep(500, 900);
            return true;
        }

        int staminaCount = staminaPotionCount(ctx);
        if (staminaCount > 0
                && (!hasOneDoseStaminaInInventory(ctx) || staminaCount > 1)) {
            int keepOneDose = hasOneDoseStaminaInInventory(ctx) ? 1 : 0;
            for (String stamina : TravelItems.STAMINA_POTIONS) {
                int count = ctx.inventory().getCount(true, stamina);
                int keep = TravelItems.STAMINA_BUY.equals(stamina) ? keepOneDose : 0;
                int toDeposit = Math.max(0, count - keep);
                if (toDeposit <= 0) {
                    continue;
                }
                stats.setStatus("Depositing non-loadout stamina potion item(s): " + toDeposit
                        + "x " + stamina);
                ctx.bank().deposit(toDeposit, stamina);
                Time.sleep(500, 900);
                return true;
            }
        }

        if (!hasOneDoseStaminaInInventory(ctx)) {
            String stamina = firstBankItem(ctx, TravelItems.STAMINA_BUY);
            if (stamina == null) {
                purchasesPlanned = false;
                bankCheckedForLoadout = false;
                stats.setStatus("Stamina potion(1) missing after loadout purchases");
                return true;
            }
            stats.setStatus("Withdrawing one Stamina potion(1)");
            withdrawOne(ctx, stamina);
            Time.sleep(500, 900);
            return true;
        }

        int inventoryCoins = ctx.inventory().getCount(true, COINS);
        int bankCoins = ctx.bank().getCount(COINS);
        if (inventoryCoins < CHARTER_COINS && bankCoins > 0) {
            int toWithdraw = Math.min(CHARTER_COINS - inventoryCoins, bankCoins);
            stats.setStatus("Withdrawing charter coins: " + toWithdraw);
            ctx.bank().withdraw(toWithdraw, COINS);
            Time.sleep(500, 900);
            return true;
        }
        if (inventoryCoins > CHARTER_COINS) {
            int excess = inventoryCoins - CHARTER_COINS;
            stats.setStatus("Depositing excess coins: " + excess);
            ctx.bank().deposit(excess, COINS);
            Time.sleep(500, 900);
            return true;
        }

        if (withdrawSelectedGear(ctx)) {
            return true;
        }

        return false;
    }

    private boolean needsMandatoryBankWithdrawal(APIContext ctx) {
        return (!hasChargedRingEquipped(ctx) && !hasChargedRingInInventory(ctx))
                || !hasOneDoseStaminaInInventory(ctx)
                || staminaPotionCount(ctx) != 1
                || ctx.inventory().getCount(true, COINS) != CHARTER_COINS;
    }

    private boolean equipInventoryLoadout(APIContext ctx) {
        if (hasChargedRingInInventory(ctx) && !hasChargedRingEquipped(ctx)) {
            long now = System.currentTimeMillis();
            if (now < nextRingEquipAttemptAt) {
                stats.setStatus("Waiting before retrying charged Ring of wealth equip; attempt="
                        + ringEquipAttempts);
                Time.sleep(350, 650);
                return true;
            }

            ringEquipAttempts++;
            boolean interacted = equipFirstMatching(ctx, IEquipmentAPI.Slot.RING,
                    "Wear", TravelItems.CHARGED_RING_OF_WEALTH);
            if (hasChargedRingEquipped(ctx)) {
                stats.debug("Charged Ring of wealth equip confirmed after attempt="
                        + ringEquipAttempts);
                ringEquipAttempts = 0;
                nextRingEquipAttemptAt = 0L;
            } else {
                nextRingEquipAttemptAt = System.currentTimeMillis() + RING_EQUIP_RETRY_MILLIS;
                stats.setStatus("Charged Ring of wealth equip not confirmed; retrying attempt="
                        + ringEquipAttempts);
                stats.debug("Charged Ring equip pending: interacted=" + interacted
                        + " inventory=" + hasChargedRingInInventory(ctx)
                        + " slot=" + equippedItemName(ctx, IEquipmentAPI.Slot.RING));
            }
            return true;
        }
        ringEquipAttempts = 0;
        nextRingEquipAttemptAt = 0L;
        if (equipSelectedGear(ctx)) {
            return true;
        }
        return false;
    }

    private boolean equipFirstMatching(
            APIContext ctx,
            IEquipmentAPI.Slot slot,
            String preferredAction,
            String... names
    ) {
        for (String name : names) {
            if (hasInventoryItem(ctx, name)) {
                return equipItem(ctx, slot, name, preferredAction);
            }
        }
        return false;
    }

    private boolean equipItem(
            APIContext ctx,
            IEquipmentAPI.Slot slot,
            String itemName,
            String preferredAction
    ) {
        ItemWidget widget = inventoryItem(ctx, itemName);
        if (widget == null) {
            return false;
        }

        int beforeInventoryCount = ctx.inventory().getCount(true, itemName);
        stats.setStatus("Equipping travel item: " + itemName);
        boolean interacted = widget.interact(preferredAction, itemName)
                || widget.interact(preferredAction)
                || widget.interact("Wear", itemName)
                || widget.interact("Wear")
                || widget.interact("Wield", itemName)
                || widget.interact("Wield")
                || widget.interact("Equip", itemName)
                || widget.interact("Equip")
                || ctx.inventory().interactItem(preferredAction, itemName);
        Time.sleep(
                900,
                2200,
                () -> slotContains(ctx, slot, itemName),
                100
        );
        if (interacted && !slotContains(ctx, slot, itemName)) {
            stats.debug("Travel item equip click not yet confirmed in slot " + slot
                    + ": item=" + itemName
                    + " inventoryBefore=" + beforeInventoryCount
                    + " inventoryNow=" + ctx.inventory().getCount(true, itemName)
                    + " slotNow=" + equippedItemName(ctx, slot));
        }
        return interacted;
    }

    private boolean hasUnequippedSelectedGearInInventory(APIContext ctx) {
        if (selectedGear == null) {
            return false;
        }
        for (GearItem item : selectedGear) {
            if (!isSlotOccupied(ctx, item.slot) && hasInventoryItem(ctx, item.name)) {
                return true;
            }
        }
        return false;
    }

    private boolean withdrawSelectedGear(APIContext ctx) {
        if (selectedGear == null || selectedGear.length == 0) {
            return false;
        }

        for (GearItem item : selectedGear) {
            if (isSlotOccupied(ctx, item.slot) || hasInventoryItem(ctx, item.name)) {
                continue;
            }

            String bankName = firstBankItem(ctx, item.name);
            if (bankName == null) {
                continue;
            }

            stats.setStatus("Withdrawing random travel gear: " + bankName);
            boolean requested = withdrawOne(ctx, bankName);
            Time.sleep(500, 900, () -> hasInventoryItem(ctx, item.name), 100);
            if (!hasInventoryItem(ctx, item.name)) {
                stats.debug("Random travel gear withdrawal not confirmed: "
                        + bankName + " requested=" + requested);
            }
            return true;
        }
        return false;
    }

    private boolean equipSelectedGear(APIContext ctx) {
        if (!hasUnequippedSelectedGearInInventory(ctx)) {
            return false;
        }

        for (GearItem item : selectedGear) {
            if (!isSlotOccupied(ctx, item.slot) && hasInventoryItem(ctx, item.name)) {
                return equipItem(ctx, item.slot, item.name, item.action);
            }
        }
        return false;
    }

    private boolean hasAnySelectedGearEquipped(APIContext ctx) {
        if (selectedGear == null) {
            return false;
        }
        for (GearItem item : selectedGear) {
            if (slotContains(ctx, item.slot, item.name)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasInventoryItem(APIContext ctx, String itemName) {
        return inventoryItem(ctx, itemName) != null;
    }

    private ItemWidget inventoryItem(APIContext ctx, String itemName) {
        return ctx.inventory().getItem(item ->
                item != null
                        && !item.isNoted()
                        && TravelItems.matchesAny(item.getName(), itemName));
    }

    private boolean hasEquippedItem(APIContext ctx, String itemName) {
        return ctx.equipment().contains(item ->
                item != null && TravelItems.matchesAny(item.getName(), itemName));
    }

    private boolean requiredStartupSlotsFilled(APIContext ctx) {
        for (GearItem[] candidates : STARTUP_GEAR_CANDIDATES) {
            if (candidates.length > 0 && !isSlotOccupied(ctx, candidates[0].slot)) {
                return false;
            }
        }
        return true;
    }

    private String missingStartupSlots(APIContext ctx) {
        List<String> missing = new ArrayList<>();
        for (GearItem[] candidates : STARTUP_GEAR_CANDIDATES) {
            if (candidates.length > 0 && !isSlotOccupied(ctx, candidates[0].slot)) {
                missing.add(candidates[0].slot.name());
            }
        }
        return missing.isEmpty() ? "none" : String.join(", ", missing);
    }

    private String equippedSlotSummary(APIContext ctx) {
        List<String> equipped = new ArrayList<>();
        for (GearItem[] candidates : STARTUP_GEAR_CANDIDATES) {
            if (candidates.length == 0) {
                continue;
            }
            IEquipmentAPI.Slot slot = candidates[0].slot;
            equipped.add(slot.name() + "=" + equippedItemName(ctx, slot));
        }
        equipped.add(IEquipmentAPI.Slot.RING.name() + "="
                + equippedItemName(ctx, IEquipmentAPI.Slot.RING));
        return String.join(", ", equipped);
    }

    private boolean isSlotOccupied(APIContext ctx, IEquipmentAPI.Slot slot) {
        ItemWidget item = ctx.equipment().getItem(slot);
        return item != null && item.getId() > 0;
    }

    private boolean slotContains(APIContext ctx, IEquipmentAPI.Slot slot, String itemName) {
        ItemWidget item = ctx.equipment().getItem(slot);
        return item != null && TravelItems.matchesAny(item.getName(), itemName);
    }

    private String equippedItemName(APIContext ctx, IEquipmentAPI.Slot slot) {
        ItemWidget item = ctx.equipment().getItem(slot);
        return item == null || item.getName() == null || item.getName().isBlank()
                ? "empty"
                : item.getName();
    }

    private boolean openBank(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            return true;
        }
        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(500, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }
        if (!ctx.bank().isReachable()) {
            stats.setStatus("Walking to nearest bank for travel loadout");
            ctx.webWalking().setUseTeleports(true);
            ctx.webWalking().walkToBank();
            Time.sleep(1200, 1800);
            return false;
        }

        return BankOpenService.open(ctx, stats, "Opening bank for travel loadout");
    }

    private GrandExchangeSlot findActiveSlot(APIContext ctx, String itemName) {
        for (GrandExchangeSlot slot : ctx.grandExchange().getSlots()) {
            if (slot == null || !slot.inUse() || slot.getOffer() == null) {
                continue;
            }
            GrandExchangeOffer offer = slot.getOffer();
            if (purchaseItemMatches(itemName, offer.getItemName())) {
                return slot;
            }
        }
        return null;
    }

    private boolean hasChargedRingAnywhere(APIContext ctx) {
        return hasChargedRingEquipped(ctx) || hasChargedRingInInventory(ctx)
                || firstBankItem(ctx, TravelItems.CHARGED_RING_OF_WEALTH) != null;
    }

    private boolean hasChargedRingEquipped(APIContext ctx) {
        ItemWidget ring = ctx.equipment().getItem(IEquipmentAPI.Slot.RING);
        return ring != null && TravelItems.isChargedRingOfWealth(ring.getName());
    }

    private boolean hasChargedRingInInventory(APIContext ctx) {
        return ctx.inventory().contains(item ->
                item != null
                        && !item.isNoted()
                        && TravelItems.isChargedRingOfWealth(item.getName()));
    }

    private boolean hasStaminaInInventory(APIContext ctx) {
        return ctx.inventory().contains(item ->
                item != null
                        && !item.isNoted()
                        && TravelItems.isStaminaPotion(item.getName()));
    }

    private boolean hasOneDoseStaminaInInventory(APIContext ctx) {
        return hasInventoryItem(ctx, TravelItems.STAMINA_BUY);
    }

    private boolean hasOneDoseStaminaAnywhere(APIContext ctx) {
        return hasOneDoseStaminaInInventory(ctx)
                || firstBankItem(ctx, TravelItems.STAMINA_BUY) != null;
    }

    private int staminaPotionCount(APIContext ctx) {
        int count = 0;
        for (String stamina : TravelItems.STAMINA_POTIONS) {
            count += ctx.inventory().getCount(true, stamina);
        }
        return count;
    }

    private boolean hasEquippedInventoryOrBank(APIContext ctx, String itemName) {
        return hasEquippedItem(ctx, itemName)
                || hasInventoryItem(ctx, itemName)
                || firstBankItem(ctx, itemName) != null;
    }

    private String firstBankItem(APIContext ctx, String... names) {
        if (!ctx.bank().isOpen()) {
            return null;
        }
        for (String name : names) {
            ItemWidget direct = ctx.bank().getItem(name);
            if (direct != null && direct.getName() != null && !direct.getName().isBlank()) {
                return direct.getName();
            }
            try {
                if (ctx.bank().getCount(name) > 0) {
                    return name;
                }
            } catch (RuntimeException ignored) {
                // Fall through to the complete bank widget scan.
            }
        }
        for (ItemWidget item : ctx.bank().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            for (String name : names) {
                if (TravelItems.matchesAny(item.getName(), name)) {
                    return item.getName();
                }
            }
        }
        return null;
    }

    private List<LoadoutPurchase> expectedCollectedItemsStillMissing(
            List<LoadoutPurchase> missing
    ) {
        List<LoadoutPurchase> expectedMissing = new ArrayList<>();
        for (LoadoutPurchase expected : awaitingCollectedBankItems) {
            for (LoadoutPurchase candidate : missing) {
                if (purchaseItemMatches(expected.itemName, candidate.itemName)) {
                    expectedMissing.add(expected);
                    break;
                }
            }
        }
        return expectedMissing;
    }

    private void clearCollectedBankExpectation() {
        awaitingCollectedBankItems.clear();
        collectedBankConfirmUntil = 0L;
        collectedBankScanAttempts = 0;
    }

    private String selectedGearSummary() {
        if (selectedGear == null || selectedGear.length == 0) {
            return "-";
        }
        List<String> items = new ArrayList<>();
        for (GearItem item : selectedGear) {
            items.add(item.slot.name() + "=" + item.name);
        }
        return String.join(", ", items);
    }

    private boolean withdrawOne(APIContext ctx, String itemName) {
        return ctx.bank().withdraw(1, itemName) || ctx.bank().withdrawAny(1, itemName);
    }

    private static class GearItem {
        private final IEquipmentAPI.Slot slot;
        private final String name;
        private final String action;
        private final int fallbackPrice;

        private GearItem(IEquipmentAPI.Slot slot, String name, String action, int fallbackPrice) {
            this.slot = slot;
            this.name = name;
            this.action = action;
            this.fallbackPrice = fallbackPrice;
        }
    }

    private static class LoadoutPurchase {
        private final String itemName;
        private final int quantity;
        private final int unitPrice;
        private final boolean mandatory;

        private LoadoutPurchase(String itemName, int quantity, int unitPrice, boolean mandatory) {
            this.itemName = itemName;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.mandatory = mandatory;
        }
    }
}
