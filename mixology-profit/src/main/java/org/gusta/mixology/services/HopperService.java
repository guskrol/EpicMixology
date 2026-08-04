package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.domain.HopperStock;
import org.gusta.mixology.domain.PasteType;
import org.gusta.mixology.stats.MixologyStats;

public class HopperService {
    private static final int HOPPER_ID = 54903;
    private static final Tile HOPPER_TILE = new Tile(1394, 9322, 0);
    private static final Tile HOPPER_APPROACH_TILE = new Tile(1394, 9321, 0);

    private final MixologySettings settings;
    private final ObjectService objects;
    private final BankService bank;
    private final HopperStockReader hopperStockReader;
    private final MixologyStats stats;

    public HopperService(
            MixologySettings settings,
            ObjectService objects,
            BankService bank,
            HopperStockReader hopperStockReader,
            MixologyStats stats
    ) {
        this.settings = settings;
        this.objects = objects;
        this.bank = bank;
        this.hopperStockReader = hopperStockReader;
        this.stats = stats;
    }

    public boolean loadAvailablePaste(APIContext ctx, HopperStock beforeStock) {
        if (!bank.hasAnyPaste(ctx)) {
            return false;
        }

        int beforePaste = totalPaste(ctx);
        stats.setStatus("Loading paste into hopper");
        boolean interacted = objects.interactByIdAtTileSingleClick(ctx, settings.alchemicalSocietyArea(),
                HOPPER_ID, "Hopper", HOPPER_TILE, HOPPER_APPROACH_TILE, 3, "Deposit");
        if (!interacted) {
            return false;
        }

        Time.sleep(3000, 5000,
                () -> totalPaste(ctx) < beforePaste || hopperStockIncreased(ctx, beforeStock),
                150);
        boolean confirmed = totalPaste(ctx) < beforePaste || hopperStockIncreased(ctx, beforeStock);
        if (!confirmed) {
            stats.setStatus("Hopper deposit not confirmed; retrying safely");
        }
        return confirmed;
    }

    private boolean hopperStockIncreased(APIContext ctx, HopperStock beforeStock) {
        if (beforeStock == null || !beforeStock.isComplete()) {
            return false;
        }
        HopperStock currentStock = hopperStockReader.readStock(ctx).orElse(null);
        if (currentStock == null || !currentStock.isComplete()) {
            return false;
        }
        for (PasteType type : PasteType.values()) {
            if (currentStock.amount(type) > beforeStock.amount(type)) {
                return true;
            }
        }
        return false;
    }

    private int totalPaste(APIContext ctx) {
        int total = 0;
        for (PasteType type : PasteType.values()) {
            total += ctx.inventory().getCount(type.pasteName());
        }
        return total;
    }
}
