package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * P7j design 3.1, 3.3: the sun's two orthographic views around the camera. Positions are camera-relative, as the LOD
 * passes and the lighting pass use them; the box is moved by less than a texel so its texel grid stays fixed in the world
 * while the camera moves inside its ANCHOR cell. shadow.glsl mirrors SHADOW_BLEND: change both together.
 */
public final class ShadowMath {
    /** Each cascade's depth map is MAP_SIZE x MAP_SIZE. */
    public static final int MAP_SIZE = 2048;
    /** Half the box width in blocks: the near cascade (block-sharp) and the far one (islands, mountains). */
    public static final float NEAR_RADIUS = 160, FAR_RADIUS = 1536;
    /**
     * Texel crawl pass (2026-10-16): a map's texel grid turns with the sun, so a shadow edge slid along with it and jumped back a
     * texel each time the true edge left the texel (0.16 blocks near: a sawtooth on every edge as the day ran). The close cascade
     * covers the blocks around the camera at a fifth of the near texel, where that sawtooth showed; it is read before the near one.
     */
    public static final float CLOSE_RADIUS = 32;
    /** The close cascade refreshes every NEAR_INTERVAL frames at CLOSE_PHASE (between the far and near ones) and is drawn again past CLOSE_MAX_MOVE. */
    public static final int CLOSE_PHASE = 1;
    public static final float CLOSE_MAX_MOVE = 4;
    /**
     * Wobble pass (2026-10-16): the sun turns about the maps' y axis, so a point slides along map x through the texel grid as the day
     * runs, and a lookup at a north-south edge swung by up to a sixth of its contrast once a texel (a ripple about twice a second; a
     * texel's weight against the whole box). The close map is CLOSE_WIDTH texels across map x (a quarter of its texel along y, which
     * does not slide) and its box CLOSE_SPREAD_TEXELS of them wide (the near cascade's 0.47 blocks): a twentieth of the swing.
     * shadow.glsl mirrors CLOSE_SPREAD: change both together.
     */
    public static final int CLOSE_WIDTH = 8192, CLOSE_HEIGHT = 1024;
    /**
     * Distant wobble (CTE2, 2026-10-22: shadows past the close cascade undulated as the day ran, most with a low sun): the same slide
     * through the near map's 0.16-block and the far map's 1.5-block texels. Their maps are NEAR_WIDTH / FAR_WIDTH texels across map x
     * (a quarter of the texel: 0.04 and 0.38 blocks) and MAP_SIZE along y; their texel (the coarser axis), and with it the bias and
     * normal offset, stay as they were. Back-and-forth movement at sunrise in the CTE2 copy fell by 22-44% for about 0.2 ms of GPU
     * a frame and some 240 MB of depth images.
     */
    public static final int NEAR_WIDTH = 8192, NEAR_HEIGHT = MAP_SIZE;
    public static final int FAR_WIDTH = 8192, FAR_HEIGHT = MAP_SIZE;
    public static final int CLOSE_SPREAD_TEXELS = 61;
    /** Extra blocks toward the sun, so casters above the box (high islands) still cast. */
    public static final float DEPTH_MARGIN = 512;
    /**
     * The far cascade refreshes one of its maps every FAR_INTERVAL frames (ShadowSteps; blocks placed or broken since), drawn
     * FAR_SLICES frames long a slice of the casters a frame (VkShadowMaps; performance, 2026-10-21: the far casters are some 17
     * million LOD quads, which at once cost about 2.5 ms of one frame in four).
     */
    public static final int FAR_INTERVAL = 8, FAR_SLICES = 8;
    /**
     * P7f-2: the near cascade refreshes every NEAR_INTERVAL frames, at NEAR_PHASE (between the far one's), and is read rebased
     * in between. A map is drawn again once the camera moved NEAR_MAX_MOVE (near) or FAR_MAX_MOVE (far, review 7) blocks from
     * where it was drawn; the sun's turn is the steps' (ShadowSteps).
     */
    public static final int NEAR_INTERVAL = 4, NEAR_PHASE = 2;
    public static final float NEAR_MAX_MOVE = 8, FAR_MAX_MOVE = 64;
    /** The receiver moves this many texels along its normal before the lookup and is compared with this many texels of depth bias. */
    public static final float NORMAL_OFFSET_TEXELS = 1.5f, BIAS_TEXELS = 1.0f;
    /** The near cascade's outer band (share of its half width) fades into the far one. */
    public static final float BLEND = 0.1f;
    /** The texel grid is measured from the camera's ANCHOR-block cell, not the world origin, so the turning sun slides it by at most the cell (final review). */
    public static final float ANCHOR = 16;

    /** The three cascades, finest first. */
    public enum Which {CLOSE, NEAR, FAR}

    /**
     * Fork design 4.3: without the LODs the vanilla terrain is the far cascade's only caster. It reaches the render distance, at
     * most FAR_VANILLA_MAX blocks (the shadow pass's cost cap, A3; gate G1-c tunes it).
     */
    public static final float FAR_VANILLA_MAX = 512;

    /**
     * 2026-10-29 (the user: Complementary Reimagined's High profile casts sharp shadows 12 chunks out): the shadow distance setting,
     * in chunks, is how far the vanilla terrain casts into the close and near cascades; it ends at the near cascade's edge.
     */
    public static final int SHADOW_DISTANCE_MIN = 2, SHADOW_DISTANCE_MAX = (int) (NEAR_RADIUS / 16);

    /** How far from the camera (horizontally, in blocks) vanilla sections cast into a cascade, at the default shadow distance. */
    public static float vanillaRadius(Which which, int renderDistanceChunks) {
        return vanillaRadius(which, renderDistanceChunks, VanillaShadowCasters.REACH);
    }

    /** The same with nearReach (nearReach(shadowDistance)) for the close and near cascades. */
    public static float vanillaRadius(Which which, int renderDistanceChunks, float nearReach) {
        return vanillaRadius(which, renderDistanceChunks, nearReach, false);
    }

    /**
     * 2026-10-29 (the user: flying from the LODs into loaded chunks, shadows went missing, then moved): with LODs casting past the
     * vanilla terrain (lodBeyond: VisualsExtension.castsFarTerrain), the far cascade takes the vanilla terrain only out to nearReach
     * too. Before, its vanilla casters reached the render distance: flying in, the casters changed LOD to vanilla at the render
     * distance (nothing cast where the chunks were not built yet), back to LOD at the near cascade's edge, and to vanilla again at
     * the shadow distance. Now they change once, at the shadow distance, where the chunks are built.
     */
    public static float vanillaRadius(Which which, int renderDistanceChunks, float nearReach, boolean lodBeyond) {
        if (which == Which.FAR) return lodBeyond ? nearReach : Math.min(renderDistanceChunks * 16f, FAR_VANILLA_MAX);
        return nearReach;
    }

    /** The vanilla casters' reach in blocks for a shadow distance in chunks (kept in its range). */
    public static float nearReach(int shadowDistanceChunks) {
        return Math.max(SHADOW_DISTANCE_MIN, Math.min(SHADOW_DISTANCE_MAX, shadowDistanceChunks)) * 16f;
    }

    /**
     * 2026-10-29 (audit): the same, never past the render distance. No vanilla section exists beyond it, while the LODs stop casting
     * inside the reach: a shadow distance of 10 chunks at a render distance of 8 left 128..160 blocks with no caster at all.
     */
    public static float nearReach(int shadowDistanceChunks, int renderDistanceChunks) {
        return Math.min(nearReach(shadowDistanceChunks), Math.max(SHADOW_DISTANCE_MIN, renderDistanceChunks) * 16f);
    }

    /** A cascade: projection (Vulkan depth 0..1) and light view (rotation), the texel and depth range in blocks, the camera it was centred on. */
    public record Cascade(Matrix4f projection, Matrix4f view, float radius, float texel, float depthRange, double cameraX, double cameraY,
                          double cameraZ) {
        /** Camera-relative world to shadow clip (orthographic: w = 1). */
        public Matrix4f viewProjection() {
            return new Matrix4f(this.projection).mul(this.view);
        }
    }

    private ShadowMath() {
    }

    /** Looking along -toSun, so the sun is behind (+z); the sun turns about z (SunMath.sunDirection has z = 0), so up = z is never parallel. */
    public static Matrix4f lightView(Vector3fc toSun) {
        Vector3f d = new Vector3f(toSun).normalize();
        return new Matrix4f().setLookAlong(-d.x, -d.y, -d.z, 0, 0, 1);
    }

    public static Cascade cascade(Vector3fc toSun, double cameraX, double cameraY, double cameraZ, float radius) {
        return cascade(toSun, cameraX, cameraY, cameraZ, radius, MAP_SIZE, MAP_SIZE);
    }

    /** A map width x height texels; its texel is the coarser of the two (the offsets' unit), the grid is held per axis. */
    public static Cascade cascade(Vector3fc toSun, double cameraX, double cameraY, double cameraZ, float radius, int width, int height) {
        Matrix4f view = lightView(toSun);
        float texelU = 2 * radius / width, texelV = 2 * radius / height, texel = Math.max(texelU, texelV);
        //The camera in light space, measured from its ANCHOR cell (in double): the grid stays in the world while the camera
        // is in the cell, and the turning sun moves it by at most the cell times the angle
        double rx = cameraX - Math.floor(cameraX / ANCHOR) * ANCHOR;
        double ry = cameraY - Math.floor(cameraY / ANCHOR) * ANCHOR;
        double rz = cameraZ - Math.floor(cameraZ / ANCHOR) * ANCHOR;
        double u = view.m00() * rx + view.m10() * ry + view.m20() * rz;
        double v = view.m01() * rx + view.m11() * ry + view.m21() * rz;
        float du = (float) (u - Math.floor(u / texelU) * texelU);
        float dv = (float) (v - Math.floor(v / texelV) * texelV);
        Matrix4f projection = new Matrix4f().setOrtho(-radius - du, radius - du, -radius - dv, radius - dv, -(radius + DEPTH_MARGIN), radius, true);
        return new Cascade(projection, view, radius, texel, 2 * radius + DEPTH_MARGIN, cameraX, cameraY, cameraZ);
    }

    /** Sun-step crossfade (2026-10-16): whether the camera moved more than maxMove blocks since a map was drawn (its reach). */
    public static boolean moved(Cascade drawn, Cascade now, float maxMove) {
        double dx = now.cameraX() - drawn.cameraX(), dy = now.cameraY() - drawn.cameraY(), dz = now.cameraZ() - drawn.cameraZ();
        return dx * dx + dy * dy + dz * dz > (double) maxMove * maxMove;
    }

    /** shadow.glsl's cascade blend: 0 inside the near cascade, 1 at its edge (edge = max of |ndc x|, |ndc y|). */
    public static float blendWeight(float edge) {
        float t = Math.max(0, Math.min(1, (edge - (1 - BLEND)) / BLEND));
        return t * t * (3 - 2 * t);
    }

    /** A cascade drawn around the camera then, read with positions relative to the camera now (the far cascade is a few frames old). */
    public static Matrix4f rebase(Matrix4f viewProjection, double thenX, double thenY, double thenZ, double nowX, double nowY, double nowZ) {
        return new Matrix4f(viewProjection).translate((float) (nowX - thenX), (float) (nowY - thenY), (float) (nowZ - thenZ));
    }
}
