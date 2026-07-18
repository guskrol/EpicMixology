package org.gusta.mixology.domain;

import java.util.EnumMap;
import java.util.Map;

public class HopperStock {
    private final Map<PasteType, Integer> amounts;

    public HopperStock(Map<PasteType, Integer> amounts) {
        this.amounts = new EnumMap<>(PasteType.class);
        if (amounts != null) {
            this.amounts.putAll(amounts);
        }
    }

    public int amount(PasteType type) {
        return amounts.getOrDefault(type, -1);
    }

    public PasteType firstAtOrBelow(int threshold) {
        for (PasteType type : PasteType.values()) {
            int amount = amount(type);
            if (amount >= 0 && amount <= threshold) {
                return type;
            }
        }
        return null;
    }

    public boolean isComplete() {
        for (PasteType type : PasteType.values()) {
            if (!amounts.containsKey(type)) {
                return false;
            }
        }
        return true;
    }

    public String summary() {
        return "Mox=" + amount(PasteType.MOX)
                + ", Aga=" + amount(PasteType.AGA)
                + ", Lye=" + amount(PasteType.LYE);
    }
}
