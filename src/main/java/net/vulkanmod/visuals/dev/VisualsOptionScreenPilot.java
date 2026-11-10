package net.vulkanmod.visuals.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.vulkanmod.config.gui.ColourPickerPopup;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.gui.VOptionScreen;
import net.vulkanmod.config.gui.util.ColourPickerMath;
import net.vulkanmod.config.gui.widget.ColourOptionWidget;
import net.vulkanmod.config.option.ButtonOption;
import net.vulkanmod.config.option.ColourOption;
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
 * vm press KEY        press a shader button (reset_defaults)
 * vm apply             press Apply
 * vm close             close the screen
 * vm picker KEY        open a colour option's picker (session 8), as a click on its swatch
 * vm pick hex RRGGBB   type a hex into the open picker; pick hsv H S V (0..1) as the square and bar would; pick rgb C V (channel
 *                      0..2, 0..255) as a slider; pick reset (its default), pick done, pick cancel; pick state logs it
 * </pre>
 * A colour option's VALUE in vm opt may be #RRGGBB.
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
                //from the end: the Shaders pages follow VulkanMod's own, and in Korean "image" and VulkanMod's video page are both 화면
                for (int i = pages.size() - 1; i >= 0; i--) {
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
            case "press" -> {
                if (!(find(screen(mc), a[1]) instanceof ButtonOption b)) throw new IllegalArgumentException("not a button: " + a[1]);
                b.press();
                VisualsLog.info("Visuals pilot: VulkanMod button " + a[1] + " pressed");
            }
            case "apply" -> call(screen(mc), "applyOptions", new Class<?>[0]);
            case "close" -> mc.setScreen(null);
            case "picker" -> {
                if (!(find(screen(mc), a[1]) instanceof ColourOption o)) throw new IllegalArgumentException("not a colour: " + a[1]);
                ((ColourOptionWidget) o.getWidget()).openPicker();
                VisualsLog.info("Visuals pilot: VulkanMod colour picker " + a[1] + " opened at " + ColourOption.hex(o.getNewValue()));
            }
            case "pick" -> {
                ColourPickerPopup p = screen(mc).popup();
                if (p == null) throw new IllegalStateException("no colour picker open");
                switch (a[1]) {
                    case "hex" -> p.type(a[2]);
                    case "hsv" -> p.setHsv(Float.parseFloat(a[2]), Float.parseFloat(a[3]), Float.parseFloat(a[4]));
                    case "rgb" -> p.setRgb(ColourPickerMath.withChannel(p.rgb(), Integer.parseInt(a[2]), Integer.parseInt(a[3])));
                    case "reset" -> p.reset();
                    case "done" -> p.close(true);
                    case "cancel" -> p.close(false);
                    case "state" -> {
                    }
                    default -> throw new IllegalArgumentException("vm pick " + a[1]);
                }
                VisualsLog.info("Visuals pilot: VulkanMod colour picker " + a[1] + ": " + ColourOption.hex(p.rgb()) + ", option "
                        + ColourOption.hex(p.option().getNewValue()) + (p.closed() ? " (closed)" : ""));
                if (p.closed()) screen(mc).openPopup(null);
            }
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

    /** 2026-11-10 (session 4): an option's own name (a range option's getName() is a literal with its value, which hid every slider). */
    private static final Field NAME;

    static {
        try {
            NAME = Option.class.getDeclaredField("name");
            NAME.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** key@N: the Nth widget of the key, from 1 (session 8 follow-up: a palette's look settings are on their page and on the colours page). */
    private static Option<?> find(VOptionScreen screen, String key) throws ReflectiveOperationException {
        int at = key.indexOf('@');
        int skip = at < 0 ? 0 : Integer.parseInt(key.substring(at + 1)) - 1;
        String name = at < 0 ? key : key.substring(0, at);
        for (OptionPage page : pages(screen)) {
            for (OptionBlock block : page.optionBlocks) {
                for (Option<?> o : block.options()) {
                    if (NAME.get(o) instanceof Component c && c.getContents() instanceof TranslatableContents t && t.getKey().equals(KEY_PREFIX + name)
                            && skip-- == 0) return o;
                }
            }
        }
        throw new IllegalArgumentException("no shader option " + key);
    }

    @SuppressWarnings("unchecked")
    private static void setNewValue(Option<?> o, String value) {
        Object current = o.getNewValue();
        Object v = current instanceof Boolean ? (Object) Boolean.parseBoolean(value)
                : value.startsWith("#") ? (Object) ColourPickerMath.parseHex(value) : (Object) Integer.parseInt(value);
        ((Option<Object>) o).setNewValue(v);
    }
}
