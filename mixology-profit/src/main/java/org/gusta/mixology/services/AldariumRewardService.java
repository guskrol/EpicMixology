package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.entity.NPC;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.methods.IEquipmentAPI;
import com.epicbot.api.shared.model.Tile;
import com.epicbot.api.shared.model.ge.GrandExchangeOffer;
import com.epicbot.api.shared.model.ge.GrandExchangeSlot;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.config.MixologySettings;
import org.gusta.mixology.data.TravelItems;
import org.gusta.mixology.stats.MixologyStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class AldariumRewardService {
    private static final String ALDARIUM = "Aldarium";
    private static final int REWARD_LALO_ID = 13921;
    private static final Tile REWARD_LALO_TILE = new Tile(1395, 9311, 0);
    private static final int REWARD_SHOP_GROUP = 819;
    private static final int BUY_1_CHILD = 22;
    private static final int BUY_5_CHILD = 23;
    private static final int BUY_10_CHILD = 24;
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

    private final MixologySettings settings;
    private final MixologyStats stats;
    private final GePricingService pricing;

    private int rewardOpenAttempts;
    private boolean bankCheckedForAldarium;
    private boolean sellOfferPlaced;
    private int currentSellPrice;
    private long nextSellCollectAt;
    private long sellOfferPlacedAt;
    private long nextRingTeleportAttemptAt;
    private long nextDiagnosticAt;
    private boolean geCheckedForAldariumOffer;

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
        nextRingTeleportAttemptAt = 0L;
        geCheckedForAldariumOffer = false;
    }

    public boolean claimAldarium(APIContext ctx) {
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
            ctx.walking().walkTo(REWARD_LALO_TILE);
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

        GrandExchangeSlot activeSlot = findAldariumOffer(ctx);
        if (activeSlot != null || sellOfferPlaced) {
            return handleExistingSellOffer(ctx, activeSlot);
        }

        int inventoryAldarium = inventoryCount(ctx, ALDARIUM);
        if (inventoryAldarium <= 0 && !bankCheckedForAldarium) {
            return withdrawAldariumFromBank(ctx);
        }
        if (inventoryAldarium <= 0 && !geCheckedForAldariumOffer) {
            return checkGrandExchangeForAldariumBeforeHerbs(ctx);
        }
        if (inventoryAldarium <= 0) {
            stats.setStatus("No Aldarium to sell before restock");
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
            stats.setStatus("Aldarium sale already collected");
            return true;
        }

        currentSellPrice = Math.max(1, pricing.quickSellPrice(ctx, ALDARIUM, 6_000L));
        stats.setStatus("Selling " + inventoryAldarium + "x Aldarium at "
                + currentSellPrice + " each before herb restock");
        boolean placed = ctx.grandExchange().placeSellOffer(ALDARIUM, inventoryAldarium, currentSellPrice);
        if (!placed) {
            stats.setStatus("Aldarium sell offer was not placed; retrying");
            Time.sleep(900, 1400);
            return false;
        }

        sellOfferPlaced = true;
        sellOfferPlacedAt = System.currentTimeMillis();
        nextSellCollectAt = System.currentTimeMillis() + 5_000L;
        Time.sleep(1000, 1500);
        return false;
    }

    private boolean buyAldariumFromOpenShop(APIContext ctx) {
        WidgetChild aldarium = findAldariumShopWidget(ctx);
        if (aldarium == null || !aldarium.isValid()) {
            stats.setStatus("Mixology reward shop open but Aldarium widget was not found");
            logRewardWidgetDiagnostic(ctx);
            closeRewardShop(ctx);
            return true;
        }

        selectAldarium(ctx, aldarium);
        ResinBalance balance = readResinBalance(ctx);
        int targetQuantity = balance == null ? -1 : balance.maxAldarium();
        if (targetQuantity == 0) {
            stats.setStatus("Not enough resin for Aldarium: " + balance.summary());
            closeRewardShop(ctx);
            return true;
        }

        int bought = targetQuantity > 0
                ? buyTargetQuantity(ctx, aldarium, targetQuantity)
                : buyFallbackUntilNoChange(ctx, aldarium);

        stats.setStatus("Aldarium reward claim complete: bought=" + bought
                + (balance == null ? " balance=unreadable" : " from " + balance.summary()));
        closeRewardShop(ctx);
        return true;
    }

    private boolean isRewardShopOpen(APIContext ctx) {
        return ctx.store().isOpen() || hasMixologyRewardShopWidget(ctx);
    }

    private void closeRewardShop(APIContext ctx) {
        if (ctx.store().isOpen()) {
            ctx.store().close();
        } else {
            ctx.widgets().closeInterface();
        }
        Time.sleep(500, 900, () -> !isRewardShopOpen(ctx), 100);
    }

    private int buyTargetQuantity(APIContext ctx, WidgetChild aldarium, int quantity) {
        int bought = 0;
        int remaining = quantity;
        while (remaining > 0) {
            int chunk = largestChunk(remaining);
            int before = inventoryCount(ctx, ALDARIUM);
            if (!buyChunk(ctx, aldarium, chunk)) {
                if (chunk == 1) {
                    break;
                }
                remaining = Math.min(remaining, chunk - 1);
                continue;
            }
            Time.sleep(700, 1100, () -> inventoryCount(ctx, ALDARIUM) > before, 100);
            int gained = Math.max(0, inventoryCount(ctx, ALDARIUM) - before);
            if (gained <= 0) {
                stats.setStatus("Aldarium Buy-" + chunk + " did not change inventory; stopping claim");
                break;
            }
            bought += gained;
            remaining -= gained;
            Time.sleep(250, 500);
        }
        return bought;
    }

    private int buyFallbackUntilNoChange(APIContext ctx, WidgetChild aldarium) {
        stats.setStatus("Aldarium resin balance unreadable; using descending Buy-50/10/5/1 fallback");
        int bought = 0;
        for (int chunk : new int[]{50, 10, 5, 1}) {
            int misses = 0;
            while (misses < 2) {
                int before = inventoryCount(ctx, ALDARIUM);
                if (!buyChunk(ctx, aldarium, chunk)) {
                    misses++;
                    break;
                }
                Time.sleep(700, 1100, () -> inventoryCount(ctx, ALDARIUM) > before, 100);
                int gained = Math.max(0, inventoryCount(ctx, ALDARIUM) - before);
                if (gained <= 0) {
                    misses++;
                    continue;
                }
                bought += gained;
                misses = 0;
                Time.sleep(250, 500);
            }
        }
        return bought;
    }

    private boolean buyChunk(APIContext ctx, WidgetChild aldarium, int amount) {
        stats.setStatus("Buying Aldarium reward chunk: " + amount);
        if (amount == 50) {
            WidgetChild button = ctx.widgets().get(REWARD_SHOP_GROUP, BUY_50_CHILD);
            return clickBuyButton(button, "Buy-50")
                    || ctx.store().buyFifty(ALDARIUM)
                    || aldarium.interact("Buy-50", ALDARIUM)
                    || aldarium.interact("Buy-50");
        }
        if (amount == 10) {
            WidgetChild button = ctx.widgets().get(REWARD_SHOP_GROUP, BUY_10_CHILD);
            return clickBuyButton(button, "Buy-10")
                    || ctx.store().buyTen(ALDARIUM)
                    || aldarium.interact("Buy-10", ALDARIUM)
                    || aldarium.interact("Buy-10");
        }
        if (amount == 5) {
            WidgetChild button = ctx.widgets().get(REWARD_SHOP_GROUP, BUY_5_CHILD);
            return clickBuyButton(button, "Buy-5")
                    || ctx.store().buyFive(ALDARIUM)
                    || aldarium.interact("Buy-5", ALDARIUM)
                    || aldarium.interact("Buy-5");
        }

        WidgetChild button = ctx.widgets().get(REWARD_SHOP_GROUP, BUY_1_CHILD);
        return clickBuyButton(button, "Buy-1")
                || ctx.store().buyOne(ALDARIUM)
                || aldarium.interact("Buy-1", ALDARIUM)
                || aldarium.interact("Buy-1");
    }

    private boolean clickBuyButton(WidgetChild button, String action) {
        return button != null
                && button.isValid()
                && (button.interact(action) || button.click());
    }

    private boolean selectAldarium(APIContext ctx, WidgetChild aldarium) {
        String allText = allRewardShopText(ctx).toLowerCase(Locale.ROOT);
        if (allText.contains("cost") && allText.contains("aldarium")) {
            return true;
        }
        stats.setStatus("Selecting Aldarium in Mixology reward shop");
        boolean selected = aldarium.interact("Select", ALDARIUM)
                || aldarium.interact("Select")
                || aldarium.click();
        Time.sleep(500, 900);
        return selected;
    }

    private int largestChunk(int quantity) {
        if (quantity >= 50) {
            return 50;
        }
        if (quantity >= 10) {
            return 10;
        }
        if (quantity >= 5) {
            return 5;
        }
        return 1;
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
            ctx.bank().open();
            Time.sleep(1000, 1600, () -> ctx.bank().isOpen(), 100);
            return false;
        }

        bankCheckedForAldarium = true;
        int bankCount = ctx.bank().getCount(ALDARIUM);
        if (bankCount <= 0 && !ctx.bank().contains(ALDARIUM)) {
            stats.setStatus("No banked Aldarium before restock");
            ctx.bank().close();
            Time.sleep(500, 900);
            return false;
        }

        stats.setStatus("Withdrawing " + bankCount + "x Aldarium for GE sale");
        if (ctx.bank().withdrawAll(ALDARIUM)
                || ctx.bank().withdraw(Math.max(1, bankCount), ALDARIUM)) {
            Time.sleep(500, 900, () -> inventoryCount(ctx, ALDARIUM) > 0, 100);
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
            sellOfferPlaced = false;
            geCheckedForAldariumOffer = true;
            stats.setStatus("Aldarium sell offer complete");
            return true;
        }

        if (slot.isCompleted() || slot.canCollect()) {
            stats.setStatus("Collecting completed Aldarium sale to bank");
            collectGeToBank(ctx);
            Time.sleep(700, 1100);
            if (findAldariumOffer(ctx) == null) {
                sellOfferPlaced = false;
                geCheckedForAldariumOffer = true;
                return true;
            }
            return false;
        }

        if (System.currentTimeMillis() - sellOfferPlacedAt > 45_000L && currentSellPrice > 1) {
            stats.setStatus("Aldarium sale slow; aborting and relisting lower");
            if (slot.abortOffer()) {
                Time.sleep(900, 1400);
                collectGeToBank(ctx);
                currentSellPrice = Math.max(1, (int) Math.floor(currentSellPrice * 0.95D));
                sellOfferPlaced = false;
                bankCheckedForAldarium = false;
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
        if (storeItem != null && storeItem.isValid()) {
            return storeItem;
        }

        WidgetChild byText = ctx.widgets()
                .query()
                .group(REWARD_SHOP_GROUP)
                .textContains(ALDARIUM)
                .results()
                .first();
        if (byText != null && byText.isValid()) {
            return byText;
        }

        return ctx.widgets()
                .query()
                .group(REWARD_SHOP_GROUP)
                .actions("Buy-1", "Buy-5", "Buy-10", "Buy-50")
                .results()
                .first();
    }

    private ResinBalance readResinBalance(APIContext ctx) {
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
        stats.debug("Aldarium reward resin balance: " + balance.summary()
                + " widgets=" + numbers.get(0).summary
                + " | " + numbers.get(1).summary
                + " | " + numbers.get(2).summary);
        return balance;
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

    private int inventoryCount(APIContext ctx, String itemName) {
        int count = 0;
        for (ItemWidget item : ctx.inventory().getItems()) {
            if (item != null && itemNameMatches(item.getName(), itemName)) {
                count += Math.max(1, item.getStackSize());
            }
        }
        return count;
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
        int parent = widget.getParentId();
        if (parent > 0) {
            return parent >>> 16;
        }
        return -1;
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

    private String widgetSummary(WidgetChild widget) {
        if (widget == null) {
            return "null";
        }
        return "child=" + widget.getChildId()
                + ", parent=" + widget.getParentId()
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
