package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.ItemDetail;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GePricingService {
    private static final double BUY_MARKUP = 1.15D;
    private static final double SELL_MARKDOWN = 0.99D;
    private static final int ALDARIUM_ITEM_ID = 29993;
    private static final long WIKI_PRICE_CACHE_MILLIS = 5 * 60_000L;
    private static final long WIKI_PRICE_FAILURE_RETRY_MILLIS = 5 * 60_000L;
    private static final String WIKI_LATEST_PRICE_URL =
            "https://prices.runescape.wiki/api/v1/osrs/latest?id=";
    private static final Pattern LOW_PRICE_PATTERN = Pattern.compile("\"low\"\\s*:\\s*(\\d+)");
    private static final Pattern HIGH_PRICE_PATTERN = Pattern.compile("\"high\"\\s*:\\s*(\\d+)");

    private int cachedAldariumWikiPrice;
    private long cachedAldariumWikiPriceAt;
    private long nextWikiPriceAttemptAt;

    public int quickBuyPrice(APIContext ctx, String itemName, long fallbackBasePrice) {
        ItemDetail detail = itemDetail(ctx, itemName);
        long market = detail == null ? 0L : firstPositive(detail.getHighestPrice(), detail.getLowestPrice());
        long price = market > 0L ? Math.round(Math.ceil(market * BUY_MARKUP)) : fallbackBasePrice;
        return clampPrice(Math.max(1L, price));
    }

    public int quickSellPrice(APIContext ctx, String itemName, long fallbackBasePrice) {
        ItemDetail detail = itemDetail(ctx, itemName);
        long market = detail == null ? 0L : firstPositive(detail.getLowestPrice(), detail.getHighestPrice());
        long price = market > 0L ? Math.round(Math.floor(market * SELL_MARKDOWN)) : fallbackBasePrice;
        return clampPrice(Math.max(1L, price));
    }

    public int aldariumRealtimePrice(APIContext ctx, long fallbackBasePrice) {
        int wikiPrice = cachedWikiLatestSellPrice(ALDARIUM_ITEM_ID);
        if (wikiPrice > 0) {
            return wikiPrice;
        }
        return quickSellPrice(ctx, "Aldarium", fallbackBasePrice);
    }

    private int cachedWikiLatestSellPrice(int itemId) {
        long now = System.currentTimeMillis();
        if (cachedAldariumWikiPrice > 0 && now - cachedAldariumWikiPriceAt <= WIKI_PRICE_CACHE_MILLIS) {
            return cachedAldariumWikiPrice;
        }
        if (now < nextWikiPriceAttemptAt) {
            return cachedAldariumWikiPrice;
        }

        try {
            int price = fetchWikiLatestSellPrice(itemId);
            if (price > 0) {
                cachedAldariumWikiPrice = price;
                cachedAldariumWikiPriceAt = now;
                nextWikiPriceAttemptAt = now + WIKI_PRICE_CACHE_MILLIS;
                return price;
            }
        } catch (RuntimeException ignored) {
            // Fall back to the client pricing API when the Wiki cannot be reached.
        }

        nextWikiPriceAttemptAt = now + WIKI_PRICE_FAILURE_RETRY_MILLIS;
        return cachedAldariumWikiPrice;
    }

    private int fetchWikiLatestSellPrice(int itemId) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(WIKI_LATEST_PRICE_URL + itemId);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(2_500);
            connection.setReadTimeout(2_500);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "mixology-profit local script price cache");

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                return 0;
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            return parseLatestWikiPrice(body.toString());
        } catch (Exception ignored) {
            return 0;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private int parseLatestWikiPrice(String json) {
        if (json == null || json.isBlank()) {
            return 0;
        }

        Integer low = firstRegexInt(LOW_PRICE_PATTERN, json);
        if (low != null && low > 0) {
            return low;
        }

        Integer high = firstRegexInt(HIGH_PRICE_PATTERN, json);
        return high == null ? 0 : Math.max(0, high);
    }

    private Integer firstRegexInt(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private ItemDetail itemDetail(APIContext ctx, String itemName) {
        if (ctx == null || itemName == null || itemName.isBlank()) {
            return null;
        }
        try {
            return ctx.pricing().get(itemName);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private long firstPositive(int preferred, int fallback) {
        return preferred > 0 ? preferred : fallback;
    }

    private int clampPrice(long price) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, price));
    }
}
