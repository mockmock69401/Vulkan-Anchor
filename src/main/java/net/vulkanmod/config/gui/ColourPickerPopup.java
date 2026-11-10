package net.vulkanmod.config.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.gui.util.ColourPickerMath;
import net.vulkanmod.config.option.ColourOption;
import org.lwjgl.glfw.GLFW;

/**
 * 2026-11-10 (session 8, the shader colour pickers): the picker a ColourOption opens over VOptionScreen (modal: the screen hands it every
 * click and key while it is open). A saturation / value square and a hue bar, red / green / blue sliders, the hex (click to type,
 * Enter applies), the colour before and now side by side, and a reset to the option's default. Every change is the option's new value at
 * once (its row shows it; Apply writes it like any option); Cancel or Escape puts back the colour it had when the picker opened, Done,
 * Enter or a click outside keeps it.
 */
public class ColourPickerPopup {
    public static final int WIDTH = 272, HEIGHT = 166;
    private static final int SV = 104, HUE_WIDTH = 12, SLIDER = 82, BUTTON_HEIGHT = 20;
    private static final int NONE = 0, SQUARE = 1, HUE = 2, RED = 3, GREEN = 4, BLUE = 5;
    private static final int PANEL = 0xF0202020, EDGE = 0xFFA0A0A0, TEXT = 0xFFFFFFFF, MUTED = 0xFFB0B0B0;

    private final ColourOption option;
    private final Component title;
    private final int opened;
    private final int x, y;
    private float hue, saturation, value;
    private int rgb;
    private int dragging = NONE;
    private boolean typing;
    private String typed = "";
    private boolean closed;

    public ColourPickerPopup(ColourOption option, Component title, int screenWidth, int screenHeight) {
        this.option = option;
        this.title = title;
        this.opened = option.getNewValue();
        this.x = Math.max(4, (screenWidth - WIDTH) / 2);
        this.y = Math.max(4, (screenHeight - HEIGHT) / 2);
        this.setRgb(this.opened, true);
    }

    public ColourOption option() {
        return this.option;
    }

    public boolean closed() {
        return this.closed;
    }

    /** The colour now (0xRRGGBB). */
    public int rgb() {
        return this.rgb;
    }

    /** Sets the colour (as the hex field or a slider would) and the option's new value. */
    public void setRgb(int rgb) {
        this.setRgb(rgb, false);
    }

    private void setRgb(int rgb, boolean init) {
        this.rgb = rgb & 0xFFFFFF;
        float[] hsv = ColourPickerMath.rgbToHsv(this.rgb, this.hue, this.saturation);
        this.hue = hsv[0];
        this.saturation = hsv[1];
        this.value = hsv[2];
        if (!init) this.option.setNewValue(this.rgb);
    }

    /** Sets hue, saturation and value (0..1), as the square and the bar do. */
    public void setHsv(float h, float s, float v) {
        this.hue = h - (float) Math.floor(h);
        this.saturation = Math.max(0, Math.min(1, s));
        this.value = Math.max(0, Math.min(1, v));
        this.rgb = ColourPickerMath.hsvToRgb(this.hue, this.saturation, this.value);
        this.option.setNewValue(this.rgb);
    }

    /** The option's default colour. */
    public void reset() {
        this.typing = false;
        this.setRgb(this.option.defaultRgb());
    }

    /** Closes keeping the colour (done) or putting back the one it had when opened. */
    public void close(boolean keep) {
        if (!keep) this.option.setNewValue(this.opened);
        this.closed = true;
    }

    // ---- layout ----

    private int squareX() { return this.x + 8; }
    private int squareY() { return this.y + 22; }
    private int hueX() { return this.squareX() + SV + 6; }
    private int rightX() { return this.hueX() + HUE_WIDTH + 8; }
    private int sliderY(int channel) { return this.y + 62 + channel * 15; }
    private int hexY() { return this.y + 108; }
    private int buttonsY() { return this.y + HEIGHT - BUTTON_HEIGHT - 8; }
    private int[] defaultButton() { return new int[]{this.x + 8, this.buttonsY(), 112, BUTTON_HEIGHT}; }
    private int[] cancelButton() { return new int[]{this.x + WIDTH - 8 - 64 - 4 - 64, this.buttonsY(), 64, BUTTON_HEIGHT}; }
    private int[] doneButton() { return new int[]{this.x + WIDTH - 8 - 64, this.buttonsY(), 64, BUTTON_HEIGHT}; }
    private int[] hexBox() { return new int[]{this.rightX() + 12, this.hexY(), 60, 16}; }

    private static boolean in(double mx, double my, int[] r) {
        return mx >= r[0] && my >= r[1] && mx < r[0] + r[2] && my < r[1] + r[3];
    }

    public boolean isInside(double mx, double my) {
        return mx >= this.x && my >= this.y && mx < this.x + WIDTH && my < this.y + HEIGHT;
    }

    // ---- drawing ----

    public void render(double mouseX, double mouseY, int screenWidth, int screenHeight) {
        Font font = Minecraft.getInstance().font;
        GuiRenderer.fill(0, 0, screenWidth, screenHeight, 0x80000000);
        GuiRenderer.fill(this.x, this.y, this.x + WIDTH, this.y + HEIGHT, PANEL);
        GuiRenderer.renderBorder(this.x, this.y, this.x + WIDTH, this.y + HEIGHT, 1, EDGE);
        GuiRenderer.drawString(font, this.title, this.x + 8, this.y + 8, TEXT);

        //the saturation (across) / value (down) square at this hue, one column at a time
        int sx = this.squareX(), sy = this.squareY();
        for (int i = 0; i < SV; i++) {
            int top = 0xFF000000 | ColourPickerMath.hsvToRgb(this.hue, i / (float) (SV - 1), 1);
            GuiRenderer.fillGradient(sx + i, sy, sx + i + 1, sy + SV, top, 0xFF000000);
        }
        GuiRenderer.renderBorder(sx - 1, sy - 1, sx + SV + 1, sy + SV + 1, 1, EDGE);
        int cx = sx + Math.round(this.saturation * (SV - 1)), cy = sy + Math.round((1 - this.value) * (SV - 1));
        GuiRenderer.renderBorder(cx - 3, cy - 3, cx + 4, cy + 4, 1, this.value > 0.6f ? 0xFF000000 : 0xFFFFFFFF);

        //the hue bar
        int hx = this.hueX();
        for (int j = 0; j < SV; j++) GuiRenderer.fill(hx, sy + j, hx + HUE_WIDTH, sy + j + 1, 0xFF000000 | ColourPickerMath.hsvToRgb(j / (float) SV, 1, 1));
        GuiRenderer.renderBorder(hx - 1, sy - 1, hx + HUE_WIDTH + 1, sy + SV + 1, 1, EDGE);
        int hy = sy + Math.round(this.hue * (SV - 1));
        GuiRenderer.fill(hx - 2, hy - 1, hx + HUE_WIDTH + 2, hy + 2, 0xFFFFFFFF);
        GuiRenderer.fill(hx, hy, hx + HUE_WIDTH, hy + 1, 0xFF000000);

        //before and now
        int rx = this.rightX();
        GuiRenderer.fill(rx, sy, rx + 56, sy + 22, 0xFF000000 | this.opened);
        GuiRenderer.fill(rx + 60, sy, rx + 116, sy + 22, 0xFF000000 | this.rgb);
        GuiRenderer.renderBorder(rx - 1, sy - 1, rx + 117, sy + 23, 1, EDGE);
        GuiRenderer.drawString(font, Component.translatable("vulkanmod.options.colour.before"), rx, sy + 26, MUTED);
        GuiRenderer.drawString(font, Component.translatable("vulkanmod.options.colour.now"), rx + 60, sy + 26, MUTED);

        //red, green, blue
        String[] names = {"R", "G", "B"};
        for (int c = 0; c < 3; c++) {
            int y0 = this.sliderY(c), bx = rx + 12;
            GuiRenderer.drawString(font, Component.literal(names[c]), rx, y0 + 1, TEXT);
            for (int k = 0; k < SLIDER; k += 2) {
                int v = Math.round(k / (float) (SLIDER - 1) * 255);
                GuiRenderer.fill(bx + k, y0, bx + k + 2, y0 + 10, 0xFF000000 | ColourPickerMath.withChannel(this.rgb, c, v));
            }
            GuiRenderer.renderBorder(bx - 1, y0 - 1, bx + SLIDER + 1, y0 + 11, 1, EDGE);
            int mark = bx + Math.round(ColourPickerMath.channel(this.rgb, c) / 255f * (SLIDER - 1));
            GuiRenderer.fill(mark - 1, y0 - 2, mark + 2, y0 + 12, 0xFFFFFFFF);
            GuiRenderer.fill(mark, y0 - 1, mark + 1, y0 + 11, 0xFF000000);
            GuiRenderer.drawString(font, Component.literal(Integer.toString(ColourPickerMath.channel(this.rgb, c))), bx + SLIDER + 4, y0 + 1, MUTED);
        }

        //the hex
        int[] hb = this.hexBox();
        GuiRenderer.drawString(font, Component.literal("#"), rx, hb[1] + 4, TEXT);
        GuiRenderer.fill(hb[0], hb[1], hb[0] + hb[2], hb[1] + hb[3], 0xFF000000);
        GuiRenderer.renderBorder(hb[0], hb[1], hb[0] + hb[2], hb[1] + hb[3], 1, this.typing ? 0xFFFFFFFF : EDGE);
        String shown = this.typing ? this.typed + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "") : ColourPickerMath.hex(this.rgb);
        GuiRenderer.drawString(font, Component.literal(shown), hb[0] + 5, hb[1] + 4, this.typing ? TEXT : MUTED);

        //buttons
        button(font, this.defaultButton(), Component.translatable("vulkanmod.options.colour.default", ColourOption.hex(this.option.defaultRgb())), mouseX, mouseY);
        button(font, this.cancelButton(), CommonComponents.GUI_CANCEL, mouseX, mouseY);
        button(font, this.doneButton(), CommonComponents.GUI_DONE, mouseX, mouseY);
    }

    private static void button(Font font, int[] r, Component text, double mx, double my) {
        boolean hover = in(mx, my, r);
        GuiRenderer.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? 0xC0505050 : 0xC0303030);
        GuiRenderer.renderBorder(r[0], r[1], r[0] + r[2], r[1] + r[3], 1, hover ? 0xFFFFFFFF : EDGE);
        GuiRenderer.drawCenteredString(font, text, r[0] + r[2] / 2, r[1] + (r[3] - 8) / 2, TEXT);
    }

    // ---- input ----

    /** A click: true when it was on the panel (a click outside it is for the screen: it closes the picker keeping the colour). */
    public boolean mouseClicked(double mx, double my, int button) {
        if (!this.isInside(mx, my)) return false;
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true;
        if (this.typing && !in(mx, my, this.hexBox())) this.commitTyped();
        int sx = this.squareX(), sy = this.squareY();
        if (in(mx, my, new int[]{sx - 2, sy - 2, SV + 4, SV + 4})) this.dragging = SQUARE;
        else if (in(mx, my, new int[]{this.hueX() - 2, sy - 2, HUE_WIDTH + 4, SV + 4})) this.dragging = HUE;
        else {
            for (int c = 0; c < 3; c++) {
                if (in(mx, my, new int[]{this.rightX() + 10, this.sliderY(c) - 3, SLIDER + 4, 16})) this.dragging = RED + c;
            }
        }
        if (this.dragging != NONE) {
            this.drag(mx, my);
            return true;
        }
        if (in(mx, my, this.hexBox())) {
            this.typing = true;
            this.typed = "";
        } else if (in(mx, my, this.defaultButton())) {
            this.reset();
        } else if (in(mx, my, this.cancelButton())) {
            this.close(false);
        } else if (in(mx, my, this.doneButton())) {
            this.close(true);
        }
        return true;
    }

    public void mouseDragged(double mx, double my) {
        if (this.dragging != NONE) this.drag(mx, my);
    }

    public void mouseReleased() {
        this.dragging = NONE;
    }

    private void drag(double mx, double my) {
        int sx = this.squareX(), sy = this.squareY();
        float u = (float) ((mx - sx) / (SV - 1)), w = (float) ((my - sy) / (SV - 1));
        switch (this.dragging) {
            case SQUARE -> this.setHsv(this.hue, u, 1 - w);
            case HUE -> this.setHsv(Math.max(0, Math.min(0.9999f, w)), this.saturation, this.value);
            case RED, GREEN, BLUE -> {
                int c = this.dragging - RED;
                float k = (float) ((mx - (this.rightX() + 12)) / (SLIDER - 1));
                this.setRgb(ColourPickerMath.withChannel(this.rgb, c, Math.round(Math.max(0, Math.min(1, k)) * 255)));
            }
            default -> {
            }
        }
    }

    /** A key: Escape cancels, Enter applies the typed hex (or closes keeping the colour); the hex field takes Backspace. */
    public boolean keyPressed(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.typing) this.typing = false;
            else this.close(false);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (this.typing) this.commitTyped();
            else this.close(true);
            return true;
        }
        if (this.typing && keyCode == GLFW.GLFW_KEY_BACKSPACE && !this.typed.isEmpty()) {
            this.typed = this.typed.substring(0, this.typed.length() - 1);
        }
        return true;
    }

    public boolean charTyped(char c) {
        if (!this.typing) return true;
        if (Character.digit(c, 16) >= 0 && this.typed.length() < 6) this.typed += Character.toUpperCase(c);
        if (this.typed.length() == 6) this.commitTyped();
        return true;
    }

    /** Types hex into the field and applies it (the pilot's way in; a typed "#" is skipped). */
    public void type(String hex) {
        this.typing = true;
        this.typed = "";
        for (char c : hex.toCharArray()) this.charTyped(c);
        if (this.typing) this.commitTyped();
    }

    private void commitTyped() {
        int v = ColourPickerMath.parseHex(this.typed);
        this.typing = false;
        if (v >= 0) this.setRgb(v);
    }
}
