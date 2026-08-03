package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.SceneObject;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IEquipmentAPI;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.data.TravelItems;
import org.gusta.mixology.stats.MixologyStats;

import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale;

public class CharterShipService {
    private static final String COINS = "Coins";
    private static final int ALDARIN_CHARTER_FARE = 3_100;
    private static final int PORT_SARIM_TRADER_STAN_ID = 23027;
    private static final Tile CHARTER_TRADER_TILE = new Tile(3038, 3192, 0);
    private static final Area CHARTER_TRADER_AREA = new Area(3028, 3182, 3048, 3202, 0);
    private static final Area FALADOR_ARRIVAL_AREA = new Area(2940, 3350, 2995, 3410, 0);
    private static final Area FALADOR_TO_PORT_SARIM_ROUTE_AREA = new Area(2930, 3180, 3055, 3425, 0);
    private static final Area ALDARIN_SHIP_AREA = new Area(1435, 2945, 1485, 2990, 1);
    private static final Area ALDARIN_LAND_ROUTE_AREA = new Area(1360, 2880, 1485, 3010, 0);
    private static final Area ALDARIN_ROUTE_REGION = new Area(1200, 2700, 1800, 3200, 0);
    private static final int OBSERVED_ALDARIN_DOCK_X = 1455;
    private static final int OBSERVED_ALDARIN_DOCK_Y = 2968;
    private static final int OBSERVED_ALDARIN_DOCK_RADIUS = 40;
    private static final long RETRY_MILLIS = 60_000L;
    private static final long CHARTER_WIDGET_GRACE_MILLIS = 20_000L;
    private static final long RING_TELEPORT_TIMEOUT_MILLIS = 12_000L;
    private static final long ALDARIN_TRAVEL_TIMEOUT_MILLIS = 15_000L;

    private final MixologySettings settings;
    private final MixologyStats stats;

    private long nextAttemptAt;
    private long nextNpcDiagnosticAt;
    private long lastCharterInteractionAt;
    private long ringTeleportPendingUntil;
    private Tile ringTeleportOrigin;
    private boolean faladorRouteStarted;
    private long aldarinTravelPendingUntil;
    private Tile aldarinTravelOrigin;
    private long nextGangplankClickAt;
    private long nextCheckpointDiagnosticAt;

    public CharterShipService(MixologySettings settings, MixologyStats stats) {
        this.settings = settings;
        this.stats = stats;
    }

    public boolean tryCharterToAldarin(APIContext ctx) {
        if (settings.alchemicalSocietyArea().contains(ctx.localPlayer().getLocation())) {
            return true;
        }
        if (handleAldarinCheckpoint(ctx)) {
            return true;
        }
        if (waitForPendingAldarinTravel(ctx)) {
            return true;
        }
        if (System.currentTimeMillis() < nextAttemptAt) {
            stats.setStatus("Waiting before retrying Ring of wealth charter route");
            Time.sleep(700, 1100);
            return true;
        }
        if (!hasFare(ctx)) {
            stats.setStatus("Ring/charter route blocked: Aldarin requires "
                    + ALDARIN_CHARTER_FARE + " coins");
            nextAttemptAt = System.currentTimeMillis() + RETRY_MILLIS;
            return false;
        }
        if (closeGangplankMenu(ctx)) {
            return true;
        }
        if (selectAldarinDestination(ctx)) {
            waitForArrival(ctx);
            return true;
        }
        if (closeUnrelatedInterface(ctx)) {
            return true;
        }

        if (prepareFaladorRoute(ctx)) {
            return true;
        }

        NPC trader = findTraderStan(ctx);
        if (trader != null && trader.isValid()) {
            return interactWithTrader(ctx, trader);
        }

        logNearbyNpcs(ctx);
        return walkToTraderTile(ctx);
    }

    private boolean hasFare(APIContext ctx) {
        return ctx.inventory().getCount(true, COINS) >= ALDARIN_CHARTER_FARE;
    }

    public boolean canResumeFromCheckpoint(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        if (location == null) {
            return false;
        }
        boolean shipCheckpoint = isAldarinShipContext(ctx);
        boolean aldarinLandCheckpoint = isAldarinLandCheckpoint(ctx);
        boolean observedDockCheckpoint = isObservedAldarinDockCheckpoint(location);
        boolean preCharterCheckpoint = hasFare(ctx)
                && (FALADOR_ARRIVAL_AREA.contains(location)
                || FALADOR_TO_PORT_SARIM_ROUTE_AREA.contains(location)
                || CHARTER_TRADER_AREA.contains(location));
        boolean checkpoint = shipCheckpoint
                || aldarinLandCheckpoint
                || observedDockCheckpoint
                || settings.societySurfaceArea().contains(location)
                || preCharterCheckpoint;
        logCheckpointScan(ctx, location, shipCheckpoint, aldarinLandCheckpoint,
                preCharterCheckpoint, checkpoint);
        return checkpoint;
    }

    public boolean isObservedAldarinDockCheckpoint(APIContext ctx) {
        return isObservedAldarinDockCheckpoint(ctx.localPlayer().getLocation());
    }

    private boolean isObservedAldarinDockCheckpoint(Tile location) {
        return location != null
                && (location.getPlane() == 0 || location.getPlane() == 1)
                && Math.abs(location.getX() - OBSERVED_ALDARIN_DOCK_X) <= OBSERVED_ALDARIN_DOCK_RADIUS
                && Math.abs(location.getY() - OBSERVED_ALDARIN_DOCK_Y) <= OBSERVED_ALDARIN_DOCK_RADIUS;
    }

    private boolean handleAldarinCheckpoint(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        boolean observedDock = isObservedAldarinDockCheckpoint(location);
        if (isAldarinShipContext(ctx)
                || (observedDock && location != null && location.getPlane() == 1)) {
            aldarinTravelPendingUntil = 0L;
            aldarinTravelOrigin = null;
            nextAttemptAt = 0L;
            return leaveAldarinShip(ctx);
        }
        if (!isAldarinLandCheckpoint(ctx)
                && !(observedDock && location != null && location.getPlane() == 0)) {
            return false;
        }

        aldarinTravelPendingUntil = 0L;
        aldarinTravelOrigin = null;
        nextAttemptAt = 0L;
        if (settings.societySurfaceArea().contains(location)) {
            stats.setStatus("Aldarin land checkpoint confirmed near Alchemical Society");
            return true;
        }
        if (ctx.localPlayer().isMoving()) {
            stats.setStatus("Continuing from Aldarin dock checkpoint toward Alchemical Society");
            Time.sleep(700, 1100);
            return true;
        }

        stats.setStatus("Aldarin dock checkpoint: walking to Alchemical Society surface");
        ctx.webWalking().setUseTeleports(false);
        ctx.webWalking().walkTo(settings.societyCenterTile());
        Time.sleep(1200, 1800,
                () -> settings.societySurfaceArea().contains(ctx.localPlayer().getLocation())
                        || ctx.localPlayer().isMoving(), 100);
        return true;
    }

    private boolean leaveAldarinShip(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextGangplankClickAt) {
            stats.setStatus("Waiting for Aldarin Gangplank transition");
            Time.sleep(700, 1100);
            return true;
        }
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus("Waiting until stable before crossing Aldarin Gangplank");
            Time.sleep(500, 800);
            return true;
        }

        SceneObject gangplank = findAldarinGangplank(ctx);
        if (gangplank == null || !gangplank.isValid()) {
            stats.setStatus("Inside Aldarin ship; waiting for Gangplank object");
            Time.sleep(700, 1100);
            return true;
        }

        String action = gangplank.hasAction("Cross")
                ? "Cross"
                : gangplank.hasAction("Walk-across")
                ? "Walk-across"
                : gangplank.hasAction("Disembark")
                ? "Disembark"
                : null;
        stats.setStatus("Leaving Aldarin ship via Gangplank"
                + (action == null ? "" : " action=" + action));
        ctx.camera().turnTo(gangplank);
        boolean clicked = action == null ? gangplank.click() : gangplank.interact(action);
        nextGangplankClickAt = now + 6_000L;
        if (clicked) {
            Time.sleep(1000, 1800,
                    () -> !ALDARIN_SHIP_AREA.contains(ctx.localPlayer().getLocation())
                            || ctx.localPlayer().isMoving(), 100);
        } else {
            stats.debug("Aldarin Gangplank click rejected: id=" + gangplank.getId()
                    + " tile=" + gangplank.getLocation()
                    + " actions=" + gangplank.getActions());
            Time.sleep(700, 1100);
        }
        return true;
    }

    private SceneObject findAldarinGangplank(APIContext ctx) {
        SceneObject gangplank = ctx.objects()
                .query()
                .nameContains("Gangplank")
                .within(ALDARIN_SHIP_AREA)
                .results()
                .nearest();
        if (gangplank != null && gangplank.isValid()) {
            return gangplank;
        }
        return ctx.objects()
                .query()
                .nameContains("Gangplank")
                .results()
                .nearest();
    }

    private boolean isAldarinShipContext(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        if (location == null) {
            return false;
        }
        if (ALDARIN_SHIP_AREA.contains(location)) {
            return true;
        }
        return location.getPlane() == 1
                && location.getX() >= 1200
                && location.getX() <= 1800
                && location.getY() >= 2700
                && location.getY() <= 3200
                && findAldarinGangplank(ctx) != null;
    }

    private boolean isAldarinLandCheckpoint(APIContext ctx) {
        Tile location = ctx.localPlayer().getLocation();
        if (location == null || location.getPlane() != 0) {
            return false;
        }
        return isWithin(location, 1200, 2700, 1800, 3200)
                || ALDARIN_LAND_ROUTE_AREA.contains(location)
                || ALDARIN_ROUTE_REGION.contains(location)
                || settings.societySurfaceArea().contains(location);
    }

    private boolean isWithin(Tile location, int minX, int minY, int maxX, int maxY) {
        return location != null
                && location.getX() >= minX
                && location.getX() <= maxX
                && location.getY() >= minY
                && location.getY() <= maxY;
    }

    private void logCheckpointScan(
            APIContext ctx,
            Tile location,
            boolean shipCheckpoint,
            boolean aldarinLandCheckpoint,
            boolean preCharterCheckpoint,
            boolean checkpoint
    ) {
        long now = System.currentTimeMillis();
        if (now < nextCheckpointDiagnosticAt) {
            return;
        }
        nextCheckpointDiagnosticAt = now + 5_000L;
        SceneObject gangplank = findAldarinGangplank(ctx);
        stats.debug("Travel checkpoint scan: loc=" + location
                + " ship=" + shipCheckpoint
                + " aldarinLand=" + aldarinLandCheckpoint
                + " preCharter=" + preCharterCheckpoint
                + " fare=" + ctx.inventory().getCount(true, COINS)
                + " gangplank=" + (gangplank == null
                ? "none"
                : gangplank.getId() + "@" + gangplank.getLocation() + " actions=" + gangplank.getActions())
                + " result=" + checkpoint);
    }

    private boolean prepareFaladorRoute(APIContext ctx) {
        if (isNearTraderTile(ctx)
                || FALADOR_ARRIVAL_AREA.contains(ctx.localPlayer().getLocation())
                || FALADOR_TO_PORT_SARIM_ROUTE_AREA.contains(ctx.localPlayer().getLocation())
                || faladorRouteStarted) {
            faladorRouteStarted = true;
            ringTeleportPendingUntil = 0L;
            ringTeleportOrigin = null;
            return false;
        }

        long now = System.currentTimeMillis();
        if (ringTeleportPendingUntil > 0L) {
            if (hasMovedFrom(ctx, ringTeleportOrigin, 20)) {
                faladorRouteStarted = true;
                ringTeleportPendingUntil = 0L;
                ringTeleportOrigin = null;
                stats.setStatus("Ring of wealth teleport confirmed; walking to Trader Stan");
                return false;
            }
            if (now < ringTeleportPendingUntil) {
                stats.setStatus("Waiting for Ring of wealth Falador teleport");
                Time.sleep(700, 1100);
                return true;
            }
            stats.debug("Ring of wealth Falador teleport was not confirmed; retrying once stable");
            ringTeleportPendingUntil = 0L;
            ringTeleportOrigin = null;
        }

        ItemWidget ring = ctx.equipment().getItem(IEquipmentAPI.Slot.RING);
        if (ring == null || !TravelItems.isChargedRingOfWealth(ring.getName())) {
            stats.setStatus("Ring route blocked: charged Ring of wealth is not equipped");
            Time.sleep(700, 1100);
            return true;
        }
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus("Waiting until stable before Ring of wealth Falador teleport");
            Time.sleep(500, 800);
            return true;
        }

        stats.setStatus("Teleporting to Falador with equipped " + ring.getName());
        Tile origin = ctx.localPlayer().getLocation();
        boolean interacted = ring.interact("Falador", ring.getName())
                || ring.interact("Falador");
        if (interacted) {
            ringTeleportOrigin = origin;
            ringTeleportPendingUntil = now + RING_TELEPORT_TIMEOUT_MILLIS;
            Time.sleep(900, 1400,
                    () -> FALADOR_ARRIVAL_AREA.contains(ctx.localPlayer().getLocation())
                            || hasMovedFrom(ctx, origin, 20), 100);
        } else {
            stats.setStatus("Could not select Falador on equipped Ring of wealth");
            Time.sleep(700, 1100);
        }
        return true;
    }

    private boolean closeGangplankMenu(APIContext ctx) {
        if (!ctx.menu().isOpen()) {
            return false;
        }
        String menu = normalize(String.valueOf(ctx.menu().getActions())
                + " " + String.valueOf(ctx.menu().getOptions()));
        if (!menu.contains("gangplank")) {
            return false;
        }
        stats.setStatus("Closing Gangplank menu; charter uses Trader Stan at "
                + tileText(CHARTER_TRADER_TILE));
        ctx.menu().closeMenu();
        Time.sleep(250, 450);
        return true;
    }

    private boolean closeUnrelatedInterface(APIContext ctx) {
        if (recentlyOpenedCharter()
                || ctx.dialogues().isDialogueOpen()
                || ctx.dialogues().isChatOpen()
                || !ctx.widgets().isInterfaceOpen()) {
            return false;
        }
        stats.setStatus("Closing unrelated interface before walking to charter NPC");
        ctx.widgets().closeInterface();
        Time.sleep(450, 750);
        return true;
    }

    private boolean interactWithTrader(APIContext ctx, NPC trader) {
        if (ctx.menu().isOpen()) {
            if (selectCharterFromOpenMenu(ctx, trader)) {
                markCharterInteraction();
                waitForCharterInterface(ctx);
                return true;
            }
            ctx.menu().closeMenu();
            Time.sleep(150, 300);
            return true;
        }
        if (ctx.inventory().isItemSelected()) {
            ctx.inventory().deselectItem();
            Time.sleep(150, 300);
            return true;
        }
        if (trader.tileDistanceTo(ctx) > 3 && !ctx.localPlayer().isMoving()) {
            stats.setStatus("Walking next to Trader Stan before charter interaction: "
                    + describeTrader(ctx, trader));
            ctx.webWalking().setUseTeleports(false);
            ctx.webWalking().walkTo(trader.getLocation());
            Time.sleep(900, 1400, () -> trader.tileDistanceTo(ctx) <= 3 || ctx.localPlayer().isMoving(), 100);
            return true;
        }

        stats.setStatus("Right-clicking Trader Stan for Charter: " + describeTrader(ctx, trader));
        ctx.camera().turnTo(trader);
        boolean menuOpened = ctx.mouse().click(trader, true) || trader.click(true);
        if (menuOpened) {
            Time.sleep(350, 650, () -> ctx.menu().isOpen(), 50);
            if (ctx.menu().isOpen() && selectCharterFromOpenMenu(ctx, trader)) {
                markCharterInteraction();
                boolean opened = waitForCharterInterface(ctx);
                if (!opened) {
                    stats.debug("Trader Stan Charter action did not open the charter map yet; will retry. "
                            + describeTrader(ctx, trader));
                }
            }
            return true;
        }

        stats.setStatus("Could not right-click Trader Stan for Charter: " + describeTrader(ctx, trader));
        if (!isNearTraderTile(ctx)) {
            return walkToTraderTile(ctx);
        }
        Time.sleep(700, 1100);
        return true;
    }

    private NPC findTraderStan(APIContext ctx) {
        NPC trader = ctx.npcs()
                .query()
                .id(PORT_SARIM_TRADER_STAN_ID)
                .results()
                .nearest();
        if (trader != null && trader.isValid()) {
            return trader;
        }

        return ctx.npcs()
                .query()
                .named("Trader Stan")
                .actions("Charter")
                .results()
                .nearest();
    }

    private boolean walkToTraderTile(APIContext ctx) {
        if (isNearTraderTile(ctx)) {
            stats.setStatus("At charter NPC tile " + tileText(CHARTER_TRADER_TILE)
                    + "; waiting for Trader Stan id=" + PORT_SARIM_TRADER_STAN_ID);
            Time.sleep(700, 1100);
            return true;
        }
        if (ctx.localPlayer().isMoving()) {
            stats.setStatus("Walking to charter NPC tile " + tileText(CHARTER_TRADER_TILE));
            Time.sleep(700, 1100);
            return true;
        }

        stats.setStatus("Webwalking to Trader Stan at Port Sarim " + tileText(CHARTER_TRADER_TILE));
        ctx.webWalking().setUseTeleports(false);
        ctx.webWalking().walkTo(CHARTER_TRADER_TILE);
        Time.sleep(1200, 1800);
        return true;
    }

    private boolean isNearTraderTile(APIContext ctx) {
        return CHARTER_TRADER_AREA.contains(ctx.localPlayer().getLocation())
                || CHARTER_TRADER_TILE.tileDistanceTo(ctx) <= 8;
    }

    private boolean selectCharterFromOpenMenu(APIContext ctx, NPC trader) {
        stats.debug("Selecting Charter from open menu for " + describeTrader(ctx, trader)
                + " menuActions=" + ctx.menu().getActions()
                + " menuOptions=" + ctx.menu().getOptions());
        return ctx.menu().interact("Charter", "Trader Stan", trader, true)
                || ctx.menu().interact("Charter", trader, true)
                || ctx.menu().interact("Charter", trader, false)
                || ctx.menu().interact("Charter", true)
                || ctx.menu().interact("Charter", false);
    }

    private boolean waitForCharterInterface(APIContext ctx) {
        Time.sleep(900, 1500,
                () -> isCharterInterfaceOpen(ctx) || ctx.dialogues().isDialogueOpen(), 100);
        return isCharterInterfaceOpen(ctx) || ctx.dialogues().isDialogueOpen() || ctx.dialogues().isChatOpen();
    }

    private void markCharterInteraction() {
        lastCharterInteractionAt = System.currentTimeMillis();
    }

    private boolean recentlyOpenedCharter() {
        return System.currentTimeMillis() - lastCharterInteractionAt <= CHARTER_WIDGET_GRACE_MILLIS;
    }

    private boolean selectAldarinDestination(APIContext ctx) {
        boolean charterContext = recentlyOpenedCharter();
        boolean dialogueContext = ctx.dialogues().isDialogueOpen() || ctx.dialogues().isChatOpen();

        if ((charterContext || dialogueContext) && isInsufficientFareDialogue(ctx)) {
            stats.setStatus("Aldarin charter needs " + ALDARIN_CHARTER_FARE
                    + " coins; cancelling charter dialogue");
            if (ctx.dialogues().hasOptionContaining("Cancel")) {
                ctx.dialogues().selectOption(text -> text != null
                        && text.toLowerCase(Locale.ROOT).contains("cancel"));
            } else {
                ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            }
            nextAttemptAt = System.currentTimeMillis() + RETRY_MILLIS;
            Time.sleep(700, 1100);
            return true;
        }

        if (dialogueContext && ctx.dialogues().hasOptionContaining("Aldarin")) {
            stats.setStatus("Selecting Aldarin charter dialogue option");
            ctx.dialogues().selectOption(text -> text != null
                    && text.toLowerCase(Locale.ROOT).contains("aldarin"));
            Time.sleep(900, 1500);
            return true;
        }
        if ((charterContext || dialogueContext) && hasYesAndDontAskAgainOption(ctx)) {
            stats.setStatus("Confirming Aldarin charter: Yes, and don't ask again");
            ctx.dialogues().selectOption(this::isYesAndDontAskAgainOption);
            Time.sleep(900, 1500);
            return true;
        }
        if ((charterContext || dialogueContext) && ctx.dialogues().hasOptionContaining("Yes")) {
            stats.setStatus("Confirming Aldarin charter");
            ctx.dialogues().selectOption(text -> text != null
                    && text.toLowerCase(Locale.ROOT).contains("yes"));
            Time.sleep(900, 1500);
            return true;
        }

        if (!charterContext) {
            if (isCharterInterfaceOpen(ctx)) {
                stats.debug("Ignoring Aldarin widget outside charter context at loc="
                        + ctx.localPlayer().getLocation());
            }
            return false;
        }

        if (isCharterInterfaceOpen(ctx)) {
            stats.setStatus("Selecting Aldarin on charter map with keyboard shortcut E");
            aldarinTravelOrigin = ctx.localPlayer().getLocation();
            ctx.keyboard().sendKey(KeyEvent.VK_E);
            aldarinTravelPendingUntil = System.currentTimeMillis() + ALDARIN_TRAVEL_TIMEOUT_MILLIS;
            Time.sleep(900, 1500);
            return true;
        }
        return false;
    }

    private boolean hasYesAndDontAskAgainOption(APIContext ctx) {
        List<WidgetChild> options = ctx.dialogues().getOptions();
        if (options == null || options.isEmpty()) {
            return false;
        }
        for (WidgetChild option : options) {
            if (option == null) {
                continue;
            }
            String text = widgetText(option);
            if (isYesAndDontAskAgainOption(text)) {
                return true;
            }
        }
        return false;
    }

    private boolean isYesAndDontAskAgainOption(String text) {
        String normalized = normalize(text);
        return normalized.contains("yes")
                && normalized.contains("don")
                && normalized.contains("ask")
                && normalized.contains("again");
    }

    private boolean waitForPendingAldarinTravel(APIContext ctx) {
        if (aldarinTravelPendingUntil <= 0L) {
            return false;
        }
        if (settings.societySurfaceArea().contains(ctx.localPlayer().getLocation())
                || hasMovedFrom(ctx, aldarinTravelOrigin, 100)) {
            stats.setStatus("Aldarin charter arrival confirmed");
            aldarinTravelPendingUntil = 0L;
            aldarinTravelOrigin = null;
            return false;
        }
        if (System.currentTimeMillis() < aldarinTravelPendingUntil) {
            stats.setStatus("Waiting for Aldarin charter travel after shortcut E");
            Time.sleep(700, 1100);
            return true;
        }
        stats.debug("Aldarin charter travel was not confirmed after shortcut E; reopening via Trader Stan");
        aldarinTravelPendingUntil = 0L;
        aldarinTravelOrigin = null;
        lastCharterInteractionAt = 0L;
        return false;
    }

    private boolean hasMovedFrom(APIContext ctx, Tile origin, int minimumDistance) {
        if (origin == null || ctx.localPlayer().getLocation() == null) {
            return false;
        }
        Tile current = ctx.localPlayer().getLocation();
        if (origin.getPlane() != current.getPlane()) {
            return true;
        }
        int dx = origin.getX() - current.getX();
        int dy = origin.getY() - current.getY();
        return Math.max(Math.abs(dx), Math.abs(dy)) >= minimumDistance;
    }

    private boolean isInsufficientFareDialogue(APIContext ctx) {
        String text = ctx.dialogues().getText();
        if (text == null || text.isBlank()) {
            text = allWidgetText(ctx);
        }
        String normalized = normalize(text);
        return normalized.contains("need")
                && normalized.contains("coins")
                && normalized.contains("aldarin");
    }

    private void waitForArrival(APIContext ctx) {
        Time.sleep(1800, 2800, () ->
                settings.alchemicalSocietyArea().contains(ctx.localPlayer().getLocation())
                        || ctx.localPlayer().isMoving(), 100);
    }

    private boolean hasWidgetText(APIContext ctx, String text) {
        return findWidgetText(ctx, text) != null;
    }

    private boolean isCharterInterfaceOpen(APIContext ctx) {
        return hasWidgetText(ctx, "aldarin")
                && (hasWidgetText(ctx, "brimhaven")
                || hasWidgetText(ctx, "catherby")
                || hasWidgetText(ctx, "port khazard")
                || hasWidgetText(ctx, "port phasmatys"));
    }

    private WidgetChild findWidgetText(APIContext ctx, String needle) {
        List<WidgetChild> widgets = ctx.widgets().getAllChildren(widget -> {
            if (widget == null || !widget.isValid() || !widget.isVisible()
                    || widget.getWidth() <= 0 || widget.getHeight() <= 0) {
                return false;
            }
            return normalize(widgetText(widget)).contains(normalize(needle));
        });
        return widgets.isEmpty() ? null : widgets.get(0);
    }

    private void logNearbyNpcs(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextNpcDiagnosticAt) {
            return;
        }
        nextNpcDiagnosticAt = now + 10_000L;

        List<NPC> npcs = ctx.npcs().query().results().toList();
        StringBuilder summary = new StringBuilder();
        int count = 0;
        for (NPC npc : npcs) {
            if (npc == null || !npc.isValid()) {
                continue;
            }
            String name = npc.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            if (!normalize(name).contains("trader")
                    && npc.getId() != PORT_SARIM_TRADER_STAN_ID
                    && !normalize(String.valueOf(npc.getActions())).contains("charter")) {
                continue;
            }
            if (count > 0) {
                summary.append(" | ");
            }
            summary.append(name)
                    .append(" id=").append(npc.getId())
                    .append(" tile=").append(npc.getLocation())
                    .append(" targetTile=").append(CHARTER_TRADER_TILE)
                    .append(" dist=").append(npc.tileDistanceTo(ctx))
                    .append(" actions=").append(npc.getActions());
            count++;
            if (count >= 5) {
                break;
            }
        }
        stats.debug("Nearby charter NPC scan: " + (summary.length() == 0 ? "none" : summary.toString()));
    }

    private String describeTrader(APIContext ctx, NPC trader) {
        if (trader == null) {
            return "Trader Stan=null targetTile=" + CHARTER_TRADER_TILE;
        }
        return trader.getName()
                + " id=" + trader.getId()
                + " tile=" + trader.getLocation()
                + " targetTile=" + CHARTER_TRADER_TILE
                + " dist=" + trader.tileDistanceTo(ctx)
                + " actions=" + trader.getActions();
    }

    private String allWidgetText(APIContext ctx) {
        StringBuilder text = new StringBuilder();
        for (WidgetChild widget : ctx.widgets().getAllChildren(widget -> widget != null && widget.isValid())) {
            String value = widgetText(widget);
            if (value != null && !value.isBlank()) {
                text.append(' ').append(value);
            }
        }
        return text.toString();
    }

    private String widgetText(WidgetChild widget) {
        String text = widget.getText();
        if (text == null || text.isBlank()) {
            text = widget.getRawText();
        }
        return text == null ? "" : text.replace("<br>", " ").replaceAll("<[^>]+>", " ").trim();
    }

    private String tileText(Tile tile) {
        return tile.getX() + "," + tile.getY() + "," + tile.getPlane();
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
