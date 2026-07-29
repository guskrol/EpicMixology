package org.gusta.mixology.core;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.util.time.Time;

import java.util.function.Consumer;

public class CameraController implements RuntimeController {
    private static final boolean ZOOM_OUT_SCROLL_DIRECTION = false;
    private static final long ZOOM_RECHECK_MILLIS = 35_000L;
    private static final int STARTUP_ZOOM_SCROLLS = 14;
    private static final int MAINTENANCE_ZOOM_SCROLLS = 5;
    private static final int ANTIBAN_ZOOM_SCROLLS = 4;

    private final Consumer<String> logger;
    private boolean adjusted;
    private long nextZoomCheckAt;

    public CameraController(Consumer<String> logger) {
        this.logger = logger;
    }

    @Override
    public String name() {
        return "runtime.camera";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return canAdjustCamera(ctx) && (!adjusted || System.currentTimeMillis() >= nextZoomCheckAt);
    }

    @Override
    public void execute(APIContext ctx) {
        if (!adjusted) {
            logger.accept("[Camera] Setting max zoom Mixology view");
            ctx.camera().setPitch(360);
            ctx.camera().setYawDeg(0);
            zoomOut(ctx, STARTUP_ZOOM_SCROLLS);
            adjusted = true;
            nextZoomCheckAt = System.currentTimeMillis() + ZOOM_RECHECK_MILLIS;
            Time.sleep(350, 650);
            return;
        }

        zoomOut(ctx, MAINTENANCE_ZOOM_SCROLLS);
        nextZoomCheckAt = System.currentTimeMillis() + ZOOM_RECHECK_MILLIS;
        Time.sleep(180, 320);
    }

    static void restoreMaxZoomAfterCameraNudge(APIContext ctx) {
        if (canAdjustCamera(ctx)) {
            zoomOut(ctx, ANTIBAN_ZOOM_SCROLLS);
        }
    }

    private static boolean canAdjustCamera(APIContext ctx) {
        return !ctx.bank().isOpen()
                && !ctx.grandExchange().isOpen()
                && !ctx.widgets().isInterfaceOpen()
                && !ctx.localPlayer().isMoving()
                && !ctx.localPlayer().isAnimating();
    }

    private static void zoomOut(APIContext ctx, int scrolls) {
        for (int i = 0; i < scrolls; i++) {
            ctx.mouse().scroll(ZOOM_OUT_SCROLL_DIRECTION);
            Time.sleep(35, 70);
        }
    }
}
