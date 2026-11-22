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
 * <p>
 * 2026-11-22 (the user: "떨어지는 유성 궤적을 하늘에 맞게 약간 둥근 궤적을 그리게 하고, 베지어 곡선으로 속도 조절 가능하게 만들어줘. 지속시간도
 * 추가하고, 실험적 기능에 넣어줘"): the meteors' bend, speed curve and duration. SHAPE_SLOT (meteorShape): x the bend's curvature (the
 * curve setting / 100 x METEOR_BEND; 0: a straight run), y the life's factor (the duration setting / 100). EASE_SLOT (meteorEase): the
 * speed's cubic bezier x1, y1, x2, y2 (settings / 100, as CSS cubic-bezier; the default eases out, the head slowing as it burns out).
 */
public final class SkyEventMath {
    public static final int SLOT = 77, SHAPE_SLOT = 78, EASE_SLOT = 79;
    /** The settings' ranges (percent). */
    public static final int TWINKLE_MAX = 200, METEOR_AMOUNT_MAX = 400, METEOR_BRIGHTNESS_MAX = 200;
    public static final int METEOR_CURVE_MAX = 200, METEOR_DURATION_MIN = 25, METEOR_DURATION_MAX = 300, METEOR_EASE_MAX = 100;
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
    public static final float METEOR_BEND = 1.2f, METEOR_EASE_STEPS = 12.0f;
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

    /** skyevents.glsl bezierAxis: the cubic bezier (0,0) (x1,y1) (x2,y2) (1,1) at t on one axis, p1, p2 its control points' coordinates. */
    public static float bezierAxis(float t, float p1, float p2) {
        float r = 1 - t;
        return 3 * r * r * t * p1 + 3 * r * t * t * p2 + t * t * t;
    }

    /** skyevents.glsl meteorEased: the head's share of the run at the life's share u for the bezier x1, y1, x2, y2. */
    public static float meteorEased(float u, float x1, float y1, float x2, float y2) {
        float lo = 0, hi = 1;
        for (int i = 0; i < (int) METEOR_EASE_STEPS; i++) {
            float mid = 0.5f * (lo + hi);
            if (bezierAxis(mid, x1, x2) < u) lo = mid;
            else hi = mid;
        }
        return Math.max(0, Math.min(1, bezierAxis(0.5f * (lo + hi), y1, y2)));
    }

    /** The longest meteor life (seconds) at the duration setting's most. */
    public static float longestLife() {
        return METEOR_LIFE_MAX * METEOR_DURATION_MAX / 100f;
    }

    /** SHAPE_SLOT for the settings c (null: the post-process off, no meteors drawn: a straight run of its own life). */
    public static float[] shape(VisualsConfig c) {
        if (c == null) return new float[]{0, 1, 0, 0};
        float curve = Math.max(0, Math.min(METEOR_CURVE_MAX, c.meteorCurve)) / 100f;
        float life = Math.max(METEOR_DURATION_MIN, Math.min(METEOR_DURATION_MAX, c.meteorDuration)) / 100f;
        return new float[]{curve * METEOR_BEND, life, 0, 0};
    }

    /** EASE_SLOT for the settings c (null: the post-process off, no meteors drawn: linear): each control point's coordinate in 0..1. */
    public static float[] ease(VisualsConfig c) {
        if (c == null) return new float[]{0, 0, 1, 1};
        return new float[]{easeOf(c.meteorEaseX1), easeOf(c.meteorEaseY1), easeOf(c.meteorEaseX2), easeOf(c.meteorEaseY2)};
    }

    private static float easeOf(int percent) {
        return Math.max(0, Math.min(METEOR_EASE_MAX, percent)) / 100f;
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
