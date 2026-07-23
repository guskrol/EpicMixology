package org.gusta.mixology.core;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.methods.ITabsAPI;
import com.epicbot.api.shared.util.time.Time;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class SimpleAntibanController implements RuntimeController {
    private final Consumer<String> logger;
    private final boolean safeInputMode;
    private long nextActionAt;

    public SimpleAntibanController(Consumer<String> logger) {
        this(logger, false);
    }

    public SimpleAntibanController(Consumer<String> logger, boolean safeInputMode) {
        this.logger = logger;
        this.safeInputMode = safeInputMode;
        scheduleNextAction(70, 180);
    }

    @Override
    public String name() {
        return "runtime.antiban";
    }

    @Override
    public boolean shouldExecute(APIContext ctx) {
        return System.currentTimeMillis() >= nextActionAt
                && !ctx.bank().isOpen()
                && !ctx.grandExchange().isOpen()
                && !ctx.widgets().isInterfaceOpen()
                && !ctx.localPlayer().isMoving()
                && !ctx.localPlayer().isAnimating();
    }

    @Override
    public void execute(APIContext ctx) {
        if (safeInputMode) {
            logger.accept("[Antiban] Safe multi-client mode: camera nudge only");
            ctx.camera().setYawDeg(randomInt(0, 360));
            ctx.camera().setPitch(randomInt(260, 380));
            Time.sleep(450, 1000);
            scheduleNextAction(80, 220);
            return;
        }

        int roll = ThreadLocalRandom.current().nextInt(100);
        if (roll < 45) {
            logger.accept("[Antiban] Camera nudge");
            ctx.camera().setYawDeg(randomInt(0, 360));
            ctx.camera().setPitch(randomInt(260, 380));
            Time.sleep(450, 1000);
        } else if (roll < 75) {
            logger.accept("[Antiban] Mouse drift");
            ctx.mouse().moveRandomly(randomInt(120, 430), randomInt(220, 720));
            Time.sleep(250, 700);
        } else {
            logger.accept("[Antiban] Inventory tab check");
            ctx.tabs().open(ITabsAPI.Tabs.INVENTORY);
            Time.sleep(600, 1300);
        }
        scheduleNextAction(80, 220);
    }

    private void scheduleNextAction(int minSeconds, int maxSeconds) {
        nextActionAt = System.currentTimeMillis()
                + ThreadLocalRandom.current().nextLong(minSeconds, maxSeconds + 1L) * 1000L;
    }

    private int randomInt(int minInclusive, int maxInclusive) {
        return ThreadLocalRandom.current().nextInt(minInclusive, maxInclusive + 1);
    }
}
