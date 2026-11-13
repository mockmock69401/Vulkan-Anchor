package net.vulkanmod.visuals.render;

/**
 * 2026-11-13 (shader roadmap session 20, the user after AstraLex: "나뭇잎 등 오브젝트의 그림자가 부드럽게 퍼져"): Soft Shadows, the
 * lighting pass's percentage-closer soft shadows (Fernando 2005, "Percentage-closer soft shadows", SIGGRAPH sketch). Around a lookup
 * the pass looks for the texels that block the sun (the lookup's own four, then SEARCH_TAPS gathers on a disc of the widest half width),
 * takes their mean height above the receiver and filters over a disc whose half width grows with it: SPREAD blocks per block of height
 * at 100%, at most MAX_WIDTH. Under a canopy ten blocks up the light spreads over some 0.3 blocks; where a post meets the floor the
 * shadow stays sharp. Close and near cascades and the entity map only: the far cascade's texel (1.5 blocks) is wider than any penumbra.
 * <p>
 * lighting.comp's softParams (SLOT): x the spread (0 = off: the sharp lookup as before, the same picture and cost), y the widest half
 * width, z the frame's index (0..FRAMES-1), which turns the taps (the TAA averages the turns).
 */
public final class SoftShadowMath {
    /** The setting's range (percent of the default softness). */
    public static final int STRENGTH_MAX = 200;
    /** Half width of the penumbra (blocks) per block between caster and receiver, at 100% (the sun alone gives 0.0047; 2026-11-13 at 0.03 the
     * dappled light under a canopy blurred flat, where AstraLex keeps the leaves' shapes). */
    public static final float SPREAD = 0.015f;
    /** The widest half width (blocks) at 100%, and the blocker search's radius. */
    public static final float MAX_WIDTH = 0.5f;
    /** lighting.comp's taps: the blocker search's gathers (besides the lookup's own) and the filter's bilinear lookups. */
    public static final int SEARCH_TAPS = 8, FILTER_TAPS = 12;
    /** The taps' turns repeat every FRAMES frames. */
    public static final int FRAMES = 64;
    /** lighting.comp's softParams location. */
    public static final int SLOT = 19;

    private SoftShadowMath() {
    }

    /** softParams for a setting (percent) and the frame's serial: {spread, widest half width, turn index}; all 0 when off. */
    public static float[] params(int setting, long frame) {
        float s = Math.max(0, Math.min(STRENGTH_MAX, setting)) / 100f;
        if (!(s > 0)) return new float[]{0, 0, 0};
        return new float[]{SPREAD * s, MAX_WIDTH * s, (float) Math.floorMod(frame, FRAMES)};
    }

    /** lighting.comp's half width for blockers above blocks over the receiver: spread x above, at most the widest; 0 when off. */
    public static float halfWidth(float above, float[] params) {
        return Math.min(Math.max(above, 0) * params[0], params[1]);
    }
}
