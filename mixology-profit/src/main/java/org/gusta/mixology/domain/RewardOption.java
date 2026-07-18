package org.gusta.mixology.domain;

public class RewardOption {
    private final String itemName;
    private final int minHerbloreLevel;
    private final int moxCost;
    private final int agaCost;
    private final int lyeCost;
    private final boolean tradeable;

    public RewardOption(
            String itemName,
            int minHerbloreLevel,
            int moxCost,
            int agaCost,
            int lyeCost,
            boolean tradeable
    ) {
        this.itemName = itemName;
        this.minHerbloreLevel = minHerbloreLevel;
        this.moxCost = moxCost;
        this.agaCost = agaCost;
        this.lyeCost = lyeCost;
        this.tradeable = tradeable;
    }

    public String itemName() {
        return itemName;
    }

    public int minHerbloreLevel() {
        return minHerbloreLevel;
    }

    public int costFor(PasteType type) {
        if (type == PasteType.MOX) {
            return moxCost;
        }
        if (type == PasteType.AGA) {
            return agaCost;
        }
        return lyeCost;
    }

    public int totalCost() {
        return moxCost + agaCost + lyeCost;
    }

    public boolean isTradeable() {
        return tradeable;
    }
}
