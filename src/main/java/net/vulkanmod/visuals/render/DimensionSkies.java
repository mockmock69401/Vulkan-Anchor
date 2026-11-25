package net.vulkanmod.visuals.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.vulkanmod.visuals.config.ColourPresets;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.render.ShaderColours.Colour;
import net.vulkanmod.visuals.vk.VkFrames;

import java.util.Map;

/**
 * 2026-11-25 (the user, of NightfallCraft's custom dimensions: "아래 월드는 색상프리셋 관계없이 통일해서 적용해줘"): a few modded dimensions
 * take a sky of their own, whatever colour palette the player chose. Each such dimension's look is a palette (ColourPresets) with some of
 * its colours changed; while the player is in it the renderer reads a copy of the settings with that look applied (current), and the
 * saved settings are not touched.
 * <ul>
 * <li>Arachne's nest (Epic Fight: Arachne): "보라색 하늘에 구름 (공포 프리셋 베이스로 보라색 느낌 추가 제작)": Horror's palette, its sepia
 * sky, sun glow, fog, clouds and light turned purple.</li>
 * <li>Rockwind Ruins (Epic Fight: Leonidas): "노란색 하늘에 구름 (공포 프리셋을 재활용하는 걸로)": Horror's palette as it is (its noon is a
 * sepia-amber).</li>
 * <li>The Lethean Sea (Mimic): "검붉은 하늘에 구름": Horror's palette turned a dark blood red.</li>
 * <li>The Sanctum of the Wraithon (Wraithon) and the Sanctum of the Battle 1 (pbf1): "검은 하늘(+옅은 검붉은색)에 구름": Horror's palette
 * turned near black with a faint dark red.</li>
 * </ul>
 * Every one of them has a fixed noon (their dimension types' fixed_time 6000), so the day's colours are the ones that show.
 */
public final class DimensionSkies {
    /** A dimension's look: the palette (a ColourPresets level) it starts from and the colours it changes. */
    public record Look(int palette, Map<Colour, Integer> colours) {
    }

    static final Look PURPLE = new Look(ColourPresets.HORROR, Map.of(Colour.DAY_SKY, 0x8E6AA8, Colour.SUN_GLOW, 0xD49CFF, Colour.FOG, 0x8A7EA2,
            Colour.CLOUDS, 0xA898B4, Colour.SUNLIGHT, 0xD8A8FF, Colour.SHADE_LIGHT, 0x8C7AA6));
    static final Look YELLOW = new Look(ColourPresets.HORROR, Map.of());
    static final Look DARK_RED = new Look(ColourPresets.HORROR, Map.of(Colour.DAY_SKY, 0x7A2620, Colour.SUN_GLOW, 0xE0503A, Colour.FOG, 0x6A3430,
            Colour.CLOUDS, 0x8E5A52, Colour.SUNLIGHT, 0xFF7A5A, Colour.SHADE_LIGHT, 0x8A5048));
    static final Look BLACK = new Look(ColourPresets.HORROR, Map.of(Colour.DAY_SKY, 0x2A1010, Colour.SUN_GLOW, 0x7A2A20, Colour.FOG, 0x2A1A1A,
            Colour.CLOUDS, 0x4A3634, Colour.SUNLIGHT, 0xC07060, Colour.SHADE_LIGHT, 0x5A4040));

    /** The looks by dimension id. */
    static final Map<String, Look> LOOKS = Map.of(
            "epicfight_arachne:arachne_nest", PURPLE,
            "epicfight_leonidas:rockwind_ruins", YELLOW,
            "mimic:the_lethean_sea", DARK_RED,
            "wraithon:sanctum_of_the_wraithon", BLACK,
            "pbf1:sanctum_of_the_battle1", BLACK);

    /**
     * The dimensions without sky light that draw the overworld's sky all the same. Cataclysm's Eternal Frosthold ("cataclysm eternal
     * frosthold -> 오버월드 밤 하늘로 수정"): the overworld's effects at a fixed midnight under a ceiling, so it shows the overworld's night.
     */
    static final java.util.Set<String> SKY_WITHOUT_SKY_LIGHT = java.util.Set.of("cataclysm_dimension:cataclysm_eternal_frosthold");

    private static VisualsConfig base;
    private static Look baseLook;
    private static long baseSerial = Long.MIN_VALUE;
    private static VisualsConfig shown;

    private DimensionSkies() {
    }

    /** The look of the dimension with this id, or null for one that keeps the player's palette. */
    public static Look look(String dimension) {
        return dimension == null ? null : LOOKS.get(dimension);
    }

    /**
     * Whether the overworld's sky (the atmosphere, the sun and moon, the far clouds) is drawn in a dimension with this id: one with sky
     * light, unless its effects are the End's (Cataclysm's Bastion Lost has sky light and the End's effects: "cataclysm_bastion_lost -> 엔드
     * 하늘로 수정"; it showed the overworld's blue sky), or one in SKY_WITHOUT_SKY_LIGHT.
     */
    public static boolean skyLit(String dimension, boolean hasSkyLight, boolean endEffects) {
        return (hasSkyLight && !endEffects) || (dimension != null && SKY_WITHOUT_SKY_LIGHT.contains(dimension));
    }

    /** skyLit for level (none for null; a failure, a modded level, reads its own sky light). */
    public static boolean skyLit(ClientLevel level) {
        if (level == null) return false;
        try {
            return skyLit(level.dimension().location().toString(), level.dimensionType().hasSkyLight(),
                    level.effects().skyType() == net.minecraft.client.renderer.DimensionSpecialEffects.SkyType.END);
        } catch (RuntimeException e) {
            return level.dimensionType().hasSkyLight();
        }
    }

    /** A copy of config with look's palette and colours (config itself when look is null). */
    public static VisualsConfig apply(VisualsConfig config, Look look) {
        if (look == null || config == null) return config;
        VisualsConfig c = config.copy();
        ColourPresets.apply(c, look.palette());
        for (Map.Entry<Colour, Integer> e : look.colours().entrySet()) c.setColour(e.getKey(), e.getValue());
        return c;
    }

    /**
     * The settings the renderer draws this frame with: the saved ones, or in a dimension with a look of its own a copy with it applied
     * (made again each frame, so a setting changed meanwhile shows). A failure (a modded level) gives the saved ones.
     */
    public static VisualsConfig current() {
        VisualsConfig c = VisualsConfig.get();
        Look look;
        try {
            ClientLevel level = Minecraft.getInstance().level;
            look = level == null ? null : look(level.dimension().location().toString());
        } catch (RuntimeException e) {
            look = null;
        }
        if (look == null || c == null) return c;
        long serial = VkFrames.serial();
        if (shown == null || c != base || look != baseLook || serial != baseSerial) {
            shown = apply(c, look);
            base = c;
            baseLook = look;
            baseSerial = serial;
        }
        return shown;
    }
}
