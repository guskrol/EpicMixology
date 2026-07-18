package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.domain.PasteType;
import org.gusta.mixology.domain.PotionOrder;
import org.gusta.mixology.domain.PotionRecipe;
import org.gusta.mixology.stats.MixologyStats;

public class MixerService {
    private static final long ACTION_RETRY_TIMEOUT_MS = 10_000L;
    private static final int AGA_LEVER_ID = 54867;
    private static final int MOX_LEVER_ID = 54868;
    private static final int LYE_LEVER_ID = 54869;
    private static final int MIXING_VESSEL_ID = 55395;
    private static final Tile AGA_LEVER_TILE = new Tile(1394, 9324, 0);
    private static final Tile MOX_LEVER_TILE = new Tile(1395, 9324, 0);
    private static final Tile LYE_LEVER_TILE = new Tile(1393, 9324, 0);
    private static final Tile MIXING_VESSEL_TILE = new Tile(1394, 9326, 0);
    private static final Tile AGA_LEVER_APPROACH_TILE = new Tile(1394, 9323, 0);
    private static final Tile MOX_LEVER_APPROACH_TILE = new Tile(1395, 9323, 0);
    private static final Tile LYE_LEVER_APPROACH_TILE = new Tile(1393, 9323, 0);
    private static final Tile MIXING_VESSEL_APPROACH_TILE = new Tile(1394, 9325, 0);

    private final MixologySettings settings;
    private final ObjectService objects;
    private final MixologyStats stats;
    private final PotionInventoryService potionInventory;

    public MixerService(
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

    public boolean mixBase(APIContext ctx, PotionOrder order) {
        if (order == null || order.recipe() == null) {
            stats.setStatus("Cannot mix unknown order");
            return false;
        }

        stats.setStatus("Mixing base: " + order.recipe().displayName() + " (" + order.recipe().code() + ")");
        for (PasteType paste : order.recipe().sequence()) {
            if (!pullLever(ctx, paste)) {
                return false;
            }
        }
        return takeFromVessel(ctx, order);
    }

    private boolean pullLever(APIContext ctx, PasteType paste) {
        int leverId = leverId(paste);
        long deadline = System.currentTimeMillis() + ACTION_RETRY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            waitUntilIdle(ctx);
            boolean interacted = leverId > 0
                    && objects.interactByIdAtTile(ctx, settings.mixingRoomArea(),
                    leverId, paste.label() + " lever", leverTile(paste), leverApproachTile(paste), "Operate");
            if (interacted) {
                Time.sleep(450, 750);
                waitUntilIdle(ctx);
                return true;
            }
            Time.sleep(250, 450);
        }
        stats.setStatus("Failed to operate " + paste.label() + " lever after retry window");
        return false;
    }

    private boolean takeFromVessel(APIContext ctx, PotionOrder order) {
        PotionRecipe recipe = order == null ? null : order.recipe();
        int beforeRecipe = potionInventory.unfinishedPotionCount(ctx, recipe);
        long deadline = System.currentTimeMillis() + ACTION_RETRY_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            waitUntilIdle(ctx);
            long actionStartedAt = System.currentTimeMillis();
            boolean interacted = objects.interactByIdAtTile(ctx, settings.mixingRoomArea(),
                    MIXING_VESSEL_ID, "Mixing vessel", MIXING_VESSEL_TILE,
                    MIXING_VESSEL_APPROACH_TILE, "Take-from");
            if (interacted) {
                Time.sleep(900, 2200,
                        () -> potionInventory.unfinishedPotionCount(ctx, recipe) > beforeRecipe
                                || stats.hasPotionCollectedSince(actionStartedAt, recipe),
                        100);
                int afterRecipe = potionInventory.unfinishedPotionCount(ctx, recipe);
                if (afterRecipe <= beforeRecipe) {
                    stats.setStatus("Mixing vessel unfinished item id was not confirmed for "
                            + recipeName(recipe));
                    stats.debug("Last vessel collect chat='"
                            + stats.lastPotionCollectedMessage()
                            + "' inventory="
                            + potionInventory.allPotionDetails(ctx));
                    return false;
                }
                stats.debug("Mixing vessel unfinished item confirmed: "
                        + recipeName(recipe)
                        + " count " + beforeRecipe + " -> " + afterRecipe
                        + " chat='" + stats.lastPotionCollectedMessage() + "'");
                return true;
            }
            Time.sleep(250, 450);
        }
        stats.setStatus("Failed to take mixed potion from vessel after retry window");
        return false;
    }

    private String recipeName(PotionRecipe recipe) {
        return recipe == null ? "potion" : recipe.displayName();
    }

    private int leverId(PasteType paste) {
        if (paste == PasteType.AGA) {
            return AGA_LEVER_ID;
        }
        if (paste == PasteType.MOX) {
            return MOX_LEVER_ID;
        }
        if (paste == PasteType.LYE) {
            return LYE_LEVER_ID;
        }
        return -1;
    }

    private Tile leverTile(PasteType paste) {
        if (paste == PasteType.AGA) {
            return AGA_LEVER_TILE;
        }
        if (paste == PasteType.MOX) {
            return MOX_LEVER_TILE;
        }
        if (paste == PasteType.LYE) {
            return LYE_LEVER_TILE;
        }
        return null;
    }

    private Tile leverApproachTile(PasteType paste) {
        if (paste == PasteType.AGA) {
            return AGA_LEVER_APPROACH_TILE;
        }
        if (paste == PasteType.MOX) {
            return MOX_LEVER_APPROACH_TILE;
        }
        if (paste == PasteType.LYE) {
            return LYE_LEVER_APPROACH_TILE;
        }
        return null;
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
