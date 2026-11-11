package net.vulkanmod.visuals.particle;

import net.vulkanmod.visuals.config.ParticleEffect;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 2026-11-11 (shader roadmap session 13, the user: "find every vanilla effect that looks pixel-shaped, such as the stars, campfire smoke and
 * embers, and give each every option possible: type (vanilla / realistic), size, amount and colour"): which vanilla particles the look
 * options reach, grouped by what they look like, and the realistic shape each is drawn with.
 *
 * <p>A particle is tagged with its {@link #tag} when ParticleEngine makes it (by its ParticleType's id, minecraft namespace only), and the
 * quad it draws reads the tag (SingleQuadParticleM). Particles not listed here (block and item fragments, the symbols whose shape is their
 * meaning, the ones that draw themselves) keep their vanilla picture whatever the options say; rain's splash belongs to the shader
 * weather (session 6) and the stars to session 12.
 */
public final class VanillaParticles {
    private VanillaParticles() {
    }

    /** The groups, in the settings' order. key: the option keys' middle part (particle_KEY_type ...). */
    public enum Group {
        SMOKE("smoke"),
        FLAMES("flames"),
        EMBERS("embers"),
        DRIPS("drips"),
        MAGIC("magic"),
        AMBIENT("ambient"),
        COMBAT("combat");

        private final String key;

        Group(String key) {
            this.key = key;
        }

        public String key() {
            return this.key;
        }

        /** The group's settings: ParticleEffect.PARTICLE_ + this name (the shared per-effect pattern). */
        public ParticleEffect effect() {
            return ParticleEffect.valueOf("PARTICLE_" + this.name());
        }
    }

    /**
     * The realistic shapes; the ordinal is particle.fsh's PARTICLE_SHAPE_* id (0: the vanilla sprite). A member whose realistic shape is
     * SPRITE has no realistic type (its shape is its meaning): size, amount and colour still reach it.
     */
    public enum Shape {
        SPRITE, PUFF, FLAME, EMBER, DROP, BUBBLE, ORB, MOTE, PETAL, GLINT
    }

    /** A listed particle: its group and its realistic shape. */
    public record Member(Group group, Shape shape) {
    }

    /** Tag bits: the group's ordinal + 1 in bits 0..3 (0: not listed), the shape in bits 4..7. */
    public static final int GROUP_MASK = 0xF, SHAPE_SHIFT = 4;

    private static final Map<String, Member> BY_ID = new HashMap<>();

    static {
        //Smoke: soft puffs. [1.21] marks the types only 1.21.1 has (harmless on 1.20.1: never made there)
        put(Group.SMOKE, Shape.PUFF, "smoke", "large_smoke", "campfire_cosy_smoke", "campfire_signal_smoke", "poof", "cloud", "sneeze",
                "explosion", "squid_ink", "glow_squid_ink", "white_smoke", "dust_plume", "gust", "small_gust");
        //Flames
        put(Group.FLAMES, Shape.FLAME, "flame", "soul_fire_flame", "small_flame");
        //Embers and sparks: glowing round embers
        put(Group.EMBERS, Shape.EMBER, "lava", "firework", "electric_spark", "wax_on", "wax_off", "scrape", "flash");
        //Drips and water
        put(Group.DRIPS, Shape.DROP, "dripping_water", "falling_water", "dripping_lava", "falling_lava", "landing_lava", "dripping_honey",
                "falling_honey", "landing_honey", "falling_nectar", "dripping_obsidian_tear", "falling_obsidian_tear", "landing_obsidian_tear",
                "dripping_dripstone_water", "falling_dripstone_water", "dripping_dripstone_lava", "falling_dripstone_lava", "splash", "fishing");
        put(Group.DRIPS, Shape.BUBBLE, "bubble", "bubble_pop", "bubble_column_up", "current_down");
        //Magic: glowing orbs
        put(Group.MAGIC, Shape.ORB, "portal", "reverse_portal", "enchant", "end_rod", "witch", "effect", "entity_effect", "ambient_entity_effect",
                "instant_effect", "dragon_breath", "totem_of_undying", "glow", "nautilus", "dolphin", "soul", "sculk_soul", "sculk_charge",
                "sculk_charge_pop", "trial_spawner_detection", "trial_spawner_detection_ominous", "vault_connection", "ominous_spawning",
                "raid_omen", "trial_omen", "infested");
        //Ambient air: small motes; cherry petals
        put(Group.AMBIENT, Shape.MOTE, "ash", "white_ash", "crimson_spore", "warped_spore", "spore_blossom_air", "falling_spore_blossom",
                "mycelium", "underwater", "dust", "dust_color_transition", "falling_dust", "snowflake", "composter", "happy_villager", "egg_crack",
                "dust_pillar");
        put(Group.AMBIENT, Shape.PETAL, "cherry_leaves");
        //Combat
        put(Group.COMBAT, Shape.GLINT, "crit", "enchanted_hit", "damage_indicator");
        //Symbols: size, amount and colour only
        put(Group.COMBAT, Shape.SPRITE, "sweep_attack", "heart", "angry_villager", "note");
    }

    private static void put(Group group, Shape shape, String... ids) {
        Member m = new Member(group, shape);
        for (String id : ids) BY_ID.put(id, m);
    }

    /** The member behind a particle type id ("minecraft:flame" or "flame"), or null when the options do not reach it. */
    public static Member of(String id) {
        if (id == null) return null;
        String path = id;
        int colon = id.indexOf(':');
        if (colon >= 0) {
            if (!id.substring(0, colon).equals("minecraft")) return null;
            path = id.substring(colon + 1);
        }
        return BY_ID.get(path.toLowerCase(Locale.ROOT));
    }

    /** A particle's tag (see GROUP_MASK): 0 for a type the options do not reach. */
    public static int tag(String id) {
        Member m = of(id);
        return m == null ? 0 : tag(m);
    }

    public static int tag(Member m) {
        return (m.group().ordinal() + 1) | (m.shape().ordinal() << SHAPE_SHIFT);
    }

    /** The tag's group, or null for 0. */
    public static Group group(int tag) {
        int g = tag & GROUP_MASK;
        return g == 0 || g > Group.values().length ? null : Group.values()[g - 1];
    }

    /** The tag's realistic shape (SPRITE when it has none). */
    public static Shape shape(int tag) {
        int s = (tag >> SHAPE_SHIFT) & 0xF;
        return s < Shape.values().length ? Shape.values()[s] : Shape.SPRITE;
    }

    /** The listed ids, for the tests. */
    static Map<String, Member> members() {
        return Map.copyOf(BY_ID);
    }
}
