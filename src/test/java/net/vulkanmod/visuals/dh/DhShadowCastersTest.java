package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiTerrainBufferContainer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiTerrainContainerUniformBufferWrapper;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiVertexBufferWrapper;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import net.vulkanmod.visuals.render.ShadowMath;
import net.vulkanmod.visuals.render.SunMath;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where DH's LODs cast: outside the square around the camera the vanilla terrain casts in (VkVanillaShadows' caster radius per
 * cascade, while the vanilla shadows are on), inside the cascade's box (by each buffer's own box, DhCullingTest). DH's camera
 * frustum culls what the main view needs, so the fork's frustum keeps every section for the shadows and remembers which ones the
 * camera sees, per section (corner and detail level).
 */
class DhShadowCastersTest {
    @Test
    void theCutoffIsTheVanillaCasterRadiusOfTheCascade() {
        //VanillaShadowCasters.REACH for the close and near cascades, the render distance up to 512 blocks for the far one
        assertEquals(64f, DhShadowCasters.cutoff("shadows.close", true, 12));
        assertEquals(64f, DhShadowCasters.cutoff("shadows.near", true, 32));
        assertEquals(192f, DhShadowCasters.cutoff("shadows.far", true, 12));
        assertEquals(512f, DhShadowCasters.cutoff("shadows.far", true, 32));
        assertEquals(512f, DhShadowCasters.cutoff("shadows.far", true, 48));
        assertEquals(32f, DhShadowCasters.cutoff("shadows.far", true, 2));
        //2026-10-29: while the LODs cast, every cascade's vanilla terrain ends at the shadow distance
        assertEquals(64f, DhShadowCasters.cutoff("shadows.far", true, 12, 4));
        assertEquals(160f, DhShadowCasters.cutoff("shadows.far", true, 32, 10));
        assertEquals(64f, DhShadowCasters.cutoff("shadows.near", true, 12, 4));
        assertEquals(128f, DhShadowCasters.cutoff("shadows.far", true, 8, 10));
        //Without the vanilla shadows the LODs are the only casters
        assertEquals(0f, DhShadowCasters.cutoff("shadows.near", false, 12));
        assertEquals(0f, DhShadowCasters.cutoff("shadows.far", false, 32));
    }

    private static ShadowMath.Cascade cascade(float timeOfDay, float radius) {
        return ShadowMath.cascade(SunMath.sunDirection(timeOfDay), 0, 0, 0, radius);
    }

    @Test
    void aBoxInsideTheCascadeCastsAndOneBesideItDoesNot() {
        ShadowMath.Cascade noon = cascade(0f, ShadowMath.NEAR_RADIUS);
        //The sun overhead: the map spans x and z around the camera
        assertTrue(DhShadowCasters.visible(noon.viewProjection(), 100, -64, 100, 164, 320, 164));
        assertFalse(DhShadowCasters.visible(noon.viewProjection(), 400, -64, 0, 464, 320, 64), "past the near box along x");
        assertFalse(DhShadowCasters.visible(noon.viewProjection(), 0, -64, -600, 64, 320, -536), "past the near box along z");
        //A column straddling the box's edge casts
        assertTrue(DhShadowCasters.visible(noon.viewProjection(), 150, -64, 0, 214, 320, 64));
    }

    /** At dusk the box reaches far towards the sun (DEPTH_MARGIN plus its radius): a distant mountain there shades the close cascade. */
    @Test
    void aLowSunReachesDistantCasters() {
        Vector3f toSun = SunMath.sunDirection(0.24f);
        assertTrue(toSun.x < -0.9f, "the sun low over -x (SunMath: timeOfDay 0.25 is the horizon)");
        ShadowMath.Cascade close = cascade(0.24f, ShadowMath.CLOSE_RADIUS);
        assertTrue(DhShadowCasters.visible(close.viewProjection(), -464, 0, -16, -400, 40, 48), "towards the sun, inside the depth margin");
        assertFalse(DhShadowCasters.visible(close.viewProjection(), 400, 0, -16, 464, 40, 48), "away from the sun");
    }

    /** The draw's corner is relative to the cascade's camera, not the world origin. */
    @Test
    void theBoxIsMeasuredFromTheCascadesCamera() {
        ShadowMath.Cascade noon = ShadowMath.cascade(SunMath.sunDirection(0f), 10_000, 70, -20_000, ShadowMath.NEAR_RADIUS);
        DhApiVertexBuffer column = this.buffer();
        column.uploadVertexBuffer(DhCullingTest.vertices(new int[]{0, 0, 0}, new int[]{64, 160, 64}), 2);
        assertTrue(DhShadowCasters.casts(new DhFrameCapture.Draw(column, 10_064, -64, -20_000), noon, 0));
        assertFalse(DhShadowCasters.casts(new DhFrameCapture.Draw(column, 64, -64, 0), noon, 0));
    }

    /**
     * Performance (2026-10-21): a refresh spread over slices draws each container in exactly one slice, picked by its corner (the
     * same in every frame, whatever DH's order), and the slices share the containers about evenly; one slice is everything.
     */
    @Test
    void everyContainerIsInExactlyOneSlice() {
        int slices = ShadowMath.FAR_SLICES;
        int[] count = new int[slices];
        int n = 0;
        for (int x = -32; x < 32; x++) {
            for (int z = -32; z < 32; z++) {
                DhFrameCapture.Draw d = new DhFrameCapture.Draw(null, x * 512L, -64, z * 512L);
                int in = 0;
                for (int s = 0; s < slices; s++) {
                    if (DhShadowCasters.inSlice(d, s, slices)) {
                        in++;
                        count[s]++;
                    }
                }
                assertEquals(1, in);
                assertTrue(DhShadowCasters.inSlice(d, 0, 1));
                assertEquals(DhShadowCasters.sliceOf(x * 512L, -64, z * 512L, slices), DhShadowCasters.sliceOf(x * 512L, -64, z * 512L, slices));
                n++;
            }
        }
        for (int c : count) {
            assertTrue(c > n / slices * 0.8 && c < n / slices * 1.2, java.util.Arrays.toString(count));
        }
    }

    /** A stand-in for DH's core frustum: sees x below 1000 only. */
    static class HalfFrustum implements IDhApiCullingFrustum {
        int updates;

        @Override
        public void update(int worldMinBlockY, int worldMaxBlockY, DhApiMat4f worldViewProjection) {
            this.updates++;
        }

        @Override
        public boolean intersects(int lodBlockPosMinX, int lodBlockPosMinZ, int lodBlockWidth, int lodDetailLevel) {
            return lodBlockPosMinX < 1000;
        }
    }

    @Test
    void theFrustumKeepsEverySectionAndRemembersWhatTheCameraSees() {
        HalfFrustum core = new HalfFrustum();
        DhCasterFrustum f = new DhCasterFrustum(() -> core, () -> 7);
        f.update(-64, 320, new DhApiMat4f());
        assertEquals(1, core.updates, "DH's own frustum is still updated");
        assertTrue(f.intersects(0, 0, 64, 6));
        assertTrue(f.intersects(4096, -512, 512, 9), "kept although the camera does not see it");
        assertEquals(DhCasterFrustum.IN_VIEW, f.find(0, 0, 6, 7));
        assertEquals(DhCasterFrustum.OUT_OF_VIEW, f.find(4096, -512, 9, 7));
        assertEquals(DhCasterFrustum.UNKNOWN, f.find(64, 0, 6, 7), "never seen");
        //A new frame forgets the last one
        f.update(-64, 320, new DhApiMat4f());
        assertEquals(DhCasterFrustum.UNKNOWN, f.find(0, 0, 6, 7));
    }

    /**
     * Ruling R26a: a parent section and its first child share their minimum corner; each keeps its own answer (the key has the
     * detail level), so DH's answer for one never stands for the other.
     */
    @Test
    void sectionsWithTheSameCornerAtTwoDetailLevelsKeepTheirOwnAnswers() {
        IDhApiCullingFrustum byDetail = new HalfFrustum() {
            @Override
            public boolean intersects(int x, int z, int w, int detail) {
                return detail == 6;
            }
        };
        DhCasterFrustum f = new DhCasterFrustum(() -> byDetail, () -> 3);
        f.update(-64, 320, new DhApiMat4f());
        f.intersects(512, 512, 64, 6);
        f.intersects(512, 512, 128, 7);
        assertEquals(DhCasterFrustum.IN_VIEW, f.find(512, 512, 6, 3));
        assertEquals(DhCasterFrustum.OUT_OF_VIEW, f.find(512, 512, 7, 3));
        //The other order gives the same answers
        f.update(-64, 320, new DhApiMat4f());
        f.intersects(512, 512, 128, 7);
        f.intersects(512, 512, 64, 6);
        assertEquals(DhCasterFrustum.IN_VIEW, f.find(512, 512, 6, 3));
        assertEquals(DhCasterFrustum.OUT_OF_VIEW, f.find(512, 512, 7, 3));
        //A detail level the key cannot hold, or none, is unknown (in view)
        f.intersects(0, 0, 1 << 20, 40);
        assertEquals(DhCasterFrustum.UNKNOWN, f.find(0, 0, 40, 3));
        assertEquals(DhCasterFrustum.UNKNOWN, f.find(512, 512, -1, 3));
    }

    /** Without DH's frustum (or when it throws) every section counts as seen: the main view is never emptied by the fork. */
    @Test
    void withoutDhsFrustumEverythingIsInView() {
        DhCasterFrustum none = new DhCasterFrustum(() -> null, () -> 1);
        none.update(0, 256, new DhApiMat4f());
        assertTrue(none.intersects(8192, 8192, 1024, 10));
        assertEquals(DhCasterFrustum.IN_VIEW, none.find(8192, 8192, 10, 1));

        DhCasterFrustum throwing = new DhCasterFrustum(() -> new HalfFrustum() {
            @Override
            public boolean intersects(int x, int z, int w, int d) {
                throw new IllegalStateException("broken");
            }
        }, () -> 1);
        throwing.update(0, 256, new DhApiMat4f());
        assertTrue(throwing.intersects(0, 0, 64, 6));
        assertEquals(DhCasterFrustum.IN_VIEW, throwing.find(0, 0, 6, 1));
    }

    /**
     * DH asks the frustum only while its frustum culling is on (its config can turn it off): a frame whose list was built without it
     * must not use an older frame's answers (a section out of view then would vanish from the main view), so they count for their
     * own frame only and everything else is unknown (in view).
     */
    @Test
    void anotherFramesAnswersAreNotUsed() {
        long[] serial = {10};
        DhCasterFrustum f = new DhCasterFrustum(HalfFrustum::new, () -> serial[0]);
        f.update(-64, 320, new DhApiMat4f());
        f.intersects(4096, 0, 512, 9);
        assertEquals(DhCasterFrustum.OUT_OF_VIEW, f.find(4096, 0, 9, 10));
        assertEquals(DhCasterFrustum.UNKNOWN, f.find(4096, 0, 9, 11), "frame 11 had no update");
        serial[0] = 11;
        assertEquals(DhCasterFrustum.UNKNOWN, f.find(4096, 0, 9, 11));
    }

    /** Shaped like DH's LodBufferContainer with its section position (DhSectionPos: the detail level in the low byte). */
    public static final class SectionContainer implements IDhApiTerrainBufferContainer {
        public final DhInternalsTest.FakePos minCornerBlockPos;
        public final long pos;
        private final IDhApiVertexBufferWrapper[] opaque;
        private final DhApiContainerUniform uniform = new DhApiContainerUniform();

        SectionContainer(int x, int y, int z, int detail, IDhApiVertexBufferWrapper[] opaque) {
            this.minCornerBlockPos = new DhInternalsTest.FakePos(x, y, z);
            this.pos = detail | (long) (x >> detail & 0xFFFFFFF) << 8 | (long) (z >> detail & 0xFFFFFFF) << 36;
            this.opaque = opaque;
        }

        @Override
        public IDhApiVertexBufferWrapper[] getVboOpaqueWrappers() {
            return this.opaque;
        }

        @Override
        public IDhApiVertexBufferWrapper[] getVboTransparentWrappers() {
            return null;
        }

        @Override
        public IDhApiTerrainContainerUniformBufferWrapper getUniformContainer() {
            return this.uniform;
        }
    }

    private final FakeGpu gpu = new FakeGpu();
    private final List<DhVertexBuffer> made = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void reset() {
        DhInternals.resetForTest();
        for (DhVertexBuffer b : this.made) b.close();
        this.made.clear();
    }

    private DhApiVertexBuffer buffer() {
        DhApiVertexBuffer b = new DhApiVertexBuffer("distantHorizons:TerrainRenderer", this.gpu);
        this.made.add(b);
        return b;
    }

    private static DhApiRenderParam param() {
        DhApiRenderParam p = new DhApiRenderParam();
        p.mcProjectionMatrix.setIdentity();
        p.mcModelViewMatrix.setIdentity();
        p.dhModelViewMatrix.setIdentity();
        p.dhProjectionMatrix.setIdentity();
        p.nearClipPlane = 172.8f;
        p.farClipPlane = 9216f;
        return p;
    }

    /** The capture tags each draw with what the frustum saw of its section, found by the container's corner and detail level. */
    @Test
    void theCaptureTagsEachDrawFromTheFrustum() {
        HalfFrustum core = new HalfFrustum();
        DhCasterFrustum frustum = new DhCasterFrustum(() -> core, () -> 40);
        DhFrameCapture capture = new DhFrameCapture(frustum);
        frustum.update(-64, 320, new DhApiMat4f());
        frustum.intersects(512, 0, 64, 6);
        frustum.intersects(2048, 0, 256, 8);
        DhApiVertexBuffer a = this.buffer(), b = this.buffer(), c = this.buffer(), d = this.buffer();
        capture.store(40, param(), true, DhTerrain.view(new DhFrameCaptureTest.ListOf<>(List.of(
                new SectionContainer(512, -64, 0, 6, new IDhApiVertexBufferWrapper[]{a}),
                new SectionContainer(2048, -64, 0, 8, new IDhApiVertexBufferWrapper[]{b}),
                new SectionContainer(-4096, -64, 0, 6, new IDhApiVertexBufferWrapper[]{c}),
                new DhFrameCaptureTest.FakeContainer(2048, -64, 0, new IDhApiVertexBufferWrapper[]{d}, null)))));
        List<DhFrameCapture.Draw> draws = capture.take(40).opaque();
        assertEquals(new DhFrameCapture.Draw(a, 512, -64, 0, true), draws.get(0));
        assertEquals(new DhFrameCapture.Draw(b, 2048, -64, 0, false), draws.get(1));
        assertEquals(new DhFrameCapture.Draw(c, -4096, -64, 0), draws.get(2), "a section the frustum never saw: in view");
        assertTrue(draws.get(2).inView());
        assertTrue(draws.get(3).inView(), "a container whose detail level cannot be read: in view");
    }

    /** R26a through the capture: a child section at the corner of an out-of-view parent is drawn by its own answer. */
    @Test
    void theCaptureFindsEachSectionByItsDetailLevel() {
        IDhApiCullingFrustum parentOut = new HalfFrustum() {
            @Override
            public boolean intersects(int x, int z, int w, int detail) {
                return detail != 7;
            }
        };
        DhCasterFrustum frustum = new DhCasterFrustum(() -> parentOut, () -> 41);
        DhFrameCapture capture = new DhFrameCapture(frustum);
        frustum.update(-64, 320, new DhApiMat4f());
        frustum.intersects(0, 0, 64, 6);
        frustum.intersects(0, 0, 128, 7);
        DhApiVertexBuffer child = this.buffer(), parent = this.buffer();
        capture.store(41, param(), true, DhTerrain.view(new DhFrameCaptureTest.ListOf<>(List.of(
                new SectionContainer(0, -64, 0, 6, new IDhApiVertexBufferWrapper[]{child}),
                new SectionContainer(0, -64, 0, 7, new IDhApiVertexBufferWrapper[]{parent})))));
        List<DhFrameCapture.Draw> draws = capture.take(41).opaque();
        assertTrue(draws.get(0).inView());
        assertFalse(draws.get(1).inView());
    }
}
