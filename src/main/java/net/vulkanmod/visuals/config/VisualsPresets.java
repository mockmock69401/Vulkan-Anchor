package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.CloudMath;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * 2026-11-10 (shader roadmap session 4): the performance presets, Potato / Low / Medium / High / Ultra, and Custom.
 * <p>
 * A preset sets the settings that cost GPU time ({@link #VALUES}, by VisualsOptionLayout key) to its level's values; settings that
 * only change the look (sun shape, ripple density, cloud size and count, strengths, colours...) keep the player's values. High is the
 * defaults, so a fresh config is High. The levels' budgets were measured in game (2560x1440, RTX 5070; the roadmap's section 3).
 * <p>
 * The rule for Custom: the preset is a level only while every one of its settings has that level's value. Changing any of them
 * (on the page, or in the file) makes it Custom, or the level whose values they now all have. Choosing Custom keeps the settings.
 */
public final class VisualsPresets {
    public static final int POTATO = 0, LOW = 1, MEDIUM = 2, HIGH = 3, ULTRA = 4, CUSTOM = 5;
    public static final int LEVELS = 5;
    /** The level a fresh config has: its values are VisualsConfig's defaults (tested). */
    public static final int DEFAULT = HIGH;

    /**
     * Each preset setting's value at Potato, Low, Medium, High, Ultra, in the option layout's units (a switch is 0 or 1). Ordered as on
     * the page. Costs: 2560x1440, RTX 5070, GPU ms per frame (session 4's re-measure in the roadmap).
     */
    public static final Map<String, int[]> VALUES;

    static {
        Map<String, int[]> v = new LinkedHashMap<>();
        //image: SMAA (its level costs ~0 here: 0.10-0.17 ms whatever the level) and TAA (0.22 ms)
        v.put("anti_aliasing", new int[]{0, 1, 1, 2, 4});
        v.put("taa", new int[]{0, 0, 1, 1, 1});
        //atmosphere: light shafts (0 skips the pass: 0.21 ms, 0.2-0.5 ms while moving; what Medium saves over High by day), far clouds
        // (vanilla shape 0.17-0.5 ms; realistic High +1.3 ms), auroras (0.8 ms on a clear night), shader weather (1.0 ms while it rains;
        // off draws vanilla's columns)
        v.put("light_shafts", new int[]{0, 0, 0, 50, 50});
        v.put("far_clouds", new int[]{0, 1, 1, 1, 1});
        v.put("cloud_style", new int[]{0, 0, 0, 0, 1});
        v.put("cloud_quality", new int[]{1, 1, 1, 1, 2});
        v.put("aurora", new int[]{0, 1, 1, 2, 2});
        v.put("shader_weather", new int[]{0, 1, 1, 1, 1});
        //session 14 follow-up: the far clouds and the shader weather at half resolution (on an AMD iGPU at 1280x720: rain 10.5 -> 2.9 ms,
        // realistic clouds 17.3 -> 7.9; RTX 5070 at 1440: about 0.6 ms each)
        v.put("half_res_effects", new int[]{1, 1, 1, 0, 0});
        //lighting: ambient occlusion (0 skips the pass: 0.15 ms), per-pixel light (0.27 ms), Distant Horizons' shadows, sun shadows
        v.put("ao_strength", new int[]{0, 100, 100, 100, 100});
        v.put("per_pixel_light", new int[]{0, 1, 1, 1, 1});
        v.put("dh_shadows", new int[]{0, 0, 1, 1, 1});
        v.put("sun_shadows", new int[]{0, 1, 1, 1, 1});
        v.put("vanilla_shadows", new int[]{0, 1, 1, 1, 1});
        v.put("shadow_distance", new int[]{2, 2, 3, 4, 8});
        v.put("entity_shadows", new int[]{0, 0, 0, 1, 1});
        //session 20: soft shadows (the blocker search and the wide filter where a shadow's edge is)
        v.put("soft_shadows", new int[]{0, 0, 100, 100, 100});
        //lighting, session 9: LabPBR materials (with a LabPBR resource pack: the terrain's material writes and the composite's PBR build;
        // nothing without one), their reflections (the reflection pass over smooth materials) and the parallax (the terrain's march)
        v.put("pbr", new int[]{0, 0, 1, 1, 1});
        v.put("pbr_reflections", new int[]{0, 0, 0, 1, 1});
        v.put("pbr_parallax", new int[]{0, 0, 0, 0, 1});
        //water: reflections (0.65 ms over open water)
        v.put("water_reflections", new int[]{0, 100, 100, 100, 100});
        //session 20: wet ground (in the rain and while it dries: the puddles' march in the reflection pass; 2026-11-14 at 1280x720 on the
        // AMD iGPU +6.8 ms in full rain at 100, +2.7 ms at 50; at 2560x1440 on the RTX +0.85 ms)
        v.put("wet_ground", new int[]{0, 0, 50, 100, 100});
        VALUES = Collections.unmodifiableMap(v);
    }

    private VisualsPresets() {
    }

    public static boolean isLevel(int preset) {
        return preset >= POTATO && preset < LEVELS;
    }

    /** The value key has at level. */
    public static int value(String key, int level) {
        return VALUES.get(key)[level];
    }

    /**
     * 2026-11-10 (session 11): the anime cloud style is a look (the Anime colour palette picks it), not a cost level: a preset keeps it
     * when it is set and counts it as the level's own cloud style. The cloud quality, which the presets still set, sets its cost.
     */
    public static boolean keepsLook(String key, int value) {
        return key.equals("cloud_style") && value == CloudMath.STYLE_ANIME;
    }

    /** Sets every preset setting to level's value (keepsLook: the anime clouds stay) and the preset to level; Custom (or anything else) only records Custom. */
    public static void apply(VisualsConfig c, int level) {
        if (!isLevel(level)) {
            c.preset = CUSTOM;
            return;
        }
        for (Map.Entry<String, int[]> e : VALUES.entrySet()) {
            VisualsOptionLayout.Item item = VisualsOptionLayout.item(e.getKey());
            if (keepsLook(e.getKey(), VisualsOptionLayout.get(c, item))) continue;
            VisualsOptionLayout.set(c, item, e.getValue()[level]);
        }
        c.preset = level;
    }

    /**
     * Whether every preset setting has level's value. values gives a setting's value by key, or Integer.MIN_VALUE for one it does not
     * know (the page's hidden Distant Horizons block): such a setting is not compared, nor the anime clouds (keepsLook).
     */
    public static boolean matches(int level, ToIntFunction<String> values) {
        if (!isLevel(level)) return false;
        for (Map.Entry<String, int[]> e : VALUES.entrySet()) {
            int have = values.applyAsInt(e.getKey());
            if (have != Integer.MIN_VALUE && have != e.getValue()[level] && !keepsLook(e.getKey(), have)) return false;
        }
        return true;
    }

    public static boolean matches(VisualsConfig c, int level) {
        return matches(level, key -> VisualsOptionLayout.get(c, VisualsOptionLayout.item(key)));
    }

    /** The level whose values every preset setting has, or Custom. */
    public static int detect(ToIntFunction<String> values) {
        for (int level = POTATO; level < LEVELS; level++) if (matches(level, values)) return level;
        return CUSTOM;
    }

    public static int detect(VisualsConfig c) {
        return detect(key -> VisualsOptionLayout.get(c, VisualsOptionLayout.item(key)));
    }

    /**
     * The preset a loaded config shows: its saved level while the settings still have that level's values, Custom otherwise (a hand
     * edit, or an out-of-range value). A saved Custom stays Custom even when the values happen to equal a level's.
     */
    public static int validated(VisualsConfig c) {
        return isLevel(c.preset) && matches(c, c.preset) ? c.preset : CUSTOM;
    }
}
