package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.config.VisualsOptionLayout.Block;
import net.vulkanmod.visuals.config.VisualsOptionLayout.Impact;
import net.vulkanmod.visuals.config.VisualsOptionLayout.Item;
import net.vulkanmod.visuals.config.VisualsOptionLayout.Kind;
import net.vulkanmod.visuals.render.FootprintMath;
import net.vulkanmod.visuals.render.StormMath;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * 2026-11-16 (session 25 follow-up, the user: "셰이더에 실험적 기능 메뉴를 추가하고 모래폭풍과 눈폭풍의 입자 굵기와 빈도를 조절할 수 있는 설정을
 * 만들어줘. 발자국 깊이와 유지 시간도 추가해줘. (각각 실험적 기능의 날씨 / 상호작용에 넣어줘)"): the Experimental page of the shader settings,
 * its blocks and every setting on it, each with the VisualsConfig field behind it.
 *
 * <p>TO ADD A SETTING: one line in ENTRIES (percent(...) or toggle(...) with its block, key, range and field), the VisualsConfig field
 * (with its default), and the lang keys vulkanmod.visuals.&lt;key&gt; and .tooltip in en_us and ko_kr. VisualsOptionLayout puts it on the page
 * and reads and writes it through here (get / set); the screen, the reset to defaults and the tests follow. A new block: a constant and a
 * line in BLOCKS (and its lang key vulkanmod.visuals.block.&lt;key&gt;). None of these are in the performance presets or the colour palettes.</p>
 */
public final class ExperimentalFeatures {
    /** The page's key (vulkanmod.visuals.page.experimental). */
    public static final String PAGE = "experimental";
    /** The blocks, in order: the weather's particles; what the world does when the player touches it. */
    public static final String WEATHER = "experimental_weather", INTERACTION = "experimental_interaction";
    public static final List<String> BLOCKS = List.of(WEATHER, INTERACTION);

    /** A setting on the page: its block, the layout item, and the config field's reader and writer (in the item's units). */
    public record Entry(String block, Item item, ToIntFunction<VisualsConfig> get, ObjIntConsumer<VisualsConfig> set) {
    }

    public static final List<Entry> ENTRIES = List.of(
            //Sandstorms: the grains' size and how many there are (percent of the default; the amount 0: no grains, the dust stays)
            percent(WEATHER, "sandstorm_grain_size", StormMath.TUNE_SIZE_MIN, StormMath.TUNE_SIZE_MAX, Impact.NONE, "storm_weather",
                    c -> c.sandstormGrainSize, (c, v) -> c.sandstormGrainSize = v),
            percent(WEATHER, "sandstorm_grain_amount", 0, StormMath.TUNE_AMOUNT_MAX, Impact.LOW, "storm_weather",
                    c -> c.sandstormGrainAmount, (c, v) -> c.sandstormGrainAmount = v),
            //Blizzards: the flakes' size and the extra flakes a blizzard adds (0: the snowfall's own amount)
            percent(WEATHER, "blizzard_flake_size", StormMath.TUNE_SIZE_MIN, StormMath.TUNE_SIZE_MAX, Impact.NONE, "storm_weather",
                    c -> c.blizzardFlakeSize, (c, v) -> c.blizzardFlakeSize = v),
            percent(WEATHER, "blizzard_flake_amount", 0, StormMath.TUNE_AMOUNT_MAX, Impact.LOW, "storm_weather",
                    c -> c.blizzardFlakeAmount, (c, v) -> c.blizzardFlakeAmount = v),
            //Footprints: how deep the hollow is, how long it stays (percent of the default; 25 s in snow, 15 s in sand)
            percent(INTERACTION, "footprint_depth", 0, FootprintMath.DEPTH_MAX, Impact.NONE, "footprints",
                    c -> c.footprintDepth, (c, v) -> c.footprintDepth = v),
            percent(INTERACTION, "footprint_duration", FootprintMath.DURATION_MIN, FootprintMath.DURATION_MAX, Impact.NONE, "footprints",
                    c -> c.footprintDuration, (c, v) -> c.footprintDuration = v),
            //Session 26: wakes behind and rings around what moves through the water (Wakes; needs the water reflections), and water running down
            // the lens after coming out of the water (LensWater); each on / off and its strength (percent of the default)
            toggle(INTERACTION, "water_wakes", Impact.LOW, null, c -> c.waterWakes, (c, on) -> c.waterWakes = on),
            percent(INTERACTION, "water_wake_strength", 0, 200, Impact.NONE, "water_wakes",
                    c -> c.waterWakeStrength, (c, v) -> c.waterWakeStrength = v),
            toggle(INTERACTION, "lens_water", Impact.NONE, null, c -> c.lensWater, (c, on) -> c.lensWater = on),
            percent(INTERACTION, "lens_water_strength", 0, 200, Impact.NONE, "lens_water",
                    c -> c.lensWaterStrength, (c, v) -> c.lensWaterStrength = v));

    private ExperimentalFeatures() {
    }

    /** A percent setting (requires: the switch or setting it needs on, null: only the post-process). */
    static Entry percent(String block, String key, int min, int max, Impact impact, String requires, ToIntFunction<VisualsConfig> get,
                         ObjIntConsumer<VisualsConfig> set) {
        return new Entry(block, new Item(key, Kind.PERCENT, min, max, impact, requires), get, set);
    }

    /** An on / off setting over a boolean field. */
    static Entry toggle(String block, String key, Impact impact, String requires, Predicate<VisualsConfig> get, BoolSetter set) {
        return new Entry(block, new Item(key, Kind.SWITCH, 0, 1, impact, requires), c -> get.test(c) ? 1 : 0, (c, v) -> set.set(c, v != 0));
    }

    @FunctionalInterface
    interface BoolSetter {
        void set(VisualsConfig c, boolean on);
    }

    /** The page's blocks, each with its entries in ENTRIES' order. */
    static List<Block> blocks() {
        List<Block> blocks = new ArrayList<>();
        for (String b : BLOCKS) {
            List<Item> items = new ArrayList<>();
            for (Entry e : ENTRIES) if (e.block().equals(b)) items.add(e.item());
            blocks.add(new Block(b, List.copyOf(items)));
        }
        return List.copyOf(blocks);
    }

    /** The entry behind key, or null. */
    static Entry byKey(String key) {
        for (Entry e : ENTRIES) if (e.item().key().equals(key)) return e;
        return null;
    }
}
