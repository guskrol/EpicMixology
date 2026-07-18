package org.gusta.mixology.core;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.model.World;
import com.epicbot.api.shared.model.WorldType;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.stats.MixologyStats;

import java.util.Set;

/** Blocks the script on F2P worlds, where Mastering Mixology and Aldarin are inaccessible. */
public class MembersWorldController implements RuntimeController {
    private static final int MAX_SAFE_POPULATION = 1_600;
    private static final long RETRY_MILLIS = 12_000L;
    private static final Set<WorldType> UNSAFE_WORLD_TYPES = Set.of(
            WorldType.PVP,
            WorldType.BOUNTY,
            WorldType.PVP_ARENA,
            WorldType.SKILL_TOTAL,
            WorldType.HIGH_RISK,
            WorldType.LAST_MAN_STANDING,
            WorldType.BETA_WORLD,
            WorldType.NOSAVE_MODE,
            WorldType.TOURNAMENT_WORLD,
            WorldType.FRESH_START_WORLD,
            WorldType.DEADMAN,
            WorldType.SEASONAL
    );

    private final MixologyStats stats;
    private long nextAttemptAt;

    public MembersWorldController(MixologyStats stats) {
        this.stats = stats;
    }

    @Override
    public String name() {
        return "runtime.members_world";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return ctx != null
                && !ctx.script().isStopping()
                && !ctx.world().isCurrentWorldMembers();
    }

    @Override
    public void execute(APIContext ctx) {
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus("F2P world detected; waiting to switch to a members world");
            Time.sleep(600, 950);
            return;
        }

        if (clearBlockingInterface(ctx)) {
            return;
        }

        long now = System.currentTimeMillis();
        int currentWorld = ctx.world().getCurrent();
        if (now < nextAttemptAt) {
            stats.setStatus("Waiting to retry members world switch from world " + currentWorld);
            Time.sleep(650, 1000);
            return;
        }

        stats.setStatus("F2P world " + currentWorld + " detected; switching to a safe members world");
        boolean requested = ctx.world().hop(world -> isSafeMembersWorld(world, currentWorld));
        nextAttemptAt = now + RETRY_MILLIS;

        if (!requested) {
            stats.setStatus("No safe members world was available; retrying shortly");
            Time.sleep(900, 1400);
            return;
        }

        Time.sleep(2500, 4500, ctx.world()::isCurrentWorldMembers, 100);
        if (ctx.world().isCurrentWorldMembers()) {
            stats.setStatus("Members world confirmed: " + ctx.world().getCurrent());
        } else {
            stats.setStatus("Members world hop requested; waiting for confirmation");
        }

        if (ctx.world().isWorldMenuOpen()) {
            ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
            Time.sleep(450, 750, () -> !ctx.world().isWorldMenuOpen(), 100);
        }
    }

    private boolean clearBlockingInterface(APIContext ctx) {
        if (ctx.menu().isOpen()) {
            stats.setStatus("Closing menu before members world switch");
            ctx.menu().closeMenu();
            Time.sleep(200, 400);
            return true;
        }
        if (ctx.inventory().isItemSelected()) {
            stats.setStatus("Clearing selected item before members world switch");
            ctx.inventory().deselectItem();
            Time.sleep(200, 400);
            return true;
        }
        if (ctx.bank().isOpen()) {
            stats.setStatus("Closing bank before members world switch");
            ctx.bank().close();
            Time.sleep(450, 750, () -> !ctx.bank().isOpen(), 100);
            return true;
        }
        if (ctx.grandExchange().isOpen()) {
            stats.setStatus("Closing Grand Exchange before members world switch");
            ctx.grandExchange().close();
            Time.sleep(450, 750, () -> !ctx.grandExchange().isOpen(), 100);
            return true;
        }
        if (ctx.dialogues().isDialogueOpen()) {
            stats.setStatus("Waiting for dialogue to close before members world switch");
            Time.sleep(600, 900);
            return true;
        }
        return false;
    }

    private boolean isSafeMembersWorld(World world, int currentWorld) {
        if (world == null
                || world.getId() == currentWorld
                || !world.isMembers()
                || world.getPopulation() <= 0
                || world.getPopulation() >= MAX_SAFE_POPULATION
                || WorldType.isPvpWorld(world.getTypes())) {
            return false;
        }

        for (WorldType type : UNSAFE_WORLD_TYPES) {
            if (world.getTypes().contains(type)) {
                return false;
            }
        }
        return true;
    }
}
