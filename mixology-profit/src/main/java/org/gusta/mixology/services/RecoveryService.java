package org.gusta.mixology.services;

import com.epicbot.api.shared.APIContext;
import com.epicbot.api.shared.entity.WidgetChild;
import com.epicbot.api.shared.util.time.Time;
import org.gusta.mixology.stats.MixologyStats;

import java.awt.event.KeyEvent;
import java.util.List;

public class RecoveryService {
    private final MixologyStats stats;
    private long nextIdleChatLogAt;

    public RecoveryService(MixologyStats stats) {
        this.stats = stats;
    }

    public boolean clearBlockingState(APIContext ctx) {
        return clearBlockingState(ctx, true);
    }

    public boolean clearBlockingState(APIContext ctx, boolean closeGrandExchange) {
        if (ctx.menu().isOpen()) {
            stats.setStatus("Closing open menu actions=" + ctx.menu().getActions()
                    + " options=" + ctx.menu().getOptions());
            ctx.menu().closeMenu();
            Time.sleep(250, 450);
            return true;
        }
        if (ctx.inventory().isItemSelected()) {
            stats.setStatus("Deselecting inventory item: " + ctx.inventory().getSelectedItemName()
                    + " id=" + ctx.inventory().getSelectedItemId()
                    + " index=" + ctx.inventory().getSelectedItemIndex());
            ctx.inventory().deselectItem();
            Time.sleep(250, 450);
            return true;
        }
        if (ctx.dialogues().canContinue()) {
            stats.setStatus("Continuing dialogue text='" + shortText(ctx.dialogues().getText(), 80) + "'");
            ctx.dialogues().selectContinue();
            Time.sleep(450, 800);
            return true;
        }

        List<WidgetChild> options = ctx.dialogues().getOptions();
        if (options != null && !options.isEmpty()) {
            stats.setStatus("Dialogue options visible: " + optionSummary(options));
            ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            Time.sleep(450, 800);
            return true;
        }

        if (ctx.dialogues().isDialogueOpen()) {
            stats.setStatus("Dialogue open without continue/options; pressing space. text='"
                    + shortText(ctx.dialogues().getText(), 80) + "'");
            ctx.keyboard().sendKey(KeyEvent.VK_SPACE);
            Time.sleep(450, 800);
            return true;
        }

        if (ctx.dialogues().isChatOpen() && System.currentTimeMillis() >= nextIdleChatLogAt) {
            stats.debug("Chatbox is open but no blocking dialogue was detected; continuing script. text='"
                    + shortText(ctx.dialogues().getText(), 80) + "'");
            nextIdleChatLogAt = System.currentTimeMillis() + 10_000L;
        }

        if (closeGrandExchange && ctx.grandExchange().isOpen()) {
            stats.setStatus("Closing Grand Exchange");
            ctx.grandExchange().close();
            Time.sleep(500, 900);
            return true;
        }
        return false;
    }

    private String optionSummary(List<WidgetChild> options) {
        StringBuilder summary = new StringBuilder();
        int count = 0;
        for (WidgetChild option : options) {
            if (option == null) {
                continue;
            }
            String text = option.getText();
            if (text == null || text.isBlank()) {
                text = option.getRawText();
            }
            if (text == null || text.isBlank()) {
                continue;
            }
            if (count > 0) {
                summary.append(" | ");
            }
            summary.append(shortText(text.replaceAll("<[^>]+>", " "), 60));
            count++;
            if (count >= 4) {
                summary.append(" | ...");
                break;
            }
        }
        return count == 0 ? "unknown options" : summary.toString();
    }

    private String shortText(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String normalized = value.replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, Math.max(1, maxChars - 3)) + "...";
    }
}
