package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.model.Area;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.stats.MixologyStats;

import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale;

public class CharterShipService {
    private static final String COINS = "Coins";
    private static final int ALDARIN_CHARTER_FARE = 3_100;
    private static final int PORT_SARIM_TRADER_CREWMEMBER_ID = 12793;
    private static final int SECONDARY_TRADER_CREWMEMBER_ID = 12805;
    private static final Tile CHARTER_TRADER_TILE = new Tile(2588, 2851, 0);
    private static final Area CHARTER_TRADER_AREA = new Area(2578, 2841, 2598, 2861, 0);
    private static final long RETRY_MILLIS = 60_000L;
    private static final long CHARTER_WIDGET_GRACE_MILLIS = 20_000L;

    private final MixologySettings settings;
    private final MixologyStats stats;

    private long nextAttemptAt;
    private long nextNpcDiagnosticAt;
    private long lastCharterInteractionAt;

    public CharterShipService(MixologySettings settings, MixologyStats stats) {
        this.settings = settings;
        this.stats = stats;
    }

    public boolean tryCharterToAldarin(APIContext ctx) {
        if (settings.alchemicalSocietyArea().contains(ctx.localPlayer().getLocation())) {
            return true;
        }
        if (System.currentTimeMillis() < nextAttemptAt) {
            return false;
        }
        if (!hasFare(ctx)) {
            stats.setStatus("No coins for Aldarin charter ship; falling back to webwalking");
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

        NPC trader = findTraderCrewmember(ctx);
        if (trader != null && trader.isValid()) {
            return interactWithTrader(ctx, trader);
        }

        logNearbyNpcs(ctx);
        return walkToTraderTile(ctx);
    }

    private boolean hasFare(APIContext ctx) {
        return ctx.inventory().getCount(true, COINS) >= ALDARIN_CHARTER_FARE;
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
        stats.setStatus("Closing Gangplank menu; charter uses Trader Crewmember at "
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
            stats.setStatus("Walking next to Trader Crewmember before charter interaction: "
                    + describeTrader(ctx, trader));
            ctx.webWalking().setUseTeleports(false);
            ctx.webWalking().walkTo(trader.getLocation());
            Time.sleep(900, 1400, () -> trader.tileDistanceTo(ctx) <= 3 || ctx.localPlayer().isMoving(), 100);
            return true;
        }

        stats.setStatus("Charter NPC found; interacting with " + describeTrader(ctx, trader));
        ctx.camera().turnTo(trader);
        boolean interacted = trader.interact("Charter", "Trader Crewmember")
                || trader.interact("Charter")
                || trader.interactMatch("Charter")
                || ctx.menu().interact("Charter", "Trader Crewmember", trader, true)
                || ctx.menu().interact("Charter", trader, true)
                || ctx.menu().interact("Charter", trader, false)
                || trader.interact("Talk-to", "Trader Crewmember")
                || trader.interact("Talk-to")
                || ctx.menu().interact("Talk-to", "Trader Crewmember", trader, true)
                || ctx.menu().interact("Talk-to", trader, true);
        if (interacted) {
            markCharterInteraction();
            boolean opened = waitForCharterInterface(ctx);
            if (!opened) {
                stats.debug("Trader Crewmember interaction did not open charter interface/dialogue yet; will retry. "
                        + describeTrader(ctx, trader));
            }
            return true;
        }

        stats.setStatus("Could not click Charter on " + describeTrader(ctx, trader));
        if (!isNearTraderTile(ctx)) {
            return walkToTraderTile(ctx);
        }
        Time.sleep(700, 1100);
        return true;
    }

    private NPC findTraderCrewmember(APIContext ctx) {
        NPC trader = ctx.npcs()
                .query()
                .id(PORT_SARIM_TRADER_CREWMEMBER_ID)
                .results()
                .nearest();
        if (trader != null && trader.isValid()) {
            return trader;
        }

        trader = ctx.npcs()
                .query()
                .id(SECONDARY_TRADER_CREWMEMBER_ID)
                .results()
                .nearest();
        if (trader != null && trader.isValid()) {
            return trader;
        }

        trader = ctx.npcs()
                .query()
                .nameContains("Trader Crewmember")
                .actions("Charter")
                .results()
                .nearest();
        if (trader != null && trader.isValid()) {
            return trader;
        }

        return ctx.npcs()
                .query()
                .nameContains("Trader Crewmember")
                .results()
                .nearest();
    }

    private boolean walkToTraderTile(APIContext ctx) {
        if (isNearTraderTile(ctx)) {
            stats.setStatus("At charter NPC tile " + tileText(CHARTER_TRADER_TILE)
                    + "; waiting for Trader Crewmember");
            Time.sleep(700, 1100);
            return true;
        }
        if (ctx.localPlayer().isMoving()) {
            stats.setStatus("Walking to charter NPC tile " + tileText(CHARTER_TRADER_TILE));
            Time.sleep(700, 1100);
            return true;
        }

        stats.setStatus("Webwalking to Trader Crewmember tile " + tileText(CHARTER_TRADER_TILE));
        ctx.webWalking().setUseTeleports(true);
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
        return ctx.menu().interact("Charter", "Trader Crewmember", trader, true)
                || ctx.menu().interact("Charter", trader, true)
                || ctx.menu().interact("Charter", trader, false)
                || ctx.menu().interact("Charter", true)
                || ctx.menu().interact("Charter", false)
                || ctx.menu().interact("Talk-to", "Trader Crewmember", trader, true)
                || ctx.menu().interact("Talk-to", trader, true)
                || ctx.menu().interact("Talk-to", true);
    }

    private boolean waitForCharterInterface(APIContext ctx) {
        Time.sleep(900, 1500,
                () -> hasWidgetText(ctx, "aldarin") || ctx.dialogues().isDialogueOpen(), 100);
        return hasWidgetText(ctx, "aldarin") || ctx.dialogues().isDialogueOpen() || ctx.dialogues().isChatOpen();
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
        if ((charterContext || dialogueContext) && ctx.dialogues().hasOptionContaining("Yes")) {
            stats.setStatus("Confirming Aldarin charter");
            ctx.dialogues().selectOption(text -> text != null
                    && text.toLowerCase(Locale.ROOT).contains("yes"));
            Time.sleep(900, 1500);
            return true;
        }

        if (!charterContext) {
            if (hasWidgetText(ctx, "aldarin")) {
                stats.debug("Ignoring Aldarin widget outside charter context at loc="
                        + ctx.localPlayer().getLocation());
            }
            return false;
        }

        WidgetChild aldarin = findWidgetText(ctx, "aldarin");
        if (aldarin != null && (aldarin.interact("Travel")
                || aldarin.interact("Charter")
                || aldarin.click())) {
            stats.setStatus("Selecting Aldarin on charter ship map");
            Time.sleep(900, 1500);
            return true;
        }
        return false;
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

    private WidgetChild findWidgetText(APIContext ctx, String needle) {
        List<WidgetChild> widgets = ctx.widgets().getAllChildren(widget -> {
            if (widget == null || !widget.isValid() || widget.getWidth() <= 0 || widget.getHeight() <= 0) {
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
                    && npc.getId() != PORT_SARIM_TRADER_CREWMEMBER_ID
                    && npc.getId() != SECONDARY_TRADER_CREWMEMBER_ID
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
            return "Trader Crewmember=null targetTile=" + CHARTER_TRADER_TILE;
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
