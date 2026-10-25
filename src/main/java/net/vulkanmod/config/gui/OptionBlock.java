package net.vulkanmod.config.gui;

import net.vulkanmod.config.option.Option;

/** A titled group of options; note, when not null or empty, is a line of text shown under them (wrapped to the list's width). */
public record OptionBlock(String title, Option<?>[] options, String note) {

    public OptionBlock(String title, Option<?>[] options) {
        this(title, options, null);
    }
}
