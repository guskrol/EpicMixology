package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.model.ItemDetail;

public class GePricingService {
    private static final double BUY_MARKUP = 1.15D;
    private static final double SELL_MARKDOWN = 0.99D;

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
