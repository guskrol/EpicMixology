package org.gusta.mixology.domain;

import java.util.Arrays;
import java.util.Locale;

public enum PotionRecipe {
    ALCO_AUGMENTATOR("Alco-augmentator", "AAA", 60, 0, 20, 0, 20, 30014, 30024, PasteType.AGA, PasteType.AGA, PasteType.AGA),
    MAMMOTH_MIGHT_MIX("Mammoth-might mix", "MMM", 60, 20, 0, 0, 20, 30011, 30021, PasteType.MOX, PasteType.MOX, PasteType.MOX),
    LIPLACK_LIQUOR("Liplack liquor", "LLL", 60, 0, 0, 20, 20, 30017, 30027, PasteType.LYE, PasteType.LYE, PasteType.LYE),
    MYSTIC_MANA_AMALGAM("Mystic mana amalgam", "MMA", 63, 20, 10, 0, 30, 30012, 30022, PasteType.MOX, PasteType.MOX, PasteType.AGA),
    MARLEYS_MOONLIGHT("Marley's moonlight", "MML", 66, 20, 0, 10, 30, 30013, 30023, PasteType.MOX, PasteType.MOX, PasteType.LYE),
    AZURE_AURA_MIX("Azure aura mix", "AAM", 69, 10, 20, 0, 30, 30016, 30026, PasteType.AGA, PasteType.AGA, PasteType.MOX),
    AQUALUX_AMALGAM("Aqualux amalgam", "ALA", 72, 0, 20, 10, 30, 30015, 30025, PasteType.AGA, PasteType.LYE, PasteType.AGA),
    MEGALITE_LIQUID("Megalite liquid", "MLL", 75, 10, 0, 20, 30, 30019, 30029, PasteType.MOX, PasteType.LYE, PasteType.LYE),
    ANTI_LEECH_LOTION("Anti-leech lotion", "ALL", 78, 0, 10, 20, 30, 30018, 30028, PasteType.AGA, PasteType.LYE, PasteType.LYE),
    MIXALOT("Mixalot", "MAL", 81, 20, 20, 20, 60, 30020, 30030, PasteType.MOX, PasteType.AGA, PasteType.LYE);

    private final String displayName;
    private final String code;
    private final int herbloreLevel;
    private final int moxResin;
    private final int agaResin;
    private final int lyeResin;
    private final int totalResin;
    private final int unfinishedItemId;
    private final int processedItemId;
    private final PasteType[] sequence;

    PotionRecipe(
            String displayName,
            String code,
            int herbloreLevel,
            int moxResin,
            int agaResin,
            int lyeResin,
            int totalResin,
            int unfinishedItemId,
            int processedItemId,
            PasteType... sequence
    ) {
        this.displayName = displayName;
        this.code = code;
        this.herbloreLevel = herbloreLevel;
        this.moxResin = moxResin;
        this.agaResin = agaResin;
        this.lyeResin = lyeResin;
        this.totalResin = totalResin;
        this.unfinishedItemId = unfinishedItemId;
        this.processedItemId = processedItemId;
        this.sequence = sequence;
    }

    public String displayName() {
        return displayName;
    }

    public String code() {
        return code;
    }

    public int herbloreLevel() {
        return herbloreLevel;
    }

    public int resinFor(PasteType type) {
        if (type == PasteType.MOX) {
            return moxResin;
        }
        if (type == PasteType.AGA) {
            return agaResin;
        }
        return lyeResin;
    }

    public int totalResin() {
        return totalResin;
    }

    public int unfinishedItemId() {
        return unfinishedItemId;
    }

    public int processedItemId() {
        return processedItemId;
    }

    public PasteType[] sequence() {
        return Arrays.copyOf(sequence, sequence.length);
    }

    public static PotionRecipe fromText(String text) {
        String normalized = normalize(text);
        for (PotionRecipe recipe : values()) {
            if (normalized.contains(normalize(recipe.displayName))
                    || normalized.contains(normalize(recipe.code))) {
                return recipe;
            }
        }
        return null;
    }

    public static PotionRecipe fromUnfinishedItemId(int itemId) {
        for (PotionRecipe recipe : values()) {
            if (recipe.unfinishedItemId == itemId) {
                return recipe;
            }
        }
        return null;
    }

    public static PotionRecipe fromProcessedItemId(int itemId) {
        for (PotionRecipe recipe : values()) {
            if (recipe.processedItemId == itemId) {
                return recipe;
            }
        }
        return null;
    }

    public static PotionRecipe fromPotionItemId(int itemId) {
        PotionRecipe processed = fromProcessedItemId(itemId);
        return processed == null ? fromUnfinishedItemId(itemId) : processed;
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
