package org.gusta.mixology.core;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.domain.HopperStock;
import org.gusta.mixology.domain.PasteType;
import org.gusta.mixology.domain.PotionOrder;
import org.gusta.mixology.domain.RewardProfit;
import org.gusta.mixology.services.BankService;
import org.gusta.mixology.services.HopperService;
import org.gusta.mixology.services.HopperStockReader;
import org.gusta.mixology.services.OrderCycleService;
import org.gusta.mixology.services.OrderReader;
import org.gusta.mixology.services.PotionInventoryService;
import org.gusta.mixology.services.ProfitPlanner;
import org.gusta.mixology.services.RecoveryService;
import org.gusta.mixology.services.RefinerService;
import org.gusta.mixology.services.SupervisorLaloService;
import org.gusta.mixology.services.SupplyPurchaseService;
import org.gusta.mixology.services.TravelService;
import org.gusta.mixology.services.TravelLoadoutService;
import org.gusta.mixology.stats.MixologyStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public class MixologyRunner implements ScriptModule {
    private static final int MIN_RESTOCK_THRESHOLD = 150;
    private static final int MAX_RESTOCK_THRESHOLD = 300;
    private static final int CRITICAL_RESTOCK_THRESHOLD = 200;
    private static final int HOPPER_UNITS_PER_LEVER = 10;
    private static final int MAX_CARRIED_POTION_BANK_ATTEMPTS = 3;
    private static final long HOPPER_STOCK_CACHE_MILLIS = 2_000L;
    private static final long RUNNER_LATENCY_LOG_MILLIS = 2_000L;

    private final MixologySettings settings;
    private final MixologyStats stats;
    private final ProfitPlanner profitPlanner;
    private final SupplyPurchaseService supplyPurchase;
    private final TravelLoadoutService travelLoadout;
    private final TravelService travel;
    private final BankService bank;
    private final RefinerService refiner;
    private final HopperService hopper;
    private final HopperStockReader hopperStockReader;
    private final PotionInventoryService potionInventory;
    private final OrderReader orderReader;
    private final OrderCycleService orderCycle;
    private final RecoveryService recovery;
    private final SupervisorLaloService supervisorLalo;

    private MixologyState state = MixologyState.CHECK_REQUIREMENTS;
    private List<PotionOrder> currentOrders = new ArrayList<>();
    private long nextSnapshotAt;
    private boolean bulkStockingComplete;
    private boolean restockRequested;
    private boolean cleanupCarriedPotionsBeforeRestock;
    private int carriedPotionBankAttempts;
    private HopperStock lastKnownHopperStock;
    private long lastKnownHopperStockAt;
    private long lastRunnerFinishedAt;
    private long mixBasesRequestedAt;
    private int restockThreshold = randomRestockThreshold();

    public MixologyRunner(
            MixologySettings settings,
            MixologyStats stats,
            ProfitPlanner profitPlanner,
            SupplyPurchaseService supplyPurchase,
            TravelLoadoutService travelLoadout,
            TravelService travel,
            BankService bank,
            RefinerService refiner,
            HopperService hopper,
            HopperStockReader hopperStockReader,
            PotionInventoryService potionInventory,
            OrderReader orderReader,
            OrderCycleService orderCycle,
            RecoveryService recovery,
            SupervisorLaloService supervisorLalo
    ) {
        this.settings = settings;
        this.stats = stats;
        this.profitPlanner = profitPlanner;
        this.supplyPurchase = supplyPurchase;
        this.travelLoadout = travelLoadout;
        this.travel = travel;
        this.bank = bank;
        this.refiner = refiner;
        this.hopper = hopper;
        this.hopperStockReader = hopperStockReader;
        this.potionInventory = potionInventory;
        this.orderReader = orderReader;
        this.orderCycle = orderCycle;
        this.recovery = recovery;
        this.supervisorLalo = supervisorLalo;
    }

    @Override
    public String name() {
        return "mixology.runner";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return true;
    }

    @Override
    public void execute(APIContext ctx) {
        logRunnerResumeGap();
        try {
            stats.startExperienceIfNeeded(ctx);
            stats.setState(state.name());
            logPeriodicSnapshot(ctx);

            if (state == MixologyState.PREPARE_SUPPLIES
                    && travel.isAtSociety(ctx)
                    && !supervisorLalo.isAuthorised()) {
                if (isInsideActiveMixologyRoom(ctx)) {
                    supervisorLalo.assumeAuthorised(ctx, "inside active Mixology room");
                } else if (!supervisorLalo.ensureAuthorised(ctx)) {
                    return;
                }
            }

            if (recovery.clearBlockingState(ctx, state != MixologyState.BUY_SUPPLIES
                    && state != MixologyState.PREPARE_LOADOUT)) {
                return;
            }

            switch (state) {
                case CHECK_REQUIREMENTS:
                    checkRequirements(ctx);
                    return;
                case PLAN_PROFIT:
                    planProfit(ctx);
                    return;
                case BUY_SUPPLIES:
                    buySupplies(ctx);
                    return;
                case PREPARE_LOADOUT:
                    prepareLoadout(ctx);
                    return;
                case TRAVEL_TO_MIXOLOGY:
                    travelToMixology(ctx);
                    return;
                case PREPARE_SUPPLIES:
                    prepareSupplies(ctx);
                    return;
                case LOAD_HOPPER:
                    loadHopper(ctx);
                    return;
                case READ_ORDERS:
                    readOrders(ctx);
                    return;
                case RETURN_TO_LEVERS:
                    returnToLevers(ctx);
                    return;
                case MIX_BASES:
                    executeOrders(ctx);
                    return;
                case RECOVER:
                default:
                    recover(ctx);
            }
        } finally {
            lastRunnerFinishedAt = System.currentTimeMillis();
        }
    }

    private void checkRequirements(APIContext ctx) {
        int herblore = herbloreLevel(ctx);
        stats.debug("Checking requirements: Herblore=" + herblore
                + " required=" + MixologySettings.REQUIRED_HERBLORE_LEVEL);
        if (herblore < MixologySettings.REQUIRED_HERBLORE_LEVEL) {
            String reason = "Mastering Mixology requires 60 Herblore; current=" + herblore;
            stats.setStatus(reason);
            ctx.script().stop(reason);
            return;
        }

        stats.setStatus("Requirements look OK for startup; Herblore=" + herblore);
        state = MixologyState.PLAN_PROFIT;
    }

    private void planProfit(APIContext ctx) {
        Optional<RewardProfit> best = profitPlanner.bestTradeableReward(ctx, herbloreLevel(ctx));
        if (best.isPresent()) {
            RewardProfit profit = best.get();
            stats.setTargetReward(profit.reward().itemName()
                    + " profit/resin=" + Math.round(profit.profitPerResin()));
        } else {
            stats.setTargetReward("Aldarium fallback");
        }

        if (travel.isInMixologyContext(ctx)) {
            stats.setStatus("Started inside Mixology lab; skipping GE/travel setup");
            state = MixologyState.PREPARE_SUPPLIES;
            return;
        }
        state = MixologyState.BUY_SUPPLIES;
    }

    private void buySupplies(APIContext ctx) {
        if (!restockRequested && travel.isInMixologyContext(ctx)) {
            stats.setStatus("Already inside Mixology lab; preparing supplies");
            state = MixologyState.PREPARE_SUPPLIES;
            return;
        }
        if (!restockRequested && (travel.isAtSocietyEntrance(ctx) || travel.isAtSociety(ctx))) {
            stats.setStatus("Already at Society entrance; entering before supply check");
            state = MixologyState.TRAVEL_TO_MIXOLOGY;
            return;
        }
        if (supplyPurchase.ensureStarterSupplies(ctx)) {
            travelLoadout.resetForRestock();
            state = MixologyState.PREPARE_LOADOUT;
        }
    }

    private void prepareLoadout(APIContext ctx) {
        if (travelLoadout.prepareForTravel(ctx)) {
            state = MixologyState.TRAVEL_TO_MIXOLOGY;
        }
    }

    private void travelToMixology(APIContext ctx) {
        if (travel.goToSociety(ctx)) {
            stats.recordTripStarted();
            state = MixologyState.PREPARE_SUPPLIES;
        }
    }

    private void prepareSupplies(APIContext ctx) {
        if (!travel.isAtSociety(ctx)) {
            state = MixologyState.TRAVEL_TO_MIXOLOGY;
            return;
        }

        if (!supervisorLalo.isAuthorised()) {
            if (isInsideActiveMixologyRoom(ctx)) {
                supervisorLalo.assumeAuthorised(ctx, "inside active Mixology room");
            } else if (!supervisorLalo.ensureAuthorised(ctx)) {
                return;
            }
        }

        int carriedPotions = potionInventory.anyPotionCount(ctx);
        if (cleanupCarriedPotionsBeforeRestock && carriedPotions > 0) {
            cleanCarriedPotionsBeforeRestock(ctx, carriedPotions);
            return;
        }
        if (cleanupCarriedPotionsBeforeRestock) {
            cleanupCarriedPotionsBeforeRestock = false;
            carriedPotionBankAttempts = 0;
            orderCycle.resetTrackedBatch("carried potion cleanup completed");
        }

        if (carriedPotions > 0 && travel.isInMixingRoom(ctx)) {
            stats.setStatus("Carried Mixology potion(s) detected; resuming order cycle before banking");
            bulkStockingComplete = true;
            state = MixologyState.RETURN_TO_LEVERS;
            return;
        }

        if (bank.hasAnyHerb(ctx)) {
            if (refiner.refineInventory(ctx)) {
                state = MixologyState.PREPARE_SUPPLIES;
            }
            return;
        }

        if (!bulkStockingComplete && bank.prepareNextHerbBatch(ctx)) {
            state = MixologyState.PREPARE_SUPPLIES;
            return;
        }

        if (!bulkStockingComplete && refreshHopperStock(ctx).map(this::isHopperAtPasteCap).orElse(false)) {
            stats.setStatus("Hopper already capped at "
                    + MixologySettings.MAX_HOPPER_PASTE_PER_TYPE
                    + " each; keeping extra paste banked");
            bulkStockingComplete = true;
            if (ctx.bank().isOpen()) {
                ctx.bank().close();
                Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            }
            state = MixologyState.RETURN_TO_LEVERS;
            return;
        }

        if (!bulkStockingComplete
                && bank.hasAnyBankPaste(ctx)
                && (lastKnownHopperStock == null || !lastKnownHopperStock.isComplete())) {
            stats.setStatus("Checking live Hopper stock before final paste withdrawal");
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            state = MixologyState.LOAD_HOPPER;
            return;
        }

        if (!bulkStockingComplete && bank.preparePasteInventoryForHopper(ctx, lastKnownHopperStock)) {
            bulkStockingComplete = true;
            state = MixologyState.LOAD_HOPPER;
            return;
        }

        if (ctx.bank().isOpen() && !bank.hasAnyBankMixologyInput(ctx)) {
            if (restockRequested && !bulkStockingComplete) {
                stats.setStatus("Bank has no Mixology herbs/paste; going directly to GE herb restock");
                ctx.bank().close();
                Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
                beginGeRestock();
                return;
            }
            stats.setStatus("Bulk Mixology stock prepared; returning to lever center");
            bulkStockingComplete = true;
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            state = MixologyState.RETURN_TO_LEVERS;
            return;
        }

        if (!bulkStockingComplete) {
            stats.setStatus("Waiting for bulk Mixology stock preparation");
            Time.sleep(1000, 1600);
            return;
        }

        if (bank.hasAnyPaste(ctx)) {
            state = MixologyState.LOAD_HOPPER;
            return;
        }

        state = MixologyState.RETURN_TO_LEVERS;
    }

    private void loadHopper(APIContext ctx) {
        if (bank.hasAnyHerb(ctx)) {
            state = MixologyState.PREPARE_SUPPLIES;
            return;
        }
        if (!travel.enterMixingRoom(ctx)) {
            return;
        }

        Optional<HopperStock> currentStock = refreshHopperStock(ctx);
        if (currentStock.isEmpty()) {
            stats.setStatus("Waiting for readable live Hopper stock");
            Time.sleep(700, 1100);
            return;
        }

        HopperStock liveStock = currentStock.get();
        if (!bank.hasAnyPaste(ctx)) {
            if (isHopperAtPasteCap(liveStock)) {
                stats.setStatus("Hopper already capped; keeping remaining paste in bank");
                bulkStockingComplete = true;
                restockRequested = false;
                state = MixologyState.RETURN_TO_LEVERS;
                return;
            }

            stats.setStatus("Live Hopper stock confirmed; preparing exact paste load");
            state = MixologyState.PREPARE_SUPPLIES;
            return;
        }

        if (!hopperCanAcceptInventoryPaste(ctx, liveStock)) {
            stats.setStatus("Hopper has no space for carried paste; banking paste before continuing");
            if (bank.depositCarriedPasteForCappedHopper(ctx)) {
                bulkStockingComplete = true;
                restockRequested = false;
                state = MixologyState.RETURN_TO_LEVERS;
            }
            return;
        }

        if (!hopper.loadAvailablePaste(ctx)) {
            return;
        }
        markHopperFilledFromCarriedPaste();
        restockRequested = false;
        resetRestockThreshold("hopper loaded");
        state = MixologyState.PREPARE_SUPPLIES;
        Time.sleep(600, 1000);
    }

    private void readOrders(APIContext ctx) {
        if (!travel.isInMixingRoom(ctx)) {
            stats.setStatus("Entering active Mixology room before reading orders");
            travel.enterMixingRoom(ctx);
            Time.sleep(800, 1200);
            return;
        }

        currentOrders = orderReader.readOrders(ctx);
        if (currentOrders.isEmpty()) {
            Time.sleep(1200, 1800);
            return;
        }
        if (currentOrders.size() < 3) {
            stats.setStatus("Waiting for 3 Mixology orders before batch delivery; read=" + currentOrders.size());
            Time.sleep(1200, 1800);
            return;
        }

        for (PotionOrder order : currentOrders) {
            if (!order.isComplete()) {
                stats.setStatus("HUD order is readable but workstation is missing: " + order.label());
                Time.sleep(1600, 2200);
                return;
            }
        }

        if (shouldRestockFromHopper(ctx)) {
            return;
        }
        state = MixologyState.MIX_BASES;
        mixBasesRequestedAt = System.currentTimeMillis();
        stats.debug("Mix handoff: orders validated; entering MIX_BASES without waiting for another task cycle");
        if (canStartMixBasesImmediately(ctx)) {
            executeOrders(ctx);
        } else {
            stats.debug("Mix handoff deferred until player/interface is stable");
        }
    }

    private void executeOrders(APIContext ctx) {
        logMixBasesEntryLatency();
        if (shouldRefillHopperBeforeMixing(ctx, currentOrders)) {
            return;
        }

        if (orderCycle.executeCycle(ctx, currentOrders)) {
            currentOrders = new ArrayList<>();
            state = MixologyState.RETURN_TO_LEVERS;
            return;
        }
        int carriedPotions = potionInventory.anyPotionCount(ctx);
        if (carriedPotions > 0) {
            if (shouldCleanCarriedPotionsForLowHopper(ctx, currentOrders, carriedPotions)) {
                return;
            }
            stats.setStatus("Order cycle paused with "
                    + carriedPotions
                    + " Mixology potion(s); returning to levers instead of banking");
            state = MixologyState.RETURN_TO_LEVERS;
            return;
        }
        state = MixologyState.PREPARE_SUPPLIES;
    }

    private void returnToLevers(APIContext ctx) {
        if (travel.moveToMixingRoomCenter(ctx, "Returning to lever center before reading orders")) {
            state = MixologyState.READ_ORDERS;
        }
    }

    private void recover(APIContext ctx) {
        if (!travel.isAtSociety(ctx)) {
            state = MixologyState.TRAVEL_TO_MIXOLOGY;
            return;
        }
        stats.setStatus("Recovering Mixology loop; rereading orders");
        currentOrders = new ArrayList<>();
        state = MixologyState.RETURN_TO_LEVERS;
        Time.sleep(1000, 1500);
    }

    private int herbloreLevel(APIContext ctx) {
        return ctx.skills().get(Skill.Skills.HERBLORE).getRealLevel();
    }

    private void logPeriodicSnapshot(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextSnapshotAt) {
            return;
        }
        stats.snapshot(ctx, "runner");
        nextSnapshotAt = now + 5_000L;
    }

    private boolean hasThreeReadableRecipes() {
        if (currentOrders.size() < 3) {
            return false;
        }
        for (PotionOrder order : currentOrders) {
            if (order.recipe() == null) {
                return false;
            }
        }
        return true;
    }

    private boolean isInsideActiveMixologyRoom(APIContext ctx) {
        return settings.mixingRoomArea().contains(ctx.localPlayer().getLocation());
    }

    private boolean shouldRestockFromHopper(APIContext ctx) {
        Optional<HopperStock> stock = refreshHopperStock(ctx);
        if (stock.isEmpty()) {
            return false;
        }

        HopperStock hopperStock = stock.get();
        int threshold = effectiveRestockThreshold();
        PasteType lowType = hopperStock.firstAtOrBelow(threshold);
        if (lowType == null) {
            stats.debug("Hopper stock OK: " + hopperStock.summary()
                    + " threshold=" + threshold
                    + " rolled=" + restockThreshold);
            return false;
        }

        int carriedPotions = potionInventory.anyPotionCount(ctx);
        if (carriedPotions > 0) {
            if (!orderCycle.hasTrackedRequiredOrdersForCurrentBatch(currentOrders)
                    && !hopperCanCoverRemainingOrders(hopperStock, currentOrders)) {
                startRestockAfterCarriedPotionCleanup("Hopper " + lowType.label()
                        + " stock=" + hopperStock.amount(lowType)
                        + " <= threshold=" + threshold
                        + " and current partial batch cannot be completed ("
                        + hopperStock.summary() + ")");
                return true;
            }

            stats.setStatus("Hopper low (" + lowType.label()
                    + "=" + hopperStock.amount(lowType)
                    + "/" + threshold
                    + ") but " + carriedPotions
                    + " carried potion(s) exist; finishing delivery before restock");
            return false;
        }

        startRestock("Hopper " + lowType.label()
                + " stock=" + hopperStock.amount(lowType)
                + " <= threshold=" + threshold
                + " (" + hopperStock.summary() + ")");
        return true;
    }

    private void startRestock(String reason) {
        stats.setStatus("Restock triggered: " + reason);
        stats.debug("Restock bank-first flow: " + reason);
        bulkStockingComplete = false;
        restockRequested = true;
        currentOrders = new ArrayList<>();
        state = MixologyState.PREPARE_SUPPLIES;
    }

    private void startRestockAfterCarriedPotionCleanup(String reason) {
        stats.setStatus("Restock recovery: " + reason);
        stats.debug("Low hopper recovery will bank carried potions before restock: " + reason);
        cleanupCarriedPotionsBeforeRestock = true;
        carriedPotionBankAttempts = 0;
        orderCycle.resetTrackedBatch("low hopper recovery");
        startRestock(reason);
    }

    private void beginGeRestock() {
        supplyPurchase.requestRestock();
        travelLoadout.resetForRestock();
        lastKnownHopperStock = null;
        lastKnownHopperStockAt = 0L;
        stats.setStatus("Skipping Aldarium claim/sale; going directly to GE herb restock");
        state = MixologyState.BUY_SUPPLIES;
    }

    private void resetRestockThreshold(String reason) {
        restockThreshold = randomRestockThreshold();
        stats.debug("New hopper restock threshold=" + restockThreshold + " after " + reason);
    }

    private int effectiveRestockThreshold() {
        return Math.max(restockThreshold, CRITICAL_RESTOCK_THRESHOLD);
    }

    private Optional<HopperStock> refreshHopperStock(APIContext ctx) {
        Optional<HopperStock> stock = hopperStockReader.readStock(ctx);
        stock.ifPresent(value -> {
            lastKnownHopperStock = value;
            lastKnownHopperStockAt = System.currentTimeMillis();
            stats.debug("Current Hopper stock: " + value.summary());
        });
        return stock;
    }

    private Optional<HopperStock> cachedOrRefreshHopperStock(APIContext ctx, String reason) {
        long snapshotAge = System.currentTimeMillis() - lastKnownHopperStockAt;
        if (lastKnownHopperStock != null
                && snapshotAge >= 0L
                && snapshotAge <= HOPPER_STOCK_CACHE_MILLIS) {
            stats.debug("Reusing Hopper snapshot age=" + snapshotAge
                    + "ms for " + reason + ": " + lastKnownHopperStock.summary());
            return Optional.of(lastKnownHopperStock);
        }
        return refreshHopperStock(ctx);
    }

    private boolean isHopperAtPasteCap(HopperStock stock) {
        for (PasteType type : PasteType.values()) {
            if (stock.amount(type) < MixologySettings.MAX_HOPPER_PASTE_PER_TYPE) {
                return false;
            }
        }
        return true;
    }

    private boolean hopperCanAcceptInventoryPaste(APIContext ctx, HopperStock stock) {
        for (PasteType type : PasteType.values()) {
            if (bank.countInventoryItem(ctx, type.pasteName()) <= 0) {
                continue;
            }
            if (stock.amount(type) < MixologySettings.MAX_HOPPER_PASTE_PER_TYPE) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldCleanCarriedPotionsForLowHopper(
            APIContext ctx,
            List<PotionOrder> orders,
            int carriedPotions
    ) {
        if (carriedPotions <= 0 || orders == null || orders.isEmpty()) {
            return false;
        }

        Optional<HopperStock> stock = cachedOrRefreshHopperStock(ctx, "MIX_BASES");
        if (stock.isEmpty()) {
            return false;
        }

        HopperStock hopperStock = stock.get();
        if (orderCycle.hasTrackedRequiredOrdersForCurrentBatch(orders)
                || hopperCanCoverRemainingOrders(hopperStock, orders)) {
            return false;
        }

        startRestockAfterCarriedPotionCleanup("Hopper cannot complete carried partial batch: "
                + hopperStock.summary()
                + " remainingNeed="
                + remainingPasteNeedText(orders)
                + " carriedPotions="
                + carriedPotions);
        return true;
    }

    private boolean shouldRefillHopperBeforeMixing(APIContext ctx, List<PotionOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return false;
        }

        Optional<HopperStock> stock = refreshHopperStock(ctx);
        if (stock.isEmpty()) {
            return false;
        }

        HopperStock hopperStock = stock.get();
        int carriedPotions = potionInventory.anyPotionCount(ctx);
        if (carriedPotions > 0 && orderCycle.hasTrackedRequiredOrdersForCurrentBatch(orders)) {
            return false;
        }
        if (hopperCanCoverRemainingOrders(hopperStock, orders)) {
            return false;
        }

        String reason = "Hopper cannot cover remaining order batch before mixing: "
                + hopperStock.summary()
                + " remainingNeed="
                + remainingPasteNeedText(orders)
                + " carriedPotions="
                + carriedPotions;
        if (carriedPotions > 0) {
            startRestockAfterCarriedPotionCleanup(reason);
        } else {
            stats.setStatus("Restock recovery: " + reason);
            orderCycle.resetTrackedBatch("pre-mix low hopper recovery");
            startRestock(reason);
        }
        return true;
    }

    private void cleanCarriedPotionsBeforeRestock(APIContext ctx, int carriedPotions) {
        carriedPotionBankAttempts++;
        stats.setStatus("Banking " + carriedPotions
                + " carried Mixology potion(s) before low-hopper restock; attempt="
                + carriedPotionBankAttempts);
        if (bank.depositInventory(ctx, "Banking carried Mixology potions before low-hopper recovery")) {
            cleanupCarriedPotionsBeforeRestock = false;
            carriedPotionBankAttempts = 0;
            orderCycle.resetTrackedBatch("banked carried potions before restock");
            bulkStockingComplete = false;
            state = MixologyState.PREPARE_SUPPLIES;
            return;
        }

        if (carriedPotionBankAttempts >= MAX_CARRIED_POTION_BANK_ATTEMPTS) {
            stats.setStatus("Bank cleanup failed; dropping carried Mixology potions before restock");
            if (potionInventory.dropAllPotions(ctx)) {
                cleanupCarriedPotionsBeforeRestock = false;
                carriedPotionBankAttempts = 0;
                orderCycle.resetTrackedBatch("dropped carried potions before restock");
                bulkStockingComplete = false;
                state = MixologyState.PREPARE_SUPPLIES;
                return;
            }
        }

        Time.sleep(800, 1300);
    }

    private boolean hopperCanCoverRemainingOrders(HopperStock stock, List<PotionOrder> orders) {
        if (stock == null || orders == null || orders.isEmpty()) {
            return true;
        }

        List<PotionOrder> remainingOrders = orderCycle.remainingOrdersForCurrentBatch(orders);
        if (remainingOrders.isEmpty()) {
            return true;
        }

        for (PasteType type : PasteType.values()) {
            int available = stock.amount(type);
            if (available < 0) {
                continue;
            }
            int required = requiredHopperUnits(remainingOrders, type);
            if (required > 0 && available < required) {
                stats.debug("Hopper cannot cover remaining order paste: "
                        + type.label()
                        + " available="
                        + available
                        + " required="
                        + required
                        + " remaining="
                        + remainingPasteNeedText(orders));
                return false;
            }
        }
        return true;
    }

    private int requiredHopperUnits(List<PotionOrder> orders, PasteType type) {
        int pulls = 0;
        for (PotionOrder order : orders) {
            if (order == null || order.recipe() == null) {
                continue;
            }
            for (PasteType paste : order.recipe().sequence()) {
                if (paste == type) {
                    pulls++;
                }
            }
        }
        return pulls * HOPPER_UNITS_PER_LEVER;
    }

    private String remainingPasteNeedText(List<PotionOrder> orders) {
        List<PotionOrder> remainingOrders = orderCycle.remainingOrdersForCurrentBatch(orders);
        return "Mox=" + requiredHopperUnits(remainingOrders, PasteType.MOX)
                + ", Aga=" + requiredHopperUnits(remainingOrders, PasteType.AGA)
                + ", Lye=" + requiredHopperUnits(remainingOrders, PasteType.LYE);
    }

    private void markHopperFilledFromCarriedPaste() {
        if (lastKnownHopperStock == null) {
            return;
        }
        stats.debug("Invalidating Hopper stock snapshot after paste deposit");
        lastKnownHopperStock = null;
        lastKnownHopperStockAt = 0L;
    }

    private boolean canStartMixBasesImmediately(APIContext ctx) {
        return !ctx.localPlayer().isMoving()
                && !ctx.localPlayer().isAnimating()
                && !ctx.menu().isOpen()
                && !ctx.dialogues().isDialogueOpen()
                && !ctx.inventory().isItemSelected();
    }

    private void logRunnerResumeGap() {
        if (lastRunnerFinishedAt <= 0L || !isLatencySensitiveState(state)) {
            return;
        }
        long gap = System.currentTimeMillis() - lastRunnerFinishedAt;
        if (gap >= RUNNER_LATENCY_LOG_MILLIS) {
            stats.debug("Scheduler latency before " + state + ": " + gap + "ms since prior runner cycle");
        }
    }

    private void logMixBasesEntryLatency() {
        if (mixBasesRequestedAt <= 0L) {
            return;
        }
        long latency = System.currentTimeMillis() - mixBasesRequestedAt;
        stats.debug("Mix handoff latency READ_ORDERS -> MIX_BASES=" + latency + "ms");
        mixBasesRequestedAt = 0L;
    }

    private boolean isLatencySensitiveState(MixologyState candidate) {
        return candidate == MixologyState.READ_ORDERS
                || candidate == MixologyState.MIX_BASES
                || candidate == MixologyState.RETURN_TO_LEVERS;
    }

    private static int randomRestockThreshold() {
        return ThreadLocalRandom.current().nextInt(MIN_RESTOCK_THRESHOLD, MAX_RESTOCK_THRESHOLD + 1);
    }
}
