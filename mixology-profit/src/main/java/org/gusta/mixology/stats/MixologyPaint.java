package org.gusta.mixology.stats;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.util.paint.PaintContext;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.Locale;

public class MixologyPaint {
    private final String version;
    private final String updateNote;

    public MixologyPaint(String version) {
        this(version, "");
    }

    public MixologyPaint(String version, String updateNote) {
        this.version = version == null || version.isBlank() ? "unknown" : version;
        this.updateNote = updateNote == null ? "" : updateNote.trim();
    }

    public void paint(PaintContext paint, APIContext ctx, MixologyStats stats) {
        if (paint == null || ctx == null || stats == null) {
            return;
        }
        stats.startExperienceIfNeeded(ctx);

        int x = 8;
        int y = 230;
        int width = 310;
        int height = 198;
        Color panel = new Color(12, 17, 22, 205);
        Color border = new Color(210, 220, 230, 210);
        Color header = new Color(34, 46, 58, 220);
        Color primary = new Color(240, 246, 255);
        Color muted = new Color(190, 205, 220);
        Color accent = new Color(247, 205, 116);
        Color good = new Color(135, 220, 160);

        paint.fill(new Rectangle(x, y, width, height), panel);
        paint.fill(new Rectangle(x, y, width, 30), header);
        paint.draw(new Rectangle(x, y, width, height), border, 1);

        int line = y + 21;
        paint.drawText("Mixology Profit " + version, x + 12, line, primary, 14);
        line += 24;
        paint.drawText("Runtime: " + stats.runtimeText()
                + "  |  State: " + stats.state(), x + 12, line, muted, 12);
        line += 16;
        paint.drawText("Status: " + shortText(stats.status(), 45), x + 12, line, muted, 11);
        line += 16;
        paint.drawText("Order: " + shortText(stats.lastOrder(), 45), x + 12, line, primary, 11);
        line += 16;
        paint.drawText("Aldarium bank: " + stats.bankedAldariumText()
                + "  |  claimable: " + stats.estimatedClaimableAldarium(), x + 12, line, good, 12);
        line += 16;
        paint.drawText("Trigger: " + stats.aldariumTriggerText(), x + 12, line, accent, 12);
        line += 16;
        paint.drawText("Aldarium Price: " + formatNumber(stats.aldariumUnitPrice()) + " gp", x + 12, line, accent, 12);
        line += 16;
        paint.drawText("Herblore XP: " + stats.herbloreXpGained(ctx)
                + " (" + formatNumber(stats.herbloreXpPerHour(ctx)) + "/h)", x + 12, line, primary, 12);
        line += 16;
        paint.drawText("Trips: " + stats.tripsStarted()
                + "  |  Potions: " + stats.potionsMixed()
                + "  |  Orders: " + stats.ordersCompleted(), x + 12, line, primary, 12);
        line += 16;
        paint.drawText("Profit est.: " + formatNumber(stats.estimatedProfit())
                + " gp (" + formatNumber(stats.estimatedProfitPerHour()) + "/h)", x + 12, line, accent, 12);
        if (!updateNote.isBlank()) {
            paint.drawText(shortText(updateNote, 34), x + 12, y + height - 8, muted, 9);
        }
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(1, maxChars - 3)) + "...";
    }

    private String formatNumber(long value) {
        return String.format(Locale.US, "%,d", value);
    }
}
