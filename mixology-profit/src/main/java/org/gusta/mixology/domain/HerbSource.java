package org.gusta.mixology.domain;

public class HerbSource {
    private final String itemName;
    private final PasteType pasteType;
    private final int pasteYield;

    public HerbSource(String itemName, PasteType pasteType, int pasteYield) {
        this.itemName = itemName;
        this.pasteType = pasteType;
        this.pasteYield = pasteYield;
    }

    public String itemName() {
        return itemName;
    }

    public PasteType pasteType() {
        return pasteType;
    }

    public int pasteYield() {
        return pasteYield;
    }
}
