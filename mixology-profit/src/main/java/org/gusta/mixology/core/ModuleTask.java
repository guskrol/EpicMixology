package org.gusta.mixology.core;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.script.task.ScriptTask;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.stats.MixologyStats;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ModuleTask implements ScriptTask {
    private static final Set<String> TIME_CRITICAL_STATES = Set.of(
            "READ_ORDERS",
            "MIX_BASES",
            "RETURN_TO_LEVERS"
    );
    private static final Set<String> COSMETIC_RUNTIME_CONTROLLERS = Set.of(
            "runtime.camera",
            "runtime.antiban"
    );
    private static final long DEFERRED_RUNTIME_LOG_INTERVAL_MILLIS = 10_000L;

    private final Supplier<APIContext> contextSupplier;
    private final Consumer<String> logger;
    private final MixologyStats stats;
    private final List<RuntimeController> runtimeControllers;
    private final List<ScriptModule> modules;
    private long nextDeferredRuntimeLogAt;

    public ModuleTask(
            Supplier<APIContext> contextSupplier,
            Consumer<String> logger,
            MixologyStats stats,
            List<RuntimeController> runtimeControllers,
            List<ScriptModule> modules
    ) {
        this.contextSupplier = contextSupplier;
        this.logger = logger;
        this.stats = stats;
        this.runtimeControllers = runtimeControllers;
        this.modules = modules;
    }

    @Override
    public boolean shouldExecute() {
        return true;
    }

    @Override
    public void run() {
        APIContext ctx = contextSupplier.get();
        for (RuntimeController controller : runtimeControllers) {
            if (!controller.shouldExecute(ctx)) {
                continue;
            }
            if (shouldDeferCosmeticController(controller)) {
                logDeferredRuntime(controller);
                continue;
            }
            runController(controller, ctx);
            return;
        }

        for (ScriptModule module : modules) {
            if (module.shouldExecute(ctx)) {
                runModule(module, ctx);
                return;
            }
        }

        logger.accept("No Mixology module was ready");
        Time.sleep(600, 900);
    }

    private boolean shouldDeferCosmeticController(RuntimeController controller) {
        return TIME_CRITICAL_STATES.contains(stats.state())
                && COSMETIC_RUNTIME_CONTROLLERS.contains(controller.name());
    }

    private void logDeferredRuntime(RuntimeController controller) {
        long now = System.currentTimeMillis();
        if (now < nextDeferredRuntimeLogAt) {
            return;
        }
        logger.accept("[Scheduler] Deferred " + controller.name()
                + " while state=" + stats.state()
                + " keeps the order cycle responsive");
        nextDeferredRuntimeLogAt = now + DEFERRED_RUNTIME_LOG_INTERVAL_MILLIS;
    }

    private void runController(RuntimeController controller, APIContext ctx) {
        try {
            controller.execute(ctx);
        } catch (Throwable e) {
            logger.accept("Runtime failed: " + controller.name()
                    + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            Time.sleep(1200, 1800);
        }
    }

    private void runModule(ScriptModule module, APIContext ctx) {
        try {
            module.execute(ctx);
        } catch (Throwable e) {
            logger.accept("Module failed: " + module.name()
                    + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());
            Time.sleep(1200, 1800);
        }
    }
}
