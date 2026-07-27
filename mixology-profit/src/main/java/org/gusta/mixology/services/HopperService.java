package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.domain.PasteType;
import org.gusta.mixology.stats.MixologyStats;

public class HopperService {
    private static final int HOPPER_ID = 54903;
    private static final Tile HOPPER_TILE = new Tile(1394, 9322, 0);
    private static final Tile HOPPER_APPROACH_TILE = new Tile(1394, 9321, 0);

    private final MixologySettings settings;
    private final ObjectService objects;
    private final BankService bank;
    private final MixologyStats stats;

    public HopperService(
            MixologySettings settings,
            ObjectService objects,
            BankService bank,
            MixologyStats stats
    ) {
        this.settings = settings;
        this.objects = objects;
        this.bank = bank;
        this.stats = stats;
    }

    public boolean loadAvailablePaste(APIContext ctx) {
        if (!bank.hasAnyPaste(ctx)) {
            return false;
        }

        if (!moveToHopperLoadTile(ctx)) {
            return false;
        }

        int beforePaste = totalPaste(ctx);
        stats.setStatus("Loading paste into hopper");
        boolean interacted = objects.interactByIdAtTileWithMinimap(ctx, settings.alchemicalSocietyArea(),
                HOPPER_ID, "Hopper", HOPPER_TILE, HOPPER_APPROACH_TILE, "Deposit");
        if (!interacted) {
            return false;
        }

        Time.sleep(1200, 2000, () -> totalPaste(ctx) < beforePaste, 100);
        return totalPaste(ctx) < beforePaste;
    }

    private boolean moveToHopperLoadTile(APIContext ctx) {
        if (HOPPER_APPROACH_TILE.tileDistanceTo(ctx) <= 1) {
            return true;
        }

        if (ctx.bank().isOpen()) {
            stats.setStatus("Closing bank before Hopper load tile");
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return false;
        }
        if (ctx.grandExchange().isOpen()) {
            stats.setStatus("Closing GE before Hopper load tile");
            ctx.grandExchange().close();
            Time.sleep(500, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus("Walking to Hopper load tile "
                    + HOPPER_APPROACH_TILE.getX() + ","
                    + HOPPER_APPROACH_TILE.getY() + ","
                    + HOPPER_APPROACH_TILE.getPlane()
                    + " dist=" + HOPPER_APPROACH_TILE.tileDistanceTo(ctx));
            Time.sleep(650, 1000);
            return false;
        }

        stats.setStatus("Walking to Hopper load tile "
                + HOPPER_APPROACH_TILE.getX() + ","
                + HOPPER_APPROACH_TILE.getY() + ","
                + HOPPER_APPROACH_TILE.getPlane()
                + " before deposit");
        boolean walking = ctx.walking().walkTo(HOPPER_APPROACH_TILE);
        if (!walking) {
            ctx.webWalking().setUseTeleports(false);
            ctx.webWalking().walkTo(HOPPER_APPROACH_TILE);
        }
        Time.sleep(900, 1500,
                () -> ctx.localPlayer().isMoving() || HOPPER_APPROACH_TILE.tileDistanceTo(ctx) <= 1,
                100);
        return HOPPER_APPROACH_TILE.tileDistanceTo(ctx) <= 1;
    }

    private int totalPaste(APIContext ctx) {
        int total = 0;
        for (PasteType type : PasteType.values()) {
            total += ctx.inventory().getCount(type.pasteName());
        }
        return total;
    }
}
