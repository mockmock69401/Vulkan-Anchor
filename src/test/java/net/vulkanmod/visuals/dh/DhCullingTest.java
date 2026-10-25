package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import net.vulkanmod.visuals.render.ShadowMath;
import net.vulkanmod.visuals.render.SunMath;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 5b: the fork culls DH's draws itself, by each buffer's own box (its vertices' minimum and maximum, kept at upload), against
 * the frame's view (the main view: the frame's DH projection and model view, Vulkan's 0..1 depth; a cascade: its orthographic box)
 * and against the main view's near discard (terrain.frag drops every LOD fragment nearer than the discard distance). A culled
 * draw is one no fragment of could have been written.
 */
class DhCullingTest {
    /** The camera at the origin looking down -z, 70 degrees high at 16:9, Vulkan's depth. */
    private static final Matrix4f VIEW = new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.5f, 1000f, true);

    private static boolean sees(Matrix4fc m, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return DhCulling.inClipVolume(m, new float[]{minX, minY, minZ, maxX, maxY, maxZ});
    }

    @Test
    void aBoxInsideTheViewIsSeenAndOneBesideItIsNot() {
        assertTrue(sees(VIEW, -5, -5, -105, 5, 5, -95), "straight ahead");
        assertFalse(sees(VIEW, 400, -5, -105, 410, 5, -95), "past the right side");
        assertFalse(sees(VIEW, -5, 200, -105, 5, 210, -95), "above the top");
        assertFalse(sees(VIEW, -5, -5, -1500, 5, 5, -1200), "past the far plane");
    }

    /** The right side is at x = tan(35 degrees) * 16/9 * 100 = 124.5 at 100 blocks: a box across it is seen. */
    @Test
    void aBoxAcrossAnEdgeIsSeen() {
        assertTrue(sees(VIEW, 100, -5, -105, 300, 5, -95));
        assertTrue(sees(VIEW, 124.4f, 0, -100, 124.4f, 0, -100), "a point just inside");
        assertFalse(sees(VIEW, 124.6f, 0, -100, 124.6f, 0, -100), "a point just outside");
        //Wider than the view on both sides and in front: seen although every corner is outside some plane
        assertTrue(sees(VIEW, -1000, -1000, -200, 1000, 1000, -100));
    }

    /** Behind the camera the projected x and y flip sign: the test is homogeneous, so a box behind is never taken for one ahead. */
    @Test
    void aBoxBehindTheCameraIsNotSeen() {
        assertFalse(sees(VIEW, -5, -5, 95, 5, 5, 105));
        assertFalse(sees(VIEW, -500, -500, 50, 500, 500, 100), "wide, but wholly behind");
        assertFalse(sees(VIEW, -5, -5, -0.4f, 5, 5, 20), "behind the near plane");
        assertTrue(sees(VIEW, -5, -5, -20, 5, 5, 20), "around the camera");
    }

    /** terrain.frag discards fragments nearer than the discard distance (a sphere): a box wholly inside it shows nothing. */
    @Test
    void aBoxWhollyInsideTheDiscardDistanceShowsNothing() {
        float[] inside = {-40, -40, -40, 40, 40, 40};//the farthest corner at 69.3
        assertTrue(DhCulling.withinDistance(inside, 115.1f));
        assertFalse(DhCulling.withinDistance(inside, 69f), "a corner past the distance");
        assertFalse(DhCulling.withinDistance(new float[]{100, -10, -10, 130, 10, 10}, 115.1f), "across the sphere");
        assertFalse(DhCulling.withinDistance(inside, 0f), "no discard");
        assertFalse(DhCulling.withinDistance(inside, Float.NaN));
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

    /** One vertex a line {x, y, z, micro}: DH's u16 position and the meta's micro-offset bits (0b00zzyyxx). */
    static ByteBuffer vertices(int[]... v) {
        ByteBuffer b = ByteBuffer.allocateDirect(v.length * DhVertexFormat.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < v.length; i++) {
            int at = i * DhVertexFormat.BYTES;
            b.putShort(at, (short) v[i][0]).putShort(at + 2, (short) v[i][1]).putShort(at + 4, (short) v[i][2]);
            b.putShort(at + DhVertexFormat.META_OFFSET, (short) (v[i].length > 3 ? v[i][3] << 8 : 0));
        }
        return b;
    }

    private DhApiVertexBuffer buffer(int[]... v) {
        DhApiVertexBuffer b = new DhApiVertexBuffer("distantHorizons:TerrainRenderer", this.gpu);
        b.uploadVertexBuffer(vertices(v), v.length);
        this.made.add(b);
        return b;
    }

    @Test
    void theBufferKeepsItsVerticesBounds() {
        DhApiVertexBuffer b = this.buffer(new int[]{3, 70, 9}, new int[]{60, 66, 2}, new int[]{12, 140, 63}, new int[]{40000, 100, 5});
        assertEquals(new DhVertexBuffer.Bounds(3, 66, 2, 40000, 140, 63), b.bounds());
        //A later upload replaces them; an empty one leaves none
        b.uploadVertexBuffer(vertices(new int[]{1, 2, 3}, new int[]{4, 5, 6}), 2);
        assertEquals(new DhVertexBuffer.Bounds(1, 2, 3, 4, 5, 6), b.bounds());
        b.uploadVertexBuffer(vertices(), 0);
        assertNull(b.bounds());
    }

    /**
     * The scan reads a direct buffer a vertex at a time as one native word (it runs on DH's uploads, on the render thread); a heap
     * buffer, or one read from a later position, gives the same bounds.
     */
    @Test
    void theBoundsAreTheSameForEveryKindOfBuffer() {
        int[][] v = {{3, 70, 9, 0b110101}, {60, 66, 2}, {12, 140, 63, 0b11}, {40000, 100, 5}, {65535, 0, 65535}};
        DhVertexBuffer.Bounds expected = new DhVertexBuffer.Bounds(3, 0, 2, 65535, 140, 65535);
        ByteBuffer direct = vertices(v);
        assertEquals(expected, DhVertexBuffer.Bounds.of(direct, v.length));
        ByteBuffer heap = ByteBuffer.allocate(direct.capacity()).order(ByteOrder.LITTLE_ENDIAN);
        heap.put(direct.duplicate()).flip();
        assertEquals(expected, DhVertexBuffer.Bounds.of(heap, v.length));
        //From the second vertex on, as DH's position may be
        ByteBuffer later = vertices(v).position(DhVertexFormat.BYTES);
        assertEquals(new DhVertexBuffer.Bounds(12, 0, 2, 65535, 140, 65535), DhVertexBuffer.Bounds.of(later, v.length - 1));
        assertEquals(DhVertexFormat.BYTES, later.position(), "left as given");
        assertNull(DhVertexBuffer.Bounds.of(direct, 0));
    }

    @Test
    void aRefusedUploadLeavesNoBounds() {
        this.gpu.refuse = true;
        assertNull(this.buffer(new int[]{1, 2, 3}).bounds());
    }

    /** The box of a draw: its corner plus its buffer's bounds, relative to the camera, x and z widened by the micro offset. */
    @Test
    void theDrawsBoxIsItsCornerPlusItsBounds() {
        DhApiVertexBuffer b = this.buffer(new int[]{0, 128, 0}, new int[]{64, 192, 32});
        float[] box = new float[6];
        assertTrue(DhCulling.box(new DhFrameCapture.Draw(b, 1000, -64, 2000), 990.5, 70, 2010, box));
        float pad = DhVertexFormat.MICRO_OFFSET + DhCulling.MARGIN;
        assertEquals(9.5f - pad, box[0], 1e-4f);
        assertEquals(-6f - DhCulling.MARGIN, box[1], 1e-4f);
        assertEquals(-10f - pad, box[2], 1e-4f);
        assertEquals(73.5f + pad, box[3], 1e-4f);
        assertEquals(58f + DhCulling.MARGIN, box[4], 1e-4f);
        assertEquals(22f + pad, box[5], 1e-4f);
        assertFalse(DhCulling.box(new DhFrameCapture.Draw(null, 0, 0, 0), 0, 0, 0, box), "no buffer: unknown");
        assertFalse(DhCulling.box(new DhFrameCapture.Draw(new DhApiVertexBuffer("t", this.gpu), 0, 0, 0), 0, 0, 0, box), "never filled: unknown");
    }

    /**
     * The bound never culls what terrain.vert can put on screen: a vertex past the view's edge by less than the micro offset is moved
     * inside by it (bit 1 of the x code: negative), and a vertex on the edge itself is on screen.
     */
    @Test
    void theBoundKeepsAVertexTheMicroOffsetMovesIntoView() {
        Matrix4f ortho = new Matrix4f().setOrtho(-10, 10, -10, 10, -10, 10, true);
        float[] box = new float[6];
        //At x = 10.005 the vertex is outside; DH's shader moves it to 9.995
        DhApiVertexBuffer pulled = this.buffer(new int[]{0, 0, 0, 0b11});
        assertTrue(DhCulling.box(new DhFrameCapture.Draw(pulled, 10, 0, 0), -0.005, 0, 0, box));
        assertTrue(DhCulling.inClipVolume(ortho, box));
        //On the edge
        DhApiVertexBuffer edge = this.buffer(new int[]{0, 0, 0});
        assertTrue(DhCulling.box(new DhFrameCapture.Draw(edge, 10, 0, 0), 0, 0, 0, box));
        assertTrue(DhCulling.inClipVolume(ortho, box));
        //A block past the edge is culled: the margin is not a blanket keep
        assertTrue(DhCulling.box(new DhFrameCapture.Draw(edge, 11, 0, 0), 0, 0, 0, box));
        assertFalse(DhCulling.inClipVolume(ortho, box));
    }

    /** The near discard keeps a buffer with any vertex at or past the distance, the micro offset included. */
    @Test
    void theDiscardKeepsAVertexAtTheDistance() {
        float[] box = new float[6];
        DhApiVertexBuffer b = this.buffer(new int[]{0, 0, 0}, new int[]{100, 0, 0, 0b01});
        assertTrue(DhCulling.box(new DhFrameCapture.Draw(b, 0, 0, 0), 0, 0, 0, box));
        assertFalse(DhCulling.withinDistance(box, 100f), "the vertex at 100.01");
        assertFalse(DhCulling.withinDistance(box, 100.05f), "within the margin");
        assertTrue(DhCulling.withinDistance(box, 101f));
    }

    private static DhFrameCapture.Frame frame(long serial, Matrix4f projection, Matrix4f modelView, float near, List<DhFrameCapture.Draw> opaque) {
        return new DhFrameCapture.Frame(serial, projection, modelView, near, 9216f, -64, opaque, List.of());
    }

    /** The main view's answer: DH's own section frustum (counted apart: it culled before Task 5b too), the buffer's box against the view, the near discard. */
    @Test
    void theMainViewDrawsOnlyWhatCanShow() {
        DhApiVertexBuffer column = this.buffer(new int[]{0, 0, 0}, new int[]{16, 32, 16});
        Matrix4f vp = DhCulling.viewProjection(frame(1, VIEW, new Matrix4f(), 99.1f, List.of()));
        float[] box = new float[6];
        assertEquals(DhCulling.DRAWN, DhCulling.mainView(new DhFrameCapture.Draw(column, -8, -16, -300), vp, 0, 0, 0, 115.1f, box));
        assertEquals(DhCulling.OUT_OF_VIEW, DhCulling.mainView(new DhFrameCapture.Draw(column, -8, -16, 300), vp, 0, 0, 0, 115.1f, box), "behind");
        assertEquals(DhCulling.DH_FRUSTUM, DhCulling.mainView(new DhFrameCapture.Draw(column, -8, -16, -300, false), vp, 0, 0, 0, 115.1f, box),
                "DH's frustum left it out");
        assertEquals(DhCulling.NEAR, DhCulling.mainView(new DhFrameCapture.Draw(column, -8, -16, -40), vp, 0, 0, 0, 115.1f, box), "all discarded");
        assertEquals(DhCulling.DRAWN, DhCulling.mainView(new DhFrameCapture.Draw(column, -8, -16, -40), vp, 0, 0, 0, 0f, box), "no discard");
        assertEquals(DhCulling.DRAWN, DhCulling.mainView(new DhFrameCapture.Draw(null, -8, -16, 300), vp, 0, 0, 0, 115.1f, box),
                "bounds unknown: drawn");
    }

    /**
     * The culling uses the frame's own matrices, the frame recordOffscreen draws (taken for its serial): the view projection is that
     * frame's projection times its model view, the matrix the draws are recorded with.
     */
    @Test
    void theCullingUsesTheFramesOwnMatrices() {
        DhFrameCaptureTest.ListOf<DhFrameCaptureTest.FakeContainer> none = new DhFrameCaptureTest.ListOf<>(List.of());
        DhFrameCapture capture = new DhFrameCapture(new DhCasterFrustum(() -> null, () -> 0));
        DhApiRenderParam moved = new DhApiRenderParam();
        moved.mcProjectionMatrix.setIdentity();
        moved.mcModelViewMatrix.setIdentity();
        moved.dhProjectionMatrix.setIdentity();
        //DH's model view of frame 8 moves the world 4 blocks along +x (row-major)
        moved.dhModelViewMatrix.setIdentity();
        moved.dhModelViewMatrix.m03 = 4;
        capture.store(8, moved, true, DhTerrain.view(none));
        DhFrameCapture.Frame f = capture.take(8);
        Matrix4f vp = DhCulling.viewProjection(f);
        assertEquals(new Matrix4f(f.dhProjectionVk()).mul(f.dhModelView()), vp);
        DhApiVertexBuffer b = this.buffer(new int[]{0, 0, 0}, new int[]{1, 1, 1});
        float[] box = new float[6];
        //This frame's identity projection: the clip volume is |x|, |y|, |z| <= 1 (GL's depth remapped to Vulkan's), after the move
        assertEquals(DhCulling.DRAWN, DhCulling.mainView(new DhFrameCapture.Draw(b, -4, 0, 0), vp, 0, 0, 0, 0f, box));
        assertEquals(DhCulling.OUT_OF_VIEW, DhCulling.mainView(new DhFrameCapture.Draw(b, 0, 0, 0), vp, 0, 0, 0, 0f, box));
        //Another frame's matrices give the other answer: the culling follows the frame, not a matrix kept from before
        Matrix4f unmoved = DhCulling.viewProjection(frame(9, new Matrix4f(f.dhProjectionVk()), new Matrix4f(), 0, List.of()));
        assertEquals(DhCulling.OUT_OF_VIEW, DhCulling.mainView(new DhFrameCapture.Draw(b, -4, 0, 0), unmoved, 0, 0, 0, 0f, box));
        assertEquals(DhCulling.DRAWN, DhCulling.mainView(new DhFrameCapture.Draw(b, 0, 0, 0), unmoved, 0, 0, 0, 0f, box));
    }

    private static ShadowMath.Cascade noon() {
        return ShadowMath.cascade(SunMath.sunDirection(0f), 0, 70, 0, ShadowMath.NEAR_RADIUS);
    }

    /** A cascade culls the casters outside its box by their buffers' own bounds, and leaves the vanilla casters' square to vanilla. */
    @Test
    void theCascadeCullsCastersOutsideItsBox() {
        ShadowMath.Cascade near = noon();
        DhApiVertexBuffer column = this.buffer(new int[]{0, 0, 0}, new int[]{64, 160, 64});
        assertTrue(DhShadowCasters.casts(new DhFrameCapture.Draw(column, 96, -64, 0), near, 64));
        assertFalse(DhShadowCasters.casts(new DhFrameCapture.Draw(column, 4096, -64, 0), near, 64), "past the near box along x");
        assertFalse(DhShadowCasters.casts(new DhFrameCapture.Draw(column, 0, -64, -4096), near, 64), "past the near box along z");
        assertFalse(DhShadowCasters.casts(new DhFrameCapture.Draw(column, -32, -64, -32), near, 128), "inside the cutoff square");
        assertTrue(DhShadowCasters.casts(new DhFrameCapture.Draw(column, -32, -64, -32), near, 0), "no cutoff without vanilla casters");
        //A buffer whose vertices stay inside the square although its section reaches out: only its own box counts
        DhApiVertexBuffer low = this.buffer(new int[]{0, 0, 0}, new int[]{40, 10, 40});
        assertFalse(DhShadowCasters.casts(new DhFrameCapture.Draw(low, 0, 60, 0), near, 64));
        //Out of the camera's view still casts (a mountain behind the camera)
        assertTrue(DhShadowCasters.casts(new DhFrameCapture.Draw(column, 96, -64, 0, false), near, 64));
        //Bounds unknown: drawn (the shader still discards inside the cutoff)
        assertTrue(DhShadowCasters.casts(new DhFrameCapture.Draw(null, -32, -64, -32), near, 64));
    }

    /** The caster's height is its vertices', not the level's: a low island far below the box is culled. */
    @Test
    void theCastersHeightIsItsOwn() {
        ShadowMath.Cascade dusk = ShadowMath.cascade(SunMath.sunDirection(0.24f), 0, 70, 0, ShadowMath.CLOSE_RADIUS);
        DhApiVertexBuffer flat = this.buffer(new int[]{0, 0, 0}, new int[]{64, 2, 64});
        //Towards the sun and within the depth margin: casts
        assertTrue(DhShadowCasters.casts(new DhFrameCapture.Draw(flat, -464, 69, -16), dusk, 0));
        //Far below the cascade's box: culled by the buffer's own height
        assertFalse(DhShadowCasters.casts(new DhFrameCapture.Draw(flat, -464, -3000, -16), dusk, 0));
    }

    /**
     * DH's GL renderer draws its terrain with face culling on (GL's default: back faces, counter-clockwise front). The opaque LOD
     * pass culls back faces the same way (the LOD target is drawn with a positive viewport, so GL's front face is clockwise there);
     * the transparent pass (water, seen from below too) and the depth-only casters keep both faces, as before.
     */
    @Test
    void theOpaquePassCullsBackFacesAsDhDoes() {
        assertTrue(DhExtension.STATE.cullBackFaces());
        assertFalse(DhExtension.TRANSLUCENT_STATE.cullBackFaces());
        //2026-10-21 (performance): the casters cull back faces too (DhShadowWiringTest)
        assertTrue(DhExtension.SHADOW_STATE.cullBackFaces());
        assertTrue(DhExtension.STATE.light(), "the light attachment is still written");
    }

    /** Containers with more than one buffer: the capture keeps them in a row, so the main view can count containers by their corners. */
    @Test
    void containersAreCountedByTheirCorners() {
        DhApiVertexBuffer a = this.buffer(new int[]{0, 0, 0}), b = this.buffer(new int[]{0, 0, 0}), c = this.buffer(new int[]{0, 0, 0});
        List<DhFrameCapture.Draw> draws = List.of(new DhFrameCapture.Draw(a, 0, -64, 0), new DhFrameCapture.Draw(b, 0, -64, 0),
                new DhFrameCapture.Draw(c, 64, -64, 0));
        assertTrue(DhCulling.sameContainer(draws.get(0), draws.get(1)));
        assertFalse(DhCulling.sameContainer(draws.get(1), draws.get(2)));
        assertFalse(DhCulling.sameContainer(null, draws.get(0)));
    }
}
