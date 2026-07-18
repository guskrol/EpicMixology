package org.gusta.mixology.services;

import com.epicbot.api.gameval.InterfaceID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.stats.MixologyStats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public class MinigameTeleportService {
    private static final long QUICK_RETRY_MILLIS = 20_000L;
    private static final long COOLDOWN_RETRY_MILLIS = 5 * 60_000L;
    private static final int MAGIC_TAB_GROUP = 164;
    private static final int MAGIC_TAB_CHILD = 58;
    private static final int MINIGAME_TELEPORT_SPELL_GROUP = 218;
    private static final int MINIGAME_TELEPORT_SPELL_CHILD = 7;
    private static final int MIXOLOGY_CARD_GROUP = 951;
    private static final int MIXOLOGY_CARD_LIST_CHILD = 4;
    private static final int MIXOLOGY_CARD_CHILD = 14;

    private final MixologySettings settings;
    private final MixologyStats stats;
    private long nextAttemptAt;
    private boolean magicTabSelected;
    private boolean mixologyCardScrollPrepared;
    private boolean mixologyCardSelected;

    public MinigameTeleportService(MixologySettings settings, MixologyStats stats) {
        this.settings = settings;
        this.stats = stats;
    }

    public boolean tryTeleport(APIContext ctx) {
        if (settings.alchemicalSocietyArea().contains(ctx.localPlayer().getLocation())) {
            return true;
        }
        if (System.currentTimeMillis() < nextAttemptAt) {
            return false;
        }
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus("Waiting for minigame teleport movement");
            Time.sleep(700, 1100);
            return true;
        }
        if (teleportLooksOnCooldown(ctx)) {
            stats.setStatus("Mastering Mixology minigame teleport appears to be on cooldown");
            nextAttemptAt = System.currentTimeMillis() + COOLDOWN_RETRY_MILLIS;
            return false;
        }
        if (mixologyCardSelected) {
            return clickTeleport(ctx);
        }
        if (isMixologyCardVisible(ctx)) {
            if (!selectMasteringMixology(ctx)) {
                return true;
            }
            mixologyCardSelected = true;
            stats.setStatus("Mastering Mixology selected; waiting for final teleport control");
            Time.sleep(350, 650);
            return true;
        }
        return openMinigameTeleportPanel(ctx);
    }

    private boolean openMinigameTeleportPanel(APIContext ctx) {
        if (!magicTabSelected) {
            stats.setStatus("Opening Magic tab before Minigame Teleport (widget 164.58)");
            WidgetChild magicTab = ctx.widgets().get(MAGIC_TAB_GROUP, MAGIC_TAB_CHILD);
            boolean opened = clickWidget(ctx, magicTab, "Magic");
            if (!opened) {
                opened = ctx.tabs().open(ITabsAPI.Tabs.MAGIC);
            }
            if (!opened) {
                stats.debug("Magic tab widget 164.58 rejected every click path");
                Time.sleep(500, 800);
                return true;
            }

            magicTabSelected = true;
            Time.sleep(500, 900);
            return true;
        }

        WidgetChild minigameTeleport = ctx.widgets().get(
                MINIGAME_TELEPORT_SPELL_GROUP,
                MINIGAME_TELEPORT_SPELL_CHILD
        );
        if (minigameTeleport == null || !minigameTeleport.isValid()) {
            stats.debug("Minigame Teleport widget 218.7 unavailable after selecting Magic; retrying 164.58");
            magicTabSelected = false;
            Time.sleep(500, 800);
            return true;
        }

        stats.setStatus("Casting Minigame Teleport (widget 218.7)");
        String teleportTarget = widgetText(minigameTeleport);
        boolean clicked = minigameTeleport.interact("Cast", minigameTeleport.getName())
                || (!teleportTarget.isBlank() && minigameTeleport.interact("Cast", teleportTarget))
                || clickWidget(ctx, minigameTeleport, "Cast");
        if (!clicked) {
            stats.debug("Minigame Teleport widget 218.7 rejected every click path: actions="
                    + minigameTeleport.getActions() + " name=" + minigameTeleport.getName()
                    + " bounds=" + minigameTeleport.getBounds()
                    + " absolute=" + minigameTeleport.getAbsoluteLocation());
            magicTabSelected = false;
            Time.sleep(500, 800);
            return true;
        }

        Time.sleep(700, 1200, () -> isMixologyCardVisible(ctx) || teleportLooksOnCooldown(ctx), 100);
        if (isMixologyCardVisible(ctx)) {
            stats.setStatus("Mastering Mixology teleport panel opened");
            magicTabSelected = false;
            mixologyCardScrollPrepared = false;
            mixologyCardSelected = false;
            return true;
        }
        if (teleportLooksOnCooldown(ctx)) {
            magicTabSelected = false;
            return false;
        }
        stats.debug("Minigame Teleport widget 218.7 clicked; waiting for Mastering Mixology card 951.14");
        magicTabSelected = false;
        return true;
    }

    private boolean openLegacyGroupingTab(APIContext ctx) {
        if (!ctx.tabs().isOpen(ITabsAPI.Tabs.QUESTS)) {
            stats.setStatus("Opening Quest/Grouping tab for minigame teleport");
            ctx.tabs().open(ITabsAPI.Tabs.QUESTS);
            Time.sleep(500, 900, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.QUESTS), 100);
            return true;
        }

        WidgetChild tab = findClickableText(ctx, "minigame", "grouping", "group");
        if (tab != null && click(ctx, tab)) {
            stats.setStatus("Opening minigame teleport grouping panel");
            Time.sleep(700, 1100, () -> ctx.quests().isMinigameTeleportActive(), 100);
            return true;
        }

        stats.setStatus("Could not open minigame teleport panel; falling back soon");
        nextAttemptAt = System.currentTimeMillis() + QUICK_RETRY_MILLIS;
        return false;
    }

    private boolean selectMasteringMixology(APIContext ctx) {
        WidgetChild mixologyCard = ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_CHILD);
        if (mixologyCard != null && mixologyCard.isValid()) {
            if (!mixologyCardScrollPrepared) {
                mixologyCardScrollPrepared = true;
                WidgetChild list = mixologyCard.getParent();
                if (list == null || !list.isValid()) {
                    list = ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_LIST_CHILD);
                }
                if (list != null && list.isValid() && ctx.widgets().scroll(list, mixologyCard)) {
                    stats.setStatus("Scrolling minigame list to Mastering Mixology (widget 951.14)");
                    Time.sleep(450, 750);
                    return false;
                }
            }

            stats.setStatus("Selecting Mastering Mixology (widget 951.14)");
            if (clickWidget(ctx, mixologyCard, "Select")) {
                Time.sleep(500, 900);
                return true;
            }
        }

        if (hasText(ctx, "mastering mixology")) {
            WidgetChild selected = findClickableText(ctx, "mastering mixology");
            if (selected == null || click(ctx, selected)) {
                stats.setStatus("Selected Mastering Mixology teleport");
                Time.sleep(500, 900);
                return true;
            }
        }

        WidgetChild dropdown = ctx.widgets().get(InterfaceID.GROUPING, InterfaceID.Grouping.DROPDOWN);
        if (dropdown != null && dropdown.isValid() && click(ctx, dropdown)) {
            stats.setStatus("Opening minigame teleport dropdown");
            Time.sleep(500, 900);
            return false;
        }

        WidgetChild dropdownText = findClickableText(ctx, "select a minigame", "last destination", "pvp arena");
        if (dropdownText != null && click(ctx, dropdownText)) {
            stats.setStatus("Opening minigame teleport dropdown");
            Time.sleep(500, 900);
            return false;
        }

        stats.setStatus("Mastering Mixology option not visible in minigame teleport UI");
        nextAttemptAt = System.currentTimeMillis() + QUICK_RETRY_MILLIS;
        return false;
    }

    private boolean isMixologyCardVisible(APIContext ctx) {
        WidgetChild mixologyCard = ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_CHILD);
        return mixologyCard != null && mixologyCard.isValid();
    }

    private boolean isMinigameTeleportSpellVisible(APIContext ctx) {
        WidgetChild minigameTeleport = ctx.widgets().get(
                MINIGAME_TELEPORT_SPELL_GROUP,
                MINIGAME_TELEPORT_SPELL_CHILD
        );
        return minigameTeleport != null && minigameTeleport.isValid();
    }

    private boolean clickTeleport(APIContext ctx) {
        WidgetChild teleport = ctx.widgets().get(InterfaceID.GROUPING, InterfaceID.Grouping.TELEPORT);
        if (teleport != null && teleport.isValid() && click(ctx, teleport)) {
            stats.setStatus("Using Mastering Mixology minigame teleport");
            waitForTeleport(ctx);
            return true;
        }

        WidgetChild teleportText = findClickableText(ctx, "teleport");
        if (teleportText != null && click(ctx, teleportText)) {
            stats.setStatus("Using Mastering Mixology minigame teleport");
            waitForTeleport(ctx);
            return true;
        }

        stats.setStatus("Teleport button unavailable; minigame teleport may be on cooldown");
        mixologyCardSelected = false;
        mixologyCardScrollPrepared = false;
        nextAttemptAt = System.currentTimeMillis() + QUICK_RETRY_MILLIS;
        return false;
    }

    private void waitForTeleport(APIContext ctx) {
        nextAttemptAt = System.currentTimeMillis() + QUICK_RETRY_MILLIS;
        Time.sleep(1800, 2800, () ->
                settings.alchemicalSocietyArea().contains(ctx.localPlayer().getLocation())
                        || ctx.localPlayer().isMoving()
                        || teleportLooksOnCooldown(ctx), 100);
        if (teleportLooksOnCooldown(ctx)) {
            nextAttemptAt = System.currentTimeMillis() + COOLDOWN_RETRY_MILLIS;
        }
        mixologyCardSelected = false;
        mixologyCardScrollPrepared = false;
    }

    private boolean teleportLooksOnCooldown(APIContext ctx) {
        String text = allWidgetText(ctx);
        return text.contains("cooldown")
                || text.contains("you must wait")
                || text.contains("recently teleported")
                || text.contains("minutes before")
                || text.contains("minute before");
    }

    private boolean hasText(APIContext ctx, String needle) {
        return allWidgetText(ctx).contains(normalize(needle));
    }

    private WidgetChild findClickableText(APIContext ctx, String... needles) {
        List<WidgetChild> widgets = widgetSnapshot(ctx, widget -> {
            try {
                if (widget == null || !widget.isValid() || widget.getWidth() <= 0 || widget.getHeight() <= 0) {
                    return false;
                }
                String text = normalize(widgetText(widget));
                for (String needle : needles) {
                    if (text.contains(normalize(needle))) {
                        return true;
                    }
                }
                return false;
            } catch (RuntimeException ignored) {
                // The client can replace a widget while the panel is animating.
                return false;
            }
        });
        return widgets.isEmpty() ? null : widgets.get(0);
    }

    private List<WidgetChild> widgetSnapshot(APIContext ctx, Predicate<WidgetChild> filter) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return new ArrayList<>(ctx.widgets().getAllChildren(filter));
            } catch (ConcurrentModificationException exception) {
                stats.debug("Minigame teleport widget tree changed during scan; retry " + attempt + "/3");
                Time.sleep(90, 160);
            }
        }
        stats.debug("Minigame teleport widget tree stayed unstable; skipping this tick");
        return Collections.emptyList();
    }

    private String allWidgetText(APIContext ctx) {
        StringBuilder text = new StringBuilder();
        for (WidgetChild widget : widgetSnapshot(ctx, widget -> widget != null && widget.isValid())) {
            try {
                String widgetText = widgetText(widget);
                if (!widgetText.isBlank()) {
                    text.append(' ').append(widgetText);
                }
            } catch (RuntimeException ignored) {
                // Ignore a stale individual entry and retain the rest of the snapshot.
            }
        }
        return normalize(text.toString());
    }

    private String widgetText(WidgetChild widget) {
        String text = widget.getText();
        if (text == null || text.isBlank()) {
            text = widget.getRawText();
        }
        if (text == null || text.isBlank()) {
            text = widget.getName();
        }
        return text == null ? "" : text.replace("<br>", " ").replaceAll("<[^>]+>", " ").trim();
    }

    private boolean click(APIContext ctx, WidgetChild widget) {
        return clickWidget(ctx, widget, "Cast", "Teleport", "Select", "Choose");
    }

    private boolean clickWidget(APIContext ctx, WidgetChild widget, String... actions) {
        if (widget == null || !widget.isValid()) {
            return false;
        }
        if (ctx.mouse().click(widget, false)) {
            return true;
        }
        for (String action : actions) {
            if (widget.interact(action)) {
                return true;
            }
        }
        return widget.interact(false) || widget.click(false) || widget.click();
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
