package net.vulkanmod.config.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.render.GuiRenderer;
import net.vulkanmod.config.option.ButtonOption;
import net.vulkanmod.vulkan.util.ColorUtil;

/** A ButtonOption's row: one button across the row, the option's name centred on it; a click anywhere on it presses. */
public class ButtonOptionWidget extends OptionWidget<ButtonOption> {

    public ButtonOptionWidget(ButtonOption option, Component name) {
        super(option, name);
    }

    @Override
    public void renderWidget(double mouseX, double mouseY) {
        int color = ColorUtil.ARGB.pack(0.0f, 0.0f, 0.0f, 0.45f);
        GuiRenderer.fill(this.x, this.y, this.x + this.width, this.y + this.height, color);

        this.renderHovering(0, 0);
        this.renderControls(mouseX, mouseY);
    }

    @Override
    protected void renderControls(double mouseX, double mouseY) {
        int x0 = this.x + 4;
        int y0 = this.y + 3;
        int w = this.width - 8;
        int h = this.height - 6;

        int color = ColorUtil.ARGB.pack(1.0f, 1.0f, 1.0f, this.active ? 0.12f : 0.05f);
        GuiRenderer.fillBox(x0, y0, w, h, color);

        color = this.active ? ColorUtil.ARGB.pack(0.6f, 0.6f, 0.6f, 1.0f) : ColorUtil.ARGB.pack(0.4f, 0.4f, 0.4f, 1.0f);
        GuiRenderer.renderBoxBorder(x0, y0, w, h, 1, color);

        color = this.active ? 0xFFFFFFFF : 0xFFA0A0A0;
        GuiRenderer.drawCenteredString(Minecraft.getInstance().font, this.getName(), this.x + this.width / 2, this.y + (this.height - 8) / 2, color);
    }

    @Override
    protected boolean clicked(double mouseX, double mouseY) {
        return this.active && this.visible && mouseX >= this.x && mouseY >= this.y && mouseX < this.x + this.width && mouseY < this.y + this.height;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.option.press();
    }

    @Override
    public void onRelease(double mouseX, double mouseY) {
    }

    @Override
    protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
    }
}
