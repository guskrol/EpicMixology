package org.gusta.mixology.services;

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
    private static final long PROCESS_FINISH_TIMEOUT_MS = 14_000L;
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
        long actionStartedAt = System.currentTimeMillis();
        boolean interacted = retryWorkstation(ctx, process);
        if (!interacted) {
            return false;
        }

        return waitForFinalizerCompletion(ctx, order, actionStartedAt, beforeReady);
    }

    private boolean waitForFinalizerCompletion(
            APIContext ctx,
            PotionOrder order,
            long actionStartedAt,
            int beforeReady
    ) {
        PotionRecipe recipe = order.recipe();
        String recipeName = recipe == null ? "potion" : recipe.displayName();
        stats.setStatus("Waiting finalizer completion: " + recipeName);

        Time.sleep(900, 1600,
                () -> ctx.localPlayer().isAnimating()
                        || stats.hasPotionFinalizerFinishedSince(actionStartedAt, recipe, order.process())
                        || potionInventory.potionCount(ctx, recipe) > beforeReady,
                100);

        long deadline = System.currentTimeMillis() + PROCESS_FINISH_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            int readyNow = potionInventory.potionCount(ctx, recipe);
            boolean matchingChat = stats.hasPotionFinalizerFinishedSince(actionStartedAt, recipe, order.process());
            if (readyNow > beforeReady && matchingChat) {
                stats.debug("Finalizer processed item confirmed: "
                        + recipeName
                        + " count " + beforeReady + " -> " + readyNow
                        + " chat='" + stats.lastPotionFinalizerFinishedMessage() + "'");
                stats.recordPotionMixed();
                Time.sleep(350, 650);
                return true;
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

        stats.setStatus("Finalized potion was not confirmed by id+chat for " + recipeName + "; retrying safely");
        stats.debug("Last finalizer completion message='"
                + stats.lastPotionFinalizerFinishedMessage()
                + "' inventory=" + potionInventory.allPotionDetails(ctx));
        return false;
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
}
