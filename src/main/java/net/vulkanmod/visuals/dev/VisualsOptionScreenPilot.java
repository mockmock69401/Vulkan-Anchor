package net.vulkanmod.visuals.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.visuals.VisualsLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static net.vulkanmod.visuals.render.PostProcessSettings.KEY_PREFIX;

/**
 * VisualsPilot's "vm" lines: drive VulkanMod-Reforged's option screen as a player would, without input, to check the Shaders
 * entry (VisualsSettingsPage). Reaches the screen's private page list, page switch and apply by reflection.
 * <pre>
 * vm open PAGE         open VulkanMod's video settings on the Shaders page PAGE (image, atmosphere, lighting, water)
 * vm opt KEY VALUE     change a shader option on the screen as a click would (KEY as in VisualsOptionLayout; true/false or a number)
 * vm state KEY         log a shader option's value, whether it can be changed (greyed out when not) and its tooltip
 * vm scroll PIXELS     scroll the open page's list (to shoot blocks below the first screen)
 * vm apply             press Apply
 * vm close             close the screen
 * </pre>
 */
final class VisualsOptionScreenPilot {
    private VisualsOptionScreenPilot() {
    }

    static void run(Minecraft mc, String arg) throws ReflectiveOperationException {
        String[] a = arg.split("\\s+");
        switch (a[0]) {
            case "open" -> {
                VOptionScreen screen = new VOptionScreen(Component.literal("Video Setting"), null);
                mc.setScreen(screen);
                String name = Component.translatable(KEY_PREFIX + "page." + a[1]).getString();
                List<OptionPage> pages = pages(screen);
                for (int i = 0; i < pages.size(); i++) {
                    if (pages.get(i).name.equals(name)) {
                        call(screen, "setOptionList", new Class<?>[]{int.class}, i);
                        VisualsLog.info("Visuals pilot: VulkanMod page " + name + " (" + i + " of " + pages.size() + ")");
                        return;
                    }
                }
                throw new IllegalArgumentException("no VulkanMod page " + name);
            }
            case "opt" -> {
                Option<?> o = find(screen(mc), a[1]);
                setNewValue(o, a[2]);
                VisualsLog.info("Visuals pilot: VulkanMod option " + a[1] + " = " + o.getNewValue() + " (" + o.getDisplayedValue().getString() + ")");
            }
            case "state" -> {
                Option<?> o = find(screen(mc), a[1]);
                o.getWidget();
                o.updateActiveState();
                Component tooltip = o.getTooltip();
                VisualsLog.info("Visuals pilot: VulkanMod option " + a[1] + " = " + o.getNewValue() + ", active " + o.getWidget().active
                        + ", tooltip " + (tooltip == null ? "none" : tooltip.getString()));
            }
            case "scroll" -> {
                //the open page's list scrolled to PIXELS (clamped), to shoot blocks below the first screen
                Field f = VOptionScreen.class.getDeclaredField("currentListIdx");
                f.setAccessible(true);
                pages(screen(mc)).get(f.getInt(screen(mc))).getOptionList().setScrollAmount(Double.parseDouble(a[1]));
            }
            case "apply" -> call(screen(mc), "applyOptions", new Class<?>[0]);
            case "close" -> mc.setScreen(null);
            default -> throw new IllegalArgumentException("vm " + arg);
        }
    }

    private static VOptionScreen screen(Minecraft mc) {
        if (mc.screen instanceof VOptionScreen s) return s;
        throw new IllegalStateException("VulkanMod's screen is not open");
    }

    @SuppressWarnings("unchecked")
    private static List<OptionPage> pages(VOptionScreen screen) throws ReflectiveOperationException {
        Field f = VOptionScreen.class.getDeclaredField("optionPages");
        f.setAccessible(true);
        return (List<OptionPage>) f.get(screen);
    }

    private static void call(VOptionScreen screen, String method, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        Method m = VOptionScreen.class.getDeclaredMethod(method, types);
        m.setAccessible(true);
        m.invoke(screen, args);
    }

    private static Option<?> find(VOptionScreen screen, String key) throws ReflectiveOperationException {
        for (OptionPage page : pages(screen)) {
            for (OptionBlock block : page.optionBlocks) {
                for (Option<?> o : block.options()) {
                    if (o.getName().getContents() instanceof TranslatableContents t && t.getKey().equals(KEY_PREFIX + key)) return o;
                }
            }
        }
        throw new IllegalArgumentException("no shader option " + key);
    }

    @SuppressWarnings("unchecked")
    private static void setNewValue(Option<?> o, String value) {
        Object current = o.getNewValue();
        Object v = current instanceof Boolean ? (Object) Boolean.parseBoolean(value) : (Object) Integer.parseInt(value);
        ((Option<Object>) o).setNewValue(v);
    }
}
