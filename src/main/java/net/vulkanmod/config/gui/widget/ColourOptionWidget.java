package net.vulkanmod.config.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.ColourPickerPopup;
import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.option.ColourOption;

/**
 * 2026-11-10 (session 8): a colour option's row: its swatch (a small mark in the corner when it is not its default) and its hex; a click
 * opens the picker over the screen (VOptionScreen.openPopup).
 */
public class ColourOptionWidget extends OptionWidget<ColourOption> {
    private static final int SWATCH_WIDTH = 34;

    public ColourOptionWidget(ColourOption option, Component name) {
        super(option, name);
        this.updateDisplayedValue();
    }

    @Override
    protected void renderControls(double mouseX, double mouseY) {
        int rgb = this.option.getNewValue();
        int x0 = this.controlX + 2, y0 = this.y + 4, y1 = this.y + this.height - 4;
        int border = this.active ? (this.controlHovered ? 0xFFFFFFFF : 0xFFA0A0A0) : 0xFF606060;
        GuiRenderer.fill(x0, y0, x0 + SWATCH_WIDTH, y1, 0xFF000000 | (this.active ? rgb : dim(rgb)));
        GuiRenderer.renderBorder(x0, y0, x0 + SWATCH_WIDTH, y1, 1, border);
        if (rgb != this.option.defaultRgb()) {
            //a corner mark: not the default
            GuiRenderer.fill(x0 + SWATCH_WIDTH - 5, y0 + 1, x0 + SWATCH_WIDTH - 1, y0 + 5, 0xFFFFFFFF);
            GuiRenderer.fill(x0 + SWATCH_WIDTH - 4, y0 + 2, x0 + SWATCH_WIDTH - 2, y0 + 4, 0xFF000000);
        }
        Font font = Minecraft.getInstance().font;
        int color = this.active ? 0xFFFFFFFF : 0xFFA0A0A0;
        GuiRenderer.drawString(font, this.getDisplayedValue(), x0 + SWATCH_WIDTH + 8, this.y + (this.height - 8) / 2, color);
    }

    private static int dim(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return ((r / 2 + 40) << 16) | ((g / 2 + 40) << 8) | (b / 2 + 40);
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.openPicker();
    }

    /** Opens the picker for this option over the open option screen (nothing without one). */
    public void openPicker() {
        if (Minecraft.getInstance().screen instanceof VOptionScreen screen) {
            screen.openPopup(new ColourPickerPopup(this.option, this.getName(), screen.width, screen.height));
        }
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
    }
}
