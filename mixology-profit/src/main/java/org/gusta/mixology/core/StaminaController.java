package org.gusta.mixology.core;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.data.TravelItems;
import org.gusta.mixology.stats.MixologyStats;

public class StaminaController implements RuntimeController {
    private static final int DRINK_RUN_ENERGY_MIN = 30;
    private static final int DRINK_RUN_ENERGY_MAX = 50;
    private static final int ENABLE_RUN_ENERGY = 45;
    private static final long SIP_COOLDOWN_MILLIS = 110_000L;

    private final MixologyStats stats;
    private long nextSipAt;

    public StaminaController(MixologyStats stats) {
        this.stats = stats;
    }

    @Override
    public String name() {
        return "mixology.stamina";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        if (ctx == null || ctx.bank().isOpen() || ctx.grandExchange().isOpen()
                || ctx.menu().isOpen() || ctx.dialogues().isDialogueOpen()) {
            return false;
        }
        int energy = ctx.walking().getRunEnergy();
        if (!ctx.walking().isRunEnabled() && energy >= ENABLE_RUN_ENERGY) {
            return true;
        }
        return energy >= DRINK_RUN_ENERGY_MIN
                && energy <= DRINK_RUN_ENERGY_MAX
                && System.currentTimeMillis() >= nextSipAt
                && staminaPotion(ctx) != null;
    }

    @Override
    public void execute(APIContext ctx) {
        int energy = ctx.walking().getRunEnergy();
        if (!ctx.walking().isRunEnabled() && energy >= ENABLE_RUN_ENERGY) {
            stats.setStatus("Enabling run energy=" + energy);
            ctx.walking().setRun(true);
            Time.sleep(400, 700);
            return;
        }

        if (energy < DRINK_RUN_ENERGY_MIN || energy > DRINK_RUN_ENERGY_MAX) {
            return;
        }

        ItemWidget potion = staminaPotion(ctx);
        if (potion == null) {
            return;
        }

        stats.setStatus("Drinking stamina potion energy=" + energy);
        boolean drank = potion.interact("Drink", potion.getName())
                || potion.interact("Drink")
                || ctx.inventory().interactItem("Drink", item ->
                item != null && TravelItems.isStaminaPotion(item.getName()));
        nextSipAt = System.currentTimeMillis() + SIP_COOLDOWN_MILLIS;
        if (drank) {
            Time.sleep(900, 1500);
        } else {
            Time.sleep(500, 800);
        }
    }

    private ItemWidget staminaPotion(APIContext ctx) {
        return ctx.inventory().getItem(item ->
                item != null && TravelItems.isStaminaPotion(item.getName()));
    }
}
