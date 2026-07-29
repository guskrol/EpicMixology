package org.gusta.mixology.services;

import com.epicbot.api.gameval.InterfaceID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.entity.WidgetGroup;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public class MinigameTeleportService {
    private static final long QUICK_RETRY_MILLIS = 20_000L;
    private static final long COOLDOWN_RETRY_MILLIS = 5 * 60_000L;
    private static final long PANEL_OPEN_GRACE_MILLIS = 4_000L;
    private static final int MAGIC_SPELLBOOK_GROUP = InterfaceID.MAGIC_SPELLBOOK;
    private static final int MIXOLOGY_CARD_GROUP = 951;
    private static final int MIXOLOGY_CARD_LIST_CHILD = 4;
    private static final int MIXOLOGY_CARD_CHILD = 14;
    private static final int MIXOLOGY_CARD_SCROLL_LOG_WINDOW = 120;
    private static final int MOUSE_SCROLL_BATCH = 4;
    private static final int GROUPING_OPEN_ATTEMPTS_BEFORE_MAGIC = 3;
    private static final long GROUPING_DROPDOWN_RETRY_MILLIS = 12_000L;
    private static final int MAGIC_OPEN_ATTEMPTS_BEFORE_CHARTER = 2;
    private static final int MAGIC_SELECTION_ATTEMPTS_BEFORE_CHARTER = 20;
    private static final int MAGIC_SELECTION_CLICKS_BEFORE_CHARTER = 2;
    private static final long TELEPORT_PENDING_MILLIS = 12_000L;
    private static final long MIXOLOGY_SELECTION_WAIT_MIN_MILLIS = 14_000L;
    private static final long MIXOLOGY_SELECTION_WAIT_MAX_MILLIS = 16_000L;

    private final MixologySettings settings;
    private final MixologyStats stats;
    private long nextAttemptAt;
    private long panelRequestedAt;
    private long nextMagicWidgetDiagnosticAt;
    private long nextGroupingDropdownAttemptAt;
    private long teleportPendingUntil;
    private long mixologySelectionPendingUntil;
    private String mixologySelectionFingerprintBefore = "";
    private int mixologyCardScrollAttempts;
    private int groupingOpenAttempts;
    private int magicOpenAttempts;
    private int magicSelectionAttempts;
    private int magicSelectionClicks;
    private boolean mixologyCardSelected;
    private boolean useMagicFallback;

    public MinigameTeleportService(MixologySettings settings, MixologyStats stats) {
        this.settings = settings;
        this.stats = stats;
    }

    public boolean tryTeleport(APIContext ctx) {
        if (settings.alchemicalSocietyArea().contains(ctx.localPlayer().getLocation())) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (teleportPendingUntil > 0L) {
            if (now < teleportPendingUntil) {
                stats.setStatus("Waiting for Magic minigame teleport arrival");
                Time.sleep(700, 1100);
                return true;
            }
            teleportPendingUntil = 0L;
            return abandonMagicForCharter(ctx, "Magic teleport did not move the player");
        }
        if (now < nextAttemptAt) {
            return false;
        }
        if (ctx.localPlayer().isMoving()) {
            stats.setStatus("Waiting for minigame teleport movement");
            Time.sleep(700, 1100);
            return true;
        }
        if (teleportLooksOnCooldown(ctx)) {
            stats.setStatus("Mastering Mixology minigame teleport appears to be on cooldown");
            nextAttemptAt = System.currentTimeMillis() + COOLDOWN_RETRY_MILLIS;
            return false;
        }
        if (mixologySelectionPendingUntil > 0L) {
            return waitForMixologySelectionConfirmation(ctx, now);
        }
        if (mixologyCardSelected) {
            return clickTeleport(ctx);
        }
        if (isMinigameTeleportPanelOpen(ctx)) {
            if (!selectMasteringMixology(ctx)) {
                magicSelectionAttempts++;
                if (magicSelectionAttempts >= MAGIC_SELECTION_ATTEMPTS_BEFORE_CHARTER) {
                    return abandonMagicForCharter(ctx,
                            "Mastering Mixology selection did not complete");
                }
                return true;
            }
            beginMixologySelectionConfirmation(ctx);
            return true;
        }
        if (isPanelOpenGraceActive()) {
            stats.setStatus("Waiting for Minigame Teleport panel to open");
            Time.sleep(500, 800);
            return true;
        }
        if (panelRequestedAt > 0L) {
            panelRequestedAt = 0L;
            return recordMagicOpenFailure(ctx, "Minigame Teleport panel 951 did not open");
        }
        return openMagicMinigameTeleportPanel(ctx);
    }

    private boolean openMagicMinigameTeleportPanel(APIContext ctx) {
        if (!ctx.tabs().isOpen(ITabsAPI.Tabs.MAGIC)) {
            stats.setStatus("Opening Magic tab before Minigame Teleport");
            boolean requested = ctx.tabs().open(ITabsAPI.Tabs.MAGIC);
            Time.sleep(700, 1200, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.MAGIC), 100);
            if (!ctx.tabs().isOpen(ITabsAPI.Tabs.MAGIC)) {
                WidgetChild tabWidget = ctx.tabs().getTabWidget(ITabsAPI.Tabs.MAGIC);
                stats.debug("Magic tab did not open: requested=" + requested
                        + " current=" + ctx.tabs().getCurrent()
                        + " tabWidget=" + widgetDiagnostic(tabWidget));
                return recordMagicOpenFailure(ctx, "Magic tab did not open");
            }
            return true;
        }

        WidgetChild minigameTeleport = findVisibleMinigameTeleportSpell(ctx);
        if (minigameTeleport == null) {
            stats.setStatus("Minigame Teleport icon not found in open Magic tab");
            logVisibleMagicWidgets(ctx);
            return recordMagicOpenFailure(ctx, "Minigame Teleport icon not found");
        }

        stats.setStatus("Opening Minigame Teleport from Magic tab");
        String teleportTarget = widgetText(minigameTeleport);
        boolean clicked = minigameTeleport.interact("Cast", minigameTeleport.getName())
                || (!teleportTarget.isBlank() && minigameTeleport.interact("Cast", teleportTarget))
                || clickWidget(ctx, minigameTeleport, "Cast");
        if (!clicked) {
            stats.debug("Minigame Teleport widget rejected every click path: "
                    + widgetDiagnostic(minigameTeleport));
            return recordMagicOpenFailure(ctx, "Minigame Teleport icon rejected click");
        }

        panelRequestedAt = System.currentTimeMillis();
        Time.sleep(700, 1200, () -> isMinigameTeleportPanelOpen(ctx) || teleportLooksOnCooldown(ctx), 100);
        if (isMinigameTeleportPanelOpen(ctx)) {
            stats.setStatus("Mastering Mixology teleport panel opened");
            panelRequestedAt = 0L;
            mixologyCardScrollAttempts = 0;
            mixologyCardSelected = false;
            magicOpenAttempts = 0;
            magicSelectionAttempts = 0;
            magicSelectionClicks = 0;
            return true;
        }
        if (teleportLooksOnCooldown(ctx)) {
            panelRequestedAt = 0L;
            nextAttemptAt = System.currentTimeMillis() + COOLDOWN_RETRY_MILLIS;
            return false;
        }
        stats.debug("Minigame Teleport clicked; panel not detected yet: "
                + widgetDiagnostic(minigameTeleport));
        return true;
    }

    private boolean recordMagicOpenFailure(APIContext ctx, String reason) {
        magicOpenAttempts++;
        stats.debug("Magic minigame teleport failure " + magicOpenAttempts + "/"
                + MAGIC_OPEN_ATTEMPTS_BEFORE_CHARTER + ": " + reason);
        if (magicOpenAttempts >= MAGIC_OPEN_ATTEMPTS_BEFORE_CHARTER) {
            return abandonMagicForCharter(ctx, reason);
        }
        stats.setStatus(reason + "; retrying Magic before charter fallback");
        Time.sleep(500, 800);
        return true;
    }

    private void beginMixologySelectionConfirmation(APIContext ctx) {
        long waitMillis = ThreadLocalRandom.current().nextLong(
                MIXOLOGY_SELECTION_WAIT_MIN_MILLIS,
                MIXOLOGY_SELECTION_WAIT_MAX_MILLIS + 1L);
        mixologySelectionPendingUntil = System.currentTimeMillis() + waitMillis;
        magicSelectionClicks++;
        mixologyCardSelected = false;
        stats.setStatus("Mastering Mixology clicked; waiting "
                + ((waitMillis + 999L) / 1000L) + "s before widget confirmation");
        stats.debug("Mastering Mixology selection pending: click=" + magicSelectionClicks
                + "/" + MAGIC_SELECTION_CLICKS_BEFORE_CHARTER
                + " waitMillis=" + waitMillis
                + " beforeFingerprintHash=" + fingerprintHash(mixologySelectionFingerprintBefore));
    }

    private boolean waitForMixologySelectionConfirmation(APIContext ctx, long now) {
        if (now < mixologySelectionPendingUntil) {
            long remainingMillis = Math.max(0L, mixologySelectionPendingUntil - now);
            stats.setStatus("Waiting after Mastering Mixology selection: "
                    + ((remainingMillis + 999L) / 1000L) + "s");
            Time.sleep(700, 1100);
            return true;
        }

        boolean confirmed = isSelectionConfirmed(ctx);
        String afterFingerprint = magicPanelSelectionFingerprint(ctx);
        if (confirmed) {
            clearMixologySelectionPending();
            magicSelectionAttempts = 0;
            mixologyCardSelected = true;
            stats.setStatus("Mastering Mixology selection confirmed; final Teleport enabled");
            stats.debug("Mastering Mixology selection confirmed after click=" + magicSelectionClicks
                    + " afterFingerprintHash=" + fingerprintHash(afterFingerprint));
            return true;
        }

        stats.debug("Mastering Mixology selection not confirmed after 14-16s: click="
                + magicSelectionClicks + "/" + MAGIC_SELECTION_CLICKS_BEFORE_CHARTER
                + " panel951=" + isMinigameTeleportPanelOpen(ctx)
                + " scopedTeleport=" + widgetDiagnostic(findMagicPanelTeleportControl(ctx))
                + " beforeFingerprintHash=" + fingerprintHash(mixologySelectionFingerprintBefore)
                + " afterFingerprintHash=" + fingerprintHash(afterFingerprint));
        clearMixologySelectionPending();
        mixologyCardSelected = false;
        magicSelectionAttempts++;
        if (magicSelectionClicks >= MAGIC_SELECTION_CLICKS_BEFORE_CHARTER
                || magicSelectionAttempts >= MAGIC_SELECTION_ATTEMPTS_BEFORE_CHARTER) {
            return abandonMagicForCharter(ctx,
                    "Mastering Mixology widget selection was not confirmed");
        }
        stats.setStatus("Mastering Mixology was not confirmed; retrying card selection once");
        Time.sleep(500, 800);
        return true;
    }

    private void clearMixologySelectionPending() {
        mixologySelectionPendingUntil = 0L;
        mixologySelectionFingerprintBefore = "";
    }

    private String fingerprintHash(String fingerprint) {
        return fingerprint == null || fingerprint.isBlank()
                ? "empty"
                : Integer.toHexString(fingerprint.hashCode());
    }

    private boolean abandonMagicForCharter(APIContext ctx, String reason) {
        stats.setStatus("Magic minigame teleport unavailable; using Falador/Port Sarim charter fallback");
        stats.debug("Releasing travel flow to Trader Stan fallback: " + reason);
        if (ctx.widgets().isInterfaceOpen()) {
            ctx.widgets().closeInterface();
            Time.sleep(350, 650);
        }
        panelRequestedAt = 0L;
        mixologyCardSelected = false;
        mixologyCardScrollAttempts = 0;
        magicOpenAttempts = 0;
        magicSelectionAttempts = 0;
        magicSelectionClicks = 0;
        teleportPendingUntil = 0L;
        clearMixologySelectionPending();
        nextAttemptAt = System.currentTimeMillis() + COOLDOWN_RETRY_MILLIS;
        return false;
    }

    private boolean openGroupingPanel(APIContext ctx) {
        if (!ctx.tabs().isOpen(ITabsAPI.Tabs.QUESTS)) {
            stats.setStatus("Opening Quest List parent tab for Grouping teleport");
            boolean requested = ctx.tabs().open(ITabsAPI.Tabs.QUESTS);
            Time.sleep(500, 900, () -> ctx.tabs().isOpen(ITabsAPI.Tabs.QUESTS), 100);
            if (!ctx.tabs().isOpen(ITabsAPI.Tabs.QUESTS)) {
                stats.debug("Quest List parent tab did not open for Grouping: requested=" + requested
                        + " current=" + ctx.tabs().getCurrent());
            }
            return true;
        }

        if (isGroupingPanelOpen(ctx)) {
            groupingOpenAttempts = 0;
            panelRequestedAt = 0L;
            stats.setStatus("Grouping minigame teleport panel opened");
            return true;
        }

        WidgetChild tab = findClickableSignal(ctx, "grouping", "minigame teleport");
        if (tab != null && (tab.interact("Grouping") || click(ctx, tab))) {
            stats.setStatus("Opening Grouping minigame teleport panel");
            panelRequestedAt = System.currentTimeMillis();
            Time.sleep(700, 1100, () -> isGroupingPanelOpen(ctx), 100);
            if (isGroupingPanelOpen(ctx)) {
                groupingOpenAttempts = 0;
                panelRequestedAt = 0L;
                mixologyCardScrollAttempts = 0;
                mixologyCardSelected = false;
                nextGroupingDropdownAttemptAt = 0L;
                return true;
            }
            panelRequestedAt = 0L;
            return recordGroupingOpenFailure();
        }

        return recordGroupingOpenFailure();
    }

    private boolean recordGroupingOpenFailure() {
        groupingOpenAttempts++;
        stats.debug("Grouping control not found/accepted; attempt=" + groupingOpenAttempts
                + "/" + GROUPING_OPEN_ATTEMPTS_BEFORE_MAGIC);
        if (groupingOpenAttempts >= GROUPING_OPEN_ATTEMPTS_BEFORE_MAGIC) {
            useMagicFallback = true;
            panelRequestedAt = 0L;
            stats.setStatus("Grouping unavailable; falling back to Magic minigame teleport");
        } else {
            stats.setStatus("Looking for Grouping minigame teleport control");
        }
        Time.sleep(450, 750);
        return true;
    }

    private boolean selectMasteringMixology(APIContext ctx) {
        if (isMasteringMixologySelected(ctx)) {
            stats.setStatus("Mastering Mixology already selected in Grouping");
            return true;
        }

        WidgetChild mixologyCard = findMasteringMixologyCard(ctx);
        if (mixologyCard != null && !isMasteringMixologyCardReadyForClick(ctx, mixologyCard)) {
            scrollMasteringMixologyIntoView(ctx, mixologyCard);
        }

        WidgetChild visibleMixologyCard = findVisibleMasteringMixologyCard(ctx);
        if (visibleMixologyCard != null) {
            stats.setStatus("Selecting Mastering Mixology (visible card)");
            mixologySelectionFingerprintBefore = magicPanelSelectionFingerprint(ctx);
            if (selectTeleportCard(ctx, visibleMixologyCard)) {
                mixologyCardScrollAttempts = 0;
                stats.debug("Mastering Mixology card clicked once; selection confirmation wait will start");
                return true;
            }
            stats.debug("Visible Mastering Mixology card rejected click after scroll attempts="
                    + mixologyCardScrollAttempts + " bounds=" + safeBounds(visibleMixologyCard));
            return false;
        }

        if (mixologyCard != null && mixologyCard.isValid()) {
            if (mouseWheelSearchingMixology(ctx, false)) {
                return false;
            }
        }

        if (hasText(ctx, "mastering mixology")) {
            WidgetChild selected = findVisibleClickableText(ctx, "mastering mixology");
            mixologySelectionFingerprintBefore = magicPanelSelectionFingerprint(ctx);
            if (selected != null && click(ctx, selected)) {
                stats.setStatus("Selected Mastering Mixology teleport");
                mixologyCardScrollAttempts = 0;
                stats.debug("Mastering Mixology label clicked once; selection confirmation wait will start");
                return true;
            }
            stats.debug("Mastering Mixology text is visible, but no clickable text widget accepted the click");
        }

        if (System.currentTimeMillis() >= nextGroupingDropdownAttemptAt
                && openGroupingActivityDropdown(ctx)) {
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
        WidgetChild mixologyCard = findMasteringMixologyCard(ctx);
        if (!force && isMasteringMixologyCardReadyForClick(ctx, mixologyCard)) {
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
            stats.debug("Mouse-wheel scroll down did not visibly move the minigame list; not counting this attempt");
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
        WidgetChild list = activeTeleportList(ctx);
        Rectangle listBounds = safeBounds(list);
        Point mouseLocation = ctx.mouse().getLocation();
        if (listBounds == null || mouseLocation == null || !listBounds.contains(mouseLocation)) {
            stats.debug("Refusing mouse-wheel scroll outside minigame list: mouse=" + mouseLocation
                    + " listBounds=" + listBounds);
            return false;
        }
        ctx.mouse().scroll(direction, MOUSE_SCROLL_BATCH);
        return Time.sleep(1200, 1800,
                () -> hasScrollMovedOrCardReady(ctx, beforeBounds)
                        || !visibleTeleportListFingerprint(ctx).equals(beforeFingerprint),
                100);
    }

    private boolean moveMouseToTeleportList(APIContext ctx) {
        WidgetChild list = activeTeleportList(ctx);
        Rectangle bounds = safeBounds(list);
        if (list == null || !list.isValid() || !list.isVisible()
                || bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return false;
        }

        Point target = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        if (!ctx.mouse().move(target)) {
            return false;
        }
        Time.sleep(150, 300, () -> {
            Point current = ctx.mouse().getLocation();
            return current != null && bounds.contains(current);
        }, 50);
        Point current = ctx.mouse().getLocation();
        return current != null && bounds.contains(current);
    }

    private String visibleTeleportListFingerprint(APIContext ctx) {
        StringBuilder fingerprint = new StringBuilder();
        Rectangle listBounds = safeBounds(activeTeleportList(ctx));
        if (listBounds == null) {
            return "";
        }
        for (WidgetChild widget : widgetSnapshot(ctx, widget -> {
            if (widget == null || !widget.isValid()) {
                return false;
            }
            Rectangle bounds = safeBounds(widget);
            return bounds != null
                    && bounds.width > 0
                    && bounds.height > 0
                    && listBounds.intersects(bounds);
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

    private boolean isMasteringMixologyCardReadyForClick(APIContext ctx, WidgetChild mixologyCard) {
        return mixologyCard != null
                && mixologyCard.isValid()
                && isWidgetInClickableArea(mixologyCard)
                && isWidgetInTeleportListClickZone(ctx, mixologyCard)
                && normalize(widgetTreeText(mixologyCard)).contains("mastering mixology");
    }

    private WidgetChild findMasteringMixologyCard(APIContext ctx) {
        WidgetChild fixedCard = ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_CHILD);
        if (fixedCard != null && fixedCard.isValid()
                && normalize(widgetTreeText(fixedCard)).contains("mastering mixology")) {
            return fixedCard;
        }

        List<WidgetChild> widgets = widgetSnapshot(ctx, widget -> widget != null
                && widget.isValid()
                && normalize(widgetText(widget)).contains("mastering mixology"));
        return widgets.isEmpty() ? null : widgets.get(0);
    }

    private boolean scrollMasteringMixologyIntoView(APIContext ctx, WidgetChild mixologyCard) {
        WidgetChild list = activeTeleportList(ctx);
        if (list == null || !list.isValid()) {
            return false;
        }

        Rectangle beforeBounds = safeBounds(mixologyCard);
        stats.setStatus("Scrolling minigame list to Mastering Mixology");
        boolean scrolled = ctx.widgets().scroll(mixologyCard, list);
        if (!scrolled) {
            stats.debug("Widget API could not scroll Mastering Mixology into view; using mouse-wheel fallback");
            return false;
        }

        Time.sleep(600, 1000,
                () -> isMasteringMixologyCardReadyForClick(ctx, mixologyCard)
                        || boundsChanged(beforeBounds, safeBounds(mixologyCard)),
                100);
        return isMasteringMixologyCardReadyForClick(ctx, mixologyCard);
    }

    private WidgetChild findVisibleMasteringMixologyCard(APIContext ctx) {
        WidgetChild fixedCard = ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_CHILD);
        if (isMasteringMixologyCardReadyForClick(ctx, fixedCard)) {
            return fixedCard;
        }

        List<WidgetChild> widgets = widgetSnapshot(ctx, widget -> {
            if (widget == null || !widget.isValid() || !isWidgetInClickableArea(widget)) {
                return false;
            }
            if (!isWidgetInTeleportListClickZone(ctx, widget)) {
                return false;
            }
            return normalize(widgetText(widget)).contains("mastering mixology");
        });
        return widgets.isEmpty() ? null : widgets.get(0);
    }

    private boolean hasScrollMovedOrCardReady(APIContext ctx, Rectangle beforeBounds) {
        WidgetChild card = findMasteringMixologyCard(ctx);
        if (isMasteringMixologyCardReadyForClick(ctx, card)) {
            return true;
        }
        Rectangle afterBounds = safeBounds(card);
        return boundsChanged(beforeBounds, afterBounds);
    }

    private boolean boundsChanged(Rectangle beforeBounds, Rectangle afterBounds) {
        return beforeBounds != null
                && afterBounds != null
                && (beforeBounds.x != afterBounds.x || beforeBounds.y != afterBounds.y);
    }

    private boolean isMixologyCardVisible(APIContext ctx) {
        WidgetChild mixologyCard = ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_CHILD);
        return mixologyCard != null && mixologyCard.isValid() && mixologyCard.isVisible();
    }

    private boolean isMinigameTeleportPanelOpen(APIContext ctx) {
        WidgetGroup panel = ctx.widgets().get(MIXOLOGY_CARD_GROUP);
        WidgetChild list = ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_LIST_CHILD);
        return (panel != null && panel.isValid() && panel.isVisible())
                || isMixologyCardVisible(ctx)
                || (list != null && list.isValid() && list.isVisible());
    }

    private boolean isGroupingPanelOpen(APIContext ctx) {
        if (!ctx.tabs().isOpen(ITabsAPI.Tabs.QUESTS)) {
            return false;
        }
        WidgetGroup grouping = ctx.widgets().get(InterfaceID.GROUPING);
        if (grouping != null && grouping.isValid() && grouping.isVisible()) {
            return true;
        }
        WidgetChild dropdown = ctx.widgets().getChild(InterfaceID.Grouping.DROPDOWN);
        return dropdown != null && dropdown.isValid() && dropdown.isVisible();
    }

    private boolean openGroupingActivityDropdown(APIContext ctx) {
        WidgetChild arrow = ctx.widgets().getChild(InterfaceID.Grouping.ARROW);
        if (arrow != null && arrow.isValid() && arrow.isVisible()) {
            stats.setStatus("Clicking Grouping activity dropdown arrow");
            stats.debug("Grouping activity arrow: " + widgetDiagnostic(arrow));
            if (clickWidget(ctx, arrow, "Open", "Select")) {
                nextGroupingDropdownAttemptAt = System.currentTimeMillis()
                        + GROUPING_DROPDOWN_RETRY_MILLIS;
                Time.sleep(700, 1100);
                return true;
            }
            stats.debug("Grouping activity arrow rejected click");
        }

        WidgetChild dropdown = ctx.widgets().getChild(InterfaceID.Grouping.DROPDOWN);
        if (dropdown != null && dropdown.isValid() && dropdown.isVisible()) {
            stats.setStatus("Opening Grouping activity dropdown");
            if (click(ctx, dropdown)) {
                nextGroupingDropdownAttemptAt = System.currentTimeMillis()
                        + GROUPING_DROPDOWN_RETRY_MILLIS;
                Time.sleep(700, 1100);
                return true;
            }
        }

        WidgetChild dropdownText = findClickableText(ctx,
                "select an activity", "select a minigame", "last destination");
        if (dropdownText != null && click(ctx, dropdownText)) {
            stats.setStatus("Opening Grouping activity dropdown by label");
            nextGroupingDropdownAttemptAt = System.currentTimeMillis()
                    + GROUPING_DROPDOWN_RETRY_MILLIS;
            Time.sleep(700, 1100);
            return true;
        }

        stats.debug("Grouping activity dropdown controls unavailable");
        nextGroupingDropdownAttemptAt = System.currentTimeMillis() + 2_000L;
        return false;
    }

    private boolean isPanelOpenGraceActive() {
        return panelRequestedAt > 0L
                && System.currentTimeMillis() - panelRequestedAt <= PANEL_OPEN_GRACE_MILLIS;
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

    private boolean isWidgetInTeleportListClickZone(APIContext ctx, WidgetChild widget) {
        Rectangle bounds = safeBounds(widget);
        if (bounds == null) {
            return false;
        }

        int centerX = bounds.x + bounds.width / 2;
        int centerY = bounds.y + bounds.height / 2;
        Rectangle listBounds = safeBounds(activeTeleportList(ctx));
        if (listBounds != null && listBounds.width > 0 && listBounds.height > 0) {
            return listBounds.contains(centerX, centerY);
        }

        // Legacy fallback for clients where the new list container is unavailable.
        return centerX >= 720
                && centerX <= 1200
                && centerY >= 300
                && centerY <= 720;
    }

    private WidgetChild activeTeleportList(APIContext ctx) {
        WidgetChild list = ctx.widgets().get(MIXOLOGY_CARD_GROUP, MIXOLOGY_CARD_LIST_CHILD);
        if (list != null && list.isValid() && list.isVisible()) {
            return list;
        }
        list = ctx.widgets().getChild(InterfaceID.Grouping.DROPDOWN_CONTENTS);
        if (list != null && list.isValid() && list.isVisible()) {
            return list;
        }
        return ctx.widgets().getChild(InterfaceID.Grouping.DROPDOWN_SCROLLBAR);
    }

    private boolean isMasteringMixologySelected(APIContext ctx) {
        int[] selectionWidgets = {
                InterfaceID.Grouping.PVP_ARENA,
                InterfaceID.Grouping.CURRENTGAME,
                InterfaceID.Grouping.DROPDOWN_TOP,
                InterfaceID.Grouping.DROPDOWN
        };
        for (int widgetId : selectionWidgets) {
            WidgetChild widget = ctx.widgets().getChild(widgetId);
            if (widget != null
                    && widget.isValid()
                    && normalize(widgetTreeText(widget)).contains("mastering mixology")) {
                return true;
            }
        }
        return false;
    }

    private boolean isSelectionConfirmed(APIContext ctx) {
        if (isGroupingPanelOpen(ctx) && !isMinigameTeleportPanelOpen(ctx)) {
            return isMasteringMixologySelected(ctx);
        }
        if (!isMinigameTeleportPanelOpen(ctx) || !hasMasteringMixologyInMagicPanel(ctx)) {
            return false;
        }

        WidgetChild teleport = findMagicPanelTeleportControl(ctx);
        if (teleport == null) {
            return false;
        }

        WidgetChild card = findMasteringMixologyCard(ctx);
        boolean teleportBelongsToCard = isSameOrDescendant(teleport, card)
                || isSameOrDescendant(card, teleport);
        boolean selectedLabelVisible = hasSelectedMixologyLabelOutsideList(ctx);
        String afterFingerprint = magicPanelSelectionFingerprint(ctx);
        boolean panelChanged = !mixologySelectionFingerprintBefore.isBlank()
                && !afterFingerprint.isBlank()
                && !afterFingerprint.equals(mixologySelectionFingerprintBefore);
        return teleportBelongsToCard || selectedLabelVisible || panelChanged;
    }

    private boolean hasMasteringMixologyInMagicPanel(APIContext ctx) {
        return !widgetSnapshot(ctx, widget -> isWidgetInGroup(widget, MIXOLOGY_CARD_GROUP)
                && widget.isVisible()
                && normalize(widgetText(widget)).contains("mastering mixology")).isEmpty();
    }

    private boolean hasSelectedMixologyLabelOutsideList(APIContext ctx) {
        Rectangle listBounds = safeBounds(activeTeleportList(ctx));
        if (listBounds == null) {
            return false;
        }
        return !widgetSnapshot(ctx, widget -> {
            if (!isWidgetInGroup(widget, MIXOLOGY_CARD_GROUP) || !widget.isVisible()) {
                return false;
            }
            Rectangle bounds = safeBounds(widget);
            return bounds != null
                    && !listBounds.intersects(bounds)
                    && normalize(widgetText(widget)).contains("mastering mixology");
        }).isEmpty();
    }

    private String magicPanelSelectionFingerprint(APIContext ctx) {
        List<WidgetChild> widgets = widgetSnapshot(ctx, widget ->
                isWidgetInGroup(widget, MIXOLOGY_CARD_GROUP) && widget.isVisible());
        widgets.sort((left, right) -> {
            int childCompare = Integer.compare(left.getChildId(), right.getChildId());
            return childCompare != 0 ? childCompare : Integer.compare(left.getIndex(), right.getIndex());
        });

        StringBuilder fingerprint = new StringBuilder();
        for (WidgetChild widget : widgets) {
            try {
                fingerprint.append('|')
                        .append(widget.getChildId()).append(':')
                        .append(widget.getIndex()).append(':')
                        .append(widget.getParentId()).append(':')
                        .append(widget.getMaterialId()).append(':')
                        .append(widget.getModelId()).append(':')
                        .append(widget.getAlpha()).append(':')
                        .append(normalize(widgetText(widget))).append(':')
                        .append(normalize(String.valueOf(widget.getActions())));
            } catch (RuntimeException ignored) {
                // The panel can replace individual children while applying the selection.
            }
        }
        return fingerprint.toString();
    }

    private boolean isSameOrDescendant(WidgetChild candidate, WidgetChild ancestor) {
        if (candidate == null || ancestor == null) {
            return false;
        }
        WidgetChild current = candidate;
        for (int depth = 0; depth < 12 && current != null; depth++) {
            if (sameWidget(current, ancestor)) {
                return true;
            }
            try {
                current = current.getParent();
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    private boolean sameWidget(WidgetChild left, WidgetChild right) {
        try {
            return left != null
                    && right != null
                    && isWidgetInGroup(left, MIXOLOGY_CARD_GROUP)
                    && isWidgetInGroup(right, MIXOLOGY_CARD_GROUP)
                    && left.getChildId() == right.getChildId()
                    && left.getIndex() == right.getIndex()
                    && left.getParentId() == right.getParentId();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean isWidgetInGroup(WidgetChild widget, int groupId) {
        try {
            return widget != null
                    && widget.isValid()
                    && widget.getGroup() != null
                    && widget.getGroup().getIndex() == groupId;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Rectangle safeBounds(WidgetChild widget) {
        try {
            return widget == null ? null : widget.getBounds();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private WidgetChild findVisibleMinigameTeleportSpell(APIContext ctx) {
        List<WidgetChild> widgets = widgetSnapshot(ctx, widget -> {
            try {
                if (widget == null || !widget.isValid() || !widget.isVisible()
                        || !isWidgetInClickableArea(widget)
                        || widget.getGroup() == null
                        || widget.getGroup().getIndex() != MAGIC_SPELLBOOK_GROUP) {
                    return false;
                }
                String signal = normalize(widgetText(widget) + " "
                        + widget.getName() + " " + widget.getActions());
                return signal.contains("minigame teleport");
            } catch (RuntimeException ignored) {
                return false;
            }
        });
        for (WidgetChild widget : widgets) {
            if (normalize(String.valueOf(widget.getActions())).contains("cast")) {
                return widget;
            }
        }
        return widgets.isEmpty() ? null : widgets.get(0);
    }

    private void logVisibleMagicWidgets(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextMagicWidgetDiagnosticAt) {
            return;
        }
        nextMagicWidgetDiagnosticAt = now + 5_000L;

        StringBuilder summary = new StringBuilder();
        int count = 0;
        for (WidgetChild widget : widgetSnapshot(ctx, candidate -> {
            try {
                return candidate != null
                        && candidate.isValid()
                        && candidate.isVisible()
                        && candidate.getGroup() != null
                        && candidate.getGroup().getIndex() == MAGIC_SPELLBOOK_GROUP;
            } catch (RuntimeException ignored) {
                return false;
            }
        })) {
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append(widgetDiagnostic(widget));
            if (++count >= 30) {
                break;
            }
        }
        stats.debug("Visible Magic widgets while searching Minigame Teleport: "
                + (summary.length() == 0 ? "none" : summary));
    }

    private String widgetDiagnostic(WidgetChild widget) {
        if (widget == null) {
            return "null";
        }
        try {
            int group = widget.getGroup() == null ? -1 : widget.getGroup().getIndex();
            return "group=" + group
                    + ", child=" + widget.getChildId()
                    + ", index=" + widget.getIndex()
                    + ", bounds=" + safeBounds(widget)
                    + ", name='" + widget.getName() + "'"
                    + ", text='" + widgetText(widget) + "'"
                    + ", actions=" + widget.getActions()
                    + ", material=" + widget.getMaterialId()
                    + ", model=" + widget.getModelId();
        } catch (RuntimeException exception) {
            return "stale-widget";
        }
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
        panelRequestedAt = 0L;
        nextAttemptAt = System.currentTimeMillis() + 5_000L;
        return false;
    }

    private WidgetChild findPanelTeleportControl(APIContext ctx) {
        if (isMinigameTeleportPanelOpen(ctx)) {
            return findMagicPanelTeleportControl(ctx);
        }

        WidgetChild legacyTeleport = ctx.widgets().getChild(InterfaceID.Grouping.TELEPORT);
        if (isLikelyTeleportControl(legacyTeleport)) {
            return legacyTeleport;
        }
        return null;
    }

    private WidgetChild findMagicPanelTeleportControl(APIContext ctx) {
        List<WidgetChild> widgets = widgetSnapshot(ctx, widget -> {
            if (!isWidgetInGroup(widget, MIXOLOGY_CARD_GROUP)
                    || !widget.isVisible()
                    || !isLikelyTeleportControl(widget)) {
                return false;
            }
            String actions = normalize(String.valueOf(widget.getActions()));
            String directLabel = normalize(widgetText(widget) + " " + widget.getName());
            return actions.contains("teleport")
                    || directLabel.equals("teleport")
                    || directLabel.startsWith("teleport ");
        });
        for (WidgetChild widget : widgets) {
            if (normalize(String.valueOf(widget.getActions())).contains("teleport")) {
                return widget;
            }
        }
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
        clearMixologySelectionPending();
        teleportPendingUntil = System.currentTimeMillis() + TELEPORT_PENDING_MILLIS;
        Time.sleep(1800, 2800, () ->
                settings.alchemicalSocietyArea().contains(ctx.localPlayer().getLocation())
                        || ctx.localPlayer().isMoving()
                        || teleportLooksOnCooldown(ctx), 100);
        if (teleportLooksOnCooldown(ctx)) {
            teleportPendingUntil = 0L;
            nextAttemptAt = System.currentTimeMillis() + COOLDOWN_RETRY_MILLIS;
        }
        mixologyCardSelected = false;
        mixologyCardScrollAttempts = 0;
        groupingOpenAttempts = 0;
        useMagicFallback = false;
        magicOpenAttempts = 0;
        magicSelectionAttempts = 0;
        magicSelectionClicks = 0;
        nextGroupingDropdownAttemptAt = 0L;
        panelRequestedAt = 0L;
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

    private WidgetChild findClickableSignal(APIContext ctx, String... needles) {
        List<WidgetChild> widgets = widgetSnapshot(ctx, widget -> {
            try {
                if (widget == null || !widget.isValid() || widget.getWidth() <= 0 || widget.getHeight() <= 0) {
                    return false;
                }
                String signal = normalize(widgetText(widget) + " "
                        + widget.getName() + " " + widget.getActions());
                for (String needle : needles) {
                    if (signal.contains(normalize(needle))) {
                        return true;
                    }
                }
                return false;
            } catch (RuntimeException ignored) {
                return false;
            }
        });
        for (WidgetChild widget : widgets) {
            String actions = normalize(String.valueOf(widget.getActions()));
            for (String needle : needles) {
                if (actions.contains(normalize(needle))) {
                    return widget;
                }
            }
        }
        for (WidgetChild widget : widgets) {
            String directSignal = normalize(widget.getName() + " " + widget.getActions());
            boolean matchesDirectSignal = false;
            for (String needle : needles) {
                if (directSignal.contains(normalize(needle))) {
                    matchesDirectSignal = true;
                    break;
                }
            }
            if (matchesDirectSignal && widget.isVisible()) {
                return widget;
            }
        }
        return null;
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

    private boolean selectTeleportCard(APIContext ctx, WidgetChild widget) {
        if (widget == null || !widget.isValid()) {
            return false;
        }
        return widget.interact("Select")
                || widget.interact("Select", widgetText(widget))
                || ctx.mouse().click(widget, false)
                || widget.click(false)
                || widget.click();
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

    private WidgetChild findVisibleClickableText(APIContext ctx, String... needles) {
        List<WidgetChild> widgets = widgetSnapshot(ctx, widget -> {
            try {
                if (widget == null || !widget.isValid() || widget.getWidth() <= 0 || widget.getHeight() <= 0) {
                    return false;
                }
                if (!isWidgetInClickableArea(widget) || !isWidgetInTeleportListClickZone(ctx, widget)) {
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
                return false;
            }
        });
        return widgets.isEmpty() ? null : widgets.get(0);
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
