package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.WidgetChild;
import org.gusta.mixology.domain.HopperStock;
import org.gusta.mixology.domain.PasteType;
import org.gusta.mixology.stats.MixologyStats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class HopperStockReader {
    private static final int ORDER_WIDGET_GROUP = 882;
    private static final int ORDER_LIST_CHILD = 2;
    private static final int STOCK_HUD_MIN_X = 0;
    private static final int STOCK_HUD_MAX_X = 240;
    private static final int STOCK_HUD_MIN_Y = 88;
    private static final int STOCK_HUD_MAX_Y = 145;
    private static final int STOCK_ROW_Y_TOLERANCE = 16;

    private final MixologyStats stats;
    private long nextDiagnosticAt;

    public HopperStockReader(MixologyStats stats) {
        this.stats = stats;
    }

    public Optional<HopperStock> readStock(APIContext ctx) {
        List<StockWidget> numbers = stockNumberWidgets(ctx);
        if (numbers.size() < PasteType.values().length) {
            logDiagnostic("not enough numeric widgets", numbers);
            return Optional.empty();
        }

        int rowY = numbers.stream()
                .mapToInt(StockWidget::y)
                .max()
                .orElse(0);
        List<StockWidget> stockRow = new ArrayList<>();
        for (StockWidget number : numbers) {
            if (Math.abs(number.y() - rowY) <= STOCK_ROW_Y_TOLERANCE) {
                stockRow.add(number);
            }
        }
        stockRow.sort(Comparator.comparingInt(StockWidget::x));

        if (stockRow.size() < PasteType.values().length) {
            logDiagnostic("stock row incomplete", stockRow);
            return Optional.empty();
        }

        Map<PasteType, Integer> amounts = new EnumMap<>(PasteType.class);
        amounts.put(PasteType.MOX, stockRow.get(0).amount());
        amounts.put(PasteType.AGA, stockRow.get(1).amount());
        amounts.put(PasteType.LYE, stockRow.get(2).amount());

        HopperStock stock = new HopperStock(amounts);
        if (!stock.isComplete()) {
            logDiagnostic("parsed incomplete stock", stockRow);
            return Optional.empty();
        }
        return Optional.of(stock);
    }

    private List<StockWidget> stockNumberWidgets(APIContext ctx) {
        List<StockWidget> numbers = new ArrayList<>();
        for (WidgetChild widget : orderHudChildren(ctx)) {
            if (!isInStockHud(widget)) {
                continue;
            }

            Integer amount = parseAmount(widgetText(widget));
            if (amount == null) {
                continue;
            }
            numbers.add(new StockWidget(amount, widget.getAbsoluteX(), widget.getAbsoluteY(), widgetSummary(widget)));
        }
        numbers.sort(Comparator
                .comparingInt(StockWidget::y)
                .thenComparingInt(StockWidget::x));
        return numbers;
    }

    private List<WidgetChild> orderHudChildren(APIContext ctx) {
        WidgetChild orderList = ctx.widgets().get(ORDER_WIDGET_GROUP, ORDER_LIST_CHILD);
        if (orderList != null && orderList.isValid() && orderList.getChildren() != null) {
            return orderList.getChildren();
        }

        return ctx.widgets().getAllChildren(widget -> widget != null
                && widget.isValid()
                && widget.getWidth() > 0
                && widget.getHeight() > 0
                && isInStockHud(widget));
    }

    private boolean isInStockHud(WidgetChild widget) {
        if (widget == null) {
            return false;
        }
        int x = widget.getAbsoluteX();
        int y = widget.getAbsoluteY();
        return x >= STOCK_HUD_MIN_X
                && x <= STOCK_HUD_MAX_X
                && y >= STOCK_HUD_MIN_Y
                && y <= STOCK_HUD_MAX_Y;
    }

    private Integer parseAmount(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("<[^>]+>", " ")
                .replace(",", "")
                .trim();
        if (!normalized.matches("\\d{1,6}")) {
            return null;
        }
        return Integer.parseInt(normalized);
    }

    private void logDiagnostic(String reason, List<StockWidget> numbers) {
        long now = System.currentTimeMillis();
        if (now < nextDiagnosticAt) {
            return;
        }
        nextDiagnosticAt = now + 15_000L;

        StringBuilder widgets = new StringBuilder();
        for (StockWidget number : numbers) {
            if (widgets.length() > 0) {
                widgets.append(" | ");
            }
            widgets.append(number.summary());
        }
        stats.debug("Hopper stock read diagnostic: " + reason
                + " candidates=" + (widgets.length() == 0 ? "none" : widgets));
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

    private String widgetSummary(WidgetChild widget) {
        return "child=" + widget.getChildId()
                + ", index=" + widget.getIndex()
                + ", loc=" + widget.getAbsoluteX() + "," + widget.getAbsoluteY()
                + ", text='" + widgetText(widget) + "'"
                + ", material=" + widget.getMaterialId();
    }

    private static class StockWidget {
        private final int amount;
        private final int x;
        private final int y;
        private final String summary;

        private StockWidget(int amount, int x, int y, String summary) {
            this.amount = amount;
            this.x = x;
            this.y = y;
            this.summary = summary;
        }

        private int amount() {
            return amount;
        }

        private int x() {
            return x;
        }

        private int y() {
            return y;
        }

        private String summary() {
            return summary;
        }
    }
}
