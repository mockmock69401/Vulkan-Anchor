package net.vulkanmod.config.option;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 2026-10-31 (the user: the shader menu's reset to defaults was a switch): a button runs its action each press and holds no value. */
class ButtonOptionTest {
    @Test
    void eachPressRunsTheAction() {
        AtomicInteger presses = new AtomicInteger();
        ButtonOption button = new ButtonOption(Component.literal("reset"));
        button.setOnPress(presses::incrementAndGet);

        button.press();
        button.press();

        assertEquals(2, presses.get());
    }

    @Test
    void aPressLeavesNothingToApply() {
        ButtonOption button = new ButtonOption(Component.literal("reset"));
        button.setOnPress(() -> {
        });

        button.press();

        assertFalse(button.isChanged());
        button.apply();
    }
}
