package org.gusta.mixology.services;

import com.epicbot.api.gameval.VarbitID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.domain.PotionOrder;
import org.gusta.mixology.domain.PotionProcess;
import org.gusta.mixology.domain.PotionRecipe;
import org.gusta.mixology.stats.MixologyStats;

public class ProcessingService {
    private static final long ACTION_RETRY_TIMEOUT_MS = 10_000L;
    private static final long PROCESS_FINISH_FAST_TIMEOUT_MS = 14_000L;
    private static final long PROCESS_FINISH_EXTENDED_TIMEOUT_MS = 25_000L;
    private static final long PROCESS_FINISH_VARBIT_TIMEOUT_MS = 40_000L;
    private static final long INVENTORY_ONLY_CONFIRM_STABILITY_MS = 2_000L;
    private static final int RETORT_ID = 55389;
    private static final int ALEMBIC_ID = 55391;
    private static final int AGITATOR_ID = 55390;
    private static final Tile RETORT_TILE = new Tile(1397, 9326, 0);
    private static final Tile ALEMBIC_TILE = new Tile(1391, 9326, 0);
    private static final Tile AGITATOR_TILE = new Tile(1394, 9329, 0);
    private static final Tile RETORT_APPROACH_TILE = new Tile(1397, 9325, 0);
    private static final Tile ALEMBIC_APPROACH_TILE = new Tile(1392, 9326, 0);
    private static final Tile AGITATOR_APPROACH_TILE = new Tile(1394, 9328, 0);

    private final MixologySettings settings;
    private final ObjectService objects;
    private final MixologyStats stats;
    private final PotionInventoryService potionInventory;

    public ProcessingService(
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

    public boolean processPotion(APIContext ctx, PotionOrder order) {
        PotionProcess process = order == null ? null : order.process();
        if (process == null) {
            stats.setStatus("Order has no readable workstation; waiting before processing");
            Time.sleep(1000, 1600);
            return false;
        }

        stats.setStatus(process.statusName() + ": " + order.recipe().displayName());
        int beforeReady = potionInventory.potionCount(ctx, order.recipe());
        int beforeUnfinished = potionInventory.unfinishedPotionCount(ctx, order.recipe());
        FinalizerVarbitSnapshot beforeVarbits = readFinalizerVarbits(ctx, process);
        long clickRequestedAt = System.currentTimeMillis();
        boolean interacted = retryWorkstation(ctx, process);
        if (!interacted) {
            return false;
        }

        long actionStartedAt = System.currentTimeMillis();
        stats.debug("Timing finalizer click accepted: recipe=" + order.recipe().displayName()
                + " process=" + process.name()
                + " clickRetry=" + (actionStartedAt - clickRequestedAt) + "ms"
                + " beforeReady=" + beforeReady
                + " beforeUnfinished=" + beforeUnfinished
                + " varbits=" + beforeVarbits.text());

        return waitForFinalizerCompletion(
                ctx, order, actionStartedAt, beforeReady, beforeUnfinished, beforeVarbits);
    }

    private boolean waitForFinalizerCompletion(
            APIContext ctx,
            PotionOrder order,
            long actionStartedAt,
            int beforeReady,
            int beforeUnfinished,
            FinalizerVarbitSnapshot beforeVarbits
    ) {
        PotionRecipe recipe = order.recipe();
        String recipeName = recipe == null ? "potion" : recipe.displayName();
        stats.setStatus("Waiting finalizer completion: " + recipeName);

        Time.sleep(900, 1600,
                () -> ctx.localPlayer().isAnimating()
                        || stats.hasPotionFinalizerFinishedSince(actionStartedAt, recipe, order.process())
                        || potionInventory.potionCount(ctx, recipe) > beforeReady,
                100);

        long fastDeadline = actionStartedAt + PROCESS_FINISH_FAST_TIMEOUT_MS;
        long deadline = fastDeadline;
        boolean observedFinalizerActivity = ctx.localPlayer().isAnimating();
        boolean extendedWindow = false;
        boolean chatLatencyLogged = false;
        boolean inventoryLatencyLogged = false;
        boolean varbitActivityObserved = false;
        boolean varbitCompletionObserved = false;
        FinalizerVarbitSnapshot lastVarbits = beforeVarbits;
        long inventoryOnlyStableSince = -1L;
        int stableReadyCount = -1;
        int stableUnfinishedCount = -1;
        while (true) {
            int readyNow = potionInventory.potionCount(ctx, recipe);
            int unfinishedNow = potionInventory.unfinishedPotionCount(ctx, recipe);
            boolean matchingChat = stats.hasPotionFinalizerFinishedSince(actionStartedAt, recipe, order.process());
            long elapsed = System.currentTimeMillis() - actionStartedAt;
            FinalizerVarbitSnapshot currentVarbits = readFinalizerVarbits(ctx, order.process());
            observedFinalizerActivity |= ctx.localPlayer().isAnimating();

            if (!currentVarbits.equals(lastVarbits)) {
                if (!varbitActivityObserved && !currentVarbits.equals(beforeVarbits)) {
                    stats.debug("Finalizer station varbits started: recipe=" + recipeName
                            + " process=" + order.process().name()
                            + " elapsed=" + elapsed + "ms"
                            + " baseline=" + beforeVarbits.text()
                            + " current=" + currentVarbits.text());
                }
                lastVarbits = currentVarbits;
            }
            if (!currentVarbits.equals(beforeVarbits)) {
                varbitActivityObserved = true;
                observedFinalizerActivity = true;
            } else if (varbitActivityObserved && !varbitCompletionObserved) {
                varbitCompletionObserved = true;
                stats.debug("Finalizer station varbits returned to baseline: recipe=" + recipeName
                        + " process=" + order.process().name()
                        + " elapsed=" + elapsed + "ms"
                        + " baseline=" + beforeVarbits.text());
            }

            if (matchingChat && !chatLatencyLogged) {
                long chatElapsed = Math.max(0L, stats.lastPotionFinalizerFinishedAt() - actionStartedAt);
                stats.debug("Timing finalizer chat confirmed: recipe=" + recipeName
                        + " elapsed=" + chatElapsed + "ms");
                chatLatencyLogged = true;
            }
            if (readyNow > beforeReady && !inventoryLatencyLogged) {
                stats.debug("Timing finalizer inventory confirmed: recipe=" + recipeName
                        + " count=" + beforeReady + "->" + readyNow
                        + " elapsed=" + elapsed + "ms");
                inventoryLatencyLogged = true;
            }
            if (readyNow > beforeReady && matchingChat) {
                stats.debug("Finalizer processed item confirmed: "
                        + recipeName
                        + " count " + beforeReady + " -> " + readyNow
                        + " elapsed=" + elapsed + "ms"
                        + " chat='" + stats.lastPotionFinalizerFinishedMessage() + "'");
                stats.recordPotionMixed();
                Time.sleep(350, 650);
                return true;
            }

            boolean inventoryConversionConfirmed = readyNow > beforeReady
                    && beforeUnfinished > 0
                    && unfinishedNow < beforeUnfinished;
            if (inventoryConversionConfirmed) {
                if (readyNow != stableReadyCount || unfinishedNow != stableUnfinishedCount) {
                    stableReadyCount = readyNow;
                    stableUnfinishedCount = unfinishedNow;
                    inventoryOnlyStableSince = System.currentTimeMillis();
                } else if (inventoryOnlyStableSince > 0L
                        && System.currentTimeMillis() - inventoryOnlyStableSince
                        >= INVENTORY_ONLY_CONFIRM_STABILITY_MS) {
                    stats.debug("Finalizer associated from stable inventory conversion: "
                            + recipeName
                            + " process=" + order.process().name()
                            + " ready=" + beforeReady + "->" + readyNow
                            + " unfinished=" + beforeUnfinished + "->" + unfinishedNow
                            + " stableFor=" + (System.currentTimeMillis() - inventoryOnlyStableSince) + "ms"
                            + " varbitActivity=" + varbitActivityObserved
                            + " varbitCompleted=" + varbitCompletionObserved
                            + " varbits=" + currentVarbits.text()
                            + " chat='" + stats.lastPotionFinalizerFinishedMessage() + "'");
                    stats.recordPotionMixed();
                    Time.sleep(350, 650);
                    return true;
                }
            } else {
                inventoryOnlyStableSince = -1L;
                stableReadyCount = -1;
                stableUnfinishedCount = -1;
            }

            if (System.currentTimeMillis() >= deadline) {
                if (!extendedWindow && observedFinalizerActivity) {
                    extendedWindow = true;
                    deadline = actionStartedAt + (varbitActivityObserved
                            ? PROCESS_FINISH_VARBIT_TIMEOUT_MS
                            : PROCESS_FINISH_EXTENDED_TIMEOUT_MS);
                    stats.debug("Timing finalizer extension: recipe=" + recipeName
                            + " waited=" + elapsed + "ms"
                            + " reason=" + (varbitActivityObserved
                            ? "station-varbit-activity"
                            : "animation-observed")
                            + " max=" + (deadline - actionStartedAt) + "ms"
                            + " varbits=" + currentVarbits.text());
                    continue;
                }
                break;
            }

            if (matchingChat) {
                stats.debug("Finalizer matching recipe/process chat seen, waiting for processed item id: "
                        + stats.lastPotionFinalizerFinishedMessage());
            } else if (readyNow > beforeReady) {
                stats.debug("Processed item id appeared but matching finalizer chat is missing; waiting before tracking: "
                        + recipeName
                        + " count " + beforeReady + " -> " + readyNow
                        + " lastChat='" + stats.lastPotionFinalizerFinishedMessage() + "'");
            }

            if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
                Time.sleep(300, 550);
                continue;
            }

            Time.sleep(450, 750);
        }

        stats.setStatus((varbitActivityObserved
                ? "Finalizer station changed, but potion conversion was not confirmed for "
                : "Finalizer click produced no station varbit activity for ")
                + recipeName + "; retrying safely");
        stats.debug("Last finalizer completion message='"
                + stats.lastPotionFinalizerFinishedMessage()
                + "' elapsed=" + (System.currentTimeMillis() - actionStartedAt) + "ms"
                + " extended=" + extendedWindow
                + " varbitActivity=" + varbitActivityObserved
                + " varbitCompleted=" + varbitCompletionObserved
                + " beforeVarbits=" + beforeVarbits.text()
                + " lastVarbits=" + lastVarbits.text()
                + " inventory=" + potionInventory.allPotionDetails(ctx));
        return false;
    }

    private FinalizerVarbitSnapshot readFinalizerVarbits(APIContext ctx, PotionProcess process) {
        return new FinalizerVarbitSnapshot(
                safeVarbit(ctx, progressVarbitId(process)),
                safeVarbit(ctx, potionVarbitId(process)));
    }

    private int progressVarbitId(PotionProcess process) {
        if (process == PotionProcess.CONCENTRATE) {
            return VarbitID.MM_RETORT_PROGRESS;
        }
        if (process == PotionProcess.CRYSTALISE) {
            return VarbitID.MM_ALEMBIC_PROGRESS;
        }
        if (process == PotionProcess.HOMOGENISE) {
            return VarbitID.MM_AGITATOR_PROGRESS;
        }
        return -1;
    }

    private int potionVarbitId(PotionProcess process) {
        if (process == PotionProcess.CONCENTRATE) {
            return VarbitID.MM_LAB_RETORT_POTION;
        }
        if (process == PotionProcess.CRYSTALISE) {
            return VarbitID.MM_LAB_ALEMBIC_POTION;
        }
        if (process == PotionProcess.HOMOGENISE) {
            return VarbitID.MM_LAB_AGITATOR_POTION;
        }
        return -1;
    }

    private int safeVarbit(APIContext ctx, int varbitId) {
        if (ctx == null || ctx.vars() == null || varbitId < 0) {
            return -1;
        }
        try {
            return ctx.vars().getVarbit(varbitId);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private boolean retryWorkstation(APIContext ctx, PotionProcess process) {
        long deadline = System.currentTimeMillis() + ACTION_RETRY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            waitUntilIdle(ctx);
            if (interactKnownWorkstation(ctx, process)) {
                return true;
            }
            Time.sleep(250, 450);
        }
        stats.setStatus("Failed to interact with " + process.workstationName() + " after retry window");
        return false;
    }

    private boolean interactKnownWorkstation(APIContext ctx, PotionProcess process) {
        if (process == PotionProcess.CRYSTALISE) {
            return objects.interactByIdAtTile(ctx, settings.mixingRoomArea(),
                    ALEMBIC_ID, "Alembic", ALEMBIC_TILE, ALEMBIC_APPROACH_TILE, process.actionName());
        }
        if (process == PotionProcess.HOMOGENISE) {
            return objects.interactByIdAtTile(ctx, settings.mixingRoomArea(),
                    AGITATOR_ID, "Agitator", AGITATOR_TILE, AGITATOR_APPROACH_TILE, process.actionName());
        }
        if (process == PotionProcess.CONCENTRATE) {
            return objects.interactByIdAtTile(ctx, settings.mixingRoomArea(),
                    RETORT_ID, "Retort", RETORT_TILE, RETORT_APPROACH_TILE, process.actionName());
        }
        return interactByName(ctx, process);
    }

    private boolean interactByName(APIContext ctx, PotionProcess process) {
        return objects.interact(ctx, settings.mixingRoomArea(),
                new String[]{process.workstationName()},
                process.actionName(), process.statusName(), "Use");
    }

    private void waitUntilIdle(APIContext ctx) {
        if (!ctx.localPlayer().isMoving() && !ctx.localPlayer().isAnimating()) {
            return;
        }
        Time.sleep(450, 900,
                () -> !ctx.localPlayer().isMoving() && !ctx.localPlayer().isAnimating(),
                100);
    }

    private record FinalizerVarbitSnapshot(int progress, int potion) {
        private String text() {
            return "progress=" + progress + ",potion=" + potion;
        }
    }
}
