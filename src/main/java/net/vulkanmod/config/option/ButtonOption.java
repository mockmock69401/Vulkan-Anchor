package net.vulkanmod.config.option;

import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.widget.ButtonOptionWidget;
import net.vulkanmod.config.gui.widget.OptionWidget;

/**
 * 2026-10-31 (the user: the shader menu's reset to defaults was a switch): an action shown as a button, its name as the label. It
 * holds no value, so it is never changed and applying it does nothing.
 */
public class ButtonOption extends Option<Boolean> {
    private Runnable onPress = () -> {
    };

    public ButtonOption(Component name) {
        super(name, v -> {
        }, () -> false, v -> name);
    }

    public ButtonOption setOnPress(Runnable onPress) {
        this.onPress = onPress;
        return this;
    }

    public void press() {
        this.onPress.run();
    }

    @Override
    protected OptionWidget<?> createWidget() {
        var widget = new ButtonOptionWidget(this, this.name);
        this.widget = widget;
        return widget;
    }
}
