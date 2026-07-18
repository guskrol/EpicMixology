package org.gusta.mixology.data;

import java.util.Locale;

public final class TravelItems {
    public static final String RING_OF_WEALTH_BUY = "Ring of wealth (5)";
    public static final String[] CHARGED_RING_OF_WEALTH = {
            "Ring of wealth (5)",
            "Ring of wealth (4)",
            "Ring of wealth (3)",
            "Ring of wealth (2)",
            "Ring of wealth (1)"
    };

    public static final String STAMINA_BUY = "Stamina potion(4)";
    public static final String[] STAMINA_POTIONS = {
            "Stamina potion(4)",
            "Stamina potion(3)",
            "Stamina potion(2)",
            "Stamina potion(1)"
    };

    private TravelItems() {
    }

    public static boolean isChargedRingOfWealth(String itemName) {
        return matchesAny(itemName, CHARGED_RING_OF_WEALTH);
    }

    public static boolean isStaminaPotion(String itemName) {
        return matchesAny(itemName, STAMINA_POTIONS);
    }

    public static boolean matchesAny(String itemName, String... names) {
        if (itemName == null || names == null) {
            return false;
        }
        String normalizedItem = normalize(itemName);
        for (String name : names) {
            if (normalizedItem.equals(normalize(name))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.replaceAll("<[^>]+>", " ")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9()]", "")
                .trim();
    }
}
