package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.data.HerbSources;
import org.gusta.mixology.domain.HerbSource;
import org.gusta.mixology.domain.HopperStock;
import org.gusta.mixology.domain.PasteType;
import org.gusta.mixology.domain.PotionOrder;
import org.gusta.mixology.stats.MixologyStats;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BankService {
    private final MixologySettings settings;
    private final ObjectService objects;
    private final MixologyStats stats;

    public BankService(MixologySettings settings, ObjectService objects, MixologyStats stats) {
        this.settings = settings;
        this.objects = objects;
        this.stats = stats;
    }

    public boolean hasAnyMixologyInput(APIContext ctx) {
        return hasAnyHerb(ctx) || hasAnyPaste(ctx);
    }

    public boolean hasAnyBankMixologyInput(APIContext ctx) {
        return ctx.bank().isOpen() && hasBankMixologyInput(ctx);
    }

    public boolean hasAnyBankHerb(APIContext ctx) {
        return ctx.bank().isOpen() && firstBankHerbForAnyPaste(ctx) != null;
    }

    public boolean hasAnyBankPaste(APIContext ctx) {
        if (!ctx.bank().isOpen()) {
            return false;
        }
        for (PasteType type : PasteType.values()) {
            if (ctx.bank().contains(type.pasteName())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAnyInputForPaste(APIContext ctx, PasteType type) {
        if (countInventoryItem(ctx, type.pasteName()) > 0) {
            return true;
        }

        for (HerbSource source : HerbSources.all()) {
            if (source.pasteType() == type && countInventoryItem(ctx, source.itemName()) > 0) {
                return true;
            }
        }

        if (!ctx.bank().isOpen()) {
            return false;
        }

        if (ctx.bank().contains(type.pasteName())) {
            return true;
        }

        for (HerbSource source : HerbSources.all()) {
            if (source.pasteType() == type && ctx.bank().contains(source.itemName())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAnyPaste(APIContext ctx) {
        for (PasteType type : PasteType.values()) {
            if (ctx.inventory().contains(type.pasteName())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAnyHerb(APIContext ctx) {
        for (HerbSource source : HerbSources.all()) {
            if (ctx.inventory().contains(source.itemName())) {
                return true;
            }
        }
        return false;
    }

    public boolean prepareInventory(APIContext ctx) {
        if (hasAnyMixologyInput(ctx) && ctx.inventory().getEmptySlotCount() >= 24) {
            return true;
        }

        if (!openBank(ctx)) {
            return false;
        }

        if (ctx.inventory().getEmptySlotCount() < 28) {
            stats.setStatus("Deposit all before withdrawing Mixology supplies");
            ctx.bank().depositInventory();
            Time.sleep(700, 1100, () -> ctx.inventory().getEmptySlotCount() == 28, 100);
            return false;
        }

        if (withdrawBankPaste(ctx)) {
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return true;
        }

        Set<PasteType> covered = EnumSet.noneOf(PasteType.class);
        for (HerbSource source : HerbSources.all()) {
            if (covered.contains(source.pasteType()) || !ctx.bank().contains(source.itemName())) {
                continue;
            }

            int quantity = Math.max(1, Math.min(9, ctx.inventory().getEmptySlotCount()));
            stats.setStatus("Withdrawing " + quantity + "x " + source.itemName() + " for " + source.pasteType().label());
            if (ctx.bank().withdraw(quantity, source.itemName())) {
                covered.add(source.pasteType());
                Time.sleep(500, 900);
            }
            if (ctx.inventory().getEmptySlotCount() <= MixologySettings.MIN_EMPTY_SLOTS_FOR_ORDERS) {
                break;
            }
        }

        if (hasAnyMixologyInput(ctx)) {
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return true;
        }

        stats.setStatus("No clean herbs/pastes found in bank for Mixology");
        return false;
    }

    public boolean prepareInventoryForOrders(APIContext ctx, List<PotionOrder> orders) {
        Map<PasteType, Integer> neededPaste = neededPasteForOrders(orders);
        if (neededPaste.isEmpty()) {
            return prepareInventory(ctx);
        }

        if (inventoryCanCoverOrders(ctx, orders)) {
            stats.setStatus("Inventory already covers visible orders: " + pasteNeedText(neededPaste));
            return true;
        }

        if (!openBank(ctx)) {
            return false;
        }

        if (ctx.inventory().getEmptySlotCount() < 28) {
            stats.setStatus("Deposit all before order-based Mixology supplies");
            ctx.bank().depositInventory();
            Time.sleep(700, 1100, () -> ctx.inventory().getEmptySlotCount() == 28, 100);
            return false;
        }

        stats.setStatus("Order batch paste need: " + pasteNeedText(neededPaste));
        withdrawPasteForOrders(ctx, neededPaste);
        withdrawHerbsForOrders(ctx, neededPaste);

        if (hasAnyMixologyInput(ctx)) {
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return true;
        }

        stats.setStatus("No bank herbs/paste available for order batch: " + pasteNeedText(neededPaste));
        return false;
    }

    public boolean depositInventory(APIContext ctx, String reason) {
        if (!openBank(ctx)) {
            return false;
        }

        stats.setStatus(reason);
        ctx.bank().depositInventory();
        Time.sleep(700, 1100, () -> ctx.inventory().getEmptySlotCount() == 28, 100);
        if (ctx.inventory().getEmptySlotCount() < 28) {
            return false;
        }

        ctx.bank().close();
        Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
        return true;
    }

    public boolean depositCarriedPasteForCappedHopper(APIContext ctx) {
        int beforeTotal = totalInventoryPaste(ctx);
        if (beforeTotal <= 0) {
            return true;
        }

        if (!openBank(ctx)) {
            return false;
        }

        stats.setStatus("Banking " + beforeTotal + " carried paste after capped Hopper");
        boolean depositTriggered = false;
        for (PasteType type : PasteType.values()) {
            int before = countInventoryItem(ctx, type.pasteName());
            if (before <= 0) {
                continue;
            }

            boolean clicked = ctx.bank().depositAll(type.pasteName())
                    || ctx.bank().deposit(before, type.pasteName());
            if (!clicked) {
                stats.debug("Paste bank action rejected: " + type.pasteName() + " amount=" + before);
                continue;
            }

            depositTriggered = true;
            Time.sleep(800, 1300, () -> countInventoryItem(ctx, type.pasteName()) < before, 100);
            int remaining = countInventoryItem(ctx, type.pasteName());
            stats.debug("Capped Hopper paste bank check: " + type.label()
                    + " before=" + before + " remaining=" + remaining);
        }

        int remainingTotal = totalInventoryPaste(ctx);
        if (remainingTotal > 0 && inventoryContainsOnlyPaste(ctx)) {
            stats.setStatus("Fallback: depositing paste-only inventory after capped Hopper");
            int beforeFallback = remainingTotal;
            ctx.bank().depositInventory();
            Time.sleep(900, 1400, () -> totalInventoryPaste(ctx) < beforeFallback, 100);
            remainingTotal = totalInventoryPaste(ctx);
            stats.debug("Capped Hopper full-inventory fallback remaining=" + remainingTotal);
        }
        if (remainingTotal > 0) {
            stats.setStatus("Paste banking not confirmed; keeping bank open (remaining=" + remainingTotal + ")");
            if (!depositTriggered) {
                stats.debug("No capped-Hopper paste deposit action was accepted; will retry from the open bank");
            }
            return false;
        }

        stats.setStatus("All carried paste banked after capped Hopper");
        ctx.bank().close();
        Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
        return !ctx.bank().isOpen();
    }

    public boolean prepareBulkInventory(APIContext ctx) {
        if (hasAnyMixologyInput(ctx)) {
            return true;
        }

        if (!openBank(ctx)) {
            return false;
        }

        if (ctx.inventory().getEmptySlotCount() < 28) {
            stats.setStatus("Deposit all before bulk Mixology stocking");
            ctx.bank().depositInventory();
            Time.sleep(700, 1100, () -> ctx.inventory().getEmptySlotCount() == 28, 100);
            return false;
        }

        HerbSource herb = firstBankHerbForAnyPaste(ctx);
        if (herb != null) {
            int bankCount = ctx.bank().getCount(herb.itemName());
            int quantity = Math.max(1, Math.min(28, bankCount));
            stats.setStatus("Bulk stocking: withdrawing " + quantity + "x "
                    + herb.itemName() + " for " + herb.pasteType().label() + " paste");
            if (ctx.bank().withdraw(quantity, herb.itemName())
                    || (quantity == bankCount && ctx.bank().withdrawAll(herb.itemName()))) {
                Time.sleep(500, 900, () -> hasAnyHerb(ctx), 100);
                ctx.bank().close();
                Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
                return true;
            }
            return false;
        }

        boolean withdrewPaste = false;
        for (PasteType type : PasteType.values()) {
            if (!ctx.bank().contains(type.pasteName())) {
                continue;
            }

            stats.setStatus("Bulk stocking: withdrawing all " + type.pasteName());
            if (ctx.bank().withdrawAll(type.pasteName())
                    || ctx.bank().withdraw(Math.max(1, ctx.bank().getCount(type.pasteName())), type.pasteName())) {
                withdrewPaste = true;
                Time.sleep(400, 700);
            }
        }

        if (withdrewPaste && hasAnyPaste(ctx)) {
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return true;
        }

        stats.setStatus("No bank herbs/paste left for bulk Mixology stocking");
        return false;
    }

    public boolean prepareNextHerbBatch(APIContext ctx) {
        if (!openBank(ctx)) {
            return false;
        }

        if (ctx.inventory().getEmptySlotCount() < 28) {
            stats.setStatus("Banking refined paste before next herb batch");
            ctx.bank().depositInventory();
            Time.sleep(700, 1100, () -> ctx.inventory().getEmptySlotCount() == 28, 100);
            if (ctx.inventory().getEmptySlotCount() < 28) {
                return false;
            }
        }

        HerbSource herb = firstBankHerbForAnyPaste(ctx);
        if (herb == null) {
            stats.setStatus("No bank herbs left; preparing stored paste for Hopper");
            return false;
        }

        int bankCount = ctx.bank().getCount(herb.itemName());
        int quantity = Math.max(1, Math.min(28, bankCount));
        stats.setStatus("Bulk herb loop: withdrawing " + quantity + "x "
                + herb.itemName() + " for " + herb.pasteType().label() + " paste");
        if (ctx.bank().withdraw(quantity, herb.itemName())
                || (quantity == bankCount && ctx.bank().withdrawAll(herb.itemName()))) {
            Time.sleep(500, 900, () -> hasAnyHerb(ctx), 100);
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return true;
        }
        return false;
    }

    public boolean preparePasteInventoryForHopper(APIContext ctx, HopperStock hopperStock) {
        if (hopperStock == null || !hopperStock.isComplete()) {
            stats.setStatus("Live Hopper stock required before final paste withdrawal");
            stats.debug("Skipping final Hopper paste withdrawal because no complete live Hopper snapshot is available");
            return false;
        }

        if (!openBank(ctx)) {
            return false;
        }

        if (hasAnyBankHerb(ctx)) {
            stats.setStatus("Bank still has herbs; delaying Hopper paste withdrawal");
            return false;
        }

        if (ctx.inventory().getEmptySlotCount() < 28) {
            stats.setStatus("Deposit all before final paste withdrawal");
            ctx.bank().depositInventory();
            Time.sleep(700, 1100, () -> ctx.inventory().getEmptySlotCount() == 28, 100);
            if (ctx.inventory().getEmptySlotCount() < 28) {
                return false;
            }
        }

        boolean withdrewPaste = false;
        for (PasteType type : PasteType.values()) {
            int bankCount = ctx.bank().getCount(type.pasteName());
            if (bankCount <= 0 && !ctx.bank().contains(type.pasteName())) {
                continue;
            }

            int currentHopperAmount = hopperStock == null ? -1 : hopperStock.amount(type);
            int spaceLeft = currentHopperAmount < 0
                    ? MixologySettings.MAX_HOPPER_PASTE_PER_TYPE
                    : Math.max(0, MixologySettings.MAX_HOPPER_PASTE_PER_TYPE - currentHopperAmount);
            if (spaceLeft <= 0) {
                stats.setStatus("Final Hopper load: " + type.label()
                        + " already at cap " + currentHopperAmount + "/"
                        + MixologySettings.MAX_HOPPER_PASTE_PER_TYPE);
                continue;
            }

            int available = bankCount > 0 ? bankCount : MixologySettings.MAX_HOPPER_PASTE_PER_TYPE;
            int quantity = Math.max(1, Math.min(spaceLeft, available));
            stats.setStatus("Final Hopper load: withdrawing " + quantity + "x "
                    + type.pasteName()
                    + " cap=" + MixologySettings.MAX_HOPPER_PASTE_PER_TYPE
                    + " current=" + (currentHopperAmount < 0 ? "unknown" : currentHopperAmount));
            if (ctx.bank().withdraw(quantity, type.pasteName())) {
                withdrewPaste = true;
                Time.sleep(400, 700);
            }
        }

        if (withdrewPaste && hasAnyPaste(ctx)) {
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return true;
        }

        stats.setStatus("No stored paste found for Hopper");
        return false;
    }

    public boolean inventoryCanCoverOrders(APIContext ctx, List<PotionOrder> orders) {
        Map<PasteType, Integer> neededPaste = neededPasteForOrders(orders);
        if (neededPaste.isEmpty()) {
            return hasAnyMixologyInput(ctx);
        }

        for (PasteType type : PasteType.values()) {
            int needed = neededPaste.getOrDefault(type, 0);
            if (needed <= 0) {
                continue;
            }
            if (availableInventoryPastePotential(ctx, type) < needed) {
                return false;
            }
        }
        return true;
    }

    private boolean withdrawBankPaste(APIContext ctx) {
        boolean withdrew = false;
        for (PasteType type : PasteType.values()) {
            if (!ctx.bank().contains(type.pasteName())) {
                continue;
            }

            int quantity = Math.max(1, Math.min(9, ctx.inventory().getEmptySlotCount()));
            stats.setStatus("Withdrawing " + quantity + "x " + type.pasteName() + " for hopper");
            if (ctx.bank().withdraw(quantity, type.pasteName())) {
                withdrew = true;
                Time.sleep(500, 900);
            }
            if (ctx.inventory().getEmptySlotCount() <= MixologySettings.MIN_EMPTY_SLOTS_FOR_ORDERS) {
                break;
            }
        }
        return withdrew && hasAnyPaste(ctx);
    }

    private void withdrawPasteForOrders(APIContext ctx, Map<PasteType, Integer> neededPaste) {
        for (PasteType type : PasteType.values()) {
            int needed = neededPaste.getOrDefault(type, 0);
            if (needed <= 0 || !ctx.bank().contains(type.pasteName())) {
                continue;
            }

            stats.setStatus("Withdrawing " + needed + "x " + type.pasteName() + " for visible orders");
            if (ctx.bank().withdraw(needed, type.pasteName())) {
                Time.sleep(500, 900);
            }
        }
    }

    private void withdrawHerbsForOrders(APIContext ctx, Map<PasteType, Integer> neededPaste) {
        for (PasteType type : PasteType.values()) {
            int missingPaste = Math.max(0, neededPaste.getOrDefault(type, 0) - countInventoryItem(ctx, type.pasteName()));
            if (missingPaste <= 0) {
                continue;
            }

            HerbSource source = firstBankHerbForPaste(ctx, type);
            if (source == null) {
                stats.setStatus("Missing bank herb for " + type.label() + " order paste need=" + missingPaste);
                continue;
            }

            int quantity = Math.max(1, (int) Math.ceil((double) missingPaste / source.pasteYield()));
            stats.setStatus("Withdrawing " + quantity + "x " + source.itemName()
                    + " for " + type.label()
                    + " need=" + missingPaste
                    + " yieldEach=" + source.pasteYield());
            if (ctx.bank().withdraw(quantity, source.itemName())) {
                Time.sleep(500, 900);
            }
        }
    }

    private HerbSource firstBankHerbForPaste(APIContext ctx, PasteType type) {
        for (HerbSource source : HerbSources.all()) {
            if (source.pasteType() == type && ctx.bank().contains(source.itemName())) {
                return source;
            }
        }
        return null;
    }

    private HerbSource firstBankHerbForAnyPaste(APIContext ctx) {
        for (HerbSource source : HerbSources.all()) {
            if (ctx.bank().contains(source.itemName())) {
                return source;
            }
        }
        return null;
    }

    private int availableInventoryPastePotential(APIContext ctx, PasteType type) {
        int amount = countInventoryItem(ctx, type.pasteName());
        for (HerbSource source : HerbSources.all()) {
            if (source.pasteType() == type) {
                amount += countInventoryItem(ctx, source.itemName()) * source.pasteYield();
            }
        }
        return amount;
    }

    private Map<PasteType, Integer> neededPasteForOrders(List<PotionOrder> orders) {
        Map<PasteType, Integer> needed = new EnumMap<>(PasteType.class);
        if (orders == null) {
            return needed;
        }

        for (PotionOrder order : orders) {
            if (order == null || order.recipe() == null) {
                continue;
            }
            for (PasteType paste : order.recipe().sequence()) {
                needed.merge(paste, 1, Integer::sum);
            }
        }
        return needed;
    }

    private String pasteNeedText(Map<PasteType, Integer> neededPaste) {
        return "Mox=" + neededPaste.getOrDefault(PasteType.MOX, 0)
                + ", Aga=" + neededPaste.getOrDefault(PasteType.AGA, 0)
                + ", Lye=" + neededPaste.getOrDefault(PasteType.LYE, 0);
    }

    public int countInventoryItem(APIContext ctx, String itemName) {
        int count = 0;
        for (ItemWidget item : ctx.inventory().getItems(itemName)) {
            if (item != null && item.getName() != null) {
                count += Math.max(1, item.getStackSize());
            }
        }
        return count;
    }

    private int totalInventoryPaste(APIContext ctx) {
        int total = 0;
        for (PasteType type : PasteType.values()) {
            total += countInventoryItem(ctx, type.pasteName());
        }
        return total;
    }

    private boolean inventoryContainsOnlyPaste(APIContext ctx) {
        boolean foundPaste = false;
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }

            boolean isPaste = false;
            for (PasteType type : PasteType.values()) {
                if (type.pasteName().equalsIgnoreCase(item.getName())) {
                    isPaste = true;
                    foundPaste = true;
                    break;
                }
            }
            if (!isPaste) {
                return false;
            }
        }
        return foundPaste;
    }

    private boolean hasBankMixologyInput(APIContext ctx) {
        for (PasteType type : PasteType.values()) {
            if (ctx.bank().contains(type.pasteName())) {
                return true;
            }
        }
        for (HerbSource source : HerbSources.all()) {
            if (ctx.bank().contains(source.itemName())) {
                return true;
            }
        }
        return false;
    }

    private boolean openBank(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            return true;
        }

        if (objects.interact(ctx, settings.alchemicalSocietyArea(),
                new String[]{"Bank chest", "Bank"}, "Bank")) {
            Time.sleep(900, 1500, () -> ctx.bank().isOpen()
                    || ctx.dialogues().isDialogueOpen()
                    || ctx.dialogues().isChatOpen()
                    || ctx.dialogues().canContinue(), 100);
            if (ctx.bank().isOpen()) {
                return true;
            }
            BankOpenService.closeBlockingContext(ctx);
        }

        return BankOpenService.open(ctx, stats, "Opening nearest bank");
    }
}
