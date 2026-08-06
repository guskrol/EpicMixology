package org.gusta.mixology.services;

import com.epicbot.api.gameval.ItemID;
import com.epicbot.api.gameval.VarPlayerID;
import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IEquipmentAPI;
import com.epicbot.api.shared.methods.IBankAPI;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.data.TravelItems;
import org.gusta.mixology.stats.MixologyStats;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class AldariumRewardService {
    private static final String ALDARIUM = "Aldarium";
    private static final int REWARD_LALO_ID = 13921;
    private static final Tile REWARD_LALO_TILE = new Tile(1395, 9311, 0);
    private static final int REWARD_SHOP_GROUP = 819;
    private static final int REWARD_LIST_VIEWPORT_CHILD = 15;
    private static final int REWARD_LIST_CHILD = 34;
    private static final int REWARD_LIST_SCROLLER_CHILD = 35;
    private static final int ALDARIUM_LIST_WIDGET_CHILD = 132;
    private static final int BUY_50_CHILD = 25;
    private static final int ALDARIUM_MOX_COST = 80;
    private static final int ALDARIUM_AGA_COST = 60;
    private static final int ALDARIUM_LYE_COST = 90;
    private static final int GE_MIN_X = 3150;
    private static final int GE_MAX_X = 3190;
    private static final int GE_MIN_Y = 3465;
    private static final int GE_MAX_Y = 3505;
    private static final Tile GRAND_EXCHANGE_WALK_TILE = new Tile(3164, 3487, 0);
    private static final int MAX_REWARD_OPEN_ATTEMPTS = 10;
    private static final int REWARD_SCROLL_ATTEMPTS = 4;
    private static final int REWARD_SCROLL_BATCH = 3;
    private static final int MAX_ALDARIUM_SELECTION_CLICKS = 2;
    private static final long ALDARIUM_SELECTION_CONFIRM_TIMEOUT_MILLIS = 2_500L;
    private static final int MAX_SELL_REPRICE_ATTEMPTS = 2;
    private static final int MIN_SELL_REPRICE_DELAY_MILLIS = 10_000;
    private static final int MAX_SELL_REPRICE_DELAY_MILLIS = 20_000;
    private static final int MIN_SELL_MARKDOWN_PERCENT = 10;
    private static final int MAX_SELL_MARKDOWN_PERCENT = 15;

    private final MixologySettings settings;
    private final MixologyStats stats;
    private final GePricingService pricing;

    private int rewardOpenAttempts;
    private boolean bankCheckedForAldarium;
    private boolean sellOfferPlaced;
    private int currentSellPrice;
    private long nextSellCollectAt;
    private long sellOfferPlacedAt;
    private long sellOfferRepriceAt;
    private int sellRepriceAttempts;
    private boolean pendingSellRelist;
    private long nextRingTeleportAttemptAt;
    private long nextDiagnosticAt;
    private boolean geCheckedForAldariumOffer;
    private int rewardListScrollsForClaim;
    private int rewardListNoProgressAttempts;
    private boolean rewardListScrolledForAldarium;
    private boolean aldariumSelectionAttempted;
    private int aldariumSelectionClicks;
    private long aldariumSelectionAttemptedAt;
    private boolean aldariumSelectedForClaim;
    private boolean depositClaimedAldarium;

    public AldariumRewardService(
            MixologySettings settings,
            MixologyStats stats,
            GePricingService pricing
    ) {
        this.settings = settings;
        this.stats = stats;
        this.pricing = pricing;
    }

    public void resetForRestock() {
        rewardOpenAttempts = 0;
        bankCheckedForAldarium = false;
        sellOfferPlaced = false;
        currentSellPrice = 0;
        nextSellCollectAt = 0L;
        sellOfferPlacedAt = 0L;
        sellOfferRepriceAt = 0L;
        sellRepriceAttempts = 0;
        pendingSellRelist = false;
        nextRingTeleportAttemptAt = 0L;
        geCheckedForAldariumOffer = false;
        rewardListScrollsForClaim = 0;
        rewardListNoProgressAttempts = 0;
        rewardListScrolledForAldarium = false;
        aldariumSelectionAttempted = false;
        aldariumSelectionClicks = 0;
        aldariumSelectionAttemptedAt = 0L;
        aldariumSelectedForClaim = false;
        depositClaimedAldarium = false;
    }

    public boolean claimAldarium(APIContext ctx) {
        if (depositClaimedAldarium) {
            return depositClaimedAldariumInSocietyBank(ctx);
        }
        if (ctx.bank().isOpen()) {
            stats.setStatus("Closing bank before Aldarium reward claim");
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return false;
        }
        if (ctx.grandExchange().isOpen()) {
            stats.setStatus("Closing GE before Aldarium reward claim");
            ctx.grandExchange().close();
            Time.sleep(500, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }
        if (isRewardShopOpen(ctx)) {
            return buyAldariumFromOpenShop(ctx);
        }

        rewardListScrollsForClaim = 0;
        rewardListNoProgressAttempts = 0;
        rewardListScrolledForAldarium = false;
        aldariumSelectionAttempted = false;
        aldariumSelectionClicks = 0;
        aldariumSelectionAttemptedAt = 0L;
        aldariumSelectedForClaim = false;
        depositClaimedAldarium = false;

        if (!settings.isAlchemicalSocietyTile(ctx.localPlayer().getLocation())) {
            stats.setStatus("Not in Alchemical Society for Aldarium claim; waiting for travel back");
            return false;
        }

        if (rewardOpenAttempts >= MAX_REWARD_OPEN_ATTEMPTS) {
            stats.setStatus("Aldarium reward shop did not open after "
                    + rewardOpenAttempts + " attempts; retrying claim before restock");
            logRewardNpcDiagnostic(ctx);
            rewardOpenAttempts = 0;
            return false;
        }

        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus("Moving to Supervisor Lalo rewards NPC");
            Time.sleep(650, 1000);
            return false;
        }

        NPC lalo = findRewardLalo(ctx);
        if (lalo == null || !lalo.isValid() || lalo.tileDistanceTo(ctx) > 7) {
            stats.setStatus("Walking to Supervisor Lalo rewards tile 1395,9311,0");
            boolean walking = REWARD_LALO_TILE.tileDistanceTo(ctx) <= 12
                    && ctx.walking().walkOnScreen(REWARD_LALO_TILE);
            if (!walking) {
                stats.setStatus("Local walk to Supervisor Lalo rewards failed; adjusting camera once");
                ctx.camera().turnTo(REWARD_LALO_TILE);
                Time.sleep(350, 650);
                walking = ctx.walking().walkOnScreen(REWARD_LALO_TILE);
                if (!walking) {
                    stats.setStatus("Camera-assisted walk to Supervisor Lalo rewards failed; minimap fallback");
                    ctx.walking().walkTo(REWARD_LALO_TILE);
                }
            }
            Time.sleep(900, 1400,
                    () -> REWARD_LALO_TILE.tileDistanceTo(ctx) <= 6 || ctx.localPlayer().isMoving(),
                    100);
            return false;
        }

        rewardOpenAttempts++;
        stats.setStatus("Opening Mixology Rewards with Lalo id="
                + lalo.getId() + " tile=" + lalo.getLocation());
        ctx.camera().turnTo(lalo);
        boolean clicked = lalo.interact("Rewards", "Supervisor Lalo")
                || lalo.interact("Rewards")
                || ctx.menu().interact("Rewards", "Supervisor Lalo", lalo, true)
                || ctx.menu().interact("Rewards", lalo, true)
                || ctx.menu().interact("Rewards", lalo, false);
        if (!clicked) {
            stats.setStatus("Could not click Lalo Rewards; attempt=" + rewardOpenAttempts);
            Time.sleep(650, 1000);
            return false;
        }

        Time.sleep(1000, 1700,
                () -> ctx.store().isOpen() || hasMixologyRewardShopWidget(ctx),
                100);
        return false;
    }

    public boolean sellAldariumBeforeRestock(APIContext ctx) {
        if (isRewardShopOpen(ctx)) {
            stats.setStatus("Closing reward shop before Aldarium GE sale");
            closeRewardShop(ctx);
            return false;
        }

        int inventoryAldarium = inventoryCount(ctx, ALDARIUM);
        if (inventoryAldarium <= 0 && !bankCheckedForAldarium) {
            return withdrawAldariumFromBank(ctx);
        }
        if (inventoryAldarium <= 0 && !geCheckedForAldariumOffer) {
            return checkGrandExchangeForAldariumBeforeHerbs(ctx);
        }

        GrandExchangeSlot activeSlot = findAldariumOffer(ctx);
        if (activeSlot != null || sellOfferPlaced) {
            return handleExistingSellOffer(ctx, activeSlot);
        }

        if (inventoryAldarium <= 0) {
            stats.setStatus("Aldarium sale audit clear before restock");
            return true;
        }

        if (ctx.bank().isOpen()) {
            stats.setStatus("Closing bank before Aldarium GE sale");
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return false;
        }
        if (!isAtGrandExchange(ctx)) {
            if (tryRingOfWealthTeleport(ctx)) {
                return false;
            }
            stats.setStatus("Walking to GE to sell " + inventoryAldarium + "x Aldarium");
            ctx.webWalking().setUseTeleports(true);
            ctx.webWalking().walkTo(GRAND_EXCHANGE_WALK_TILE);
            Time.sleep(1200, 1800);
            return false;
        }
        if (!ctx.grandExchange().isOpen()) {
            stats.setStatus("Opening GE to sell Aldarium");
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return false;
        }

        collectGeToBank(ctx);

        inventoryAldarium = inventoryCount(ctx, ALDARIUM);
        if (inventoryAldarium <= 0) {
            bankCheckedForAldarium = false;
            stats.setStatus("Aldarium inventory empty after GE collect; rechecking bank before restock");
            return false;
        }

        if (currentSellPrice <= 0 || sellRepriceAttempts <= 0) {
            currentSellPrice = Math.max(1, pricing.aldariumRealtimePrice(ctx, 6_000L));
        }
        stats.setAldariumUnitPrice(currentSellPrice);
        stats.setStatus("Selling " + inventoryAldarium + "x Aldarium at "
                + currentSellPrice + " each before herb restock"
                + (sellRepriceAttempts > 0
                ? " (reprice " + sellRepriceAttempts + "/" + MAX_SELL_REPRICE_ATTEMPTS + ")"
                : ""));
        boolean placed = ctx.grandExchange().placeSellOffer(ALDARIUM, inventoryAldarium, currentSellPrice);
        if (!placed) {
            stats.setStatus("Aldarium sell offer was not placed; retrying");
            Time.sleep(900, 1400);
            return false;
        }

        sellOfferPlaced = true;
        pendingSellRelist = false;
        sellOfferPlacedAt = System.currentTimeMillis();
        sellOfferRepriceAt = sellOfferPlacedAt + randomSellRepriceDelayMillis();
        nextSellCollectAt = System.currentTimeMillis() + 5_000L;
        stats.debug("Aldarium sell offer accepted: price=" + currentSellPrice
                + " repriceAttempt=" + sellRepriceAttempts
                + " repriceAfter=" + (sellOfferRepriceAt - sellOfferPlacedAt) + "ms");
        Time.sleep(1000, 1500);
        return false;
    }

    private boolean buyAldariumFromOpenShop(APIContext ctx) {
        ResinBalance balance = readResinBalance(ctx);
        if (balance == null) {
            stats.setStatus("Could not read reward resin balance; skipping Aldarium claim safely");
            closeRewardShop(ctx);
            return true;
        }
        int trigger = stats.aldariumLyeTrigger();
        if (balance.lye <= trigger) {
            stats.setStatus("Lye resin below Aldarium trigger "
                    + stats.aldariumTriggerText()
                    + ": " + balance.summary());
            closeRewardShop(ctx);
            return true;
        }

        WidgetChild directBuyFifty = findAldariumBuyFiftyActionWidget(ctx);
        if (directBuyFifty != null) {
            stats.setStatus("Buying visible Aldarium directly with Buy-50");
            int bought = buyAldariumWithBuyFiftyUntilLyeBelowCost(ctx);
            if (bought > 0 || readLyeResin(ctx) < ALDARIUM_LYE_COST) {
                return finishAldariumClaim(ctx, bought, balance);
            }
            stats.debug("Direct Aldarium Buy-50 was not confirmed; falling back to item selection");
        }

        if (!aldariumSelectedForClaim) {
            aldariumSelectedForClaim = confirmAldariumSelected(ctx);
        }
        if (!aldariumSelectedForClaim && aldariumSelectionAttempted) {
            long elapsed = System.currentTimeMillis() - aldariumSelectionAttemptedAt;
            if (elapsed < ALDARIUM_SELECTION_CONFIRM_TIMEOUT_MILLIS) {
                stats.setStatus("Waiting for Aldarium details panel confirmation");
                Time.sleep(350, 600);
                return false;
            }
            if (aldariumSelectionClicks >= MAX_ALDARIUM_SELECTION_CLICKS) {
                stats.setStatus("Aldarium selection was not confirmed; reopening reward shop safely");
                logAldariumSelectionDiagnostic(ctx);
                closeRewardShop(ctx);
                return false;
            }
            aldariumSelectionAttempted = false;
            aldariumSelectionAttemptedAt = 0L;
        }
        if (!aldariumSelectedForClaim && !aldariumSelectionAttempted) {
            WidgetChild aldarium = ensureAldariumVisible(ctx);
            if (aldarium == null || !aldarium.isValid() || !aldarium.isVisible()) {
                if (rewardListScrolledForAldarium) {
                    stats.setStatus("Aldarium was not resolved after verified scrolling; reopening reward shop");
                    logRewardWidgetDiagnostic(ctx);
                    closeRewardShop(ctx);
                }
                return false;
            }

            if (!clickAldariumOnce(ctx, aldarium)) {
                stats.setStatus("Could not click Aldarium in the left reward list; retrying");
                return false;
            }
            stats.setStatus("Waiting for Aldarium details panel after one selection click");
            Time.sleep(650, 1000, () -> confirmAldariumSelected(ctx), 100);
            aldariumSelectedForClaim = confirmAldariumSelected(ctx);
        }
        if (!aldariumSelectedForClaim) {
            stats.setStatus("Waiting for Aldarium details panel after one selection click");
            logAldariumSelectionDiagnostic(ctx);
            return false;
        }

        int bought = buyAldariumWithBuyFiftyUntilLyeBelowCost(ctx);
        return finishAldariumClaim(ctx, bought, balance);
    }

    private boolean finishAldariumClaim(APIContext ctx, int bought, ResinBalance initialBalance) {
        ResinBalance finalBalance = readResinBalance(ctx);
        stats.setStatus("Aldarium reward claim complete: bought=" + bought
                + " from " + initialBalance.summary()
                + (finalBalance == null ? "" : " to " + finalBalance.summary()));
        closeRewardShop(ctx);
        if (inventoryCount(ctx, ALDARIUM) > 0) {
            depositClaimedAldarium = true;
            return false;
        }
        return true;
    }

    private boolean isRewardShopOpen(APIContext ctx) {
        return ctx.store().isOpen() || hasMixologyRewardShopWidget(ctx);
    }

    private boolean closeRewardShop(APIContext ctx) {
        if (!isRewardShopOpen(ctx)) {
            resetRewardShopSelectionState();
            return true;
        }

        for (int attempt = 1; attempt <= 4; attempt++) {
            stats.setStatus("Closing Mixology reward shop attempt " + attempt);
            boolean clicked = clickRewardShopCloseWidget(ctx);
            if (!clicked && ctx.store().isOpen()) {
                ctx.store().close();
            }
            Time.sleep(350, 600, () -> !isRewardShopOpen(ctx), 100);
            if (!isRewardShopOpen(ctx)) {
                resetRewardShopSelectionState();
                return true;
            }

            ctx.widgets().closeInterface();
            Time.sleep(350, 600, () -> !isRewardShopOpen(ctx), 100);
            if (!isRewardShopOpen(ctx)) {
                resetRewardShopSelectionState();
                return true;
            }

            ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            Time.sleep(350, 650, () -> !isRewardShopOpen(ctx), 100);
            if (!isRewardShopOpen(ctx)) {
                resetRewardShopSelectionState();
                return true;
            }

            if (minimapWalkToCloseRewardShop(ctx)) {
                resetRewardShopSelectionState();
                return true;
            }
        }

        stats.setStatus("Reward shop close failed; retrying before Aldarium banking");
        logRewardWidgetDiagnostic(ctx);
        return false;
    }

    private boolean clickRewardShopCloseWidget(APIContext ctx) {
        WidgetChild close = findRewardShopCloseWidget(ctx);
        return close != null
                && close.isValid()
                && (close.interact("Close")
                || close.click()
                || ctx.mouse().click(close, false));
    }

    private WidgetChild findRewardShopCloseWidget(APIContext ctx) {
        Rectangle closeZone = rewardShopCloseZone(ctx);
        WidgetChild fallback = null;
        for (WidgetChild widget : ctx.widgets().getAllChildren(widget -> widget != null
                && widget.isValid()
                && widgetGroup(widget) == REWARD_SHOP_GROUP
                && isVisibleWidget(widget))) {
            if (widgetMentionsClose(widget) || widgetHasAction(widget, "Close")) {
                return widget;
            }
            if (closeZone != null && widgetCenterInBounds(widget, closeZone)) {
                fallback = widget;
            }
        }
        return fallback;
    }

    private Rectangle rewardShopCloseZone(APIContext ctx) {
        Rectangle shop = rewardShopBounds(ctx);
        if (shop == null || shop.width <= 0 || shop.height <= 0) {
            return null;
        }
        return new Rectangle(shop.x + shop.width - 54, shop.y, 54, 54);
    }

    private boolean widgetMentionsClose(WidgetChild widget) {
        String text = (widgetText(widget) + " " + widget.getName()).toLowerCase(Locale.ROOT);
        return text.contains("close");
    }

    private boolean minimapWalkToCloseRewardShop(APIContext ctx) {
        stats.setStatus("Reward shop still open; minimap walk fallback");
        Tile target = settings.mixingRoomCenterTile();
        boolean walking = ctx.walking().walkTo(target);
        Time.sleep(800, 1300,
                () -> !isRewardShopOpen(ctx)
                        || ctx.localPlayer().isMoving()
                        || target.tileDistanceTo(ctx) <= 2,
                100);
        return !isRewardShopOpen(ctx) || walking && ctx.localPlayer().isMoving();
    }

    private void resetRewardShopSelectionState() {
        rewardListScrollsForClaim = 0;
        rewardListNoProgressAttempts = 0;
        rewardListScrolledForAldarium = false;
        aldariumSelectionAttempted = false;
        aldariumSelectionClicks = 0;
        aldariumSelectionAttemptedAt = 0L;
        aldariumSelectedForClaim = false;
    }

    private boolean clickBuyButton(WidgetChild button, String action) {
        return button != null
                && button.isValid()
                && button.isVisible()
                && (button.interact(action) || button.click());
    }

    private boolean clickAldariumOnce(APIContext ctx, WidgetChild aldarium) {
        if (!isAldariumClickableInRewardList(ctx, aldarium)) {
            stats.setStatus("Aldarium widget is outside left reward list click area; retrying scroll");
            rewardListScrollsForClaim = 0;
            rewardListScrolledForAldarium = false;
            return false;
        }

        int selectedBefore = safeVarp(ctx, VarPlayerID.OMNISHOP_SELECTED_ID);
        stats.setStatus("Selecting Aldarium once in Mixology reward shop");
        boolean clicked = aldarium.interact("Select", ALDARIUM)
                || aldarium.interact("Select")
                || clickAldariumInsideRewardList(ctx, aldarium);
        if (clicked) {
            aldariumSelectionAttempted = true;
            aldariumSelectionClicks++;
            aldariumSelectionAttemptedAt = System.currentTimeMillis();
            Time.sleep(450, 750, () -> isAldariumSelected(ctx), 100);
            stats.debug("Aldarium selection click accepted: itemId=" + safeItemId(aldarium)
                    + " selectedVarp=" + selectedBefore + "->"
                    + safeVarp(ctx, VarPlayerID.OMNISHOP_SELECTED_ID));
        }
        return clicked;
    }

    private boolean confirmAldariumSelected(APIContext ctx) {
        boolean selected = isAldariumSelected(ctx);
        if (selected) {
            aldariumSelectedForClaim = true;
        }
        return selected;
    }

    private boolean isAldariumSelected(APIContext ctx) {
        int selectedItemId = safeVarp(ctx, VarPlayerID.OMNISHOP_SELECTED_ID);
        String rightText = rightRewardPanelText(ctx).toLowerCase(Locale.ROOT);
        return selectedItemId == ItemID.ALDARIUM
                || rightText.contains("aldarium")
                || findRightPanelAldariumWidget(ctx) != null;
    }

    private WidgetChild ensureAldariumVisible(APIContext ctx) {
        if (ctx.localPlayer().isMoving() || ctx.localPlayer().isAnimating()) {
            stats.setStatus("Waiting until stable before Aldarium reward selection");
            Time.sleep(500, 800);
            return null;
        }

        WidgetChild aldarium = findAldariumShopWidget(ctx);
        if (isAldariumClickableInRewardList(ctx, aldarium)) {
            rewardListNoProgressAttempts = 0;
            return aldarium;
        }

        if (rewardListScrollsForClaim >= REWARD_SCROLL_ATTEMPTS
                || rewardListNoProgressAttempts >= 2) {
            rewardListScrolledForAldarium = true;
            return null;
        }

        WidgetChild rewardList = ctx.widgets().get(REWARD_SHOP_GROUP, REWARD_LIST_CHILD);
        String beforeFingerprint = rewardListFingerprint(ctx);
        stats.setStatus("Scrolling Mixology Rewards to Aldarium "
                + (rewardListScrollsForClaim + 1) + "/" + REWARD_SCROLL_ATTEMPTS);

        boolean requested;
        if (rewardListNoProgressAttempts == 0) {
            requested = scrollRewardListDown(ctx);
        } else {
            requested = scrollRewardScrollbarDown(ctx);
        }
        if (!requested) {
            stats.debug("Mixology reward list controls were unavailable for scrolling");
        }

        Time.sleep(550, 900, () -> {
            WidgetChild freshAldarium = findAldariumShopWidget(ctx);
            return isAldariumClickableInRewardList(ctx, freshAldarium)
                    || !beforeFingerprint.equals(rewardListFingerprint(ctx));
        }, 100);

        WidgetChild freshAldarium = findAldariumShopWidget(ctx);
        if (isAldariumClickableInRewardList(ctx, freshAldarium)) {
            rewardListNoProgressAttempts = 0;
            return freshAldarium;
        }

        String afterFingerprint = rewardListFingerprint(ctx);
        if (!beforeFingerprint.equals(afterFingerprint)) {
            rewardListScrollsForClaim++;
            rewardListNoProgressAttempts = 0;
        } else {
            rewardListNoProgressAttempts++;
            stats.debug("Mixology reward list did not move while searching for Aldarium; noProgress="
                    + rewardListNoProgressAttempts + "/2");
        }
        return null;
    }

    private boolean scrollRewardListDown(APIContext ctx) {
        Rectangle bounds = rewardListViewportBounds(ctx);
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return false;
        }

        Point target = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
        stats.debug("Scrolling Mixology reward list with mouse wheel at " + target
                + " viewport=" + bounds);
        ctx.mouse().move(target);
        Time.sleep(120, 240);
        ctx.mouse().scroll(false, REWARD_SCROLL_BATCH);
        return true;
    }

    private boolean scrollRewardScrollbarDown(APIContext ctx) {
        WidgetChild scroller = ctx.widgets().get(REWARD_SHOP_GROUP, REWARD_LIST_SCROLLER_CHILD);
        Rectangle bounds = safeBounds(scroller);
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return false;
        }

        int inset = Math.max(4, Math.min(10, bounds.height / 8));
        Point target = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height - inset);
        stats.debug("Reward list wheel made no progress; clicking scrollbar down control at " + target
                + " scroller=" + bounds);
        ctx.mouse().move(target);
        Time.sleep(120, 240);
        return ctx.mouse().click(target, false);
    }

    private String rewardListFingerprint(APIContext ctx) {
        WidgetChild rewardList = ctx.widgets().get(REWARD_SHOP_GROUP, REWARD_LIST_CHILD);
        Rectangle listBounds = rewardListViewportBounds(ctx);
        StringBuilder fingerprint = new StringBuilder();
        if (rewardList != null && rewardList.isValid()) {
            fingerprint.append("scroll=").append(rewardList.getScrollY());
        }
        if (listBounds == null) {
            return fingerprint.toString();
        }
        List<WidgetChild> visibleWidgets = new ArrayList<>(ctx.widgets().getAllChildren(candidate -> candidate != null
                && candidate.isValid()
                && candidate.isVisible()
                && widgetGroup(candidate) == REWARD_SHOP_GROUP));
        List<String> entries = new ArrayList<>();
        for (WidgetChild widget : visibleWidgets) {
            try {
                Rectangle bounds = safeBounds(widget);
                if (bounds == null || !listBounds.intersects(bounds)) {
                    continue;
                }
                entries.add(widget.getChildId() + ":"
                        + widget.getIndex() + ":"
                        + bounds.x + "," + bounds.y + ":"
                        + widgetTextAndName(widget));
            } catch (RuntimeException ignored) {
                // The reward shop can replace individual children while scrolling.
            }
        }
        entries.sort(String::compareTo);
        for (String entry : entries) {
            fingerprint.append('|').append(entry);
        }
        return fingerprint.toString();
    }

    private Rectangle rewardListViewportBounds(APIContext ctx) {
        WidgetChild viewport = ctx.widgets().get(REWARD_SHOP_GROUP, REWARD_LIST_VIEWPORT_CHILD);
        Rectangle bounds = safeBounds(viewport);
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            return bounds;
        }

        WidgetChild rewardList = ctx.widgets().get(REWARD_SHOP_GROUP, REWARD_LIST_CHILD);
        bounds = safeBounds(rewardList);
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            return bounds;
        }
        return null;
    }

    private Rectangle rewardShopBounds(APIContext ctx) {
        Rectangle bounds = null;
        for (WidgetChild widget : ctx.widgets().getAllChildren(widget -> widget != null
                && widget.isValid()
                && widget.isVisible()
                && widget.getWidth() > 0
                && widget.getHeight() > 0
                && widgetGroup(widget) == REWARD_SHOP_GROUP)) {
            Rectangle widgetBounds = new Rectangle(
                    widget.getAbsoluteX(),
                    widget.getAbsoluteY(),
                    widget.getWidth(),
                    widget.getHeight());
            bounds = bounds == null ? widgetBounds : bounds.union(widgetBounds);
        }
        return bounds;
    }

    private int buyAldariumWithBuyFiftyUntilLyeBelowCost(APIContext ctx) {
        int bought = 0;
        int misses = 0;
        while (misses < 2) {
            ResinBalance beforeBalance = readResinBalance(ctx);
            if (beforeBalance == null) {
                stats.setStatus("Lost readable resin balance during Aldarium claim; stopping safely");
                break;
            }
            if (beforeBalance.lye < ALDARIUM_LYE_COST) {
                stats.setStatus("Lye resin below Aldarium cost: " + beforeBalance.summary());
                break;
            }

            int beforeInventory = inventoryCount(ctx, ALDARIUM);
            stats.setStatus("Buying Aldarium with Buy-50; " + beforeBalance.summary());
            if (!buyFiftyAldarium(ctx)) {
                misses++;
                continue;
            }

            Time.sleep(700, 1100,
                    () -> inventoryCount(ctx, ALDARIUM) > beforeInventory
                            || readLyeResin(ctx) < beforeBalance.lye,
                    100);

            int gained = Math.max(0, inventoryCount(ctx, ALDARIUM) - beforeInventory);
            int afterLye = readLyeResin(ctx);
            if (gained <= 0 && afterLye >= beforeBalance.lye) {
                stats.setStatus("Aldarium Buy-50 did not change inventory/resin; stopping claim");
                misses++;
                continue;
            }

            bought += gained;
            misses = 0;
            Time.sleep(250, 500);
        }
        return bought;
    }

    private int readLyeResin(APIContext ctx) {
        ResinBalance balance = readResinBalance(ctx);
        return balance == null ? Integer.MAX_VALUE : balance.lye;
    }

    private boolean buyFiftyAldarium(APIContext ctx) {
        WidgetChild aldariumBuyFifty = findAldariumBuyFiftyActionWidget(ctx);
        if (aldariumBuyFifty != null) {
            stats.debug("Using Aldarium card context action Buy-50: " + widgetSummary(aldariumBuyFifty));
            if (buyFiftyFromRandomCardPoint(ctx, aldariumBuyFifty)) {
                return true;
            }
        }

        if (!aldariumSelectedForClaim && !confirmAldariumSelected(ctx)) {
            return false;
        }
        WidgetChild button = findBuyFiftyButton(ctx);
        return clickBuyButton(button, "Buy-50")
                || ctx.store().buyFifty(ALDARIUM);
    }

    private boolean buyFiftyFromRandomCardPoint(APIContext ctx, WidgetChild card) {
        Rectangle bounds = safeBounds(card);
        Rectangle viewport = rewardListViewportBounds(ctx);
        if (bounds == null || viewport == null) {
            return card.interact("Buy-50", ALDARIUM)
                    || card.interact("Buy-50");
        }

        Rectangle clickArea = bounds.intersection(viewport);
        if (clickArea.width < 12 || clickArea.height < 12) {
            return card.interact("Buy-50", ALDARIUM)
                    || card.interact("Buy-50");
        }

        int insetX = Math.max(6, Math.min(18, clickArea.width / 5));
        int insetY = Math.max(6, Math.min(14, clickArea.height / 5));
        int minX = clickArea.x + insetX;
        int maxX = clickArea.x + clickArea.width - insetX - 1;
        int minY = clickArea.y + insetY;
        int maxY = clickArea.y + clickArea.height - insetY - 1;
        if (maxX <= minX || maxY <= minY) {
            return card.interact("Buy-50", ALDARIUM)
                    || card.interact("Buy-50");
        }

        Point target = new Point(
                ThreadLocalRandom.current().nextInt(minX, maxX + 1),
                ThreadLocalRandom.current().nextInt(minY, maxY + 1));
        stats.debug("Opening Aldarium context menu at varied card point " + target
                + " safeArea=" + clickArea);
        ctx.mouse().move(target);
        Time.sleep(140, 280);
        if (!ctx.mouse().click(target, true)) {
            return false;
        }

        Time.sleep(300, 600, () -> ctx.menu().isOpen(), 50);
        if (!ctx.menu().isOpen()) {
            return false;
        }

        boolean selected = ctx.menu().interact("Buy-50", ALDARIUM, true)
                || ctx.menu().interact("Buy-50", ALDARIUM, false)
                || ctx.menu().interact("Buy-50", true)
                || ctx.menu().interact("Buy-50", false);
        if (!selected && ctx.menu().isOpen()) {
            ctx.menu().closeMenu();
        }
        return selected;
    }

    private boolean depositClaimedAldariumInSocietyBank(APIContext ctx) {
        if (isRewardShopOpen(ctx)) {
            stats.setStatus("Closing reward shop before banking claimed Aldarium");
            closeRewardShop(ctx);
            return false;
        }
        if (ctx.grandExchange().isOpen()) {
            stats.setStatus("Closing GE before banking claimed Aldarium");
            ctx.grandExchange().close();
            Time.sleep(500, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }

        int carried = inventoryCount(ctx, ALDARIUM);
        if (carried <= 0) {
            if (ctx.bank().isOpen()) {
                stats.recordBankedAldarium(bankCount(ctx, ALDARIUM), "minigame bank after Aldarium claim");
                ctx.bank().close();
                Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            }
            depositClaimedAldarium = false;
            return true;
        }

        if (!settings.isAlchemicalSocietyTile(ctx.localPlayer().getLocation())) {
            stats.setStatus("Waiting to bank claimed Aldarium at the minigame bank");
            return false;
        }

        if (!ctx.bank().isOpen()) {
            stats.setStatus("Opening minigame bank to store " + carried + "x Aldarium");
            if (!ctx.bank().isReachable()) {
                ctx.webWalking().setUseTeleports(true);
                ctx.webWalking().walkToBank();
                Time.sleep(1000, 1600);
                return false;
            }
            BankOpenService.open(ctx, stats, "Opening minigame bank to store Aldarium");
            return false;
        }

        stats.recordBankedAldarium(bankCount(ctx, ALDARIUM), "minigame bank before Aldarium deposit");
        stats.setStatus("Depositing claimed Aldarium in minigame bank: " + carried);
        boolean deposited = ctx.bank().depositAll(ALDARIUM)
                || ctx.bank().deposit(carried, ALDARIUM);
        Time.sleep(700, 1100, () -> inventoryCount(ctx, ALDARIUM) < carried, 100);
        int remaining = inventoryCount(ctx, ALDARIUM);
        stats.recordBankedAldarium(bankCount(ctx, ALDARIUM), "minigame bank after Aldarium deposit");
        if (!deposited || remaining > 0) {
            stats.setStatus("Aldarium deposit not confirmed; retrying remaining=" + remaining);
            return false;
        }

        ctx.bank().close();
        Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
        depositClaimedAldarium = false;
        return true;
    }

    private boolean withdrawAldariumFromBank(APIContext ctx) {
        if (ctx.grandExchange().isOpen()) {
            ctx.grandExchange().close();
            Time.sleep(500, 900, () -> !ctx.grandExchange().isOpen(), 100);
            return false;
        }
        if (!ctx.bank().isOpen()) {
            stats.setStatus("Opening bank to check for Aldarium before restock sale");
            if (!ctx.bank().isReachable()) {
                ctx.webWalking().setUseTeleports(true);
                ctx.webWalking().walkToBank();
                Time.sleep(1200, 1800);
                return false;
            }
            BankOpenService.open(ctx, stats, "Opening bank to check for Aldarium before restock sale");
            return false;
        }

        int bankCount = bankCount(ctx, ALDARIUM);
        stats.recordBankedAldarium(bankCount, "GE restock bank check");
        if (bankCount <= 0) {
            bankCheckedForAldarium = true;
            stats.setStatus("No banked Aldarium before restock");
            ctx.bank().close();
            Time.sleep(500, 900);
            return false;
        }

        bankCheckedForAldarium = false;
        geCheckedForAldariumOffer = false;

        if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE)) {
            stats.setStatus("Selecting noted withdraw mode for Aldarium sale");
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.NOTE);
            Time.sleep(600, 900, () -> ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE), 100);
        }
        if (!ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE)) {
            stats.setStatus("Could not select noted mode for Aldarium sale; retrying");
            return false;
        }

        stats.setStatus("Withdrawing " + bankCount + "x noted Aldarium for GE sale");
        boolean withdrew = ctx.bank().withdrawAll(ALDARIUM)
                || ctx.bank().withdraw(Math.max(1, bankCount), ALDARIUM);
        if (withdrew) {
            Time.sleep(500, 900, () -> inventoryCount(ctx, ALDARIUM) > 0, 100);
        }
        int inventoryAfterWithdraw = inventoryCount(ctx, ALDARIUM);
        int bankAfterWithdraw = bankCount(ctx, ALDARIUM);
        stats.recordBankedAldarium(bankAfterWithdraw, "withdrew Aldarium for GE sale");
        if (!withdrew || inventoryAfterWithdraw <= 0) {
            bankCheckedForAldarium = false;
            stats.setStatus("Aldarium noted withdraw not confirmed; retrying bank scan"
                    + " bank=" + bankAfterWithdraw
                    + " inventory=" + inventoryAfterWithdraw);
            return false;
        }
        bankCheckedForAldarium = bankAfterWithdraw <= 0;
        if (ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.NOTE)) {
            ctx.bank().selectWithdrawMode(IBankAPI.WithdrawMode.ITEM);
            Time.sleep(400, 700, () -> ctx.bank().isWithdrawMode(IBankAPI.WithdrawMode.ITEM), 100);
        }
        ctx.bank().close();
        Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
        return false;
    }

    private boolean handleExistingSellOffer(APIContext ctx, GrandExchangeSlot slot) {
        if (!isAtGrandExchange(ctx)) {
            if (tryRingOfWealthTeleport(ctx)) {
                return false;
            }
            stats.setStatus("Walking to GE to monitor Aldarium sale");
            ctx.webWalking().setUseTeleports(true);
            ctx.webWalking().walkTo(GRAND_EXCHANGE_WALK_TILE);
            Time.sleep(1200, 1800);
            return false;
        }
        if (!ctx.grandExchange().isOpen()) {
            stats.setStatus("Opening GE to monitor Aldarium sale");
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return false;
        }

        slot = findAldariumOffer(ctx);
        if (slot == null) {
            collectGeToBank(ctx);
            if (pendingSellRelist) {
                sellOfferPlaced = false;
                bankCheckedForAldarium = false;
                stats.setStatus("Aborted Aldarium offer cleared; recovering items for lower relist");
                return false;
            }
            clearCompletedSellState();
            geCheckedForAldariumOffer = true;
            bankCheckedForAldarium = false;
            stats.setStatus("Aldarium GE offer clear; auditing bank before restock");
            return false;
        }

        if (slot.isCompleted() || slot.canCollect()) {
            stats.setStatus("Collecting completed Aldarium sale to bank");
            collectGeToBank(ctx);
            Time.sleep(700, 1100);
            if (findAldariumOffer(ctx) == null) {
                if (pendingSellRelist) {
                    sellOfferPlaced = false;
                    bankCheckedForAldarium = false;
                    stats.setStatus("Aborted Aldarium offer collected; preparing lower relist");
                    return false;
                }
                clearCompletedSellState();
                geCheckedForAldariumOffer = true;
                bankCheckedForAldarium = false;
                stats.setStatus("Aldarium sale collected; auditing bank before restock");
                return false;
            }
            return false;
        }

        if (sellOfferPlacedAt <= 0L || sellOfferRepriceAt <= 0L || currentSellPrice <= 0) {
            GrandExchangeOffer offer = slot.getOffer();
            int offerPrice = offer == null ? 0 : offer.getPrice();
            currentSellPrice = offerPrice > 0
                    ? offerPrice
                    : Math.max(1, pricing.aldariumRealtimePrice(ctx, 6_000L));
            sellOfferPlaced = true;
            sellOfferPlacedAt = System.currentTimeMillis();
            sellOfferRepriceAt = sellOfferPlacedAt + randomSellRepriceDelayMillis();
            stats.setAldariumUnitPrice(currentSellPrice);
            stats.debug("Recovered existing Aldarium sell offer monitoring: price="
                    + currentSellPrice
                    + " repriceAfter=" + (sellOfferRepriceAt - sellOfferPlacedAt) + "ms");
        }

        if (System.currentTimeMillis() >= sellOfferRepriceAt
                && sellRepriceAttempts < MAX_SELL_REPRICE_ATTEMPTS
                && currentSellPrice > 1) {
            int markdownPercent = ThreadLocalRandom.current().nextInt(
                    MIN_SELL_MARKDOWN_PERCENT,
                    MAX_SELL_MARKDOWN_PERCENT + 1);
            int reducedPrice = Math.max(1,
                    (int) Math.floor(currentSellPrice * (100 - markdownPercent) / 100.0D));
            stats.setStatus("Aldarium sale slow; aborting and relisting "
                    + markdownPercent + "% lower at " + reducedPrice);
            if (slot.abortOffer()) {
                Time.sleep(900, 1400);
                collectGeToBank(ctx);
                int previousPrice = currentSellPrice;
                currentSellPrice = reducedPrice;
                sellRepriceAttempts++;
                stats.setAldariumUnitPrice(currentSellPrice);
                sellOfferPlaced = false;
                sellOfferPlacedAt = 0L;
                sellOfferRepriceAt = 0L;
                pendingSellRelist = true;
                bankCheckedForAldarium = false;
                stats.debug("Aldarium sell offer repriced: attempt=" + sellRepriceAttempts
                        + "/" + MAX_SELL_REPRICE_ATTEMPTS
                        + " markdown=" + markdownPercent + "%"
                        + " price=" + previousPrice + "->" + currentSellPrice);
            }
            return false;
        }

        if (System.currentTimeMillis() < nextSellCollectAt) {
            Time.sleep(600, 900);
            return false;
        }
        stats.setStatus("Waiting for Aldarium sale before herb restock");
        nextSellCollectAt = System.currentTimeMillis() + 5_000L;
        return false;
    }

    private long randomSellRepriceDelayMillis() {
        return ThreadLocalRandom.current().nextLong(
                MIN_SELL_REPRICE_DELAY_MILLIS,
                MAX_SELL_REPRICE_DELAY_MILLIS + 1L);
    }

    private void clearCompletedSellState() {
        sellOfferPlaced = false;
        currentSellPrice = 0;
        nextSellCollectAt = 0L;
        sellOfferPlacedAt = 0L;
        sellOfferRepriceAt = 0L;
        sellRepriceAttempts = 0;
        pendingSellRelist = false;
    }

    private boolean checkGrandExchangeForAldariumBeforeHerbs(APIContext ctx) {
        if (ctx.bank().isOpen()) {
            stats.setStatus("Closing bank before checking GE for Aldarium offers");
            ctx.bank().close();
            Time.sleep(500, 900, () -> !ctx.bank().isOpen(), 100);
            return false;
        }

        if (!isAtGrandExchange(ctx)) {
            if (tryRingOfWealthTeleport(ctx)) {
                return false;
            }
            stats.setStatus("Checking GE for Aldarium sale before herb restock");
            ctx.webWalking().setUseTeleports(true);
            ctx.webWalking().walkTo(GRAND_EXCHANGE_WALK_TILE);
            Time.sleep(1200, 1800);
            return false;
        }

        if (!ctx.grandExchange().isOpen()) {
            stats.setStatus("Opening GE to check for Aldarium offers before herbs");
            ctx.grandExchange().open();
            Time.sleep(1200, 1800, () -> ctx.grandExchange().isOpen(), 100);
            return false;
        }

        GrandExchangeSlot slot = findAldariumOffer(ctx);
        if (slot != null || sellOfferPlaced) {
            return handleExistingSellOffer(ctx, slot);
        }

        stats.setStatus("Collecting any completed GE offers before herb restock");
        collectGeToBank(ctx);
        Time.sleep(700, 1100);
        slot = findAldariumOffer(ctx);
        if (slot != null) {
            return handleExistingSellOffer(ctx, slot);
        }

        geCheckedForAldariumOffer = true;
        stats.setStatus("No Aldarium GE offer found before herb restock");
        return false;
    }

    private NPC findRewardLalo(APIContext ctx) {
        NPC byId = ctx.npcs()
                .query()
                .id(REWARD_LALO_ID)
                .actions("Rewards")
                .results()
                .nearest();
        if (byId != null && byId.isValid()) {
            return byId;
        }

        return ctx.npcs()
                .query()
                .named("Supervisor Lalo")
                .actions("Rewards")
                .results()
                .nearest();
    }

    private WidgetChild findAldariumShopWidget(APIContext ctx) {
        WidgetChild storeItem = ctx.store().getItem(ALDARIUM);
        if (isVisibleAldariumWidget(storeItem)) {
            return storeItem;
        }

        WidgetChild itemIdWidget = findAldariumItemWidget(ctx);
        if (itemIdWidget != null) {
            return itemIdWidget;
        }

        WidgetChild directAldarium = directAldariumWidget(ctx);
        if (isAldariumClickableInRewardList(ctx, directAldarium)) {
            return directAldarium;
        }

        WidgetChild visibleFallback = null;
        WidgetChild hiddenFallback = null;
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> candidate != null
                && candidate.isValid()
                && widgetGroup(candidate) == REWARD_SHOP_GROUP)) {
            if (!widgetMentionsAldarium(widget)) {
                continue;
            }
            if (isAldariumClickableInRewardList(ctx, widget)) {
                return widget;
            }
            if (widget.isVisible() && visibleFallback == null) {
                visibleFallback = widget;
            } else if (hiddenFallback == null) {
                hiddenFallback = widget;
            }
        }

        if (visibleFallback != null) {
            return visibleFallback;
        }

        if (directAldarium != null && directAldarium.isValid()) {
            return directAldarium;
        }

        if (hiddenFallback != null) {
            return hiddenFallback;
        }

        WidgetChild fallback = null;
        for (WidgetChild widget : ctx.widgets().getAllChildren(widget -> widget != null
                && widget.isValid()
                && widget.isVisible()
                && widgetGroup(widget) == REWARD_SHOP_GROUP)) {
            if (!widgetMentionsAldarium(widget)) {
                continue;
            }
            if (widgetHasAction(widget, "Select")
                    || widgetHasAction(widget, "Buy-50")) {
                return widget;
            }
            if (fallback == null) {
                fallback = widget;
            }
        }

        return fallback;
    }

    private WidgetChild findAldariumBuyFiftyActionWidget(APIContext ctx) {
        WidgetChild aldarium = findAldariumShopWidget(ctx);
        if (aldarium == null || !aldarium.isValid()) {
            return findLastVisibleBuyFiftyRewardAtListBottom(ctx);
        }

        WidgetChild current = aldarium;
        for (int depth = 0; depth < 6 && current != null && current.isValid(); depth++) {
            if (widgetHasAction(current, "Buy-50")
                    && isWidgetInsideRewardList(ctx, current)) {
                return current;
            }
            current = current.getParent();
        }

        WidgetChild descendant = aldarium.find(widget -> widget != null
                && widget.isValid()
                && widgetHasAction(widget, "Buy-50")
                && isWidgetInsideRewardList(ctx, widget));
        if (descendant != null) {
            return descendant;
        }

        Rectangle aldariumBounds = safeBounds(aldarium);
        if (aldariumBounds == null) {
            return null;
        }
        Point aldariumCenter = new Point(
                aldariumBounds.x + aldariumBounds.width / 2,
                aldariumBounds.y + aldariumBounds.height / 2);
        WidgetChild overlappingAction = ctx.widgets().getAllChildren(widget -> widget != null
                        && widget.isValid()
                        && widgetHasAction(widget, "Buy-50")
                        && widgetGroup(widget) == REWARD_SHOP_GROUP
                        && isWidgetInsideRewardList(ctx, widget))
                .stream()
                .filter(widget -> {
                    Rectangle bounds = safeBounds(widget);
                    return bounds != null && (bounds.contains(aldariumCenter)
                            || bounds.intersects(aldariumBounds));
                })
                .findFirst()
                .orElse(null);
        return overlappingAction != null
                ? overlappingAction
                : findLastVisibleBuyFiftyRewardAtListBottom(ctx);
    }

    private WidgetChild findLastVisibleBuyFiftyRewardAtListBottom(APIContext ctx) {
        if (!isRewardListAtBottom(ctx)) {
            return null;
        }

        return ctx.widgets().getAllChildren(widget -> widget != null
                        && widget.isValid()
                        && widget.isVisible()
                        && widgetGroup(widget) == REWARD_SHOP_GROUP
                        && widgetHasAction(widget, "Buy-50")
                        && isWidgetInsideRewardList(ctx, widget))
                .stream()
                .max(Comparator
                        .comparingInt(WidgetChild::getAbsoluteY)
                        .thenComparingInt(WidgetChild::getAbsoluteX))
                .orElse(null);
    }

    private boolean isRewardListAtBottom(APIContext ctx) {
        WidgetChild rewardList = ctx.widgets().get(REWARD_SHOP_GROUP, REWARD_LIST_CHILD);
        Rectangle viewport = rewardListViewportBounds(ctx);
        if (rewardList == null || !rewardList.isValid() || viewport == null) {
            return rewardListNoProgressAttempts > 0;
        }

        int scrollHeight = rewardList.getScrollHeight();
        if (scrollHeight <= 0) {
            return rewardListNoProgressAttempts > 0;
        }
        int maximumScroll = Math.max(0, scrollHeight - viewport.height);
        return maximumScroll == 0
                || rewardList.getScrollY() >= maximumScroll - 4
                || rewardListNoProgressAttempts > 0;
    }

    private boolean isWidgetInsideRewardList(APIContext ctx, WidgetChild widget) {
        Rectangle bounds = safeBounds(widget);
        Rectangle viewport = rewardListViewportBounds(ctx);
        return widget != null
                && widget.isVisible()
                && bounds != null
                && viewport != null
                && !bounds.intersection(viewport).isEmpty();
    }

    private WidgetChild findAldariumItemWidget(APIContext ctx) {
        WidgetChild visibleFallback = null;
        WidgetChild hiddenFallback = null;
        for (WidgetChild widget : ctx.widgets().getAllChildren(candidate -> candidate != null
                && candidate.isValid()
                && widgetGroup(candidate) == REWARD_SHOP_GROUP)) {
            if (safeItemId(widget) != ItemID.ALDARIUM) {
                continue;
            }
            if (isAldariumClickableInRewardList(ctx, widget)) {
                return widget;
            }
            if (widget.isVisible() && visibleFallback == null) {
                visibleFallback = widget;
            } else if (hiddenFallback == null) {
                hiddenFallback = widget;
            }
        }
        return visibleFallback != null ? visibleFallback : hiddenFallback;
    }

    private WidgetChild directAldariumWidget(APIContext ctx) {
        WidgetChild directChild = ctx.widgets().get(REWARD_SHOP_GROUP, ALDARIUM_LIST_WIDGET_CHILD);
        if (directChild != null && directChild.isValid()) {
            return directChild;
        }

        WidgetChild rewardList = ctx.widgets().get(REWARD_SHOP_GROUP, REWARD_LIST_CHILD);
        if (rewardList != null && rewardList.isValid()) {
            WidgetChild nested = rewardList.getChild(ALDARIUM_LIST_WIDGET_CHILD);
            if (nested != null && nested.isValid()) {
                return nested;
            }
        }

        return ctx.widgets().getAllChildren(widget -> widget != null
                && widget.isValid()
                && widgetGroup(widget) == REWARD_SHOP_GROUP
                && (widget.getChildId() & 0xFFFF) == ALDARIUM_LIST_WIDGET_CHILD)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private boolean isVisibleAldariumWidget(WidgetChild widget) {
        return isVisibleWidget(widget)
                && (safeItemId(widget) == ItemID.ALDARIUM || widgetMentionsAldarium(widget));
    }

    private boolean isVisibleWidget(WidgetChild widget) {
        return widget != null
                && widget.isValid()
                && widget.isVisible()
                && widget.getWidth() > 0
                && widget.getHeight() > 0;
    }

    private boolean isAldariumClickableInRewardList(APIContext ctx, WidgetChild widget) {
        Rectangle clickArea = clickableIntersectionWithRewardList(ctx, widget);
        return clickArea != null && clickArea.width >= 8 && clickArea.height >= 8;
    }

    private boolean clickAldariumInsideRewardList(APIContext ctx, WidgetChild widget) {
        Rectangle clickArea = clickableIntersectionWithRewardList(ctx, widget);
        if (clickArea == null || clickArea.width < 8 || clickArea.height < 8) {
            stats.debug("Aldarium widget is not clickable inside left list: widget="
                    + widgetSummary(widget)
                    + " listBounds=" + rewardListViewportBounds(ctx));
            return false;
        }

        Point target = new Point(clickArea.x + clickArea.width / 2, clickArea.y + clickArea.height / 2);
        stats.debug("Clicking Aldarium inside left reward list at " + target
                + " widget=" + widgetSummary(widget));
        ctx.mouse().move(target);
        Time.sleep(120, 240);
        return ctx.mouse().click(target, false);
    }

    private Rectangle clickableIntersectionWithRewardList(APIContext ctx, WidgetChild widget) {
        if (!isVisibleWidget(widget)) {
            return null;
        }

        Rectangle widgetBounds = safeBounds(widget);
        Rectangle listBounds = rewardListViewportBounds(ctx);
        if (widgetBounds == null || listBounds == null) {
            return null;
        }

        Rectangle intersection = widgetBounds.intersection(listBounds);
        if (intersection.isEmpty()) {
            return null;
        }
        return intersection;
    }

    private boolean widgetMentionsAldarium(WidgetChild widget) {
        if (widget == null) {
            return false;
        }
        if (safeItemId(widget) == ItemID.ALDARIUM) {
            return true;
        }
        String haystack = widgetTextAndName(widget).toLowerCase(Locale.ROOT);
        return haystack.contains("aldarium");
    }

    private int safeItemId(WidgetChild widget) {
        try {
            return widget == null || !widget.isValid() ? -1 : widget.getItemId();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private String widgetTextAndName(WidgetChild widget) {
        if (widget == null) {
            return "";
        }
        return (widgetText(widget) + " " + widget.getName()).trim();
    }

    private boolean widgetHasAction(WidgetChild widget, String action) {
        return widget != null
                && widget.getActions() != null
                && widget.getActions().stream().anyMatch(candidate -> action.equalsIgnoreCase(candidate));
    }

    private ResinBalance readResinBalance(APIContext ctx) {
        ResinBalance varpBalance = readResinBalanceFromVarps(ctx);
        if (varpBalance != null) {
            stats.recordResinBalance(varpBalance.mox, varpBalance.aga, varpBalance.lye, "reward varp");
            stats.debug("Aldarium reward resin balance from varp: " + varpBalance.summary());
            return varpBalance;
        }

        List<NumberWidget> numbers = new ArrayList<>();
        for (WidgetChild widget : ctx.widgets().getAllChildren(widget -> widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0
                && widgetGroup(widget) == REWARD_SHOP_GROUP)) {
            Integer value = parseNumber(widgetText(widget));
            if (value == null) {
                continue;
            }
            int x = widget.getAbsoluteX();
            int y = widget.getAbsoluteY();
            if (x >= 940 && x <= 1265 && y >= 480 && y <= 625) {
                numbers.add(new NumberWidget(value, x, y, widgetSummary(widget)));
            }
        }

        numbers.sort(Comparator
                .comparingInt((NumberWidget number) -> number.y)
                .thenComparingInt(number -> number.x));
        if (numbers.size() > 3) {
            int bestY = numbers.get(numbers.size() - 1).y;
            List<NumberWidget> sameRow = new ArrayList<>();
            for (NumberWidget number : numbers) {
                if (Math.abs(number.y - bestY) <= 12) {
                    sameRow.add(number);
                }
            }
            numbers = sameRow;
        }
        numbers.sort(Comparator.comparingInt(number -> number.x));
        if (numbers.size() < 3) {
            logRewardWidgetDiagnostic(ctx);
            return null;
        }

        ResinBalance balance = new ResinBalance(numbers.get(0).value, numbers.get(1).value, numbers.get(2).value);
        stats.recordResinBalance(balance.mox, balance.aga, balance.lye, "reward shop");
        stats.debug("Aldarium reward resin balance: " + balance.summary()
                + " widgets=" + numbers.get(0).summary
                + " | " + numbers.get(1).summary
                + " | " + numbers.get(2).summary);
        return balance;
    }

    private ResinBalance readResinBalanceFromVarps(APIContext ctx) {
        if (ctx == null || ctx.vars() == null) {
            return null;
        }
        int mox = safeVarp(ctx, VarPlayerID.MIXOLOGY_MOX_POINTS);
        int aga = safeVarp(ctx, VarPlayerID.MIXOLOGY_AGA_POINTS);
        int lye = safeVarp(ctx, VarPlayerID.MIXOLOGY_LYE_POINTS);
        if (mox < 0 || aga < 0 || lye < 0) {
            return null;
        }
        return new ResinBalance(mox, aga, lye);
    }

    private GrandExchangeSlot findAldariumOffer(APIContext ctx) {
        if (!ctx.grandExchange().isOpen()) {
            return null;
        }
        for (GrandExchangeSlot slot : ctx.grandExchange().getSlots()) {
            if (slot == null || !slot.inUse() || slot.getOffer() == null) {
                continue;
            }
            GrandExchangeOffer offer = slot.getOffer();
            if (itemNameMatches(offer.getItemName(), ALDARIUM)) {
                return slot;
            }
        }
        return null;
    }

    private boolean tryRingOfWealthTeleport(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextRingTeleportAttemptAt) {
            return false;
        }

        ItemWidget equippedRing = ctx.equipment().getItem(IEquipmentAPI.Slot.RING);
        if (equippedRing != null && TravelItems.isChargedRingOfWealth(equippedRing.getName())) {
            nextRingTeleportAttemptAt = now + 15_000L;
            stats.setStatus("Teleporting to GE with equipped Ring of wealth for Aldarium sale");
            if (interactRingTeleport(equippedRing)) {
                Time.sleep(2500, 5000,
                        () -> isAtGrandExchange(ctx) || ctx.localPlayer().isMoving(),
                        100);
                return true;
            }
        }

        ItemWidget inventoryRing = ctx.inventory().getItem(item ->
                item != null && TravelItems.isChargedRingOfWealth(item.getName()));
        if (inventoryRing == null) {
            return false;
        }

        nextRingTeleportAttemptAt = now + 15_000L;
        stats.setStatus("Teleporting to GE with inventory Ring of wealth for Aldarium sale");
        if (interactRingTeleport(inventoryRing)) {
            Time.sleep(2500, 5000,
                    () -> isAtGrandExchange(ctx) || ctx.localPlayer().isMoving(),
                    100);
            return true;
        }
        return false;
    }

    private boolean interactRingTeleport(ItemWidget ring) {
        String name = ring.getName();
        return ring.interact("Grand Exchange", name)
                || ring.interact("Grand Exchange")
                || ring.interact("Grand Exchange teleport", name)
                || ring.interact("Grand Exchange teleport")
                || ring.interact("Rub", name)
                || ring.interact("Rub");
    }

    private void collectGeToBank(APIContext ctx) {
        try {
            ctx.grandExchange().collectToBank();
        } catch (RuntimeException ignored) {
            // GE collection is harmless to retry.
        }
    }

    private boolean isAtGrandExchange(APIContext ctx) {
        Tile tile = ctx.localPlayer().getLocation();
        return tile != null
                && tile.getPlane() == 0
                && tile.getX() >= GE_MIN_X
                && tile.getX() <= GE_MAX_X
                && tile.getY() >= GE_MIN_Y
                && tile.getY() <= GE_MAX_Y;
    }

    private boolean hasMixologyRewardShopWidget(APIContext ctx) {
        WidgetChild title = ctx.widgets()
                .query()
                .group(REWARD_SHOP_GROUP)
                .textContains("Mixology Rewards")
                .results()
                .first();
        return title != null && title.isValid();
    }

    private String allRewardShopText(APIContext ctx) {
        StringBuilder text = new StringBuilder();
        for (WidgetChild widget : ctx.widgets().getAllChildren(widget -> widget != null
                && widget.isValid()
                && widgetGroup(widget) == REWARD_SHOP_GROUP)) {
            String value = widgetText(widget);
            if (!value.isBlank()) {
                text.append(' ').append(value);
            }
        }
        return text.toString();
    }

    private String rightRewardPanelText(APIContext ctx) {
        Rectangle rightPanel = rightRewardPanelBounds(ctx);
        if (rightPanel == null || rightPanel.width <= 0 || rightPanel.height <= 0) {
            return "";
        }

        StringBuilder text = new StringBuilder();
        for (WidgetChild widget : ctx.widgets().getAllChildren(widget -> widget != null
                && widget.isValid()
                && widgetGroup(widget) == REWARD_SHOP_GROUP
                && widgetCenterInBounds(widget, rightPanel))) {
            String value = widgetTextAndName(widget);
            if (!value.isBlank()) {
                text.append(' ').append(value);
            }
        }
        return text.toString();
    }

    private WidgetChild findRightPanelAldariumWidget(APIContext ctx) {
        Rectangle rightPanel = rightRewardPanelBounds(ctx);
        if (rightPanel == null || rightPanel.width <= 0 || rightPanel.height <= 0) {
            return null;
        }
        return ctx.widgets().getAllChildren(widget -> widget != null
                && widget.isValid()
                && widgetGroup(widget) == REWARD_SHOP_GROUP
                && isVisibleWidget(widget)
                && widgetCenterInBounds(widget, rightPanel)
                && widgetMentionsAldarium(widget))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Rectangle rightRewardPanelBounds(APIContext ctx) {
        Rectangle shop = rewardShopBounds(ctx);
        if (shop == null || shop.width <= 0 || shop.height <= 0) {
            return null;
        }

        int rightPanelMinX = shop.x + (int) Math.round(shop.width * 0.64D);
        return new Rectangle(
                rightPanelMinX,
                shop.y,
                Math.max(1, shop.x + shop.width - rightPanelMinX),
                shop.height
        );
    }

    private boolean widgetCenterInBounds(WidgetChild widget, Rectangle bounds) {
        Rectangle widgetBounds = safeBounds(widget);
        if (widgetBounds == null || bounds == null) {
            return false;
        }
        int centerX = widgetBounds.x + widgetBounds.width / 2;
        int centerY = widgetBounds.y + widgetBounds.height / 2;
        return bounds.contains(centerX, centerY);
    }

    private int inventoryCount(APIContext ctx, String itemName) {
        int count = 0;
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item != null && itemNameMatches(item.getName(), itemName)) {
                count += Math.max(1, item.getStackSize());
            }
        }
        return count;
    }

    private int bankCount(APIContext ctx, String itemName) {
        if (!ctx.bank().isOpen()) {
            return 0;
        }

        int total = 0;
        for (ItemWidget item : ctx.bank().getItems()) {
            if (item != null && itemNameMatches(item.getName(), itemName)) {
                total += Math.max(0, item.getStackSize());
            }
        }

        try {
            return Math.max(total, Math.max(0, ctx.bank().getCount(itemName)));
        } catch (RuntimeException ignored) {
            return total;
        }
    }

    private boolean itemNameMatches(String actualName, String expectedName) {
        return normalizeItemName(actualName).equals(normalizeItemName(expectedName));
    }

    private String normalizeItemName(String value) {
        return value == null
                ? ""
                : value.replaceAll("<[^>]+>", " ")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9() ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private Integer parseNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String cleaned = value.replaceAll("<[^>]+>", " ")
                .replace(",", "")
                .replaceAll("[^0-9]", "")
                .trim();
        if (cleaned.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(cleaned);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int widgetGroup(WidgetChild widget) {
        if (widget == null) {
            return -1;
        }
        if (widget.getGroup() != null) {
            return widget.getGroup().getIndex();
        }
        int parent = widget.getParentId();
        if (parent > 0) {
            return parent >>> 16;
        }
        return -1;
    }

    private Rectangle safeBounds(WidgetChild widget) {
        if (widget == null || !widget.isValid()) {
            return null;
        }
        Rectangle bounds = widget.getBounds();
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            return bounds;
        }
        if (widget.getWidth() <= 0 || widget.getHeight() <= 0) {
            return null;
        }
        return new Rectangle(
                widget.getAbsoluteX(),
                widget.getAbsoluteY(),
                widget.getWidth(),
                widget.getHeight()
        );
    }

    private WidgetChild findBuyFiftyButton(APIContext ctx) {
        WidgetChild configured = ctx.widgets().get(REWARD_SHOP_GROUP, BUY_50_CHILD);
        if (isBuyFiftyButton(configured)) {
            return configured;
        }

        WidgetChild fallback = null;
        for (WidgetChild widget : ctx.widgets().getAllChildren(widget -> widget != null
                && widget.isValid()
                && widgetGroup(widget) == REWARD_SHOP_GROUP)) {
            if (!isBuyFiftyButton(widget)) {
                continue;
            }
            if (widget.isVisible()) {
                return widget;
            }
            if (fallback == null) {
                fallback = widget;
            }
        }
        return fallback;
    }

    private boolean isBuyFiftyButton(WidgetChild widget) {
        if (widget == null || !widget.isValid()) {
            return false;
        }
        if (widgetHasAction(widget, "Buy-50")) {
            return true;
        }
        String haystack = (widgetText(widget) + " " + widget.getName()).toLowerCase(Locale.ROOT);
        return haystack.contains("buy-50") || haystack.contains("buy 50");
    }

    private int safeVarp(APIContext ctx, int varpId) {
        try {
            return ctx.vars().getVarp(varpId);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private String widgetText(WidgetChild widget) {
        if (widget == null) {
            return "";
        }
        String text = widget.getText();
        if (text == null || text.isBlank()) {
            text = widget.getRawText();
        }
        return text == null ? "" : text.replace("<br>", " ").replaceAll("<[^>]+>", " ").trim();
    }

    private void logRewardNpcDiagnostic(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextDiagnosticAt) {
            return;
        }
        nextDiagnosticAt = now + 10_000L;
        StringBuilder nearby = new StringBuilder();
        int count = 0;
        for (NPC npc : ctx.npcs().query().results().nearestList()) {
            if (npc == null || !npc.isValid()) {
                continue;
            }
            if (count >= 8) {
                break;
            }
            if (nearby.length() > 0) {
                nearby.append(" | ");
            }
            nearby.append(npc.getId())
                    .append(':')
                    .append(npc.getName())
                    .append('@')
                    .append(npc.getLocation())
                    .append(" actions=")
                    .append(npc.getActions());
            count++;
        }
        stats.debug("Aldarium reward Lalo diagnostic playerLoc="
                + ctx.localPlayer().getLocation()
                + " targetId=" + REWARD_LALO_ID
                + " targetTile=" + REWARD_LALO_TILE
                + " nearbyNpcs=" + nearby);
    }

    private void logRewardWidgetDiagnostic(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextDiagnosticAt) {
            return;
        }
        nextDiagnosticAt = now + 10_000L;
        StringBuilder widgets = new StringBuilder();
        int count = 0;
        for (WidgetChild widget : ctx.widgets().getAllChildren(widget -> widget != null
                && widget.isValid()
                && widgetGroup(widget) == REWARD_SHOP_GROUP)) {
            if (count >= 18) {
                widgets.append(" | ...");
                break;
            }
            if (widgets.length() > 0) {
                widgets.append(" | ");
            }
            widgets.append(widgetSummary(widget));
            count++;
        }
        stats.debug("Aldarium reward widget diagnostic storeOpen="
                + ctx.store().isOpen()
                + " widgets=" + widgets);
    }

    private void logAldariumSelectionDiagnostic(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextDiagnosticAt) {
            return;
        }
        nextDiagnosticAt = now + 10_000L;
        WidgetChild rightAldarium = findRightPanelAldariumWidget(ctx);
        WidgetChild buyFifty = findBuyFiftyButton(ctx);
        stats.debug("Aldarium selection diagnostic rightPanel="
                + rightRewardPanelBounds(ctx)
                + " rightText='" + rightRewardPanelText(ctx) + "'"
                + " rightAldarium=" + widgetSummary(rightAldarium)
                + " buy50=" + widgetSummary(buyFifty)
                + " selectedVarp=" + safeVarp(ctx, VarPlayerID.OMNISHOP_SELECTED_ID)
                + " selectionAttempted=" + aldariumSelectionAttempted
                + " selectedForClaim=" + aldariumSelectedForClaim);
    }

    private String widgetSummary(WidgetChild widget) {
        if (widget == null) {
            return "null";
        }
        return "child=" + widget.getChildId()
                + ", parent=" + widget.getParentId()
                + ", itemId=" + safeItemId(widget)
                + ", loc=" + widget.getAbsoluteX() + "," + widget.getAbsoluteY()
                + ", size=" + widget.getWidth() + "x" + widget.getHeight()
                + ", text='" + widgetText(widget) + "'"
                + ", name='" + widget.getName() + "'"
                + ", actions=" + widget.getActions();
    }

    private static class NumberWidget {
        private final int value;
        private final int x;
        private final int y;
        private final String summary;

        private NumberWidget(int value, int x, int y, String summary) {
            this.value = value;
            this.x = x;
            this.y = y;
            this.summary = summary;
        }
    }

    private static class ResinBalance {
        private final int mox;
        private final int aga;
        private final int lye;

        private ResinBalance(int mox, int aga, int lye) {
            this.mox = mox;
            this.aga = aga;
            this.lye = lye;
        }

        private int maxAldarium() {
            return Math.min(mox / ALDARIUM_MOX_COST,
                    Math.min(aga / ALDARIUM_AGA_COST, lye / ALDARIUM_LYE_COST));
        }

        private String summary() {
            return "Mox=" + mox
                    + ", Aga=" + aga
                    + ", Lye=" + lye
                    + ", maxAldarium=" + maxAldarium();
        }
    }
}
