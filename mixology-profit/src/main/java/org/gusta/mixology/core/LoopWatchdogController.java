package org.gusta.mixology.core;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.model.Skill;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.stats.MixologyStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class LoopWatchdogController implements RuntimeController {
    private static final long WARMUP_MILLIS = 90_000L;
    private static final long MIN_IDLE_STUCK_MILLIS = 2 * 60_000L;
    private static final long MAX_IDLE_STUCK_MILLIS = 3 * 60_000L;
    private static final long MIN_HARD_STUCK_MILLIS = 6 * 60_000L;
    private static final long MAX_HARD_STUCK_MILLIS = 8 * 60_000L;
    private static final long INFO_LOG_INTERVAL_MILLIS = 75_000L;
    private static final int TILE_PROGRESS_DISTANCE = 4;

    private final Consumer<String> logger;
    private final MixologyStats stats;
    private final String scriptVersion;
    private final long startedAt = System.currentTimeMillis();
    private Snapshot lastProgressSnapshot;
    private String lastState = "";
    private String lastStatus = "";
    private Tile lastStableTile;
    private long lastProgressAt;
    private long sameStateSince;
    private long sameTileSince;
    private long nextInfoLogAt;
    private long idleStuckMillis;
    private long hardStuckMillis;
    private String pendingStopReason;

    public LoopWatchdogController(Consumer<String> logger, MixologyStats stats, String scriptVersion) {
        this.logger = logger;
        this.stats = stats;
        this.scriptVersion = scriptVersion == null || scriptVersion.isBlank()
                ? "unknown"
                : scriptVersion;
        resetThresholds();
    }

    @Override
    public String name() {
        return "runtime.loop_watchdog";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        if (ctx == null || ctx.script().isStopping()) {
            return false;
        }

        long now = System.currentTimeMillis();
        Snapshot current = Snapshot.capture(ctx, stats);
        if (lastProgressSnapshot == null) {
            initialize(now, current);
            return false;
        }

        if (current.hasMaterialProgressSince(lastProgressSnapshot)) {
            initialize(now, current);
            return false;
        }

        updateStableState(ctx, now, current);
        if (now - startedAt < WARMUP_MILLIS) {
            return false;
        }

        long noProgressFor = now - lastProgressAt;
        long sameTileFor = now - sameTileSince;
        long sameStateFor = now - sameStateSince;
        boolean active = ctx.localPlayer().isMoving()
                || ctx.localPlayer().isAnimating()
                || ctx.localPlayer().isInCombat()
                || ctx.localPlayer().isAttacking();
        boolean interfaceStuck = ctx.bank().isOpen()
                || ctx.grandExchange().isOpen()
                || ctx.store().isOpen()
                || ctx.menu().isOpen()
                || ctx.dialogues().isDialogueOpen()
                || ctx.inventory().isItemSelected();

        if (!active
                && noProgressFor >= idleStuckMillis
                && sameTileFor >= idleStuckMillis
                && sameStateFor >= idleStuckMillis / 2) {
            pendingStopReason = "Loop watchdog logout: idle without progress for "
                    + minutes(noProgressFor)
                    + " min; state=" + stats.state()
                    + " status='" + stats.status() + "'";
            return true;
        }

        if (noProgressFor >= hardStuckMillis
                && sameTileFor >= hardStuckMillis / 2
                && (!active || interfaceStuck || sameStateFor >= idleStuckMillis)) {
            pendingStopReason = "Loop watchdog logout: no material progress for "
                    + minutes(noProgressFor)
                    + " min; state=" + stats.state()
                    + " status='" + stats.status() + "'";
            return true;
        }

        if (noProgressFor >= 90_000L && now >= nextInfoLogAt) {
            logger.accept("[Watchdog] No material progress for " + minutes(noProgressFor)
                    + " min; state=" + stats.state()
                    + " status='" + stats.status() + "'");
            nextInfoLogAt = now + INFO_LOG_INTERVAL_MILLIS;
        }

        return false;
    }

    @Override
    public void execute(APIContext ctx) {
        String reason = pendingStopReason == null ? "Loop watchdog logout triggered" : pendingStopReason;
        stats.setStatus(reason);
        logger.accept("[Watchdog] " + reason);
        saveReport(ctx, reason);
        clearBlockingInterfaces(ctx);
        Time.sleep(600, 1000);
        try {
            boolean loggedOut = ctx.game().logout();
            logger.accept("[Watchdog] Logout requested result=" + loggedOut);
            Time.sleep(1200, 1800);
        } catch (RuntimeException e) {
            logger.accept("[Watchdog] Logout failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
        ctx.script().stop(reason);
    }

    private void initialize(long now, Snapshot snapshot) {
        lastProgressSnapshot = snapshot;
        lastState = snapshot.state;
        lastStatus = snapshot.status;
        lastStableTile = snapshot.tile;
        lastProgressAt = now;
        sameStateSince = now;
        sameTileSince = now;
        nextInfoLogAt = now + INFO_LOG_INTERVAL_MILLIS;
        pendingStopReason = null;
        resetThresholds();
    }

    private void updateStableState(APIContext ctx, long now, Snapshot snapshot) {
        if (!snapshot.state.equals(lastState)) {
            lastState = snapshot.state;
            sameStateSince = now;
        }
        lastStatus = snapshot.status;

        Tile currentTile = ctx.localPlayer().getLocation();
        if (lastStableTile == null
                || currentTile == null
                || tileDistance(lastStableTile, currentTile) >= TILE_PROGRESS_DISTANCE) {
            lastStableTile = currentTile;
            sameTileSince = now;
        }
    }

    private void clearBlockingInterfaces(APIContext ctx) {
        try {
            if (ctx.menu().isOpen()) {
                ctx.menu().closeMenu();
            }
            if (ctx.inventory().isItemSelected()) {
                ctx.inventory().deselectItem();
            }
            if (ctx.store().isOpen()) {
                ctx.store().close();
            }
            if (ctx.grandExchange().isOpen()) {
                ctx.grandExchange().close();
            }
            if (ctx.bank().isOpen()) {
                ctx.bank().close();
            }
            if (ctx.widgets().isInterfaceOpen()) {
                ctx.widgets().closeInterface();
            }
        } catch (RuntimeException e) {
            logger.accept("[Watchdog] Interface cleanup failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private void resetThresholds() {
        idleStuckMillis = randomLong(MIN_IDLE_STUCK_MILLIS, MAX_IDLE_STUCK_MILLIS);
        hardStuckMillis = randomLong(MIN_HARD_STUCK_MILLIS, MAX_HARD_STUCK_MILLIS);
    }

    private long randomLong(long minInclusive, long maxInclusive) {
        return ThreadLocalRandom.current().nextLong(minInclusive, maxInclusive + 1L);
    }

    private long minutes(long millis) {
        return Math.max(1L, Math.round(millis / 60_000.0D));
    }

    private int tileDistance(Tile left, Tile right) {
        if (left == null || right == null || left.getPlane() != right.getPlane()) {
            return Integer.MAX_VALUE;
        }
        return Math.max(Math.abs(left.getX() - right.getX()), Math.abs(left.getY() - right.getY()));
    }

    private void saveReport(APIContext ctx, String reason) {
        try {
            Path path = reportPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, buildReport(ctx, reason));
            logger.accept("[Watchdog] Report saved: " + path);
        } catch (RuntimeException | IOException firstFailure) {
            try {
                Path fallback = Path.of(
                        System.getProperty("user.home", "."),
                        "mixology-watchdog-reports",
                        reportFileName()
                );
                Files.createDirectories(fallback.getParent());
                Files.writeString(fallback, buildReport(ctx, reason));
                logger.accept("[Watchdog] Report saved: " + fallback);
            } catch (RuntimeException | IOException secondFailure) {
                logger.accept("[Watchdog] Could not save report: " + secondFailure.getMessage());
            }
        }
    }

    private Path reportPath() {
        return Path.of(System.getProperty("user.dir", "."), "watchdog-reports", reportFileName());
    }

    private String reportFileName() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return "mixology-watchdog-" + timestamp + ".txt";
    }

    private String buildReport(APIContext ctx, String reason) {
        StringBuilder report = new StringBuilder();
        report.append("Mixology Profit watchdog report\n");
        report.append("version=").append(scriptVersion).append('\n');
        report.append("reason=").append(reason).append('\n');
        report.append("runtime=").append(stats.runtimeText()).append('\n');
        report.append("state=").append(stats.state()).append('\n');
        report.append("status=").append(stats.status()).append('\n');
        report.append("targetReward=").append(stats.targetReward()).append('\n');
        report.append("lastOrder=").append(stats.lastOrder()).append('\n');
        report.append("trips=").append(stats.tripsStarted()).append('\n');
        report.append("potions=").append(stats.potionsMixed()).append('\n');
        report.append("orders=").append(stats.ordersCompleted()).append('\n');
        report.append("location=").append(locationText(ctx)).append('\n');
        report.append("inventory=").append(itemSummary(ctx.inventory().getItems())).append('\n');
        report.append("equipment=").append(itemSummary(ctx.equipment().getItems())).append('\n');
        report.append("recentLogs:\n");
        List<String> events = stats.recentEvents(35);
        if (events.isEmpty()) {
            report.append("- none\n");
        } else {
            for (String event : events) {
                report.append("- ").append(event).append('\n');
            }
        }
        return report.toString();
    }

    private String locationText(APIContext ctx) {
        Tile tile = ctx.localPlayer().getLocation();
        if (tile == null) {
            return "unknown";
        }
        return tile.getX() + "," + tile.getY() + "," + tile.getPlane();
    }

    private String itemSummary(Iterable<ItemWidget> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemWidget item : items) {
            if (item == null || item.getName() == null || item.getName().isBlank()) {
                continue;
            }
            String key = item.getName() + (item.isNoted() ? " (noted)" : "");
            int amount = Math.max(1, item.getStackSize());
            counts.merge(key, amount, Integer::sum);
        }

        if (counts.isEmpty()) {
            return "empty";
        }

        StringBuilder summary = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) {
                summary.append(", ");
            }
            summary.append(entry.getKey()).append(" x").append(entry.getValue());
            first = false;
        }
        return summary.toString();
    }

    private static class Snapshot {
        private final int herbloreXp;
        private final int inventoryFingerprint;
        private final int equipmentFingerprint;
        private final int grandExchangeFingerprint;
        private final long statsProgress;
        private final String state;
        private final String status;
        private final Tile tile;

        private Snapshot(
                int herbloreXp,
                int inventoryFingerprint,
                int equipmentFingerprint,
                int grandExchangeFingerprint,
                long statsProgress,
                String state,
                String status,
                Tile tile
        ) {
            this.herbloreXp = herbloreXp;
            this.inventoryFingerprint = inventoryFingerprint;
            this.equipmentFingerprint = equipmentFingerprint;
            this.grandExchangeFingerprint = grandExchangeFingerprint;
            this.statsProgress = statsProgress;
            this.state = state == null ? "" : state;
            this.status = status == null ? "" : status;
            this.tile = tile;
        }

        private static Snapshot capture(APIContext ctx, MixologyStats stats) {
            return new Snapshot(
                    herbloreXp(ctx),
                    itemFingerprint(ctx.inventory().getItems()),
                    itemFingerprint(ctx.equipment().getItems()),
                    grandExchangeFingerprint(ctx),
                    stats.progressScore(),
                    stats.state(),
                    stats.status(),
                    ctx.localPlayer().getLocation()
            );
        }

        private boolean hasMaterialProgressSince(Snapshot previous) {
            return herbloreXp != previous.herbloreXp
                    || inventoryFingerprint != previous.inventoryFingerprint
                    || equipmentFingerprint != previous.equipmentFingerprint
                    || grandExchangeFingerprint != previous.grandExchangeFingerprint
                    || statsProgress != previous.statsProgress;
        }

        private static int grandExchangeFingerprint(APIContext ctx) {
            int result = 17;
            try {
                for (GrandExchangeSlot slot : ctx.grandExchange().getSlots()) {
                    if (slot == null || !slot.inUse()) {
                        continue;
                    }
                    result = 31 * result + slot.getIndex();
                    result = 31 * result + (slot.getState() == null ? 0 : slot.getState().ordinal());
                    GrandExchangeOffer offer = slot.getOffer();
                    if (offer == null) {
                        continue;
                    }
                    result = 31 * result + offer.getItemId();
                    result = 31 * result + offer.getPrice();
                    result = 31 * result + offer.getCurrentQuantity();
                    result = 31 * result + offer.getRemaining();
                }
            } catch (RuntimeException ignored) {
                return result;
            }
            return result;
        }

        private static int herbloreXp(APIContext ctx) {
            try {
                return ctx.skills().get(Skill.Skills.HERBLORE).getExperience();
            } catch (RuntimeException ignored) {
                return 0;
            }
        }

        private static int itemFingerprint(Iterable<ItemWidget> items) {
            int result = 17;
            for (ItemWidget item : items) {
                if (item == null || item.getName() == null || item.getName().isBlank()) {
                    continue;
                }

                result = 31 * result + item.getIndex();
                result = 31 * result + item.getId();
                result = 31 * result + item.getStackSize();
                result = 31 * result + (item.isNoted() ? 1 : 0);
            }
            return result;
        }
    }
}
