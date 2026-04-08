package io.github.pokahs.easyenchant;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import io.github.pokahs.easyenchant.SelectedItemManager.AddResult;

public class StatusManager {

    public enum TextColor {
        DEFAULT(0xFFFFFF),
        ERROR(0xFF0000),
        SUCCESS(0x00FF00),
        WARNING(0xFF8F00),
        PROCESSING(0x6F00FF);

        private final int A_RGB;

        // Constructor
        TextColor(int RGB) {
            this.A_RGB = ARGB.opaque(RGB);
        }

        // Getter
        public int getA_RGB() {
            return A_RGB;
        }
    }

    private Font textRenderer;

    private int x;
    private int y;
    private int screenWidth;

    private int defaultFadeDuration = 1500;
    private int defaultOpaqueDuration = 3000;

    private int opaqueDuration;
    private long startFadeTime;
    private long endStatusTime;

    private TextColor statusColor = TextColor.DEFAULT;

    private List<FormattedCharSequence> statusLines = java.util.Collections.emptyList();



    public StatusManager(Font textRenderer, int x, int y, int screenWidth) {
        this.textRenderer = textRenderer;
        this.x = x;
        this.y = y;
        this.screenWidth = screenWidth;
    }

    public void updateStatusTo(String msg, TextColor color, int opaqueDuration) {
        this.statusColor = color;
        this.opaqueDuration = opaqueDuration;
        this.startFadeTime = net.minecraft.util.Util.getMillis() + this.opaqueDuration;
        this.endStatusTime = this.startFadeTime + defaultFadeDuration;

        this.statusLines = this.textRenderer.split(
            Component.literal(msg),
            this.screenWidth
        );
        
    }

    public void updateStatusTo(String msg, TextColor color) {
        updateStatusTo(msg, color, defaultOpaqueDuration);
    }

    public void updateStatusTo(String msg) {
        updateStatusTo(msg, TextColor.DEFAULT);
    }

    public void updateStatusTo(AddResult result) {
        updateStatusTo(result.failReason(), result.successful() ? TextColor.SUCCESS : TextColor.ERROR);
    }

    private void render(GuiGraphicsExtractor ctx, int color) {
        for (int i = 0; i < statusLines.size(); i++) {
            FormattedCharSequence line = statusLines.get(i);
            int lineWidth = textRenderer.width(line);
            int lineX = this.x - lineWidth / 2;

            ctx.text(textRenderer, line, lineX, this.y + i * textRenderer.lineHeight, color);
        }
    }


    public void tryRender(GuiGraphicsExtractor ctx) {
        long now = net.minecraft.util.Util.getMillis();
        if (!statusLines.isEmpty() && endStatusTime > now) {

            if (startFadeTime > now) render(ctx, statusColor.getA_RGB());
            else {
                float remainingMs = endStatusTime - now;
                float fade = remainingMs / defaultFadeDuration;

                int argb = ARGB.color(fade, statusColor.getA_RGB());
                    

                render(ctx, argb);

            }
        }
    }
}
