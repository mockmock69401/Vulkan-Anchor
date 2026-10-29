package net.vulkanmod.visuals.render;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Shadow pass (2026-10-14): Reforged's chunk builder gives every terrain vertex alpha 1. A quad vanilla draws without
 * directional shade (BakedQuad.isShade false: grass, flowers, crops, saplings and other cross models) gets ALPHA instead, which
 * the terrain shader carries into the main colour's alpha (texture alpha times vertex alpha). The post-process then lights those
 * pixels like vanilla does, flat, by the shadow maps alone: their depth-reconstructed normals are noise across thin blades, and
 * vertical blades under a high sun read N.L near 0 (dense grass fell to 30-40% of the ground beside it). BlockRenderer marks
 * the quads (putQuadData) and notes each block's emission (renderBatched), on the chunk build threads.
 */
public final class FoliageMarker {
    /** 254 in the 8-bit attachment whether Reforged's float-to-int truncates or rounds (254.25). */
    public static final float ALPHA = 254.25f / 255f;
    /**
     * Glow pass (2026-10-16): 253 for every quad of a block that emits light (lantern, torch, glowstone...), whose bright texels the
     * post-process adds as emission. Takes precedence over the plant mark.
     */
    public static final float EMISSIVE_ALPHA = 253.25f / 255f;
    /**
     * 2026-10-28 (the user: Complementary Reimagined's ores glow): 252 for every quad of an ore block, lit as any block; the composite
     * adds the glow of its specks (the texels that stand out from the host stone). After the emissive mark, before the plant mark.
     * 2026-10-29 (the user: the ores' glow looked odd however tuned): nether quartz ore only (BlockRenderer.renderBatched).
     */
    public static final float ORE_ALPHA = 252.25f / 255f;
    /**
     * 2026-10-28: 251 for a fluid that emits light (lava; LiquidRenderer), a light source like EMISSIVE_ALPHA whose glow is
     * FLUID_GLOW_SCALE times as strong (a lava lake is one broad bright surface; next to Complementary Reimagined it stayed orange).
     */
    public static final float EMISSIVE_FLUID_ALPHA = 251.25f / 255f;
    /**
     * 2026-10-28 (the user: next to Complementary Reimagined the lakes were opaque): 247 for water's quads (LiquidRenderer), a
     * vertex-only mark terrain.vsh turns into WATER_FLAG; the terrain fragment then draws the water texture at WaterAlpha of its
     * opacity (1 with the visuals off: vanilla's water).
     */
    public static final float WATER_ALPHA = 247.25f / 255f;
    /**
     * 2026-10-29 (the user: Complementary Reimagined's High profile reflects on polished and metal blocks): 246 for a metal block's
     * shaded quads, 245 for a polished one's (smoothKind), which the composite gives the sun's highlight and the reflection pass its
     * reflection (blockreflect.glsl). After the emissive and ore marks; a quad without directional shade keeps the plant mark.
     */
    public static final float METAL_ALPHA = 246.25f / 255f, POLISHED_ALPHA = 245.25f / 255f;
    /** smoothKind's answers: a block that is neither, polished stone and the like, metal. */
    public static final int ROUGH = 0, POLISHED = 1, METAL = 2;
    /**
     * Metal by registry name (any namespace, so modded storage blocks count): unweathered copper, the metal blocks, iron's doors,
     * trapdoors and bars, chains, anvils, weighted pressure plates, cauldrons and hoppers.
     */
    static final Pattern METAL_NAMES = Pattern.compile("(waxed_)?(exposed_)?(cut_)?copper(_block|_stairs|_slab)?"
            + "|(iron|gold|netherite|steel|tin|bronze|silver|lead|nickel|aluminum|aluminium|zinc|brass|platinum|osmium|electrum|invar|constantan|titanium|cobalt|iridium)_block"
            + "|iron_(door|trapdoor|bars)|chain|(chipped_|damaged_)?anvil|(heavy|light)_weighted_pressure_plate|(water_|lava_|powder_snow_)?cauldron|hopper");
    /** Polished by registry name: anything polished_, quartz, glazed terracotta, obsidian, packed and blue ice, gem blocks, prismarine bricks. */
    static final Pattern POLISHED_NAMES = Pattern.compile(".*polished_.*|smooth_quartz.*|quartz_(block|pillar|bricks|stairs|slab)|chiseled_quartz_block"
            + "|.*_glazed_terracotta|(crying_)?obsidian|packed_ice|blue_ice|(diamond|emerald|lapis|amethyst)_block|prismarine_brick.*|dark_prismarine.*");
    private static final Map<Block, Integer> SMOOTH_KINDS = new ConcurrentHashMap<>();
    /**
     * Wind (2026-10-21): vertex-only marks terrain.vsh reads and turns back into the plant mark (SWAY, SWAY_HIGH) or into 1 (LEAF)
     * before the fragment stage, so the main colour's alpha keeps its meaning. A plant vertex above its block's base is SWAY (height
     * 1), the top of a double plant's upper half SWAY_HIGH (height 2); leaves sway as a whole block. The base stays ALPHA (height 0).
     */
    public static final float SWAY_ALPHA = 250.25f / 255f, SWAY_HIGH_ALPHA = 249.25f / 255f, LEAF_ALPHA = 248.25f / 255f;
    /** The block kinds the wind moves: none, a plant (its lower half), a double plant's upper half, leaves. */
    public static final int STILL = 0, PLANT = 1, PLANT_UPPER = 2, LEAVES = 3;
    /** A plant vertex this far above its block's base (blocks) is above the base. */
    public static final float BASE_MARGIN = 0.01f;
    /** Whether the block the chunk build thread is drawing emits light (set as BlockRenderer.renderBatched starts). */
    private static final ThreadLocal<boolean[]> EMISSIVE = ThreadLocal.withInitial(() -> new boolean[1]);
    /** Whether the block the chunk build thread is drawing is an ore. */
    private static final ThreadLocal<boolean[]> ORE = ThreadLocal.withInitial(() -> new boolean[1]);
    /** The smooth kind of the block the chunk build thread is drawing. */
    private static final ThreadLocal<int[]> SMOOTH = ThreadLocal.withInitial(() -> new int[1]);
    /** The wind kind of the block the chunk build thread is drawing. */
    private static final ThreadLocal<int[]> SWAY = ThreadLocal.withInitial(() -> new int[1]);

    private FoliageMarker() {
    }

    public static void setEmissive(boolean emissive) {
        EMISSIVE.get()[0] = emissive;
    }

    public static void setOre(boolean ore) {
        ORE.get()[0] = ore;
    }

    public static void setSmooth(int kind) {
        SMOOTH.get()[0] = kind;
    }

    /** A block's smooth kind by its registry path (METAL_NAMES, POLISHED_NAMES). */
    public static int smoothKind(String path) {
        if (path == null) return ROUGH;
        if (METAL_NAMES.matcher(path).matches()) return METAL;
        return POLISHED_NAMES.matcher(path).matches() ? POLISHED : ROUGH;
    }

    /** smoothKind of a block, worked out once per block. */
    public static int smoothKind(BlockState state) {
        return SMOOTH_KINDS.computeIfAbsent(state.getBlock(), b -> smoothKind(BuiltInRegistries.BLOCK.getKey(b).getPath()));
    }

    /** The vertex alpha for this quad: ALPHA for a vanilla quad without directional shade, else the alpha given. */
    public static float alpha(Object quad, float alpha) {
        return quad instanceof BakedQuad q && !q.isShade() ? ALPHA : alpha;
    }

    /** As alpha(quad, alpha), EMISSIVE_ALPHA first for a light source's quads. */
    public static float alpha(Object quad, float alpha, boolean emissive) {
        return emissive ? EMISSIVE_ALPHA : alpha(quad, alpha);
    }

    /** The mark for the block this thread is drawing: a light source's, else an ore's, else a smooth block's, else as alpha(quad, alpha). */
    public static float markedAlpha(Object quad, float alpha) {
        return alpha(quad, alpha, EMISSIVE.get()[0], ORE.get()[0], SMOOTH.get()[0]);
    }

    /** As alpha(quad, alpha, emissive, ore), METAL_ALPHA or POLISHED_ALPHA for a smooth block's shaded quads that are neither. */
    public static float alpha(Object quad, float alpha, boolean emissive, boolean ore, int smooth) {
        if (!emissive && !ore && smooth != ROUGH && !(quad instanceof BakedQuad q && !q.isShade())) {
            return smooth == METAL ? METAL_ALPHA : POLISHED_ALPHA;
        }
        return alpha(quad, alpha, emissive, ore);
    }

    /** As alpha(quad, alpha, emissive), ORE_ALPHA for an ore's quads that are no light source's. */
    public static float alpha(Object quad, float alpha, boolean emissive, boolean ore) {
        return !emissive && ore ? ORE_ALPHA : alpha(quad, alpha, emissive);
    }

    /** The wind kind of a block: plants (bushes, not lily pads) and the halves of double plants, leaves; anything else still. */
    public static int swayKind(BlockState state) {
        Block b = state.getBlock();
        if (state.is(BlockTags.LEAVES)) return LEAVES;
        if (!(b instanceof BushBlock) || b instanceof WaterlilyBlock) return STILL;
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER ? PLANT_UPPER : PLANT;
    }

    public static void setSway(int kind) {
        SWAY.get()[0] = kind;
    }

    /**
     * The vertex alpha with the wind's marks: mark is markedAlpha's answer for the quad, y the vertex's height in its block (the
     * model's coordinate). A plant's vertex gets its height mark, a leaf's LEAF_ALPHA; light sources and anything else keep mark.
     */
    public static float swayAlpha(float mark, int kind, float y) {
        if (mark == ALPHA && (kind == PLANT || kind == PLANT_UPPER)) {
            boolean above = y > BASE_MARGIN;
            if (kind == PLANT) return above ? SWAY_ALPHA : ALPHA;
            return above ? SWAY_HIGH_ALPHA : SWAY_ALPHA;
        }
        return kind == LEAVES && mark == 1.0f ? LEAF_ALPHA : mark;
    }

    /** swayAlpha for the block this thread is drawing. */
    public static float vertexAlpha(Object quad, float y) {
        return swayAlpha(markedAlpha(quad, 1.0f), SWAY.get()[0], y);
    }
}
