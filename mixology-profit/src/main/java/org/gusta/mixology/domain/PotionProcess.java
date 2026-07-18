package org.gusta.mixology.domain;

public enum PotionProcess {
    CONCENTRATE("Concentrate-potion", "Retort", "Concentrating"),
    HOMOGENISE("Homogenise-potion", "Agitator", "Homogenising"),
    CRYSTALISE("Crystalise-potion", "Alembic", "Crystallising");

    private final String actionName;
    private final String workstationName;
    private final String statusName;

    PotionProcess(String actionName, String workstationName, String statusName) {
        this.actionName = actionName;
        this.workstationName = workstationName;
        this.statusName = statusName;
    }

    public String actionName() {
        return actionName;
    }

    public String workstationName() {
        return workstationName;
    }

    public String statusName() {
        return statusName;
    }
}
