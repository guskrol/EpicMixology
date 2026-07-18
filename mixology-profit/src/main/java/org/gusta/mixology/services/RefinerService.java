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
}
