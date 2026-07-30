package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import org.gusta.mixology.data.HerbSources;
import org.gusta.mixology.data.RewardCatalog;
import org.gusta.mixology.domain.HerbSource;
import org.gusta.mixology.domain.PasteType;
import org.gusta.mixology.domain.PasteSourceQuote;
import org.gusta.mixology.domain.RewardOption;
import org.gusta.mixology.domain.RewardProfit;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

public class ProfitPlanner {
    private final GePricingService pricing;

    public ProfitPlanner(GePricingService pricing) {
        this.pricing = pricing;
    }

    public Optional<RewardProfit> bestTradeableReward(APIContext ctx, int herbloreLevel) {
        Map<PasteType, Long> pasteCosts = cheapestPasteCosts(ctx);
        return RewardCatalog.tradeableProfitRewards().stream()
                .filter(RewardOption::isTradeable)
                .filter(reward -> herbloreLevel >= reward.minHerbloreLevel())
                .map(reward -> profitFor(ctx, reward, pasteCosts))
                .max(Comparator.comparingDouble(RewardProfit::profitPerResin));
    }

    public int aldariumRealtimePrice(APIContext ctx) {
        return pricing.aldariumRealtimePrice(ctx, fallbackPrice("Aldarium"));
    }

    public Map<PasteType, Long> cheapestPasteCosts(APIContext ctx) {
        Map<PasteType, Long> costs = new EnumMap<>(PasteType.class);
        for (PasteType type : PasteType.values()) {
            costs.put(type, cheapestSource(ctx, type)
                    .map(PasteSourceQuote::costPerPaste)
                    .orElse(1L));
        }
        return costs;
    }

    public Optional<PasteSourceQuote> cheapestSource(APIContext ctx, PasteType type) {
        return HerbSources.all().stream()
                .filter(source -> source.pasteType() == type)
                .map(source -> quote(ctx, source))
                .min(Comparator.comparingLong(PasteSourceQuote::costPerPaste));
    }

    private RewardProfit profitFor(APIContext ctx, RewardOption reward, Map<PasteType, Long> pasteCosts) {
        long sellValue = pricing.quickSellPrice(ctx, reward.itemName(), fallbackPrice(reward.itemName()));
        long pasteCost = 0L;
        for (PasteType type : PasteType.values()) {
            pasteCost += reward.costFor(type) * pasteCosts.getOrDefault(type, 1L);
        }
        return new RewardProfit(reward, sellValue, pasteCost, sellValue - pasteCost);
    }

    private PasteSourceQuote quote(APIContext ctx, HerbSource source) {
        int buyPrice = pricing.quickBuyPrice(ctx, source.itemName(), fallbackPrice(source.itemName()));
        long perPaste = Math.max(1L, Math.round(Math.ceil((double) buyPrice / source.pasteYield())));
        return new PasteSourceQuote(source, buyPrice, perPaste);
    }

    public long fallbackPrice(String itemName) {
        if ("Aldarium".equals(itemName)) {
            return 6_000L;
        }
        if ("Chugging barrel (disassembled)".equals(itemName)) {
            return 30_000_000L;
        }
        if ("Guam leaf".equals(itemName)) {
            return 60L;
        }
        if ("Marrentill".equals(itemName)) {
            return 90L;
        }
        if ("Tarromin".equals(itemName)) {
            return 140L;
        }
        if ("Harralander".equals(itemName)) {
            return 500L;
        }
        if ("Irit leaf".equals(itemName)) {
            return 1_100L;
        }
        if ("Huasca".equals(itemName)) {
            return 1_200L;
        }
        if ("Cadantine".equals(itemName)) {
            return 1_400L;
        }
        if ("Lantadyme".equals(itemName)) {
            return 1_700L;
        }
        if ("Dwarf weed".equals(itemName)) {
            return 1_600L;
        }
        if ("Torstol".equals(itemName)) {
            return 5_000L;
        }
        if ("Ranarr weed".equals(itemName)) {
            return 7_000L;
        }
        if ("Toadflax".equals(itemName)) {
            return 2_000L;
        }
        if ("Avantoe".equals(itemName)) {
            return 1_600L;
        }
        if ("Kwuarm".equals(itemName)) {
            return 1_500L;
        }
        if ("Snapdragon".equals(itemName)) {
            return 8_000L;
        }
        return 1_000L;
    }
}
