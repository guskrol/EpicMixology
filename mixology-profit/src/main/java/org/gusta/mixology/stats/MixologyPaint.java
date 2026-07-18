package org.gusta.mixology.stats;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.util.paint.PaintContext;

import java.awt.Color;
import java.awt.Rectangle;

public class MixologyPaint {
    private static final String VERSION = "v0.1.88-capped-hopper-bank-confirm";

    public void paint(PaintContext paint, APIContext ctx, MixologyStats stats) {
        if (paint == null || ctx == null || stats == null) {
            return;
        }
        stats.startExperienceIfNeeded(ctx);

        int x = 8;
        int y = 230;
        int width = 300;
        int height = 212;
        paint.fill(new Rectangle(x, y, width, height), new Color(15, 20, 24, 190));
        paint.draw(new Rectangle(x, y, width, height), new Color(230, 235, 245, 210), 1);

        int line = y + 20;
        paint.drawText("Mixology Profit " + VERSION, x + 12, line, Color.WHITE, 14);
        line += 18;
        paint.drawText("Runtime: " + stats.runtimeText(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("State: " + stats.state(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Status: " + shortText(stats.status(), 38), x + 12, line, new Color(195, 210, 230), 11);
        line += 16;
        paint.drawText("Target: " + shortText(stats.targetReward(), 36), x + 12, line, new Color(245, 228, 160), 12);
        line += 16;
        paint.drawText("Order: " + shortText(stats.lastOrder(), 38), x + 12, line, new Color(220, 235, 255), 11);
        line += 16;
        paint.drawText("Trips: " + stats.tripsStarted()
                + " | Potions: " + stats.potionsMixed()
                + " | Orders: " + stats.ordersCompleted(), x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Herblore XP: " + stats.herbloreXpGained(ctx)
                + " (" + stats.herbloreXpPerHour(ctx) + "/h)", x + 12, line, new Color(220, 235, 255), 12);
        line += 16;
        paint.drawText("Profit est.: " + stats.estimatedProfit()
                + " (" + stats.estimatedProfitPerHour() + "/h)", x + 12, line, new Color(245, 228, 160), 12);
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
}
