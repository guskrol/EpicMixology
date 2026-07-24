package org.gusta.mixology.services;

import com.epicbot.api.gameval.InterfaceID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.stats.MixologyStats;

import java.awt.Point;
import java.awt.Rectangle;
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
    private static final int MIXOLOGY_CARD_SCROLL_LOG_WINDOW = 30;
    private static final int MOUSE_SCROLL_BATCH = 3;

    private final MixologySettings settings;
    private final MixologyStats stats;
    private long nextAttemptAt;
    private boolean magicTabSelected;
    private int mixologyCardScrollAttempts;
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
        if (isMinigameTeleportPanelOpen(ctx)) {
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

        Time.sleep(700, 1200, () -> isMinigameTeleportPanelOpen(ctx) || teleportLooksOnCooldown(ctx), 100);
        if (isMinigameTeleportPanelOpen(ctx)) {
            stats.setStatus("Mastering Mixology teleport panel opened");
            magicTabSelected = false;
            mixologyCardScrollAttempts = 0;
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
            if (isMasteringMixologyCardReadyForClick(mixologyCard)) {
                stats.setStatus("Selecting Mastering Mixology (widget 951.14)");
                if (clickWidget(ctx, mixologyCard, "Select")) {
                    Time.sleep(600, 900, () -> findPanelTeleportControl(ctx) != null, 100);
                    if (findPanelTeleportControl(ctx) != null) {
                        mixologyCardScrollAttempts = 0;
                        return true;
                    }
                    stats.debug("Mastering Mixology card clicked, but final teleport button is not visible yet; continuing scroll search");
                    mouseWheelSearchingMixology(ctx, true);
                    Time.sleep(350, 650);
                    return false;
                }
                stats.debug("Mastering Mixology widget 951.14 rejected click after scroll attempts="
                        + mixologyCardScrollAttempts + " bounds=" + safeBounds(mixologyCard));
                return false;
            }

            if (mouseWheelSearchingMixology(ctx, false)) {
                return false;
            }
        }

        if (hasText(ctx, "mastering mixology")) {
            WidgetChild selected = findClickableText(ctx, "mastering mixology");
            if (selected != null && click(ctx, selected)) {
                stats.setStatus("Selected Mastering Mixology teleport");
                Time.sleep(600, 900, () -> findPanelTeleportControl(ctx) != null, 100);
                if (findPanelTeleportControl(ctx) != null) {
                    mixologyCardScrollAttempts = 0;
                    return true;
                }
                stats.debug("Mastering Mixology text clicked, but final teleport button is not visible yet; continuing scroll search");
                mouseWheelSearchingMixology(ctx, true);
                return false;
            }
            stats.debug("Mastering Mixology text is visible, but no clickable text widget accepted the click");
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

        if (isMinigameTeleportPanelOpen(ctx)) {
            if (mouseWheelSearchingMixology(ctx, false)) {
                return false;
            }
            stats.setStatus("Mastering Mixology not visible yet; keeping minigame teleport search active");
            Time.sleep(450, 750);
            return false;
        }

        stats.setStatus("Mastering Mixology option not visible in minigame teleport UI");
        mixologyCardScrollAttempts = 0;
        nextAttemptAt = System.currentTimeMillis() + QUICK_RETRY_MILLIS;
        return false;
    }

    private boolean mouseWheelSearchingMixology(APIContext ctx, boolean force) {
        WidgetChild mixologyCard = ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_CHILD);
        if (!force && isMasteringMixologyCardReadyForClick(mixologyCard)) {
            return false;
        }

        if (!moveMouseToTeleportList(ctx)) {
            stats.debug("Minigame teleport list area unavailable for mouse-wheel search");
            return false;
        }

        int displayAttempt = (mixologyCardScrollAttempts % MIXOLOGY_CARD_SCROLL_LOG_WINDOW) + 1;
        stats.setStatus("Mouse-wheel scrolling minigame list for Mastering Mixology "
                + displayAttempt + "/" + MIXOLOGY_CARD_SCROLL_LOG_WINDOW);

        Rectangle beforeBounds = safeBounds(mixologyCard);
        String beforeFingerprint = visibleTeleportListFingerprint(ctx);
        boolean downMoved = scrollAndWaitForListChange(ctx, false, beforeBounds, beforeFingerprint);
        if (!downMoved) {
            Rectangle retryBounds = safeBounds(ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_CHILD));
            String retryFingerprint = visibleTeleportListFingerprint(ctx);
            downMoved = scrollAndWaitForListChange(ctx, true, retryBounds, retryFingerprint);
        }

        if (!downMoved) {
            stats.debug("Mouse-wheel scroll did not visibly move the minigame list; not counting this attempt");
            Time.sleep(500, 800);
            return false;
        }

        mixologyCardScrollAttempts++;
        return true;
    }

    private boolean scrollAndWaitForListChange(
            APIContext ctx,
            boolean direction,
            Rectangle beforeBounds,
            String beforeFingerprint
    ) {
        ctx.mouse().scroll(direction, MOUSE_SCROLL_BATCH);
        return Time.sleep(700, 1100,
                () -> hasScrollMovedOrCardReady(ctx, beforeBounds)
                        || !visibleTeleportListFingerprint(ctx).equals(beforeFingerprint),
                100);
    }

    private boolean moveMouseToTeleportList(APIContext ctx) {
        WidgetChild list = ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_LIST_CHILD);
        Rectangle bounds = safeBounds(list);
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            int x = clamp(bounds.x + Math.min(bounds.width - 20, Math.max(20, bounds.width / 2)), 650, 1230);
            int y = clamp(bounds.y + Math.min(bounds.height - 35, Math.max(35, bounds.height / 2)), 260, 760);
            return ctx.mouse().move(new Point(x, y));
        }

        WidgetChild card = ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_CHILD);
        bounds = safeBounds(card);
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            int x = clamp(bounds.x + bounds.width / 2, 650, 1230);
            int y = clamp(bounds.y + bounds.height / 2, 260, 760);
            return ctx.mouse().move(new Point(x, y));
        }

        return ctx.mouse().move(new Point(930, 520));
    }

    private String visibleTeleportListFingerprint(APIContext ctx) {
        StringBuilder fingerprint = new StringBuilder();
        for (WidgetChild widget : widgetSnapshot(ctx, widget -> {
            if (widget == null || !widget.isValid()) {
                return false;
            }
            Rectangle bounds = safeBounds(widget);
            return bounds != null
                    && bounds.width > 0
                    && bounds.height > 0
                    && bounds.x >= 550
                    && bounds.x <= 1250
                    && bounds.y >= 220
                    && bounds.y <= 820;
        })) {
            Rectangle bounds = safeBounds(widget);
            String text = normalize(widgetText(widget));
            if (bounds != null && (!text.isBlank() || widget.getMaterialId() > 0)) {
                fingerprint.append('|')
                        .append(widget.getIndex())
                        .append('@')
                        .append(bounds.x)
                        .append(',')
                        .append(bounds.y)
                        .append(':')
                        .append(widget.getMaterialId())
                        .append(':')
                        .append(text);
            }
        }
        return fingerprint.toString();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean isMasteringMixologyCardReadyForClick(WidgetChild mixologyCard) {
        return mixologyCard != null
                && mixologyCard.isValid()
                && isWidgetInClickableArea(mixologyCard)
                && isWidgetInTeleportListClickZone(mixologyCard)
                && normalize(widgetTreeText(mixologyCard)).contains("mastering mixology");
    }

    private boolean hasScrollMovedOrCardReady(APIContext ctx, Rectangle beforeBounds) {
        WidgetChild card = ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_CHILD);
        if (isMasteringMixologyCardReadyForClick(card)) {
            return true;
        }
        Rectangle afterBounds = safeBounds(card);
        if (beforeBounds == null || afterBounds == null) {
            return false;
        }
        return beforeBounds.x != afterBounds.x || beforeBounds.y != afterBounds.y;
    }

    private boolean isMixologyCardVisible(APIContext ctx) {
        WidgetChild mixologyCard = ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_CHILD);
        return mixologyCard != null && mixologyCard.isValid();
    }

    private boolean isMinigameTeleportPanelOpen(APIContext ctx) {
        WidgetChild list = ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_LIST_CHILD);
        return isMixologyCardVisible(ctx) || (list != null && list.isValid()) || hasText(ctx, "mastering mixology");
    }

    private boolean isWidgetInClickableArea(WidgetChild widget) {
        Rectangle bounds = safeBounds(widget);
        return bounds != null
                && bounds.width > 0
                && bounds.height > 0
                && bounds.x >= 0
                && bounds.y >= 0
                && bounds.x < 1900
                && bounds.y < 1050;
    }

    private boolean isWidgetInTeleportListClickZone(WidgetChild widget) {
        Rectangle bounds = safeBounds(widget);
        if (bounds == null) {
            return false;
        }

        // The card can exist in the widget tree while clipped outside the visible scroll list.
        // Only select it once it is inside the middle list area the player can actually click.
        int centerX = bounds.x + bounds.width / 2;
        int centerY = bounds.y + bounds.height / 2;
        return centerX >= 720
                && centerX <= 1200
                && centerY >= 300
                && centerY <= 720;
    }

    private Rectangle safeBounds(WidgetChild widget) {
        try {
            return widget == null ? null : widget.getBounds();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean isMinigameTeleportSpellVisible(APIContext ctx) {
        WidgetChild minigameTeleport = ctx.widgets().get(
                MINIGAME_TELEPORT_SPELL_GROUP,
                MINIGAME_TELEPORT_SPELL_CHILD
        );
        return minigameTeleport != null && minigameTeleport.isValid();
    }

    private boolean clickTeleport(APIContext ctx) {
        WidgetChild teleport = findPanelTeleportControl(ctx);
        if (teleport != null && clickTeleportControl(ctx, teleport)) {
            stats.setStatus("Using Mastering Mixology minigame teleport");
            waitForTeleport(ctx);
            return true;
        }

        if (mixologyCardSelected && isMinigameTeleportPanelOpen(ctx)) {
            stats.setStatus("Waiting for Mastering Mixology final teleport button");
            Time.sleep(600, 900, () -> findPanelTeleportControl(ctx) != null, 100);
            teleport = findPanelTeleportControl(ctx);
            if (teleport != null && clickTeleportControl(ctx, teleport)) {
                stats.setStatus("Using Mastering Mixology minigame teleport");
                waitForTeleport(ctx);
                return true;
            }
        }

        stats.setStatus("Teleport button unavailable; minigame teleport may be on cooldown");
        if (isMinigameTeleportPanelOpen(ctx)) {
            stats.debug("Teleport control missing while minigame panel is open; returning to card search");
            mixologyCardSelected = false;
            return true;
        }
        mixologyCardSelected = false;
        mixologyCardScrollAttempts = 0;
        nextAttemptAt = System.currentTimeMillis() + 5_000L;
        return false;
    }

    private WidgetChild findPanelTeleportControl(APIContext ctx) {
        WidgetChild legacyTeleport = ctx.widgets().get(InterfaceID.GROUPING, InterfaceID.Grouping.TELEPORT);
        if (isLikelyTeleportControl(legacyTeleport)) {
            return legacyTeleport;
        }

        List<WidgetChild> widgets = widgetSnapshot(ctx, widget -> {
            if (!isLikelyTeleportControl(widget)) {
                return false;
            }
            Rectangle bounds = safeBounds(widget);
            if (bounds == null) {
                return false;
            }
            return bounds.y > 180 && bounds.y < 850 && bounds.x > 250 && bounds.x < 1550;
        });
        return widgets.isEmpty() ? null : widgets.get(0);
    }

    private boolean isLikelyTeleportControl(WidgetChild widget) {
        if (widget == null || !widget.isValid() || !isWidgetInClickableArea(widget)) {
            return false;
        }
        String haystack = normalize(widgetText(widget) + " " + widget.getName() + " " + widget.getActions());
        return haystack.contains("teleport");
    }

    private boolean clickTeleportControl(APIContext ctx, WidgetChild widget) {
        if (widget == null || !widget.isValid()) {
            return false;
        }
        if (widget.interact("Teleport")) {
            return true;
        }
        String text = widgetText(widget);
        if (!text.isBlank() && widget.interact("Teleport", text)) {
            return true;
        }
        return ctx.mouse().click(widget, false) || widget.click(false) || widget.click();
    }

    private WidgetChild getNestedWidget(APIContext ctx, int group, int... childIndexes) {
        if (childIndexes.length == 0) {
            return null;
        }

        WidgetChild current = ctx.widgets().get(group, childIndexes[0]);
        for (int i = 1; i < childIndexes.length && current != null && current.isValid(); i++) {
            List<WidgetChild> children = current.getChildren();
            if (children == null || children.isEmpty()) {
                return null;
            }
            current = null;
            for (WidgetChild child : children) {
                if (child != null && child.isValid() && child.getIndex() == childIndexes[i]) {
                    current = child;
                    break;
                }
            }
        }
        return current;
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
        mixologyCardScrollAttempts = 0;
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

    private String widgetTreeText(WidgetChild widget) {
        if (widget == null || !widget.isValid()) {
            return "";
        }
        StringBuilder text = new StringBuilder(widgetText(widget));
        try {
            List<WidgetChild> children = widget.getChildren();
            if (children != null) {
                for (WidgetChild child : children) {
                    String childText = widgetTreeText(child);
                    if (!childText.isBlank()) {
                        text.append(' ').append(childText);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Widget children can refresh while the minigame panel scrolls.
        }
        return text.toString();
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
