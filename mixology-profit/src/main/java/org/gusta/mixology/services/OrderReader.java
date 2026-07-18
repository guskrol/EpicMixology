package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.WidgetChild;
import org.gusta.mixology.domain.PotionOrder;
import org.gusta.mixology.domain.PotionProcess;
import org.gusta.mixology.domain.PotionRecipe;
import org.gusta.mixology.stats.MixologyStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class OrderReader {
    private static final int ORDER_WIDGET_GROUP = 882;
    private static final int ORDER_LIST_CHILD = 2;
    private static final int ORDER_LIST_PARENT_ID = (ORDER_WIDGET_GROUP << 16) | ORDER_LIST_CHILD;
    private static final int RETORT_ICON_MATERIAL = 5672;
    private static final int ALEMBIC_ICON_MATERIAL = 5673;
    private static final int AGITATOR_ICON_MATERIAL = 5674;
    private static final int ORDER_HUD_MIN_X = 0;
    private static final int ORDER_HUD_MAX_X = 270;
    private static final int ORDER_HUD_MIN_Y = 8;
    private static final int ORDER_HUD_MAX_Y = 115;

    private final MixologyStats stats;
    private long nextUnknownProcessDiagnosticAt;
    private long nextHudDiagnosticAt;

    public OrderReader(MixologyStats stats) {
        this.stats = stats;
    }

    public List<PotionOrder> readOrders(APIContext ctx) {
        List<PotionOrder> directHudOrders = readDirectHudOrders(ctx);
        if (!directHudOrders.isEmpty()) {
            reportOrders(directHudOrders);
            return directHudOrders;
        }

        List<WidgetChild> visibleWidgets = visibleWidgets(ctx);
        List<WidgetChild> hudWidgets = orderHudWidgets(visibleWidgets);
        List<WidgetChild> textWidgets = readableSignalWidgets(hudWidgets);
        List<PotionOrder> orders = new ArrayList<>();
        Set<String> seenRows = new LinkedHashSet<>();

        for (WidgetChild widget : textWidgets) {
            PotionRecipe recipe = recipeFromText(widgetText(widget));
            if (recipe == null) {
                continue;
            }

            String rowKey = orderRowKey(widget);
            if (!seenRows.add(rowKey)) {
                continue;
            }

            String context = nearbyText(widget, textWidgets);
            PotionProcess process = processFromOrderIcon(widget, hudWidgets);
            if (process == null) {
                process = processFromText(context);
            }
            if (process == null) {
                process = nearestProcessWidget(widget, textWidgets);
            }
            if (process == null) {
                logUnknownProcess(widget, context, textWidgets, hudWidgets);
            }
            orders.add(new PotionOrder(recipe, process));
            if (orders.size() >= 3) {
                break;
            }
        }

        if (orders.isEmpty()) {
            logDirectHudDiagnostic(ctx);
            logHudDiagnostic(textWidgets, hudWidgets);
            stats.setStatus("Waiting for Mixology HUD orders");
        } else {
            reportOrders(orders);
        }
        return orders;
    }

    public boolean hasReadableHud(APIContext ctx) {
        return !readOrders(ctx).isEmpty();
    }

    private List<WidgetChild> visibleWidgets(APIContext ctx) {
        List<WidgetChild> widgets = ctx.widgets().getAllChildren(widget -> widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0);
        widgets.sort(Comparator
                .comparingInt(WidgetChild::getAbsoluteY)
                .thenComparingInt(WidgetChild::getAbsoluteX));
        return widgets;
    }

    private List<PotionOrder> readDirectHudOrders(APIContext ctx) {
        WidgetChild orderList = ctx.widgets().get(ORDER_WIDGET_GROUP, ORDER_LIST_CHILD);
        if (orderList == null || !orderList.isValid()) {
            return List.of();
        }

        List<WidgetChild> children = orderList.getChildren();
        if (children == null || children.isEmpty()) {
            return List.of();
        }

        List<PotionOrder> orders = new ArrayList<>();
        for (WidgetChild icon : children) {
            PotionProcess process = processFromIconMaterial(icon.getMaterialId());
            if (process == null) {
                continue;
            }

            WidgetChild recipeWidget = nearestRecipeTextForIcon(icon, children);
            PotionRecipe recipe = recipeWidget == null ? null : recipeFromText(widgetText(recipeWidget));
            if (recipe == null) {
                continue;
            }

            stats.debug("Direct Mixology order read recipe=" + recipe.displayName()
                    + " process=" + process.actionName()
                    + " icon=" + widgetSummary(icon)
                    + " text=" + widgetSummary(recipeWidget));
            orders.add(new PotionOrder(recipe, process));
            if (orders.size() >= 3) {
                break;
            }
        }
        return orders;
    }

    private WidgetChild nearestRecipeTextForIcon(WidgetChild icon, List<WidgetChild> children) {
        WidgetChild best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (WidgetChild candidate : children) {
            PotionRecipe recipe = recipeFromText(widgetText(candidate));
            if (recipe == null) {
                continue;
            }

            int yDistance = Math.abs(candidate.getAbsoluteY() - icon.getAbsoluteY());
            int xDistance = candidate.getAbsoluteX() - icon.getAbsoluteX();
            if (xDistance < 0 || xDistance > 240 || yDistance > 14) {
                continue;
            }

            int distance = yDistance * 6 + xDistance;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private List<WidgetChild> orderHudWidgets(List<WidgetChild> widgets) {
        List<WidgetChild> hudWidgets = new ArrayList<>();
        for (WidgetChild widget : widgets) {
            if (widget.getParentId() == ORDER_LIST_PARENT_ID) {
                hudWidgets.add(widget);
            }
        }
        if (!hudWidgets.isEmpty()) {
            return hudWidgets;
        }

        for (WidgetChild widget : widgets) {
            if (isInOrderHud(widget)) {
                hudWidgets.add(widget);
            }
        }
        return hudWidgets;
    }

    private void reportOrders(List<PotionOrder> orders) {
        int completeOrders = 0;
        for (PotionOrder order : orders) {
            if (order.isComplete()) {
                completeOrders++;
            }
        }
        if (completeOrders == orders.size()) {
            stats.setStatus("Read " + orders.size() + " complete Mixology order(s)");
        } else {
            stats.setStatus("Detected " + orders.size()
                    + " Mixology recipe(s), workstation unreadable; complete=" + completeOrders);
        }
        stats.setLastOrder(orders.get(0).label());
    }

    private boolean isInOrderHud(WidgetChild widget) {
        if (widget == null) {
            return false;
        }
        int x = widget.getAbsoluteX();
        int y = widget.getAbsoluteY();
        return x >= ORDER_HUD_MIN_X
                && x <= ORDER_HUD_MAX_X
                && y >= ORDER_HUD_MIN_Y
                && y <= ORDER_HUD_MAX_Y;
    }

    private List<WidgetChild> readableSignalWidgets(List<WidgetChild> widgets) {
        List<WidgetChild> readable = new ArrayList<>();
        for (WidgetChild widget : widgets) {
            String text = widgetText(widget);
            if (!text.isBlank()) {
                readable.add(widget);
            }
        }
        return readable;
    }

    private String nearbyText(WidgetChild source, List<WidgetChild> widgets) {
        StringBuilder text = new StringBuilder(widgetText(source));
        for (WidgetChild widget : widgets) {
            if (widget == source) {
                continue;
            }
            boolean sameParent = widget.getParentId() == source.getParentId();
            boolean near = Math.abs(widget.getAbsoluteY() - source.getAbsoluteY()) <= 110
                    && Math.abs(widget.getAbsoluteX() - source.getAbsoluteX()) <= 460;
            if (sameParent || near) {
                text.append(' ').append(widgetText(widget));
            }
        }
        return text.toString();
    }

    private PotionProcess processFromText(String text) {
        String normalized = normalize(text);
        if (normalized.contains("concentrate") || normalized.contains("concentrating")
                || normalized.contains("concentration")
                || normalized.contains("retort")) {
            return PotionProcess.CONCENTRATE;
        }
        if (normalized.contains("homogenise") || normalized.contains("homogenize")
                || normalized.contains("homogenising") || normalized.contains("homogenizing")
                || normalized.contains("agitator")) {
            return PotionProcess.HOMOGENISE;
        }
        if (normalized.contains("crystalise") || normalized.contains("crystallise")
                || normalized.contains("crystalize") || normalized.contains("crystallize")
                || normalized.contains("crystalising") || normalized.contains("crystallising")
                || normalized.contains("crystalizing") || normalized.contains("crystallizing")
                || normalized.contains("crystallization") || normalized.contains("crystallisation")
                || normalized.contains("alembic")) {
            return PotionProcess.CRYSTALISE;
        }
        return null;
    }

    private PotionProcess processFromOrderIcon(WidgetChild recipeWidget, List<WidgetChild> widgets) {
        WidgetChild bestIcon = null;
        PotionProcess bestProcess = null;
        int bestDistance = Integer.MAX_VALUE;

        for (WidgetChild widget : widgets) {
            PotionProcess process = processFromIconMaterial(widget.getMaterialId());
            if (process == null) {
                continue;
            }

            int yDistance = Math.abs(widget.getAbsoluteY() - recipeWidget.getAbsoluteY());
            int xDistance = Math.abs(widget.getAbsoluteX() - recipeWidget.getAbsoluteX());
            boolean sameContainer = widget.getParentId() == recipeWidget.getParentId();
            boolean leftOfRecipe = widget.getAbsoluteX() <= recipeWidget.getAbsoluteX();
            if (!sameContainer || !leftOfRecipe || yDistance > 10 || xDistance > 80) {
                continue;
            }

            int distance = yDistance * 5 + xDistance;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIcon = widget;
                bestProcess = process;
            }
        }

        if (bestProcess != null) {
            stats.debug("Mapped Mixology order icon material=" + bestIcon.getMaterialId()
                    + " to " + bestProcess.actionName()
                    + " for recipeWidget=" + widgetSummary(recipeWidget)
                    + " iconWidget=" + widgetSummary(bestIcon));
        }
        return bestProcess;
    }

    private PotionProcess processFromIconMaterial(int materialId) {
        if (materialId == RETORT_ICON_MATERIAL) {
            return PotionProcess.CONCENTRATE;
        }
        if (materialId == ALEMBIC_ICON_MATERIAL) {
            return PotionProcess.CRYSTALISE;
        }
        if (materialId == AGITATOR_ICON_MATERIAL) {
            return PotionProcess.HOMOGENISE;
        }
        return null;
    }

    private PotionProcess nearestProcessWidget(WidgetChild source, List<WidgetChild> widgets) {
        PotionProcess bestProcess = null;
        int bestDistance = Integer.MAX_VALUE;
        for (WidgetChild widget : widgets) {
            if (widget == source) {
                continue;
            }
            PotionProcess process = processFromText(widgetText(widget));
            if (process == null) {
                continue;
            }

            int yDistance = Math.abs(widget.getAbsoluteY() - source.getAbsoluteY());
            int xDistance = Math.abs(widget.getAbsoluteX() - source.getAbsoluteX());
            if (yDistance > 150 || xDistance > 520) {
                continue;
            }

            int distance = yDistance * 3 + xDistance;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestProcess = process;
            }
        }
        return bestProcess;
    }

    private String orderRowKey(WidgetChild widget) {
        return widget.getParentId() + ":" + Math.max(0, widget.getAbsoluteY() / 10);
    }

    private PotionRecipe recipeFromText(String text) {
        String normalized = normalize(text);
        for (PotionRecipe recipe : PotionRecipe.values()) {
            if (normalized.contains(normalize(recipe.displayName()))) {
                return recipe;
            }
        }

        String tokenized = text == null
                ? ""
                : text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ");
        for (String token : tokenized.split("\\s+")) {
            for (PotionRecipe recipe : PotionRecipe.values()) {
                if (token.equals(recipe.code())) {
                    return recipe;
                }
            }
        }
        return null;
    }

    private void logHudDiagnostic(List<WidgetChild> textWidgets, List<WidgetChild> hudWidgets) {
        long now = System.currentTimeMillis();
        if (now < nextHudDiagnosticAt) {
            return;
        }
        nextHudDiagnosticAt = now + 10_000L;

        StringBuilder texts = new StringBuilder();
        int textCount = 0;
        for (WidgetChild widget : textWidgets) {
            if (textCount >= 10) {
                break;
            }
            String text = widgetText(widget);
            if (text.isBlank()) {
                continue;
            }
            if (texts.length() > 0) {
                texts.append(" | ");
            }
            texts.append(widgetSummary(widget));
            textCount++;
        }

        StringBuilder icons = new StringBuilder();
        int iconCount = 0;
        for (WidgetChild widget : hudWidgets) {
            if (iconCount >= 10) {
                break;
            }
            if (widget.getMaterialId() <= 0) {
                continue;
            }
            if (icons.length() > 0) {
                icons.append(" | ");
            }
            icons.append(widgetSummary(widget));
            iconCount++;
        }

        stats.debug("Mixology HUD diagnostic textWidgets="
                + (texts.length() == 0 ? "none" : trim(texts.toString(), 600))
                + " iconWidgets="
                + (icons.length() == 0 ? "none" : trim(icons.toString(), 600)));
    }

    private void logDirectHudDiagnostic(APIContext ctx) {
        long now = System.currentTimeMillis();
        if (now < nextHudDiagnosticAt) {
            return;
        }

        WidgetChild orderList = ctx.widgets().get(ORDER_WIDGET_GROUP, ORDER_LIST_CHILD);
        if (orderList == null) {
            stats.debug("Direct Mixology HUD diagnostic: ctx.widgets().get(882,2)=null");
            return;
        }
        if (!orderList.isValid()) {
            stats.debug("Direct Mixology HUD diagnostic: widget 882,2 invalid " + widgetSummary(orderList));
            return;
        }

        List<WidgetChild> children = orderList.getChildren();
        if (children == null || children.isEmpty()) {
            stats.debug("Direct Mixology HUD diagnostic: widget 882,2 has no children " + widgetSummary(orderList));
            return;
        }

        StringBuilder summary = new StringBuilder();
        int count = 0;
        for (WidgetChild child : children) {
            if (count >= 16) {
                summary.append(" | ...");
                break;
            }
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append(widgetSummary(child));
            count++;
        }
        stats.debug("Direct Mixology HUD diagnostic children=" + trim(summary.toString(), 900));
    }

    private void logUnknownProcess(
            WidgetChild recipeWidget,
            String context,
            List<WidgetChild> widgets,
            List<WidgetChild> visibleWidgets
    ) {
        long now = System.currentTimeMillis();
        if (now < nextUnknownProcessDiagnosticAt) {
            return;
        }
        nextUnknownProcessDiagnosticAt = now + 10_000L;

        StringBuilder nearby = new StringBuilder();
        int included = 0;
        for (WidgetChild widget : widgets) {
            if (included >= 12) {
                break;
            }
            int yDistance = Math.abs(widget.getAbsoluteY() - recipeWidget.getAbsoluteY());
            int xDistance = Math.abs(widget.getAbsoluteX() - recipeWidget.getAbsoluteX());
            if (widget == recipeWidget || yDistance <= 160 && xDistance <= 560) {
                if (nearby.length() > 0) {
                    nearby.append(" | ");
                }
                nearby.append(widgetSummary(widget));
                included++;
            }
        }

        stats.debug("Unknown Mixology process widget=" + widgetSummary(recipeWidget)
                + " context='" + trim(context, 220) + "' nearby=" + trim(nearby.toString(), 500));
        stats.debug("Unknown Mixology process icon candidates near row="
                + trim(nearbyIconSummary(recipeWidget, visibleWidgets), 500));
    }

    private String nearbyIconSummary(WidgetChild recipeWidget, List<WidgetChild> widgets) {
        StringBuilder nearby = new StringBuilder();
        int included = 0;
        for (WidgetChild widget : widgets) {
            if (included >= 10) {
                break;
            }
            if (widget.getMaterialId() <= 0) {
                continue;
            }
            int yDistance = Math.abs(widget.getAbsoluteY() - recipeWidget.getAbsoluteY());
            int xDistance = Math.abs(widget.getAbsoluteX() - recipeWidget.getAbsoluteX());
            if (yDistance > 24 || xDistance > 120) {
                continue;
            }
            if (nearby.length() > 0) {
                nearby.append(" | ");
            }
            nearby.append(widgetSummary(widget));
            included++;
        }
        return nearby.length() == 0 ? "none" : nearby.toString();
    }

    private String widgetText(WidgetChild widget) {
        if (widget == null) {
            return "";
        }
        String text = widget.getText();
        if (text == null || text.isBlank()) {
            text = widget.getRawText();
        }
        return text == null ? "" : text.replace("<br>", " ").replaceAll("<[^>]+>", " ").trim();
    }

    private String widgetSignalText(WidgetChild widget) {
        if (widget == null) {
            return "";
        }

        StringBuilder text = new StringBuilder(widgetText(widget));
        String name = widget.getName();
        if (name != null && !name.isBlank() && !"null".equalsIgnoreCase(name)) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(name);
        }
        return text.toString().trim();
    }

    private String widgetSummary(WidgetChild widget) {
        if (widget == null) {
            return "null";
        }
        return "parent=" + widget.getParentId()
                + ", child=" + widget.getChildId()
                + ", index=" + widget.getIndex()
                + ", loc=" + widget.getAbsoluteX() + "," + widget.getAbsoluteY()
                + ", size=" + widget.getWidth() + "x" + widget.getHeight()
                + ", item=" + widget.getItemId()
                + ", model=" + widget.getModelId()
                + ", material=" + widget.getMaterialId()
                + ", text='" + trim(widgetText(widget), 70) + "'"
                + ", name='" + trim(widget.getName(), 70) + "'";
    }

    private String trim(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxChars) {
            return compact;
        }
        return compact.substring(0, Math.max(1, maxChars - 3)) + "...";
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
