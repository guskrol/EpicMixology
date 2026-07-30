package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.details.Locatable;
import com.epicbot.api.shared.util.time.Time;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class ViewRecovery {
    private static final boolean ZOOM_OUT_SCROLL_DIRECTION = false;
    private static final int MIN_ZOOM_OUT_SCROLLS = 6;
    private static final int MAX_ZOOM_OUT_SCROLLS = 11;
    private static final int MIN_PITCH = 340;
    private static final int MAX_PITCH = 384;

    private ViewRecovery() {
    }

    public static void recover(APIContext ctx, String targetLabel, Consumer<String> logger) {
        recover(ctx, null, targetLabel, logger);
    }

    public static void recover(APIContext ctx, Locatable focus, String targetLabel, Consumer<String> logger) {
        if (ctx == null) {
            return;
        }

        log(logger, "Adjusting camera/zoom to search for " + safeLabel(targetLabel));
        clearInteractionState(ctx);
        turnCamera(ctx, focus);
        moveMouseToViewport(ctx);
        zoomOut(ctx);
        Time.sleep(700, 1200);
    }

    private static void clearInteractionState(APIContext ctx) {
        try {
            if (ctx.menu().isOpen()) {
                ctx.menu().closeMenu();
                Time.sleep(150, 300);
            }
            if (ctx.inventory().isItemSelected()) {
                ctx.inventory().deselectItem();
                Time.sleep(150, 300);
            }
        } catch (RuntimeException ignored) {
            // Camera recovery must not break the owning script state.
        }
    }

    private static void turnCamera(APIContext ctx, Locatable focus) {
        try {
            if (focus != null && ThreadLocalRandom.current().nextInt(100) < 45) {
                ctx.camera().turnTo(focus);
                Time.sleep(250, 450);
            }
        } catch (RuntimeException ignored) {
            // Fallback to yaw/pitch below.
        }

        try {
            int yaw = (ctx.camera().getYawDeg() + ThreadLocalRandom.current().nextInt(75, 181)) % 360;
            int pitch = ThreadLocalRandom.current().nextInt(MIN_PITCH, MAX_PITCH + 1);
            ctx.camera().setYawDeg(yaw);
            ctx.camera().setPitch(pitch, true);
        } catch (RuntimeException ignored) {
            // Some client states temporarily reject camera commands.
        }
    }

    private static void moveMouseToViewport(APIContext ctx) {
        try {
            Rectangle viewport = ctx.game().getViewport();
            if (viewport != null && viewport.width > 0 && viewport.height > 0) {
                int x = viewport.x + Math.max(80, viewport.width / 3);
                int y = viewport.y + Math.max(80, viewport.height / 3);
                ctx.mouse().move(new Point(x, y));
                return;
            }

            int x = Math.max(120, ctx.client().getCanvasWidth() / 2);
            int y = Math.max(120, ctx.client().getCanvasHeight() / 2);
            ctx.mouse().move(new Point(x, y));
        } catch (RuntimeException ignored) {
            // Scrolling can still help when the mouse move fails.
        }
    }

    private static void zoomOut(APIContext ctx) {
        try {
            int scrolls = ThreadLocalRandom.current().nextInt(MIN_ZOOM_OUT_SCROLLS, MAX_ZOOM_OUT_SCROLLS + 1);
            ctx.mouse().scroll(ZOOM_OUT_SCROLL_DIRECTION, scrolls);
        } catch (RuntimeException ignored) {
            // Ignore; yaw/pitch recovery already happened.
        }
    }

    private static String safeLabel(String targetLabel) {
        return targetLabel == null || targetLabel.isBlank() ? "scene object" : targetLabel;
    }

    private static void log(Consumer<String> logger, String message) {
        if (logger != null) {
            logger.accept(message);
        }
    }
}
