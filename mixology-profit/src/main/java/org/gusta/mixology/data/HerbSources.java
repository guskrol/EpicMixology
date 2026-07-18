package org.gusta.mixology.data;

import org.gusta.mixology.domain.HerbSource;
import org.gusta.mixology.domain.PasteType;

import java.util.List;

public final class HerbSources {
    private static final List<HerbSource> ALL = List.of(
            new HerbSource("Guam leaf", PasteType.MOX, 10),
            new HerbSource("Marrentill", PasteType.MOX, 13),
            new HerbSource("Tarromin", PasteType.MOX, 15),
            new HerbSource("Harralander", PasteType.MOX, 20),
            new HerbSource("Irit leaf", PasteType.AGA, 30),
            new HerbSource("Huasca", PasteType.AGA, 20),
            new HerbSource("Cadantine", PasteType.AGA, 34),
            new HerbSource("Lantadyme", PasteType.AGA, 40),
            new HerbSource("Dwarf weed", PasteType.AGA, 42),
            new HerbSource("Torstol", PasteType.AGA, 44),
            new HerbSource("Ranarr weed", PasteType.LYE, 26),
            new HerbSource("Toadflax", PasteType.LYE, 32),
            new HerbSource("Avantoe", PasteType.LYE, 30),
            new HerbSource("Kwuarm", PasteType.LYE, 33),
            new HerbSource("Snapdragon", PasteType.LYE, 40)
    );

    private HerbSources() {
    }

    public static List<HerbSource> all() {
        return ALL;
    }
}
