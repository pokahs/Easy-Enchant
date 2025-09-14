package io.github.pokahs.easyenchant;

import java.util.List;

import io.github.pokahs.easyenchant.SelectedItemManager.AddResult;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

public class StatusManager {

    public enum TextColor {
        DEFAULT(0xFFFFFF),
        ERROR(0xFF0000),
        SUCCESS(0x00FF00),
        WARNING(0xFF8F00),
        PROCESSING(0x6F00FF);

        private final int RGB;

        // Constructor
        TextColor(int RGB) {
            this.RGB = RGB;
        }

        // Getter
        public int getRGB() {
            return RGB;
        }
    }

    private TextRenderer textRenderer;

    private int x;
    private int y;
    private int screenWidth;

    private int defaultFadeDuration = 1500;
    private int defaultOpaqueDuration = 3000;

    private int opaqueDuration;
    private long startFadeTime;
    private long endStatusTime;

    private TextColor statusColor = TextColor.DEFAULT;

    private List<OrderedText> statusLines = java.util.Collections.emptyList();



    public StatusManager(TextRenderer textRenderer, int x, int y, int screenWidth) {
        this.textRenderer = textRenderer;
        this.x = x;
        this.y = y;
        this.screenWidth = screenWidth;
    }

    public void updateStatusTo(String msg, TextColor color, int opaqueDuration) {
        this.statusColor = color;
        this.opaqueDuration = opaqueDuration;
        this.startFadeTime = net.minecraft.util.Util.getMeasuringTimeMs() + this.opaqueDuration;
        this.endStatusTime = this.startFadeTime + defaultFadeDuration;

        this.statusLines = this.textRenderer.wrapLines(
            Text.literal(msg),
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

    private void render(DrawContext ctx, int color) {
        for (int i = 0; i < statusLines.size(); i++) {
            OrderedText line = statusLines.get(i);
            int lineWidth = textRenderer.getWidth(line);
            int lineX = this.x - lineWidth / 2;

            ctx.drawText(textRenderer, line, lineX, this.y + i * textRenderer.fontHeight, color, false);
        }
    }


    public void tryRender(DrawContext ctx) {
        long now = net.minecraft.util.Util.getMeasuringTimeMs();
        if (!statusLines.isEmpty() && endStatusTime > now) {

            if (startFadeTime > now) render(ctx, statusColor.getRGB());
            else {
                float remainingMs = endStatusTime - now;
                float fade = remainingMs / defaultFadeDuration;
                int alpha = Math.max(1, (int)(fade * 255.0f));

                if (alpha > 8) { // put a gun to my head i could not explain why vals below this randomly cause opaque rendering

                    int argb = (alpha << 24) | statusColor.getRGB();

                    render(ctx, argb);

                } else {
                    statusLines = java.util.Collections.emptyList();
                }
            }
        }
    }
}
