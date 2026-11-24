package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;

/**
 * 2026-11-22 (the user: "현재 구름이 특정 높이에서만 한정되어 보여. 높은 곳에서 구름을 보면 평평해보이고 있어. 좀 더 아래까지 구름을 퍼트리고
 * 다양한 높낮이로 구현해봐줘. 구현 높낮이 범위값을 실험적 기능에 넣어줘."): the realistic and anime clouds stand at different levels
 * (clouds.glsl cloudLevelAt, CloudMath.levelAt). Experimental > Clouds sets the range: how far below the layer's usual base the lowest
 * clouds go (cloudLevelLow, blocks), how far above it the highest (cloudLevelHigh, blocks), and how much of that range the levels use
 * (cloudLevelSpread, percent: 0 every cloud on the usual base, as before).
 * <p>SLOT (the composite's cloudLevel, scene_copy.comp) and REFLECTION_SLOT (reflection.comp): x the lowest level (blocks, at most 0),
 * y the highest (at least 0), z the level lattice's cell in blocks (CLOUD_LEVEL_CELL times the cloud size, whole cells to vanilla's
 * period), w 1 when the levels are on (the realistic or anime clouds drawn and a range above 0); all 0 otherwise.</p>
 */
public final class CloudLevelMath {
    public static final int SLOT = 80, REFLECTION_SLOT = 37;
    /** clouds.glsl's level lattice at cloud size 100% (blocks), the share of a cell over which two levels blend at its edge, the hash's layer. */
    public static final float CLOUD_LEVEL_CELL = 384, CLOUD_LEVEL_EDGE = 0.2f;
    public static final int CLOUD_LEVEL_SEED = 7;
    /**
     * 2026-11-24 (the user: some realistic clouds were drawn diagonally or vertically, leaning across a border between two levels): corners
     * within JOIN blocks of each other blend their levels; from SPLIT blocks apart each keeps its clouds flat on its own (CloudMath.realPick).
     */
    public static final float CLOUD_LEVEL_JOIN = 8, CLOUD_LEVEL_SPLIT = 24;
    /** The settings' ranges (blocks, percent) and defaults. */
    public static final int RANGE_MAX = 256, SPREAD_MAX = 100;
    /** 96 below and 64 above barely showed from high up (session18-heights-*.jpg); 192 and 128 did. */
    public static final int LOW_DEFAULT = 160, HIGH_DEFAULT = 96, SPREAD_DEFAULT = 100;

    private CloudLevelMath() {
    }

    /** This frame's cloudLevel from the config (null: off) while the realistic or anime clouds are drawn (styled). */
    public static float[] params(VisualsConfig c, boolean styled) {
        if (c == null || !c.enabled || !styled) return new float[4];
        return params(c.cloudLevelLow, c.cloudLevelHigh, c.cloudLevelSpread, c.cloudSize / 100f);
    }

    /** cloudLevel for the lowest and highest offsets (blocks), the spread (percent) and the cloud size (fraction). */
    public static float[] params(int low, int high, int spread, float size) {
        float s = Math.max(0, Math.min(SPREAD_MAX, spread)) / 100f;
        float lo = -Math.max(0, Math.min(RANGE_MAX, low)) * s, hi = Math.max(0, Math.min(RANGE_MAX, high)) * s;
        if (!(hi - lo > 0)) return new float[4];
        float k = Math.max(CloudMath.SIZE_MIN, Math.min(CloudMath.SIZE_MAX, size * 100)) / 100f;
        return new float[]{lo, hi, CloudMath.animeCell(CLOUD_LEVEL_CELL, k), 1};
    }
}
