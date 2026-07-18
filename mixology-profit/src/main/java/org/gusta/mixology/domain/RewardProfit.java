package org.gusta.mixology.domain;

public class RewardProfit {
    private final RewardOption reward;
    private final long sellValue;
    private final long pasteCost;
    private final long profit;

    public RewardProfit(RewardOption reward, long sellValue, long pasteCost, long profit) {
        this.reward = reward;
        this.sellValue = sellValue;
        this.pasteCost = pasteCost;
        this.profit = profit;
    }

    public RewardOption reward() {
        return reward;
    }

    public long sellValue() {
        return sellValue;
    }

    public long pasteCost() {
        return pasteCost;
    }

    public long profit() {
        return profit;
    }

    public double profitPerResin() {
        return reward.totalCost() <= 0 ? 0.0D : (double) profit / reward.totalCost();
    }
}
