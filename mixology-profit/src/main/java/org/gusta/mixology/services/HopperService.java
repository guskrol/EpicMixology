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
    private static final Tile HOPPER_APPROACH_TILE = new Tile(1394, 9320, 0);

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

        int beforePaste = totalPaste(ctx);
        stats.setStatus("Loading paste into hopper");
        boolean interacted = objects.interactByIdAtTile(ctx, settings.alchemicalSocietyArea(),
                HOPPER_ID, "Hopper", HOPPER_TILE, HOPPER_APPROACH_TILE, "Deposit");
        if (!interacted) {
            return false;
        }

        Time.sleep(1200, 2000, () -> totalPaste(ctx) < beforePaste, 100);
        return totalPaste(ctx) < beforePaste;
    }

    private int totalPaste(APIContext ctx) {
        int total = 0;
        for (PasteType type : PasteType.values()) {
            total += ctx.inventory().getCount(type.pasteName());
        }
        return total;
    }
}
