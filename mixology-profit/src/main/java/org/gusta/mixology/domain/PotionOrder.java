package org.gusta.mixology.domain;

public class PotionOrder {
    private final PotionRecipe recipe;
    private final PotionProcess process;

    public PotionOrder(PotionRecipe recipe, PotionProcess process) {
        this.recipe = recipe;
        this.process = process;
    }

    public PotionRecipe recipe() {
        return recipe;
    }

    public PotionProcess process() {
        return process;
    }

    public boolean isComplete() {
        return recipe != null && process != null;
    }

    public String label() {
        String recipeName = recipe == null ? "Unknown recipe" : recipe.displayName();
        String processName = process == null ? "unknown process" : process.actionName();
        return recipeName + " / " + processName;
    }
}
