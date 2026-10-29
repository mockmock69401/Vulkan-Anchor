package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.config.VisualsOptionLayout.Item;
import net.vulkanmod.visuals.dh.DhPresence;
import net.vulkanmod.visuals.fsr.FsrSettings;
import net.vulkanmod.visuals.fsr.FsrSupport;
import net.vulkanmod.visuals.render.PostProcessSettings;
import net.vulkanmod.visuals.render.ShadingSettings;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.config.gui.ModSettingsEntry;
import net.vulkanmod.config.gui.ModSettingsRegistry;
import net.vulkanmod.config.gui.OptionBlock;
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
                () -> ResourceLocation.fromNamespaceAndPath("vulkanmod", "vlogo_transparent.png"), VisualsSettingsPage::pages, VisualsSettingsPage::save));
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
        //A reset to the defaults (which shows them, for Apply or Undo), above the first page's blocks
        SwitchOption reset = new SwitchOption(Component.translatable(KEY_PREFIX + VisualsOptionLayout.RESET), v -> {
        }, () -> false);
        reset.setTooltip(Component.translatable(KEY_PREFIX + VisualsOptionLayout.RESET + ".tooltip"));
        boolean first = true;
        for (VisualsOptionLayout.Page page : VisualsOptionLayout.PAGES) {
            List<OptionBlock> blocks = new ArrayList<>();
            if (first) {
                blocks.add(new OptionBlock(Component.translatable(KEY_PREFIX + "block." + VisualsOptionLayout.GENERAL).getString(), new Option<?>[]{reset}));
                first = false;
            }
            for (VisualsOptionLayout.Block block : shownBlocks(page, dh)) {
                Option<?>[] options = block.items().stream().map(item -> {
                    Option<?> o = option(item);
                    byKey.put(item.key(), o);
                    items.put(o, item);
                    return o;
                }).toArray(Option<?>[]::new);
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
            if (o instanceof SwitchOption) o.setOnChange(refresh);
        }
        //Optical Flow and FSR frame generation exclude each other: turning one on shows the other off (FsrSettings.onToggle does the same in the config)
        excludeEachOther(byKey.get(VisualsOptionLayout.FRAME_GENERATION), byKey.get(VisualsOptionLayout.FSR_FRAME_GENERATION), refresh);
        excludeEachOther(byKey.get(VisualsOptionLayout.FSR_FRAME_GENERATION), byKey.get(VisualsOptionLayout.FRAME_GENERATION), refresh);
        reset.setOnChange(() -> {
            if (!Boolean.TRUE.equals(reset.getNewValue())) return;
            items.forEach(VisualsSettingsPage::showDefault);
            reset.setNewValue(false);
            refresh.run();
        });
        return pages;
    }

    @SuppressWarnings("unchecked")
    private static void showDefault(Option<?> o, Item item) {
        int v = VisualsOptionLayout.defaultValue(item);
        if (item.kind() == VisualsOptionLayout.Kind.SWITCH) {
            ((Option<Boolean>) o).setNewValue(v != 0);
        } else {
            ((Option<Integer>) o).setNewValue(v);
        }
    }

    @SuppressWarnings("unchecked")
    private static void excludeEachOther(Option<?> one, Option<?> other, Runnable refresh) {
        one.setOnChange(() -> {
            if (on(one) && on(other)) ((Option<Boolean>) other).setNewValue(false);
            refresh.run();
        });
    }

    private static boolean on(Option<?> o) {
        return Boolean.TRUE.equals(o.getNewValue());
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
