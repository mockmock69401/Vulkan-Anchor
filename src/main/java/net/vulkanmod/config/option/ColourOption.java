package net.vulkanmod.config.option;

import net.minecraft.network.chat.Component;
import net.vulkanmod.config.gui.widget.ColourOptionWidget;
import net.vulkanmod.config.gui.widget.OptionWidget;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 2026-11-10 (session 8, the shader colour pickers): a colour, 0xRRGGBB, shown as a swatch and its hex; clicking it opens the picker
 * (ColourPickerPopup: hue / saturation / value, RGB, hex, reset to its default). Like every option it is written on Apply.
 */
public class ColourOption extends Option<Integer> {
    private final int defaultRgb;

    public ColourOption(Component name, int defaultRgb, Consumer<Integer> setter, Supplier<Integer> getter) {
        super(name, setter, getter, ColourOption::hexComponent);
        this.defaultRgb = defaultRgb & 0xFFFFFF;
    }

    /** The colour a reset gives (0xRRGGBB). */
    public int defaultRgb() {
        return this.defaultRgb;
    }

    @Override
    public void setNewValue(Integer rgb) {
        super.setNewValue(rgb & 0xFFFFFF);
    }

    /** "#RRGGBB". */
    public static String hex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    private static Component hexComponent(Integer rgb) {
        return Component.literal(hex(rgb));
    }

    @Override
    protected OptionWidget<?> createWidget() {
        var widget = new ColourOptionWidget(this, this.name);
        this.widget = widget;
        return widget;
    }
}
