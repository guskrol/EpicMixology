package org.gusta.mixology.data;

import org.gusta.mixology.domain.RewardOption;

import java.util.List;

public final class RewardCatalog {
    private static final List<RewardOption> TRADEABLE_PROFIT_REWARDS = List.of(
            new RewardOption("Aldarium", 60, 80, 60, 90, true),
            new RewardOption("Chugging barrel (disassembled)", 81, 17_250, 14_000, 18_600, true)
    );

    private RewardCatalog() {
    }

    public static List<RewardOption> tradeableProfitRewards() {
        return TRADEABLE_PROFIT_REWARDS;
    }
}
