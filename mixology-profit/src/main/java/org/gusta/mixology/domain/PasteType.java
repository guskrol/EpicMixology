package org.gusta.mixology.domain;

public enum PasteType {
    MOX("Mox", "Mox paste", "Mox resin"),
    AGA("Aga", "Aga paste", "Aga resin"),
    LYE("Lye", "Lye paste", "Lye resin");

    private final String label;
    private final String pasteName;
    private final String resinName;

    PasteType(String label, String pasteName, String resinName) {
        this.label = label;
        this.pasteName = pasteName;
        this.resinName = resinName;
    }

    public String label() {
        return label;
    }

    public String pasteName() {
        return pasteName;
    }

    public String resinName() {
        return resinName;
    }
}
