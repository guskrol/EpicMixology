package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IEquipmentAPI;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.data.TravelItems;
import org.gusta.mixology.stats.MixologyStats;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final String COINS = "Coins";
    private static final int CHARTER_COINS = 5_000;

    private static final GearItem[][] RANDOM_GEAR_PRESETS = {
            {
                    new GearItem("Staff of air", "Wield", 2_000),
                    new GearItem("Blue wizard hat", "Wear", 800),
                    new GearItem("Blue wizard robe", "Wear", 1_200),
                    new GearItem("Black cape", "Wear", 1_000),
                    new GearItem("Leather boots", "Wear", 500),
                    new GearItem("Leather gloves", "Wear", 500),
                    new GearItem("Amulet of accuracy", "Wear", 1_000)
            },
            {
                    new GearItem("Shortbow", "Wield", 800),
                    new GearItem("Leather cowl", "Wear", 500),
                    new GearItem("Leather body", "Wear", 800),
                    new GearItem("Leather chaps", "Wear", 800),
                    new GearItem("Red cape", "Wear", 1_000),
                    new GearItem("Leather boots", "Wear", 500),
                    new GearItem("Leather gloves", "Wear", 500)
            },
            {
                    new GearItem("Iron scimitar", "Wield", 1_500),
                    new GearItem("Black cape", "Wear", 1_000),
                    new GearItem("Leather boots", "Wear", 500),
                    new GearItem("Leather gloves", "Wear", 500),
                    new GearItem("Amulet of accuracy", "Wear", 1_000)
            }
    };

    private final MixologyStats stats;
    private final GePricingService pricing;
    private final Queue<LoadoutPurchase> pendingPurchases = new ArrayDeque<>();
    private final List<LoadoutPurchase> placedBatch = new ArrayList<>();
    private final List<LoadoutPurchase> missingAfterBankCheck = new ArrayList<>();

    private GearItem[] selectedGear;
    private boolean allowOptionalGearPurchases;
    private boolean bankCheckedForLoadout;
    private boolean geCheckedForExistingOffers;
    private boolean purchasesPlanned;
    private LoadoutPurchase activePurchase;
    private int offerFailures;
    private long nextBatchCollectAt;

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
        activePurchase = null;
        offerFailures = 0;
        nextBatchCollectAt = 0L;
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

        if (hasUnequippedSelectedGearInInventory(ctx)) {
            stats.setStatus("Random travel gear still in inventory; retrying equip before travel");
            Time.sleep(500, 900);
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
        if (!hasAnySelectedGearEquipped(ctx)) {
            stats.setStatus("Travel loadout missing equipped random gear");
            purchasesPlanned = false;
            return false;
        }

        stats.setStatus("Travel loadout ready: ROW equipped, stamina optional, random gear equipped");
        return true;
    }

    private boolean planMissingPurchases(APIContext ctx) {
        if (!bankCheckedForLoadout) {
            if (!openBank(ctx)) {
                return false;
            }

            selectGearFromAvailableItems(ctx);
            missingAfterBankCheck.clear();
            addMissingLoadoutPurchases(ctx, missingAfterBankCheck);
            bankCheckedForLoadout = true;
            stats.debug("Travel loadout bank scan: row=" + firstBankItem(ctx, TravelItems.CHARGED_RING_OF_WEALTH)
                    + " stamina=" + firstBankItem(ctx, TravelItems.STAMINA_POTIONS)
                    + " selectedGear=" + selectedGearSummary());

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
        if (!allowOptionalGearPurchases) {
            stats.debug("Optional gear GE buy disabled because existing travel gear was found: "
                    + selectedGearSummary());
            return;
        }
        for (GearItem item : selectedGear) {
            if (!hasEquippedInventoryOrBank(ctx, item.name)) {
                missing.add(new LoadoutPurchase(
                        item.name,
                        1,
                        loadoutBuyPrice(ctx, item.name, item.fallbackPrice),
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

        int bestIndex = -1;
        int bestScore = -1;
        for (int i = 0; i < RANDOM_GEAR_PRESETS.length; i++) {
            int score = gearAvailabilityScore(ctx, RANDOM_GEAR_PRESETS[i]);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }

        if (bestScore > 0) {
            List<GearItem> availableGear = new ArrayList<>();
            for (GearItem item : RANDOM_GEAR_PRESETS[bestIndex]) {
                if (hasEquippedInventoryOrBank(ctx, item.name)) {
                    availableGear.add(item);
                }
            }
            selectedGear = availableGear.toArray(new GearItem[0]);
            allowOptionalGearPurchases = false;
            stats.debug("Selected existing travel gear preset index=" + bestIndex
                    + " availablePieces=" + bestScore + "/" + RANDOM_GEAR_PRESETS[bestIndex].length
                    + "; GE optional gear buy disabled");
            return;
        }

        int index = ThreadLocalRandom.current().nextInt(RANDOM_GEAR_PRESETS.length);
        selectedGear = Arrays.copyOf(RANDOM_GEAR_PRESETS[index], RANDOM_GEAR_PRESETS[index].length);
        allowOptionalGearPurchases = true;
        stats.debug("No existing travel gear found; selected random travel gear preset index=" + index
                + " for GE purchase");
    }

    private int gearAvailabilityScore(APIContext ctx, GearItem[] preset) {
        int score = 0;
        for (GearItem item : preset) {
            if (hasEquippedInventoryOrBank(ctx, item.name)) {
                score++;
            }
        }
        return score;
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
            return false;
        }

        if (shouldCollectBatch(ctx)) {
            collectLoadoutBatch(ctx);
            return false;
        }

        if (activePurchase == null) {
            activePurchase = pendingPurchases.poll();
            offerFailures = 0;
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
            nextBatchCollectAt = System.currentTimeMillis() + 2_500L;
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
        if (!placed) {
            if (confirmHighPriceWarning(ctx)) {
                return false;
            }
            offerFailures++;
            stats.setStatus("GE loadout offer was not placed for " + activePurchase.itemName
                    + " attempt=" + offerFailures);
            if (!activePurchase.mandatory && offerFailures >= 3) {
                stats.setStatus("Skipping optional random gear item: " + activePurchase.itemName);
                activePurchase = null;
                offerFailures = 0;
            }
            return false;
        }

        placedBatch.add(activePurchase);
        stats.setStatus("GE loadout batch offers placed " + placedBatch.size()
                + "/" + GE_SLOT_BATCH_SIZE + ": " + activePurchase.itemName);
        activePurchase = null;
        offerFailures = 0;
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

        String itemName = activePurchase == null ? "loadout batch" : activePurchase.itemName;
        stats.setStatus("Confirming GE price warning for loadout item: " + itemName);
        if (!confirm.interact("Continue") && !confirm.interact("Yes")) {
            confirm.click();
        }
        Time.sleep(700, 1100);
        return true;
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
        for (GearItem[] preset : RANDOM_GEAR_PRESETS) {
            for (GearItem item : preset) {
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

        int ready = 0;
        int waiting = 0;
        for (LoadoutPurchase purchase : placedBatch) {
            GrandExchangeSlot slot = findActiveSlot(ctx, purchase.itemName);
            if (slot == null || slot.isCompleted() || slot.canCollect()) {
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
        try {
            ctx.grandExchange().collectToBank();
        } catch (RuntimeException ignored) {
            // Collection can be retried when the client reports the slot a tick early.
        }
        Time.sleep(700, 1100);
        stats.setStatus("GE loadout batch collected; rechecking bank before any more buys");
        clearPlannedPurchasesForBankRecheck();
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

        if (!hasStaminaInInventory(ctx)) {
            String stamina = firstBankItem(ctx, TravelItems.STAMINA_POTIONS);
            if (stamina != null) {
                stats.setStatus("Withdrawing optional stamina potion");
                withdrawOne(ctx, stamina);
                Time.sleep(500, 900);
                return true;
            }
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

        for (GearItem item : selectedGear) {
            if (ctx.equipment().contains(item.name) || ctx.inventory().contains(item.name)) {
                continue;
            }
            String bankItem = firstBankItem(ctx, item.name);
            if (bankItem != null) {
                stats.setStatus("Withdrawing random gear: " + bankItem);
                withdrawOne(ctx, bankItem);
                Time.sleep(500, 900);
                return true;
            }
        }

        return false;
    }

    private boolean needsMandatoryBankWithdrawal(APIContext ctx) {
        return !hasChargedRingEquipped(ctx) && !hasChargedRingInInventory(ctx);
    }

    private boolean equipInventoryLoadout(APIContext ctx) {
        if (hasChargedRingInInventory(ctx) && !hasChargedRingEquipped(ctx)) {
            return equipFirstMatching(ctx, "Wear", TravelItems.CHARGED_RING_OF_WEALTH);
        }
        for (GearItem item : selectedGear) {
            if (!hasEquippedItem(ctx, item.name) && hasInventoryItem(ctx, item.name)) {
                return equipItem(ctx, item.name, item.action);
            }
        }
        return false;
    }

    private boolean equipFirstMatching(APIContext ctx, String preferredAction, String... names) {
        for (String name : names) {
            if (hasInventoryItem(ctx, name)) {
                return equipItem(ctx, name, preferredAction);
            }
        }
        return false;
    }

    private boolean equipItem(APIContext ctx, String itemName, String preferredAction) {
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
                700,
                1600,
                () -> ctx.equipment().contains(itemName)
                        || ctx.inventory().getCount(true, itemName) < beforeInventoryCount,
                100
        );
        return interacted;
    }

    private boolean hasUnequippedSelectedGearInInventory(APIContext ctx) {
        if (selectedGear == null) {
            return false;
        }
        for (GearItem item : selectedGear) {
            if (!hasEquippedItem(ctx, item.name) && hasInventoryItem(ctx, item.name)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnySelectedGearEquipped(APIContext ctx) {
        if (selectedGear == null) {
            return false;
        }
        for (GearItem item : selectedGear) {
            if (hasEquippedItem(ctx, item.name)) {
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
                item != null && TravelItems.matchesAny(item.getName(), itemName));
    }

    private boolean hasEquippedItem(APIContext ctx, String itemName) {
        return ctx.equipment().contains(item ->
                item != null && TravelItems.matchesAny(item.getName(), itemName));
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

        stats.setStatus("Opening bank for travel loadout");
        ctx.bank().open();
        Time.sleep(1000, 1600, () -> ctx.bank().isOpen(), 100);
        return ctx.bank().isOpen();
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
                item != null && TravelItems.isChargedRingOfWealth(item.getName()));
    }

    private boolean hasStaminaInInventory(APIContext ctx) {
        return ctx.inventory().contains(item ->
                item != null && TravelItems.isStaminaPotion(item.getName()));
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

    private String selectedGearSummary() {
        if (selectedGear == null) {
            return "-";
        }
        List<String> items = new ArrayList<>();
        for (GearItem item : selectedGear) {
            items.add(item.name);
        }
        return String.join(", ", items);
    }

    private boolean withdrawOne(APIContext ctx, String itemName) {
        return ctx.bank().withdraw(1, itemName) || ctx.bank().withdrawAny(1, itemName);
    }

    private void selectGearIfNeeded() {
        if (selectedGear != null) {
            return;
        }
        int index = ThreadLocalRandom.current().nextInt(RANDOM_GEAR_PRESETS.length);
        selectedGear = RANDOM_GEAR_PRESETS[index];
        stats.debug("Selected random travel gear preset index=" + index);
    }

    private static class GearItem {
        private final String name;
        private final String action;
        private final int fallbackPrice;

        private GearItem(String name, String action, int fallbackPrice) {
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
