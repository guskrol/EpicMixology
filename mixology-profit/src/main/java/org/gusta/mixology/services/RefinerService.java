package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.stats.MixologyStats;

public class RefinerService {
    private static final int REFINER_ID = 54904;
    private static final Tile REFINER_TILE = new Tile(1399, 9312, 0);
    private static final Tile REFINER_APPROACH_TILE = new Tile(1398, 9313, 0);

    private final MixologySettings settings;
    private final ObjectService objects;
    private final BankService bank;
    private final MixologyStats stats;

    public RefinerService(
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

    public boolean refineInventory(APIContext ctx) {
        if (!bank.hasAnyHerb(ctx)) {
            return false;
        }
        if (clearBlockingUi(ctx)) {
            return false;
        }

        int beforeSlots = ctx.inventory().getEmptySlotCount();
        stats.setStatus("Refining clean herbs into paste");

        boolean interacted = objects.interactByIdAtTile(ctx, settings.alchemicalSocietyArea(),
                REFINER_ID, "Refiner", REFINER_TILE, REFINER_APPROACH_TILE, "Operate");
        if (!interacted) {
            return false;
        }

        Time.sleep(1400, 2200, () -> ctx.localPlayer().isAnimating()
                || ctx.inventory().getEmptySlotCount() != beforeSlots
                || bank.hasAnyPaste(ctx), 100);
        Time.sleep(800, 1300);
        return true;
    }

    private boolean clearBlockingUi(APIContext ctx) {
        if (ctx.menu().isOpen()) {
            stats.setStatus("Refiner blocked by open menu; closing before retry");
            ctx.menu().closeMenu();
            Time.sleep(250, 450);
            return true;
        }
        if (ctx.inventory().isItemSelected()) {
            stats.setStatus("Refiner blocked by selected inventory item; deselecting before retry");
            ctx.inventory().deselectItem();
            Time.sleep(250, 450);
            return true;
        }
        if (ctx.bank().isOpen()) {
            stats.setStatus("Closing bank before refining clean herbs");
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return true;
        }
        if (ctx.grandExchange().isOpen()) {
            stats.setStatus("Closing GE before refining clean herbs");
            ctx.grandExchange().close();
            Time.sleep(500, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return true;
        }
        return false;
    }
}
