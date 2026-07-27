package org.gusta.mixology;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.GameType;
import com.epicbot.api.shared.event.ChatMessageEvent;
import com.epicbot.api.shared.script.Script;
import com.epicbot.api.shared.script.ScriptManifest;
import com.epicbot.api.shared.util.paint.PaintContext;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.core.CameraController;
import org.gusta.mixology.core.MixologyRunner;
import org.gusta.mixology.core.ModuleTask;
import org.gusta.mixology.core.LoopWatchdogController;
import org.gusta.mixology.core.MembersWorldController;
import org.gusta.mixology.core.RuntimeController;
import org.gusta.mixology.core.SimpleAntibanController;
import org.gusta.mixology.core.StaminaController;
import org.gusta.mixology.services.BankService;
import org.gusta.mixology.services.ConveyorService;
import org.gusta.mixology.services.GePricingService;
import org.gusta.mixology.services.HopperService;
import org.gusta.mixology.services.HopperStockReader;
import org.gusta.mixology.services.MixerService;
import org.gusta.mixology.services.ObjectService;
import org.gusta.mixology.services.OrderCycleService;
import org.gusta.mixology.services.OrderReader;
import org.gusta.mixology.services.ProcessingService;
import org.gusta.mixology.services.PotionInventoryService;
import org.gusta.mixology.services.ProfitPlanner;
import org.gusta.mixology.services.RecoveryService;
import org.gusta.mixology.services.RefinerService;
import org.gusta.mixology.services.SupplyPurchaseService;
import org.gusta.mixology.services.SupervisorLaloService;
import org.gusta.mixology.services.TravelLoadoutService;
import org.gusta.mixology.services.TravelService;
import org.gusta.mixology.stats.MixologyPaint;
import org.gusta.mixology.stats.MixologyStats;

import java.util.List;

@ScriptManifest(name = "Mixology Profit", gameType = GameType.OS)
public class MixologyProfitScript extends Script {
    private static final String SCRIPT_VERSION = "v0.2.16-stamina-optional";

    private MixologyStats stats;
    private MixologyPaint paint;

    @Override
    public boolean onStart(String... args) {
        stats = new MixologyStats(message -> getLogger().info(message));
        paint = new MixologyPaint(SCRIPT_VERSION);

        MixologySettings settings = new MixologySettings();
        ObjectService objects = new ObjectService(stats);
        GePricingService pricing = new GePricingService();
        ProfitPlanner profitPlanner = new ProfitPlanner(pricing);
        SupplyPurchaseService supplyPurchase = new SupplyPurchaseService(settings, stats, profitPlanner);
        TravelLoadoutService travelLoadout = new TravelLoadoutService(stats, pricing);
        TravelService travel = new TravelService(settings, stats);
        BankService bank = new BankService(settings, objects, stats);
        RefinerService refiner = new RefinerService(settings, objects, bank, stats);
        HopperService hopper = new HopperService(settings, objects, bank, stats);
        HopperStockReader hopperStockReader = new HopperStockReader(stats);
        OrderReader orderReader = new OrderReader(stats);
        PotionInventoryService potionInventory = new PotionInventoryService();
        MixerService mixer = new MixerService(settings, objects, stats, potionInventory);
        ProcessingService processing = new ProcessingService(settings, objects, stats, potionInventory);
        ConveyorService conveyor = new ConveyorService(settings, objects, stats, potionInventory);
        OrderCycleService orderCycle = new OrderCycleService(mixer, processing, conveyor, bank, stats, potionInventory);
        RecoveryService recovery = new RecoveryService(stats);
        SupervisorLaloService supervisorLalo = new SupervisorLaloService(stats);

        MixologyRunner runner = new MixologyRunner(
                settings,
                stats,
                profitPlanner,
                supplyPurchase,
                travelLoadout,
                travel,
                bank,
                refiner,
                hopper,
                hopperStockReader,
                potionInventory,
                orderReader,
                orderCycle,
                recovery,
                supervisorLalo
        );

        List<RuntimeController> runtime = List.of(
                new MembersWorldController(stats),
                new LoopWatchdogController(message -> getLogger().info(message), stats, SCRIPT_VERSION),
                new StaminaController(stats),
                new CameraController(this::logInfo),
                new SimpleAntibanController(this::logInfo, true)
        );

        addTask(new ModuleTask(
                this::getAPIContext,
                this::logInfo,
                stats,
                runtime,
                List.of(runner)
        ));

        logInfo("Mixology Profit " + SCRIPT_VERSION + " started");
        return true;
    }

    @Override
    protected void onChatMessage(ChatMessageEvent event) {
        if (stats == null || event == null || event.getMessage() == null) {
            return;
        }

        String message = event.getMessage();
        stats.recordChatMessage(message);
        if (message.toLowerCase().contains("digweed")) {
            logInfo("Digweed message: " + message);
        }
    }

    @Override
    protected void onPaint(PaintContext paintContext, APIContext ctx) {
        if (paint != null && stats != null) {
            paint.paint(paintContext, ctx, stats);
        }
    }

    @Override
    protected void onStop() {
        clearClientInteractionState();
        getLogger().info("Mixology Profit " + SCRIPT_VERSION + " stopped");
    }

    @Override
    protected void onPause() {
        clearClientInteractionState();
    }

    private void clearClientInteractionState() {
        APIContext ctx = getAPIContext();
        if (ctx == null) {
            return;
        }

        try {
            if (ctx.menu().isOpen()) {
                ctx.menu().closeMenu();
            }
            if (ctx.inventory().isItemSelected()) {
                ctx.inventory().deselectItem();
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup only; stopping must not throw.
        }
    }

    private void logInfo(String message) {
        if (stats != null) {
            stats.setStatus(message);
            return;
        }
        getLogger().info(message);
    }
}
