package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiTerrainBufferContainer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiTerrainContainerUniformBufferWrapper;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiVertexBufferWrapper;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.util.IDhApiReadOnlyList;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DH calls its terrain renderer twice a frame (opaque, then transparent) from its hook, before the visuals frame records the
 * staged copies; the capture keeps what it was given for that frame's serial, and the draws are recorded later.
 */
class DhFrameCaptureTest {
    /** Shaped like DH's LodBufferContainer: a public final corner field next to the API methods. */
    public static final class FakeContainer implements IDhApiTerrainBufferContainer {
        public final DhInternalsTest.FakePos minCornerBlockPos;
        private final IDhApiVertexBufferWrapper[] opaque, transparent;
        private final DhApiContainerUniform uniform = new DhApiContainerUniform();

        FakeContainer(int x, int y, int z, IDhApiVertexBufferWrapper[] opaque, IDhApiVertexBufferWrapper[] transparent) {
            this.minCornerBlockPos = new DhInternalsTest.FakePos(x, y, z);
            this.opaque = opaque;
            this.transparent = transparent;
        }

        @Override
        public IDhApiVertexBufferWrapper[] getVboOpaqueWrappers() {
            return this.opaque;
        }

        @Override
        public IDhApiVertexBufferWrapper[] getVboTransparentWrappers() {
            return this.transparent;
        }

        @Override
        public IDhApiTerrainContainerUniformBufferWrapper getUniformContainer() {
            return this.uniform;
        }
    }

    /** A container that is not DH's: no corner field. */
    static final class NoCornerContainer implements IDhApiTerrainBufferContainer {
        private final IDhApiVertexBufferWrapper[] opaque;
        private final DhApiContainerUniform uniform = new DhApiContainerUniform();

        NoCornerContainer(IDhApiVertexBufferWrapper[] opaque) {
            this.opaque = opaque;
        }

        @Override
        public IDhApiVertexBufferWrapper[] getVboOpaqueWrappers() {
            return this.opaque;
        }

        @Override
        public IDhApiVertexBufferWrapper[] getVboTransparentWrappers() {
            return new IDhApiVertexBufferWrapper[0];
        }

        @Override
        public IDhApiTerrainContainerUniformBufferWrapper getUniformContainer() {
            return this.uniform;
        }
    }

    record ListOf<T>(List<T> items) implements IDhApiReadOnlyList<T> {
        @Override
        public T get(int index) {
            return this.items.get(index);
        }

        @Override
        public int size() {
            return this.items.size();
        }
    }

    private final FakeGpu gpu = new FakeGpu();
    private final List<DhVertexBuffer> made = new ArrayList<>();
    private final DhFrameCapture capture = new DhFrameCapture();

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

    /** A frame's parameters as DH fills them: MC's projection, DH's projection with its own clip planes, the model view. */
    private static DhApiRenderParam param(float near, float far) {
        DhApiRenderParam p = new DhApiRenderParam();
        p.mcProjectionMatrix.setIdentity();
        p.mcModelViewMatrix.setIdentity();
        p.dhModelViewMatrix.setIdentity();
        p.dhModelViewMatrix.m03 = 4;//row-major: x translation
        p.dhProjectionMatrix.setIdentity();
        p.dhProjectionMatrix.m22 = (far + near) / (near - far);
        p.dhProjectionMatrix.m23 = 2 * far * near / (near - far);
        p.dhProjectionMatrix.m32 = -1;
        p.dhProjectionMatrix.m33 = 0;
        p.nearClipPlane = near;
        p.farClipPlane = far;
        p.worldYOffset = -64;
        return p;
    }

    @Test
    void theFrameIsKeptForItsSerialOnly() {
        DhApiVertexBuffer b = this.buffer();
        this.capture.store(5, param(99.1f, 9216f), true, DhTerrain.view(new ListOf<>(List.of(new FakeContainer(512, -64, -1024, new IDhApiVertexBufferWrapper[]{b}, null)))));
        assertNull(this.capture.take(6), "another serial");
        DhFrameCapture.Frame f = this.capture.take(5);
        assertNotNull(f);
        assertEquals(5, f.serial());
        assertEquals(99.1f, f.near());
        assertEquals(9216f, f.far());
        assertEquals(-64, f.worldYOffset());
        assertEquals(1, f.opaque().size());
        DhFrameCapture.Draw d = f.opaque().get(0);
        assertSame(b, d.buffer());
        assertEquals(512, d.originX());
        assertEquals(-64, d.originY());
        assertEquals(-1024, d.originZ());
        assertTrue(f.transparent().isEmpty());
    }

    @Test
    void aLaterSerialReplacesTheFrame() {
        this.capture.store(5, param(99.1f, 9216f), true, DhTerrain.view(new ListOf<>(List.of())));
        this.capture.store(7, param(50f, 9216f), true, DhTerrain.view(new ListOf<>(List.of())));
        assertNull(this.capture.take(5));
        assertEquals(50f, this.capture.take(7).near());
    }

    @Test
    void opaqueAndTransparentAreKeptApart() {
        DhApiVertexBuffer o1 = this.buffer(), o2 = this.buffer(), t1 = this.buffer();
        FakeContainer a = new FakeContainer(0, -64, 0, new IDhApiVertexBufferWrapper[]{o1, null}, new IDhApiVertexBufferWrapper[]{t1});
        FakeContainer c = new FakeContainer(64, -64, 0, new IDhApiVertexBufferWrapper[]{o2}, new IDhApiVertexBufferWrapper[0]);
        ListOf<IDhApiTerrainBufferContainer> both = new ListOf<>(List.of(a, c));
        this.capture.store(9, param(99.1f, 9216f), true, DhTerrain.view(both));
        this.capture.store(9, param(99.1f, 9216f), false, DhTerrain.view(both));
        DhFrameCapture.Frame f = this.capture.take(9);
        assertEquals(List.of(o1, o2), f.opaque().stream().map(DhFrameCapture.Draw::buffer).toList(), "in DH's order, nulls skipped");
        assertEquals(64, f.opaque().get(1).originX());
        assertEquals(List.of(t1), f.transparent().stream().map(DhFrameCapture.Draw::buffer).toList());
    }

    /** The spike saw NaN in the first rows of MC's projection on the first frame: that frame draws no LOD. */
    @Test
    void aNanProjectionDropsTheFrame() {
        DhApiRenderParam p = param(99.1f, 9216f);
        p.mcProjectionMatrix.m00 = Float.NaN;
        this.capture.store(11, p, true, DhTerrain.view(new ListOf<>(List.of(new FakeContainer(0, 0, 0, new IDhApiVertexBufferWrapper[]{this.buffer()}, null)))));
        assertNull(this.capture.take(11));

        DhApiRenderParam q = param(99.1f, 9216f);
        q.dhProjectionMatrix.m11 = Float.NaN;
        this.capture.store(12, q, true, DhTerrain.view(new ListOf<>(List.of())));
        assertNull(this.capture.take(12));
    }

    /** A NaN in the transparent call drops the whole frame, not just its second half. */
    @Test
    void aNanInTheSecondCallDropsTheFrameToo() {
        this.capture.store(13, param(99.1f, 9216f), true, DhTerrain.view(new ListOf<>(List.of())));
        DhApiRenderParam p = param(99.1f, 9216f);
        p.mcProjectionMatrix.m10 = Float.NaN;
        this.capture.store(13, p, false, DhTerrain.view(new ListOf<>(List.of())));
        assertNull(this.capture.take(13));
    }

    /** The matrices are JOML's, the projection already remapped to Vulkan's 0..1 depth. */
    @Test
    void theMatricesAreConvertedOnce() {
        this.capture.store(3, param(10f, 1000f), true, DhTerrain.view(new ListOf<>(List.of())));
        DhFrameCapture.Frame f = this.capture.take(3);
        Vector4f near = f.dhProjectionVk().transform(new Vector4f(0, 0, -10f, 1));
        Vector4f far = f.dhProjectionVk().transform(new Vector4f(0, 0, -1000f, 1));
        assertEquals(0f, near.z / near.w, 1e-5f);
        assertEquals(1f, far.z / far.w, 1e-5f);
        assertEquals(new Matrix4f().translation(4, 0, 0), f.dhModelView());
    }

    /** Taken once: a second take of the same serial is null (the frame is drawn once). */
    @Test
    void takeHandsTheFrameOutOnce() {
        this.capture.store(4, param(99.1f, 9216f), true, DhTerrain.view(new ListOf<>(List.of())));
        assertNotNull(this.capture.take(4));
        assertNull(this.capture.take(4));
    }

    /** DH core never calls tryUpload (its GL renderer does): the capture reads the corner itself, and skips what it cannot. */
    @Test
    void theCaptureReadsTheCornerAndSkipsContainersWithout() {
        DhApiVertexBuffer b = this.buffer(), c = this.buffer();
        FakeContainer good = new FakeContainer(32, -64, 48, new IDhApiVertexBufferWrapper[]{b}, null);
        this.capture.store(20, param(99.1f, 9216f), true, DhTerrain.view(new ListOf<>(List.of(good))));
        assertTrue(((DhContainerUniform) good.getUniformContainer()).valid());
        assertEquals(1, this.capture.take(20).opaque().size());

        try (LogCapture log = LogCapture.visuals()) {
            this.capture.store(21, param(99.1f, 9216f), true, DhTerrain.view(new ListOf<>(List.of(new NoCornerContainer(new IDhApiVertexBufferWrapper[]{c})))));
            assertTrue(this.capture.take(21).opaque().isEmpty());
            assertTrue(DhRegistration.disabled(), "the first real failure turns DH drawing off");
            assertEquals(1, log.warnings().size());
        }
    }
}
