package org.gusta.mixology.core;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.util.time.Time;

import java.util.function.Consumer;

public class CameraController implements RuntimeController {
    private final Consumer<String> logger;
    private boolean adjusted;

    public CameraController(Consumer<String> logger) {
        this.logger = logger;
    }

    @Override
    public String name() {
        return "runtime.camera";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return !adjusted && !ctx.localPlayer().isMoving();
    }

    @Override
    public void execute(APIContext ctx) {
        logger.accept("[Camera] Setting comfortable Mixology view");
        ctx.camera().setPitch(360);
        ctx.camera().setYawDeg(0);
        adjusted = true;
        Time.sleep(500, 900);
    }
}
