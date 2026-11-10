package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.config.VisualsOptionLayout.Item;
import net.vulkanmod.visuals.dh.DhPresence;
import net.vulkanmod.visuals.fsr.FsrSettings;
import net.vulkanmod.visuals.fsr.FsrSupport;
import net.vulkanmod.visuals.render.PostProcessSettings;
import net.vulkanmod.visuals.render.ShaderColours;
import net.vulkanmod.visuals.render.ShadingSettings;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.config.gui.ModSettingsEntry;
import net.vulkanmod.config.gui.ModSettingsRegistry;
import net.vulkanmod.config.gui.OptionBlock;
import net.vulkanmod.config.option.ButtonOption;
import net.vulkanmod.config.option.ColourOption;
import net.vulkanmod.config.option.CyclingOption;
import net.vulkanmod.config.option.Option;
import net.vulkanmod.config.option.OptionPage;
import net.vulkanmod.config.option.PerformanceImpact;
import net.vulkanmod.config.option.RangeOption;
import net.vulkanmod.config.option.SwitchOption;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static net.vulkanmod.visuals.render.PostProcessSettings.KEY_PREFIX;

/**
 * The visuals settings as a "Shaders" entry in the video settings screen (ModSettingsRegistry), laid out by VisualsOptionLayout.
 * The options write VisualsConfig.get() when the screen applies them (the renderer reads it every frame); the entry's apply
 * saves and logs it.
 */
public final class VisualsSettingsPage {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod");

    private VisualsSettingsPage() {
    }

    public static void register() {
        ModSettingsRegistry.INSTANCE.addModEntry(new ModSettingsEntry(Component.translatable(KEY_PREFIX + "title"),
                () -> new ResourceLocation("vulkanmod", "vlogo_transparent.png"), VisualsSettingsPage::pages, VisualsSettingsPage::save));
        LOGGER.info("Shader settings added to the video settings screen");
    }

    private static VisualsConfig config() {
        return VisualsConfig.get();
    }

    /**
     * A page's blocks as the screen shows them: the Distant Horizons block only when the fork draws DH's LODs (DhPresence, which names
     * no DH type); without DH, or with a DH that cannot draw through the fork, the screen is the fork's own. The settings stay in
     * the config either way.
     */
    static List<VisualsOptionLayout.Block> shownBlocks(VisualsOptionLayout.Page page, DhPresence.State dh) {
        if (DhPresence.drawsLods(dh)) return page.blocks();
        return page.blocks().stream().filter(b -> !b.key().equals(VisualsOptionLayout.DH_BLOCK)).toList();
    }

    /** Built each time the screen opens, from the current settings. */
    static List<OptionPage> pages() {
        DhPresence.State dh = DhPresence.current();
        Map<String, Option<?>> byKey = new HashMap<>();
        Map<Option<?>, Item> items = new HashMap<>();
        List<OptionPage> pages = new ArrayList<>();
        //A reset to the defaults (which shows them, for Apply or Undo), in the first page's general block under the preset, as a button
        // (2026-10-31, the user: it was a switch that turned itself back off)
        ButtonOption reset = new ButtonOption(Component.translatable(KEY_PREFIX + VisualsOptionLayout.RESET));
        reset.setTooltip(Component.translatable(KEY_PREFIX + VisualsOptionLayout.RESET + ".tooltip"));
        //2026-11-10 (session 8): the colours page's own reset, first on it (every colour to its default: the look before the pickers)
        ButtonOption resetColours = new ButtonOption(Component.translatable(KEY_PREFIX + VisualsOptionLayout.RESET_COLOURS));
        resetColours.setTooltip(Component.translatable(KEY_PREFIX + VisualsOptionLayout.RESET_COLOURS + ".tooltip"));
        for (VisualsOptionLayout.Page page : VisualsOptionLayout.PAGES) {
            List<OptionBlock> blocks = new ArrayList<>();
            for (VisualsOptionLayout.Block block : shownBlocks(page, dh)) {
                List<Option<?>> list = new ArrayList<>();
                for (Item item : block.items()) {
                    Option<?> o = option(item);
                    byKey.put(item.key(), o);
                    items.put(o, item);
                    list.add(o);
                }
                //2026-11-10 (session 4): the first page's general block is the preset, then the reset
                if (block.key().equals(VisualsOptionLayout.GENERAL)) list.add(reset);
                if (block.key().equals(VisualsOptionLayout.COLOURS_FIRST)) list.add(Math.min(1, list.size()), resetColours);
                Option<?>[] options = list.toArray(Option<?>[]::new);
                String noteKey = KEY_PREFIX + "block." + block.key() + ".note";
                String note = net.minecraft.locale.Language.getInstance().has(noteKey) ? Component.translatable(noteKey).getString() : null;
                blocks.add(new OptionBlock(Component.translatable(KEY_PREFIX + "block." + block.key()).getString(), options, note));
            }
            pages.add(new OptionPage(Component.translatable(KEY_PREFIX + "page." + page.key()).getString(), blocks.toArray(OptionBlock[]::new)));
        }
        //Everything but frame generation follows the post-process switch (VisualsOptionLayout.followsMasterSwitch), and an item its own
        // switch; a switch updates the others as it changes
        Option<?> enabled = byKey.get(VisualsOptionLayout.ENABLED);
        for (VisualsOptionLayout.Page page : VisualsOptionLayout.PAGES) {
            for (VisualsOptionLayout.Block block : shownBlocks(page, dh)) {
                for (Item item : block.items()) {
                    if (item.key().equals(VisualsOptionLayout.ENABLED)) continue;
                    Option<?> requires = item.requires() == null ? null : byKey.get(item.requires());
                    boolean master = VisualsOptionLayout.followsMasterSwitch(item);
                    Option<?> option = byKey.get(item.key());
                    if (item.key().equals(VisualsOptionLayout.FSR_FRAME_GENERATION)) {
                        //greyed out with the reason as its tooltip when FSR cannot run here (FAILED is picked up the next time the page opens)
                        FsrSupport.Reason fsr = FsrSupport.current();
                        if (fsr != FsrSupport.Reason.OK) option.setTooltip(Component.translatable(FsrSettings.unavailableKey(fsr)));
                        option.setActivationFn(() -> fsr == FsrSupport.Reason.OK);
                        continue;
                    }
                    option.setActivationFn(() -> (!master || on(enabled)) && (requires == null || on(requires)));
                }
            }
        }
        Runnable refresh = () -> pages.forEach(OptionPage::updateOptionStates);
        for (Option<?> o : byKey.values()) {
            if (o instanceof SwitchOption || o instanceof CyclingOption) o.setOnChange(refresh);
        }
        linkPreset(byKey, refresh);
        linkColourPreset(byKey);
        //Optical Flow and FSR frame generation exclude each other: turning one on shows the other off (FsrSettings.onToggle does the same in the config)
        excludeEachOther(byKey.get(VisualsOptionLayout.FRAME_GENERATION), byKey.get(VisualsOptionLayout.FSR_FRAME_GENERATION), refresh);
        excludeEachOther(byKey.get(VisualsOptionLayout.FSR_FRAME_GENERATION), byKey.get(VisualsOptionLayout.FRAME_GENERATION), refresh);
        reset.setOnPress(() -> {
            items.forEach(VisualsSettingsPage::showDefault);
            refresh.run();
        });
        resetColours.setOnPress(() -> {
            items.forEach((o, item) -> {
                if (item.kind() == VisualsOptionLayout.Kind.COLOUR) showDefault(o, item);
            });
            refresh.run();
        });
        return pages;
    }

    /**
     * 2026-11-10 (session 4): choosing a preset level shows its values on every preset setting the page has (Apply writes them, and
     * the preset's own apply sets the hidden ones, VisualsPresets.apply); changing any preset setting shows the level they now all
     * match, or Custom (VisualsPresets.detect; a setting the page hides is not compared).
     */
    @SuppressWarnings("unchecked")
    private static void linkPreset(Map<String, Option<?>> byKey, Runnable refresh) {
        Option<Integer> preset = (Option<Integer>) byKey.get(VisualsOptionLayout.PRESET);
        if (preset == null) return;
        boolean[] linking = {false};
        preset.setOnChange(() -> {
            int level = preset.getNewValue();
            if (!linking[0] && VisualsPresets.isLevel(level)) {
                linking[0] = true;
                try {
                    for (String key : VisualsPresets.VALUES.keySet()) {
                        Option<?> o = byKey.get(key);
                        if (o != null) show(o, VisualsOptionLayout.item(key), VisualsPresets.value(key, level));
                    }
                } finally {
                    linking[0] = false;
                }
            }
            refresh.run();
        });
        Runnable follow = () -> {
            if (linking[0]) return;
            int shown = VisualsPresets.detect(key -> byKey.containsKey(key) ? intValue(byKey.get(key)) : Integer.MIN_VALUE);
            if (preset.getNewValue() == shown) return;
            linking[0] = true;
            try {
                preset.setNewValue(shown);
            } finally {
                linking[0] = false;
            }
        };
        for (String key : VisualsPresets.VALUES.keySet()) {
            Option<?> o = byKey.get(key);
            if (o == null) continue;
            boolean refreshes = o instanceof SwitchOption || o instanceof CyclingOption;
            o.setOnChange(() -> {
                follow.run();
                if (refreshes) refresh.run();
            });
        }
    }

    /**
     * 2026-11-10 (session 8): choosing a colour palette shows its colours on every colour option (Apply writes them); changing any colour
     * shows the palette they are all on now, or Custom (ColourPresets.detect). As linkPreset, for the colours.
     */
    @SuppressWarnings("unchecked")
    private static void linkColourPreset(Map<String, Option<?>> byKey) {
        Option<Integer> preset = (Option<Integer>) byKey.get(VisualsOptionLayout.COLOUR_PRESET);
        if (preset == null) return;
        boolean[] linking = {false};
        preset.setOnChange(() -> {
            int level = preset.getNewValue();
            if (linking[0] || !ColourPresets.isLevel(level)) return;
            linking[0] = true;
            try {
                for (ShaderColours.Colour c : ShaderColours.Colour.values()) {
                    if (byKey.get(c.key()) instanceof ColourOption o) o.setNewValue(ColourPresets.value(c, level));
                }
            } finally {
                linking[0] = false;
            }
        });
        Runnable follow = () -> {
            if (linking[0]) return;
            int shown = ColourPresets.detect(c -> byKey.get(c.key()) instanceof ColourOption o ? o.getNewValue() : c.defaultRgb());
            if (preset.getNewValue() == shown) return;
            linking[0] = true;
            try {
                preset.setNewValue(shown);
            } finally {
                linking[0] = false;
            }
        };
        for (ShaderColours.Colour c : ShaderColours.Colour.values()) {
            if (byKey.get(c.key()) instanceof ColourOption o) o.setOnChange(follow);
        }
    }

    private static int intValue(Option<?> o) {
        Object v = o.getNewValue();
        return v instanceof Boolean b ? (b ? 1 : 0) : (Integer) v;
    }

    @SuppressWarnings("unchecked")
    private static void show(Option<?> o, Item item, int v) {
        if (item.kind() == VisualsOptionLayout.Kind.SWITCH) {
            ((Option<Boolean>) o).setNewValue(v != 0);
        } else {
            ((Option<Integer>) o).setNewValue(v);
        }
    }

    private static void showDefault(Option<?> o, Item item) {
        show(o, item, VisualsOptionLayout.defaultValue(item));
    }

    @SuppressWarnings("unchecked")
    private static void excludeEachOther(Option<?> one, Option<?> other, Runnable refresh) {
        one.setOnChange(() -> {
            if (on(one) && on(other)) ((Option<Boolean>) other).setNewValue(false);
            refresh.run();
        });
    }

    //2026-11-09 (session 3): a choice counts as on when it is not its first entry (the cloud style: the realistic clouds)
    private static boolean on(Option<?> o) {
        Object v = o.getNewValue();
        return Boolean.TRUE.equals(v) || v instanceof Integer i && i != 0;
    }

    private static Option<?> option(Item item) {
        Component name = Component.translatable(KEY_PREFIX + item.key());
        Option<?> o = switch (item.kind()) {
            //2026-10-29 (the user): turning a generator on no longer sets the frame limit to 60
            case SWITCH -> new SwitchOption(name, v -> VisualsOptionLayout.set(config(), item, v ? 1 : 0),
                    () -> VisualsOptionLayout.get(config(), item) != 0);
            case PERCENT -> new RangeOption(name, item.min(), item.max(), 1, v -> Component.literal(v + "%"),
                    v -> VisualsOptionLayout.set(config(), item, v), () -> VisualsOptionLayout.get(config(), item));
            case BLOCKS -> new RangeOption(name, item.min(), item.max(), 1, v -> Component.literal(Integer.toString(v)),
                    v -> VisualsOptionLayout.set(config(), item, v), () -> VisualsOptionLayout.get(config(), item));
            case CHUNKS -> new RangeOption(name, item.min(), item.max(), 1, v -> Component.translatable("options.chunks", v),
                    v -> VisualsOptionLayout.set(config(), item, v), () -> VisualsOptionLayout.get(config(), item));
            case CHOICE -> new CyclingOption<>(name, IntStream.rangeClosed(item.min(), item.max()).boxed().toArray(Integer[]::new),
                    v -> VisualsOptionLayout.set(config(), item, v), () -> VisualsOptionLayout.get(config(), item))
                    .setTranslator(v -> Component.translatable(KEY_PREFIX + item.key() + "." + v));
            //2026-11-10 (session 8): a shader colour, its swatch and hex; the picker (ColourPickerPopup) with a reset to its default
            case COLOUR -> new ColourOption(name, VisualsOptionLayout.defaultValue(item), v -> VisualsOptionLayout.set(config(), item, v),
                    () -> VisualsOptionLayout.get(config(), item));
        };
        o.setTooltip(Component.translatable(KEY_PREFIX + item.key() + ".tooltip"));
        PerformanceImpact impact = switch (item.impact()) {
            case NONE -> null;
            case LOW -> PerformanceImpact.LOW;
            case MEDIUM -> PerformanceImpact.MEDIUM;
            case HIGH -> PerformanceImpact.HIGH;
        };
        if (impact != null) o.setImpact(impact);
        return o;
    }

    /** The screen's apply (after the changed options wrote VisualsConfig.get()): save and log. */
    private static void save() {
        VisualsConfig c = config();
        c.revision = VisualsConfig.currentRevision();
        c.save();
        LOGGER.info("Shader settings: " + PostProcessSettings.from(c).describe() + ", " + ShadingSettings.from(c).describe());
    }
}
