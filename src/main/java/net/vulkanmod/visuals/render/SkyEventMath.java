package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;

/**
 * 2026-11-21 (the user: "유성(별똥별) 옵션과 별빛이 불규칙하게 더 빛나는 (star scintillation) 옵션도 추가해줘. 실험적 기능에 넣어놔"): the night
 * sky's events on the Experimental page's Sky block: the stars' twinkle and meteors. Mirrors skyevents.glsl's consts and its
 * Java-checkable functions (SkyEventTest compares them); skyevents.glsl has the look.
 * <p>
 * SLOT (the composite's skyEvents, scene_copy.comp): x the twinkle's strength (setting / 100; 0: off), y meteors a minute (the amount
 * setting x METEORS_A_MINUTE / 100; 0: off), z the time in seconds (the flow clock's, stopped while paused, wrapped at
 * SKY_EVENT_TIME_WRAP), w the meteors' brightness (setting / 100). All 0 with the post-process off or where the composite draws no sky
 * (the Nether, the End). Both show only at night: the composite weighs them with the stars' weight (starGate).
 */
public final class SkyEventMath {
    public static final int SLOT = 77;
    /** The settings' ranges (percent). */
    public static final int TWINKLE_MAX = 200, METEOR_AMOUNT_MAX = 400, METEOR_BRIGHTNESS_MAX = 200;
    /** The meteors a minute at an amount of 100 %. */
    public static final float METEORS_A_MINUTE = 4.0f;

    public static final float TWINKLE_CELLS = 30.0f;
    public static final float TWINKLE_RATE_A = 1.7f, TWINKLE_RATE_B = 2.9f, TWINKLE_RATE_C = 4.6f;
    public static final float TWINKLE_DIM = 0.4f, TWINKLE_FLARE_FROM = 0.3f, TWINKLE_FLARE = 3.0f, TWINKLE_LOW = 1.6f;
    public static final float METEOR_LANES = 4.0f, METEOR_CHANCE = 0.6f;
    public static final float METEOR_LIFE_MIN = 0.45f, METEOR_LIFE_MAX = 1.1f;
    public static final float METEOR_HEIGHT_MIN = 0.35f, METEOR_HEIGHT_MAX = 0.9f;
    public static final float METEOR_LENGTH_MIN = 0.14f, METEOR_LENGTH_MAX = 0.38f;
    public static final float METEOR_HEADING_SPREAD = 1.1f, METEOR_TAIL = 0.5f;
    public static final float METEOR_WIDTH = 0.9f, METEOR_WIDTH_MIN = 0.0009f, METEOR_FADE = 0.6f, METEOR_LIGHT = 2.4f;
    public static final float METEOR_HORIZON = 0.04f;
    public static final float METEOR_R = 0.86f, METEOR_G = 0.95f, METEOR_B = 1.0f;
    public static final float SKY_EVENT_TIME_WRAP = 3600.0f;

    private static volatile double clock;

    private SkyEventMath() {
    }

    /** The flow clock's seconds this frame (VisualsRenderer, with the caustics'). */
    public static void setClock(double seconds) {
        clock = seconds;
    }

    /** skyevents.glsl twinkleFactor: the brightness factor for the mean s (-1..1) of the three sines at strength k. */
    public static float twinkleFactor(float s, float k) {
        float flare = Math.max(s - TWINKLE_FLARE_FROM, 0);
        return Math.max(1 + k * (TWINKLE_DIM * s + TWINKLE_FLARE * flare * flare), 0);
    }

    /** skyevents.glsl meteorAt's slot length (seconds) for rate meteors a minute: the lanes together bring rate a minute on average. */
    public static float slotSeconds(float rate) {
        return METEOR_LANES * METEOR_CHANCE * 60f / rate;
    }

    /** The slot for the settings c (null: the post-process off) when the composite draws the sky (sky) at seconds. */
    public static float[] params(VisualsConfig c, boolean sky, double seconds) {
        if (c == null || !c.enabled || !sky) return new float[4];
        float twinkle = c.starTwinkle ? Math.max(0, Math.min(TWINKLE_MAX, c.starTwinkleStrength)) / 100f : 0;
        float brightness = c.meteors ? Math.max(0, Math.min(METEOR_BRIGHTNESS_MAX, c.meteorBrightness)) / 100f : 0;
        float rate = brightness > 0 ? Math.max(0, Math.min(METEOR_AMOUNT_MAX, c.meteorAmount)) * METEORS_A_MINUTE / 100f : 0;
        if (!(twinkle > 0) && !(rate > 0)) return new float[4];
        double t = seconds - Math.floor(seconds / SKY_EVENT_TIME_WRAP) * SKY_EVENT_TIME_WRAP;
        return new float[]{twinkle, rate, Double.isFinite(t) ? (float) t : 0, rate > 0 ? brightness : 0};
    }

    /** This frame's slot. */
    public static float[] params(VisualsConfig c, boolean sky) {
        return params(c, sky, clock);
    }
}
