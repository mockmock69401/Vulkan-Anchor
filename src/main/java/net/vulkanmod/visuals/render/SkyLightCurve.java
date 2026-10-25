package net.vulkanmod.visuals.render;

/** P7j design 3.4: the lightmap's sky brightness made flatter, so overhangs darken less; 0 stays 0 (caves), strength 0 is vanilla. */
public final class SkyLightCurve {
    /** Gate G7j: 0.6 left most of an island's vertical darkening, 0.9 a faint shade at 100%; at 0.95 about 10% is left (caves stay 0). */
    public static final float FLATTEN = 0.95f;

    private SkyLightCurve() {
    }

    public static float apply(float vanilla, float strength) {
        float s = Math.max(0, Math.min(1, strength));
        if (s == 0 || vanilla <= 0) return vanilla;
        return (float) Math.pow(Math.min(vanilla, 1), 1 - FLATTEN * s);
    }
}
