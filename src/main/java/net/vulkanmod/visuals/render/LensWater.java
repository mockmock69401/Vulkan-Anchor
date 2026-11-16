package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;

/**
 * 2026-11-16 (shader roadmap session 26; the user: "물에 들어갔다 나올 때 물이 흘러내리는 효과도 구현해줘", Experimental > Interaction): water
 * running down the lens after the camera comes up out of the water (lenswater.glsl, drawn by the final colour or the anti-aliased blit). The
 * camera's time under water sets how wet the lens comes out (WET_MIN after a dip, 1 from FULL_UNDER seconds); every exit gets a new seed so
 * the swirls fall elsewhere (2026-11-17: revised after the user's reference video to one warping sheet that runs off in about a second). Updated once a frame by VisualsRenderer with the flow clock (stopped while paused).
 */
public final class LensWater {
    /** lenswater.glsl's LENS_DURATION and LENS_CLEAR_TIME (WakeTest). */
    public static final float DURATION = 1.2f, CLEAR_TIME = 1.05f;
    /** Seconds under water for a fully wet lens, and the wetness of a quick dip. */
    public static final float FULL_UNDER = 0.6f, WET_MIN = 0.35f;
    /** post_final.frag's and post_blit.frag's loose uniform slots. */
    public static final int FINAL_SLOT = 6, BLIT_SLOT = 1;

    private static boolean under;
    private static double enteredAt, exitAt = Double.NEGATIVE_INFINITY, now;
    private static float wet, seed;
    private static long exits;

    private LensWater() {
    }

    /** The setting's strength (ratio; 0 = off). */
    public static float strength() {
        VisualsConfig c = VisualsConfig.get();
        return c != null && c.enabled && c.lensWater ? Math.max(c.lensWaterStrength, 0) / 100f : 0;
    }

    /** One frame: whether the camera is in water, at the flow clock's seconds. */
    public static void update(boolean inWater, double seconds) {
        now = seconds;
        if (inWater) {
            if (!under) enteredAt = seconds;
            under = true;
        } else if (under) {
            under = false;
            exitAt = seconds;
            wet = wetness(seconds - enteredAt);
            exits++;
            //A new pattern each time (the golden ratio's steps never repeat)
            seed = (float) ((exits * 0.6180339887498949) % 1.0);
        }
    }

    /** How wet the lens comes out after this many seconds under water. */
    public static float wetness(double underSeconds) {
        return (float) Math.max(WET_MIN, Math.min(1, underSeconds / FULL_UNDER));
    }

    /** lensParams for this frame: {seconds out, wetness x strength (0 = off), seed, 0}. */
    public static float[] params() {
        float s = strength();
        double age = now - exitAt;
        if (s <= 0 || under || !(age >= 0) || age >= DURATION) return new float[4];
        return new float[]{(float) age, wet * s, seed, 0};
    }
}
