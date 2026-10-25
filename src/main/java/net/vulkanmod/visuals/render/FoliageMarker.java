package net.vulkanmod.visuals.render;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

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
    /** The wind kind of the block the chunk build thread is drawing. */
    private static final ThreadLocal<int[]> SWAY = ThreadLocal.withInitial(() -> new int[1]);

    private FoliageMarker() {
    }

    public static void setEmissive(boolean emissive) {
        EMISSIVE.get()[0] = emissive;
    }

    /** The vertex alpha for this quad: ALPHA for a vanilla quad without directional shade, else the alpha given. */
    public static float alpha(Object quad, float alpha) {
        return quad instanceof BakedQuad q && !q.isShade() ? ALPHA : alpha;
    }

    /** As alpha(quad, alpha), EMISSIVE_ALPHA first for a light source's quads. */
    public static float alpha(Object quad, float alpha, boolean emissive) {
        return emissive ? EMISSIVE_ALPHA : alpha(quad, alpha);
    }

    /** The mark for the block this thread is drawing. */
    public static float markedAlpha(Object quad, float alpha) {
        return alpha(quad, alpha, EMISSIVE.get()[0]);
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
