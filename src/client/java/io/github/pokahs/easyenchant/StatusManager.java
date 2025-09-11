package io.github.pokahs.easyenchant;

import io.github.pokahs.easyenchant.SelectedItemManager.AddResult;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

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

    private String statusMsg = "";

    private TextRenderer textRenderer;

    private int x;
    private int y;
    private int width;

    private int curTextX;
    private int curTextY;

    private int defaultFadeDuration = 1500;
    private int defaultOpaqueDuration = 3000;

    private int opaqueDuration;
    private long startFadeTime;
    private long endStatusTime;

    private TextColor statusColor = TextColor.DEFAULT;


    public StatusManager(TextRenderer textRenderer, int x, int y, int width) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.textRenderer = textRenderer;
    }

    public void updateStatusTo(String msg, TextColor color, int opaqueDuration) {
        this.statusMsg = msg;
        this.statusColor = color;
        this.opaqueDuration = opaqueDuration;
        this.startFadeTime = net.minecraft.util.Util.getMeasuringTimeMs() + this.opaqueDuration;
        this.endStatusTime = this.startFadeTime + defaultFadeDuration;

        int textWidth = this.textRenderer.getWidth(statusMsg);
        int centerX = this.x + this.width / 2;

        curTextX = centerX - textWidth / 2;
        curTextY = this.y;
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


    public void render(DrawContext ctx) {
        long now = net.minecraft.util.Util.getMeasuringTimeMs();
        if (!statusMsg.isEmpty() && endStatusTime > now) {

            if (startFadeTime > now) {
                ctx.drawText(this.textRenderer, statusMsg, curTextX, curTextY, this.statusColor.getRGB(), false);
            } else {
                float remainingMs = endStatusTime - now;
                float fade = remainingMs / this.defaultFadeDuration;
                int alpha = Math.max(1, (int)(fade * 255.0f));

                if (alpha > 8) { // put a gun to my head i could not explain why vals below this randomly cause opaque rendering

                    int argb = (alpha << 24) | this.statusColor.getRGB();

                    ctx.drawText(this.textRenderer, statusMsg, curTextX, curTextY, argb, false);

                } else {
                    statusMsg = ""; // Clear the message to avoid dummy checks for last frames
                }
            }
        }
    }
}
