package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.ItemWidget;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.domain.PotionOrder;
import org.gusta.mixology.domain.PotionRecipe;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PotionInventoryService {
    public Map<PotionRecipe, Integer> readyPotionCounts(APIContext ctx) {
        return potionCounts(ctx, PotionState.PROCESSED);
    }

    public Map<PotionRecipe, Integer> unfinishedPotionCounts(APIContext ctx) {
        return potionCounts(ctx, PotionState.UNFINISHED);
    }

    public int unfinishedPotionCount(APIContext ctx, PotionRecipe recipe) {
        if (recipe == null) {
            return 0;
        }
        return unfinishedPotionCounts(ctx).getOrDefault(recipe, 0);
    }

    public int anyPotionCount(APIContext ctx) {
        int total = 0;
        for (ItemWidget item : ctx.inventory().getItems()) {
            PotionRecipe recipe = recipeFromAnyPotionItem(item);
            if (recipe != null) {
                total += Math.max(1, item.getStackSize());
            }
        }
        return total;
    }

    private Map<PotionRecipe, Integer> potionCounts(APIContext ctx, PotionState state) {
        Map<PotionRecipe, Integer> counts = new EnumMap<>(PotionRecipe.class);
        for (ItemWidget item : ctx.inventory().getItems()) {
            PotionRecipe recipe = recipeFromInventoryItem(item, state);
            if (recipe == null) {
                continue;
            }

            counts.merge(recipe, Math.max(1, item.getStackSize()), Integer::sum);
        }
        return counts;
    }

    public int readyPotionCount(APIContext ctx) {
        int total = 0;
        for (int count : readyPotionCounts(ctx).values()) {
            total += count;
        }
        return total;
    }

    public int potionCount(APIContext ctx, PotionRecipe recipe) {
        if (recipe == null) {
            return 0;
        }
        return readyPotionCounts(ctx).getOrDefault(recipe, 0);
    }

    public boolean hasPotionOutsideOrders(APIContext ctx, List<PotionOrder> orders) {
        Set<PotionRecipe> allowed = orderRecipes(orders);
        for (ItemWidget item : ctx.inventory().getItems()) {
            PotionRecipe recipe = recipeFromAnyPotionItem(item);
            if (recipe != null && !allowed.contains(recipe)) {
                return true;
            }
        }
        return false;
    }

    public String potionDetailsOutsideOrders(APIContext ctx, List<PotionOrder> orders) {
        Set<PotionRecipe> allowed = orderRecipes(orders);
        StringBuilder details = new StringBuilder();
        for (ItemWidget item : ctx.inventory().getItems()) {
            PotionRecipe recipe = recipeFromAnyPotionItem(item);
            if (recipe == null || allowed.contains(recipe)) {
                continue;
            }
            if (details.length() > 0) {
                details.append(" | ");
            }
            details.append("slot=").append(item.getIndex())
                    .append(" id=").append(item.getId())
                    .append(" name=").append(item.getName())
                    .append(" recipe=").append(recipe.code())
                    .append(" state=").append(potionState(item))
                    .append(" stack=").append(Math.max(1, item.getStackSize()));
        }
        return details.length() == 0 ? "none" : details.toString();
    }

    public boolean dropPotionsOutsideOrders(APIContext ctx, List<PotionOrder> orders) {
        Set<PotionRecipe> allowed = orderRecipes(orders);
        return dropPotionsOutsideRecipes(ctx, allowed);
    }

    public boolean dropAllPotions(APIContext ctx) {
        return dropPotionsOutsideRecipes(ctx, EnumSet.noneOf(PotionRecipe.class));
    }

    private boolean dropPotionsOutsideRecipes(APIContext ctx, Set<PotionRecipe> allowed) {
        boolean dropped = false;
        long deadline = System.currentTimeMillis() + 8_000L;
        while (System.currentTimeMillis() < deadline) {
            ItemWidget stale = firstPotionOutsideOrders(ctx, allowed);
            if (stale == null) {
                return dropped;
            }

            String name = stale.getName();
            int before = anyPotionCount(ctx);
            boolean interacted = stale.interact("Drop", name)
                    || stale.interact("Drop");
            if (!interacted) {
                return dropped;
            }

            dropped = true;
            Time.sleep(500, 900, () -> anyPotionCount(ctx) < before, 100);
        }
        return dropped;
    }

    public String readyPotionText(Map<PotionRecipe, Integer> counts) {
        if (counts.isEmpty()) {
            return "none";
        }

        StringBuilder text = new StringBuilder();
        for (PotionRecipe recipe : PotionRecipe.values()) {
            int count = counts.getOrDefault(recipe, 0);
            if (count <= 0) {
                continue;
            }
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(recipe.code()).append('=').append(count);
        }
        return text.toString();
    }

    public String readyPotionDetails(APIContext ctx) {
        return potionDetails(ctx, PotionState.PROCESSED);
    }

    public String unfinishedPotionDetails(APIContext ctx) {
        return potionDetails(ctx, PotionState.UNFINISHED);
    }

    public String allPotionDetails(APIContext ctx) {
        return potionDetails(ctx, PotionState.ANY);
    }

    private String potionDetails(APIContext ctx, PotionState state) {
        StringBuilder details = new StringBuilder();
        for (ItemWidget item : ctx.inventory().getItems()) {
            PotionRecipe recipe = recipeFromInventoryItem(item, state);
            if (recipe == null) {
                continue;
            }
            if (details.length() > 0) {
                details.append(" | ");
            }
            details.append("slot=").append(item.getIndex())
                    .append(" id=").append(item.getId())
                    .append(" name=").append(item.getName())
                    .append(" recipe=").append(recipe.code())
                    .append(" state=").append(potionState(item))
                    .append(" stack=").append(Math.max(1, item.getStackSize()));
        }
        return details.length() == 0 ? "none" : details.toString();
    }

    private PotionRecipe recipeFromInventoryItem(ItemWidget item, PotionState state) {
        if (item == null) {
            return null;
        }

        if (state == PotionState.PROCESSED) {
            return PotionRecipe.fromProcessedItemId(item.getId());
        }
        if (state == PotionState.UNFINISHED) {
            return PotionRecipe.fromUnfinishedItemId(item.getId());
        }
        return recipeFromAnyPotionItem(item);
    }

    private PotionRecipe recipeFromAnyPotionItem(ItemWidget item) {
        if (item == null) {
            return null;
        }
        return PotionRecipe.fromPotionItemId(item.getId());
    }

    private String potionState(ItemWidget item) {
        if (item == null) {
            return "unknown";
        }
        if (PotionRecipe.fromProcessedItemId(item.getId()) != null) {
            return "processed";
        }
        if (PotionRecipe.fromUnfinishedItemId(item.getId()) != null) {
            return "unfinished";
        }
        return "unknown";
    }

    private ItemWidget firstPotionOutsideOrders(APIContext ctx, Set<PotionRecipe> allowed) {
        for (ItemWidget item : ctx.inventory().getItems()) {
            PotionRecipe recipe = recipeFromAnyPotionItem(item);
            if (recipe != null && !allowed.contains(recipe)) {
                return item;
            }
        }
        return null;
    }

    private Set<PotionRecipe> orderRecipes(List<PotionOrder> orders) {
        Set<PotionRecipe> recipes = EnumSet.noneOf(PotionRecipe.class);
        if (orders == null) {
            return recipes;
        }
        for (PotionOrder order : orders) {
            if (order != null && order.recipe() != null) {
                recipes.add(order.recipe());
            }
        }
        return recipes;
    }

    private enum PotionState {
        PROCESSED,
        UNFINISHED,
        ANY
    }
}
