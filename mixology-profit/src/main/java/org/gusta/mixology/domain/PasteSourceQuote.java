package org.gusta.mixology.domain;

public class PasteSourceQuote {
    private final HerbSource source;
    private final int unitBuyPrice;
    private final long costPerPaste;

    public PasteSourceQuote(HerbSource source, int unitBuyPrice, long costPerPaste) {
        this.source = source;
        this.unitBuyPrice = unitBuyPrice;
        this.costPerPaste = costPerPaste;
    }

    public HerbSource source() {
        return source;
    }

    public int unitBuyPrice() {
        return unitBuyPrice;
    }

    public long costPerPaste() {
        return costPerPaste;
    }
}
