package net.vulkanmod.visuals.render;

/**
 * 2026-11-09 (session 2b): the Java mirror of post/underwater.glsl, the view with the camera inside water (the composite's
 * underwaterView). Every shader const is a field of the same name here and every function the shader's (UnderwaterTest compares
 * them): change both together. See the shader's header for the model (Beer-Lambert extinction, the single-scattered daylight falling
 * with depth, Snell's window with Schlick's Fresnel term and total internal reflection past the critical angle).
 */
public final class UnderwaterMath {
    /** Extinction per block at visibility 100% (green and blue keep 5% after about 75 and 100 blocks, red after 25). */
    public static final float UNDERWATER_EXTINCTION_RED = 0.12f, UNDERWATER_EXTINCTION_GREEN = 0.04f, UNDERWATER_EXTINCTION_BLUE = 0.03f;
    /** The daylight's attenuation per block of depth (Mobley 1994's diffuse attenuation K_d, a game look). */
    public static final float UNDERWATER_DIFFUSE_RED = 0.10f, UNDERWATER_DIFFUSE_GREEN = 0.045f, UNDERWATER_DIFFUSE_BLUE = 0.03f;
    /** The scattered light's rate never falls below this share of sigma looking up. */
    public static final float UNDERWATER_MIN_RATE = 0.2f;
    /** The longest water path (sky pixels). */
    public static final float UNDERWATER_LONGEST = 1024.0f;
    /** The visibility setting's range (ratio; the settings screen's 25..300%). */
    public static final float UNDERWATER_VISIBILITY_MIN = 0.25f, UNDERWATER_VISIBILITY_MAX = 3.0f;
    /** Water's refractive index over air's, and the radiance gain n^2 into water. */
    public static final float UNDERWATER_IOR = 1.333f, UNDERWATER_WINDOW_GAIN = 1.777f;
    /** A view rises to the surface only above this sine. */
    public static final float UNDERWATER_MIN_UP = 0.001f;
    /** A pixel lies on or behind the surface when it stands at least as high as the surface less SLACK blocks and SHARE of its distance. */
    public static final float UNDERWATER_SURFACE_SHARE = 0.002f, UNDERWATER_SURFACE_SLACK = 0.05f;
    /** Land behind the surface farther than this counts as sky (scene_copy's underwaterSideAt). */
    public static final float UNDERWATER_LAND_MAX = 512.0f;
    /**
     * 2026-11-09 (fix session after 2b): a screen lookup (scene_copy's underwaterLookup) fades out over UNDERWATER_LOOKUP_EDGE of the
     * screen at its edges; its second step takes at least UNDERWATER_LOOKUP_MIN blocks of the ray.
     */
    public static final float UNDERWATER_LOOKUP_EDGE = 0.08f, UNDERWATER_LOOKUP_MIN = 1.0f;

    private UnderwaterMath() {
    }

    /** The visibility setting as sigma's divisor: clamped to its range, 1 when not a number. */
    public static float underwaterVisibility(float visibility) {
        if (visibility >= UNDERWATER_VISIBILITY_MIN) return Math.min(visibility, UNDERWATER_VISIBILITY_MAX);
        return visibility < UNDERWATER_VISIBILITY_MIN ? UNDERWATER_VISIBILITY_MIN : 1;
    }

    public static float[] underwaterExtinction(float visibility) {
        float v = underwaterVisibility(visibility);
        return new float[]{UNDERWATER_EXTINCTION_RED / v, UNDERWATER_EXTINCTION_GREEN / v, UNDERWATER_EXTINCTION_BLUE / v};
    }

    public static float[] underwaterDiffuse() {
        return new float[]{UNDERWATER_DIFFUSE_RED, UNDERWATER_DIFFUSE_GREEN, UNDERWATER_DIFFUSE_BLUE};
    }

    /** The water's own colour along a view of d blocks rising by y from h0 blocks under the surface, lit by light (see the shader). */
    public static float[] underwaterInscatter(float d, float y, float h0, float[] light, float visibility) {
        float len = d > 0 ? Math.min(d, UNDERWATER_LONGEST) : 0;
        float[] sigma = underwaterExtinction(visibility), k = underwaterDiffuse();
        float[] body = {WaterMath.WATER_BODY_RED, WaterMath.WATER_BODY_GREEN, WaterMath.WATER_BODY_BLUE};
        float h = Math.max(h0, 0);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            float rate = Math.max(sigma[i] - k[i] * y, sigma[i] * UNDERWATER_MIN_RATE);
            float b = body[i] * Math.max(light[i], 0) * (float) Math.exp(-k[i] * h);
            out[i] = b * sigma[i] / rate * (1 - (float) Math.exp(-rate * len));
        }
        return out;
    }

    /** colour seen through d blocks of water. */
    public static float[] underwaterMedium(float[] colour, float d, float y, float h0, float[] light, float visibility) {
        float len = d > 0 ? Math.min(d, UNDERWATER_LONGEST) : 0;
        float[] sigma = underwaterExtinction(visibility), in = underwaterInscatter(len, y, h0, light, visibility);
        return new float[]{colour[0] * (float) Math.exp(-sigma[0] * len) + in[0], colour[1] * (float) Math.exp(-sigma[1] * len) + in[1],
                colour[2] * (float) Math.exp(-sigma[2] * len) + in[2]};
    }

    /** The distance along dir to the surface surfaceUp blocks above, -1 where the view does not rise to it (no surface: 0). */
    public static float underwaterSurfaceDistance(float surfaceUp, float[] dir) {
        return surfaceUp > 0 && dir[1] > UNDERWATER_MIN_UP ? surfaceUp / dir[1] : -1;
    }

    /** Whether a pixel dist blocks away along a view rising by dirY (sky: nothing in front) lies on or behind the surface surfaceUp above, met at t. */
    public static boolean underwaterThroughSurface(float surfaceUp, float t, float dirY, float dist, boolean sky) {
        return t > 0 && (sky || dirY * dist >= surfaceUp - UNDERWATER_SURFACE_SLACK - UNDERWATER_SURFACE_SHARE * dist);
    }

    /** GLSL's refract(i, n, eta): (0, 0, 0) on total internal reflection. */
    static float[] refract(float[] i, float[] n, float eta) {
        float c = dot(n, i);
        float k = 1 - eta * eta * (1 - c * c);
        if (k < 0) return new float[3];
        float s = eta * c + (float) Math.sqrt(k);
        return new float[]{eta * i[0] - s * n[0], eta * i[1] - s * n[1], eta * i[2] - s * n[2]};
    }

    /** The view d (rising) leaving the water through the surface of upward normal n; (0, 0, 0) past the critical angle. */
    public static float[] underwaterRefract(float[] d, float[] n) {
        return refract(d, new float[]{-n[0], -n[1], -n[2]}, UNDERWATER_IOR);
    }

    /** The share reflected back down: Schlick's term in the air's angle, 1 past the critical angle. */
    public static float underwaterReflectance(float[] d, float[] n) {
        float[] r = underwaterRefract(d, n);
        if (dot(r, r) < 0.5f) return 1;
        float c = Math.max(0, Math.min(1, dot(r, n)));
        return WaterMath.WATER_F0 + (1 - WaterMath.WATER_F0) * (float) Math.pow(1 - c, WaterMath.FRESNEL_POWER);
    }

    /**
     * What the surface mirrors for a view d off the upward normal n: what the screen shows along the reflected ray (seen, path blocks
     * from the surface) with weight, else the endless water below, each through the water between it and the surface.
     */
    public static float[] underwaterMirrored(float[] d, float[] n, float[] seen, float path, float weight, float[] light, float visibility) {
        //GLSL's reflect(d, -n): the reflection off the downward face
        float dn = -dot(d, n);
        float y = Math.min(d[1] - 2 * dn * -n[1], 0);
        float[] deep = underwaterInscatter(UNDERWATER_LONGEST, y, 0, light, visibility);
        float[] near = underwaterMedium(seen, path, y, 0, light, visibility);
        float w = Math.max(0, Math.min(1, weight));
        return new float[]{deep[0] + (near[0] - deep[0]) * w, deep[1] + (near[1] - deep[1]) * w, deep[2] + (near[2] - deep[2]) * w};
    }

    /** The surface from below along d: above through the window (times the n^2 gain), below (underwaterMirrored) reflected. */
    public static float[] underwaterWindow(float[] d, float[] n, float[] above, float[] below) {
        float f = underwaterReflectance(d, n);
        float gain = UNDERWATER_WINDOW_GAIN * (1 - f);
        return new float[]{above[0] * gain + below[0] * f, above[1] * gain + below[1] * f, above[2] * gain + below[2] * f};
    }

    /** A view-space direction's place on the screen (GL uv, y up) for a perspective projection whose inverse has ix, iy on its diagonal; (-1, -1) behind. */
    public static float[] underwaterScreenOf(float[] v, float ix, float iy) {
        if (v[2] > -1e-4f) return new float[]{-1, -1};
        return new float[]{v[0] / (-v[2] * ix) * 0.5f + 0.5f, v[1] / (-v[2] * iy) * 0.5f + 0.5f};
    }

    /** How much a screen lookup at uv counts: 1 inside, falling to 0 at the screen's edges and 0 past them. */
    public static float underwaterEdgeFade(float[] uv) {
        float e = Math.min(Math.min(uv[0], 1 - uv[0]), Math.min(uv[1], 1 - uv[1])) / UNDERWATER_LOOKUP_EDGE;
        return Math.max(0, Math.min(1, e));
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
