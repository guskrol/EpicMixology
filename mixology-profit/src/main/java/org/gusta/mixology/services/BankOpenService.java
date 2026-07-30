package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.stats.MixologyStats;

import java.awt.event.KeyEvent;

public final class BankOpenService {
    private static final int MAX_OPEN_ATTEMPTS = 4;

    private BankOpenService() {
    }

    public static boolean open(APIContext ctx, MixologyStats stats, String status) {
        if (ctx.bank().isOpen()) {
            stats.scanOpenBankInventory(ctx);
            return true;
        }

        for (int attempt = 1; attempt <= MAX_OPEN_ATTEMPTS; attempt++) {
            closeBlockingContext(ctx);
            if (ctx.bank().isOpen()) {
                stats.scanOpenBankInventory(ctx);
                return true;
            }

            stats.setStatus(attempt == 1 ? status : status + " retry " + attempt);
            ctx.bank().open();
            Time.sleep(700, 1100, () -> ctx.bank().isOpen() || hasBlockingContext(ctx), 100);
            if (ctx.bank().isOpen()) {
                stats.scanOpenBankInventory(ctx);
                return true;
            }

            if (hasBlockingContext(ctx)) {
                stats.setStatus("Bank click opened dialogue/menu; closing and retrying");
                closeBlockingContext(ctx);
                Time.sleep(250, 450);
            } else if (attempt < MAX_OPEN_ATTEMPTS) {
                recoverBankView(ctx, stats, attempt);
            }
        }

        boolean opened = ctx.bank().isOpen();
        if (opened) {
            stats.scanOpenBankInventory(ctx);
        }
        return opened;
    }

    public static boolean closeBlockingContext(APIContext ctx) {
        boolean hadContext = hasBlockingContext(ctx);
        if (!hadContext) {
            return false;
        }

        if (ctx.dialogues().canContinue()) {
            ctx.dialogues().selectContinue();
            Time.sleep(200, 350);
        }

        if (hasBlockingContext(ctx)) {
            ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            Time.sleep(250, 400);
        }

        if (hasBlockingContext(ctx)) {
            ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            Time.sleep(250, 400);
        }

        if (hasBlockingContext(ctx)) {
            ctx.keyboard().sendKey(KeyEvent.VK_ESCAPE);
            Time.sleep(250, 400);
        }

        return hadContext;
    }

    private static boolean hasBlockingContext(APIContext ctx) {
        return ctx.menu().isOpen()
                || ctx.dialogues().isDialogueOpen()
                || ctx.dialogues().canContinue();
    }

    private static void recoverBankView(APIContext ctx, MixologyStats stats, int attempt) {
        stats.setStatus("Bank not visible; rotating camera retry " + (attempt + 1));
        ViewRecovery.recover(ctx, "nearest bank", message -> stats.debug("Bank view recovery: " + message));
    }
}
