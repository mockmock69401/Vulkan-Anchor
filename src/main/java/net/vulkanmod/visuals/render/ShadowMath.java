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
     * 2026-11-22 (the user, Patrix 32x in the "map test" pack: a staircase along a leaf's shadow on a grey block): along map y the close
     * texel was 1/16 block (1024 over 64 blocks). Where a caster nearly touches its receiver the soft width is under a texel and the
     * lookup is the 2 x 2 bilinear one, so an edge running across map y came out in 1/16-block steps. CLOSE_HEIGHT 2048 (1/32 block)
     * draws the leaves' own outline; 4096 looked the same for twice the memory. The texel, and with it the bias and normal offset,
     * halve with it (the coarser axis). The two close maps take 128 MB instead of 64.
     */
    public static final int CLOSE_WIDTH = 8192, CLOSE_HEIGHT = 2048;
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
    /**
     * 2026-11-21 (the user's 1% lows, Luxium's "redraw on an interval" idea): the near and close refreshes (blocks placed or broken,
     * entities moved) come at most this often, at 150 fps every 8 frames instead of every 4. Below 60 fps NEAR_INTERVAL frames already
     * take longer; the sun's step draws (ShadowSteps) are not refreshes and keep their pace.
     */
    public static final long REFRESH_MIN_NANOS = 50_000_000L;
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

    /**
     * The same with nearReach (nearReach(shadowDistance)) for the close and near cascades. 2026-11-09 (the user: with Distant Horizons
     * the sun shadows ended in a circle about the shadow distance around the player): the far cascade takes the vanilla terrain out to
     * the render distance whether or not LODs cast too. From 2026-10-29 it took it only out to the shadow distance while LODs cast,
     * expecting the LODs to cover the rest; but DH does not draw its LODs where vanilla chunks stand, so nothing cast between the
     * shadow distance and the render distance.
     */
    public static float vanillaRadius(Which which, int renderDistanceChunks, float nearReach) {
        if (which == Which.FAR) return Math.min(renderDistanceChunks * 16f, FAR_VANILLA_MAX);
        return nearReach;
    }

    /**
     * 2026-10-29 (the user: flying from the LODs into loaded chunks, shadows went missing, then moved): while the vanilla terrain casts
     * too, LODs cast only past nearReach (the shadow distance) in every cascade; in the far one they overlap the vanilla terrain out to
     * the render distance, and the depth map keeps the nearer caster of the two. So flying in, a chunk not built yet still casts by its
     * LOD and a built one by its blocks; nothing changes at the render distance.
     */
    public static float lodCutoff(float nearReach) {
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

    /**
     * 2026-11-19 (the user: near mountains, the shadows of forests outside the chunks appeared all at once as they came in): the close
     * and near maps take the vanilla terrain only out to nearReach, so lighting.comp's farFill takes the far map's shadow where its
     * blocker lies farther from the camera. Within fillReach a block surely casts into them: the sections within nearReach (+ half a
     * section) of the camera that drew a map, which may since have moved NEAR_MAX_MOVE blocks. The far blockers fade in over FILL_BAND
     * blocks before it; one within FILL_ABOVE far texels of its receiver (toward the sun) is the far map's own coarse ground and does
     * not count, coming in over another FILL_ABOVE.
     */
    public static final float FILL_BAND = 8, FILL_ABOVE = 2;
    /** lighting.comp's fillParams location. */
    public static final int FILL_SLOT = 20;

    /** How far from the camera (blocks, on both horizontal axes) the close and near maps surely hold the vanilla casters. */
    public static float fillReach(float nearReach) {
        return Math.max(nearReach - NEAR_MAX_MOVE, 0);
    }

    /**
     * fillParams: {fillReach, FILL_BAND, the far blocker's least distance and its band (blocks)}; all 0 (off) without the vanilla
     * casters or the far maps, where the near maps hold all there is.
     */
    public static float[] fillParams(boolean vanillaOn, boolean farOn, int shadowDistanceChunks, int renderDistanceChunks) {
        if (!vanillaOn || !farOn) return new float[4];
        float farTexel = 2 * FAR_RADIUS / Math.min(FAR_WIDTH, FAR_HEIGHT);
        return new float[]{fillReach(nearReach(shadowDistanceChunks, renderDistanceChunks)), FILL_BAND, FILL_ABOVE * farTexel, FILL_ABOVE * farTexel};
    }

    /** lighting.comp's farFill weight for a far blocker at reach blocks from the camera (Chebyshev, horizontal) and above blocks toward the sun. */
    public static float fillWeight(float[] params, float reach, float above) {
        if (!(params[0] > 0)) return 0;
        return smoothstep(params[0] - params[1], params[0], reach) * smoothstep(params[2], params[2] + params[3], above);
    }

    private static float smoothstep(float a, float b, float x) {
        float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
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

    /**
     * Whether a near or close cascade refreshes this frame: its phase of NEAR_INTERVAL, and REFRESH_MIN_NANOS since its last whole draw
     * (lastDrawNanos; Long.MIN_VALUE: never drawn).
     */
    public static boolean refreshDue(int frameId, int phase, long nowNanos, long lastDrawNanos) {
        return refreshDue(frameId, phase, nowNanos, lastDrawNanos, REFRESH_MIN_NANOS);
    }

    /** 2026-11-24: as above with the least time between refreshes (the End star's: EndStarLight.REFRESH_NANOS). */
    public static boolean refreshDue(int frameId, int phase, long nowNanos, long lastDrawNanos, long minNanos) {
        if (Math.floorMod(frameId, NEAR_INTERVAL) != phase) return false;
        return lastDrawNanos == Long.MIN_VALUE || nowNanos - lastDrawNanos >= minNanos;
    }
}
