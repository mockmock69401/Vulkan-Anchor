package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiTerrainBufferContainer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiVertexBufferWrapper;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS_OR_EQUAL;

/**
 * DH's transparent LODs (water) are drawn after the opaque ones, in the order DH hands them over, blended over the opaque LOD in
 * a load pass of the LOD target. Before them the target keeps the opaque depth (snapshotBeforeTranslucents: the band depth, which
 * the lighting tells LOD water by). A transparent call for a frame the visuals frame already took is late and is dropped.
 */
class DhTranslucentOrderTest {
    private static final String EXTENSION = "net/vulkanmod/visuals/dh/DhExtension";

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

    private static DhApiRenderParam param() {
        DhApiRenderParam p = new DhApiRenderParam();
        p.mcProjectionMatrix.setIdentity();
        p.mcModelViewMatrix.setIdentity();
        p.dhModelViewMatrix.setIdentity();
        p.dhProjectionMatrix.setIdentity();
        p.dhProjectionMatrix.m22 = (9216f + 99.1f) / (99.1f - 9216f);
        p.dhProjectionMatrix.m23 = 2 * 9216f * 99.1f / (99.1f - 9216f);
        p.dhProjectionMatrix.m32 = -1;
        p.dhProjectionMatrix.m33 = 0;
        p.nearClipPlane = 99.1f;
        p.farClipPlane = 9216f;
        return p;
    }

    /** Container by container as DH listed them, each container's buffers in its array's order; nothing is sorted again. */
    @Test
    void theTranslucentsKeepDhsOrder() {
        DhApiVertexBuffer t1 = this.buffer(), t2 = this.buffer(), t3 = this.buffer(), t4 = this.buffer();
        List<IDhApiTerrainBufferContainer> containers = List.of(
                new DhFrameCaptureTest.FakeContainer(1024, -64, 0, new IDhApiVertexBufferWrapper[0], new IDhApiVertexBufferWrapper[]{t3, t1}),
                new DhFrameCaptureTest.FakeContainer(-512, -64, 0, new IDhApiVertexBufferWrapper[0], null),
                new DhFrameCaptureTest.FakeContainer(0, -64, 512, new IDhApiVertexBufferWrapper[0], new IDhApiVertexBufferWrapper[]{t4, null, t2}));
        DhFrameCaptureTest.ListOf<IDhApiTerrainBufferContainer> list = new DhFrameCaptureTest.ListOf<>(containers);
        this.capture.store(30, param(), true, DhTerrain.view(list));
        this.capture.store(30, param(), false, DhTerrain.view(list));
        DhFrameCapture.Frame f = this.capture.take(30);
        assertEquals(List.of(t3, t1, t4, t2), f.transparent().stream().map(DhFrameCapture.Draw::buffer).toList());
        assertEquals(List.of(1024L, 1024L, 0L, 0L), f.transparent().stream().map(DhFrameCapture.Draw::originX).toList());
        assertEquals(512, f.transparent().get(3).originZ());
    }

    /** The draw loop goes through the list as given: no sort anywhere on the drawing path. */
    @Test
    void theDrawingPathSortsNothing() throws Exception {
        List<String> calls = new ArrayList<>(Bytecode.calls(EXTENSION, "recordOffscreen"));
        calls.addAll(Bytecode.calls(EXTENSION, "drawAll"));
        calls.addAll(Bytecode.calls("net/vulkanmod/visuals/dh/DhFrameCapture", "take"));
        for (String c : calls) assertFalse(c.endsWith(".sort") || c.endsWith(".reverse") || c.endsWith(".sorted"), c);
    }

    /**
     * Ruling R15 and the band depth: flush, the opaque draws in the clear pass, the snapshot, the transparent draws in the load
     * pass (beginPass(cmd, false)), then the target goes readable.
     */
    @Test
    void theSnapshotComesBeforeTheTranslucents() throws Exception {
        List<String> calls = Bytecode.calls(EXTENSION, "recordOffscreen");
        assertTrue(Bytecode.inOrder(calls, List.of("DhStaging.flush", "LodTarget.beginPass", "DhExtension.drawAll", "LodTarget.endPass",
                "LodTarget.snapshotBeforeTranslucents", "LodTarget.beginPass", "DhExtension.drawAll", "LodTarget.endPass", "LodTarget.toShaderRead")), calls.toString());
        assertEquals(1, Bytecode.count(calls, "LodTarget.snapshotBeforeTranslucents"));
        assertEquals(2, Bytecode.count(calls, "DhExtension.drawAll"));
        int snapshot = calls.indexOf("LodTarget.snapshotBeforeTranslucents");
        assertEquals(1, calls.subList(0, snapshot).stream().filter("DhExtension.drawAll"::equals).count(), "only the opaque draws before the snapshot");

        //The second pass loads what the opaque pass left
        MethodNode m = Bytecode.method(EXTENSION, "recordOffscreen");
        List<MethodInsnNode> begins = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode c && Bytecode.name(c).equals("LodTarget.beginPass")) begins.add(c);
        }
        assertEquals(2, begins.size());
        assertEquals("(Lorg/lwjgl/vulkan/VkCommandBuffer;)V", begins.get(0).desc, "the opaque pass clears");
        assertEquals("(Lorg/lwjgl/vulkan/VkCommandBuffer;Z)V", begins.get(1).desc);
        AbstractInsnNode flag = Bytecode.previous(begins.get(1));
        assertNotNull(flag);
        assertEquals(Opcodes.ICONST_0, flag.getOpcode(), "the transparent pass loads");
    }

    /**
     * Every main-view draw sits in drawAll, which records them all inside a pass; nothing else calls it. Stage 3: the shadow
     * casters (drawCasters, inside the cascade's pass VkShadowMaps begins) are the only other draws.
     */
    @Test
    void drawAllIsTheOnlyDrawingAndOnlyRecordOffscreenCallsIt() throws Exception {
        assertTrue(Bytecode.calls(EXTENSION, "drawAll").contains("VK10.vkCmdDrawIndexed"));
        for (MethodNode m : Bytecode.read(EXTENSION).methods) {
            if (m.name.equals("recordOffscreen") || m.name.equals("drawAll") || m.name.equals("drawCasters")) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) {
                    assertFalse(Bytecode.name(c).equals("DhExtension.drawAll"), m.name + " draws");
                    assertFalse(Bytecode.name(c).startsWith("VK10.vkCmdDraw"), m.name + " draws");
                }
            }
        }
    }

    /** DH's GL state for the transparent pass: blended (the visuals' blend is DH's), depth tested and written, the light written. */
    @Test
    void theTranslucentPipelineBlendsAndWritesDepth() {
        assertTrue(DhExtension.TRANSLUCENT_STATE.blend());
        assertTrue(DhExtension.TRANSLUCENT_STATE.depthTest());
        assertTrue(DhExtension.TRANSLUCENT_STATE.depthWrite(), "LOD water writes the LOD depth, the band depth keeps the floor");
        assertEquals(VK_COMPARE_OP_LESS_OR_EQUAL, DhExtension.TRANSLUCENT_STATE.depthCompare());
        assertTrue(DhExtension.TRANSLUCENT_STATE.light());
        assertFalse(DhExtension.STATE.blend());
    }

    /** Ruling R21: a call for a serial already taken (Iris's deferred transparent pass) is dropped and counted. */
    @Test
    void aLateTransparentCallIsDroppedAndCounted() {
        DhApiVertexBuffer t = this.buffer();
        DhFrameCaptureTest.ListOf<IDhApiTerrainBufferContainer> list = new DhFrameCaptureTest.ListOf<>(List.of(
                new DhFrameCaptureTest.FakeContainer(0, -64, 0, new IDhApiVertexBufferWrapper[0], new IDhApiVertexBufferWrapper[]{t})));
        this.capture.store(40, param(), true, DhTerrain.view(list));
        DhFrameCapture.Frame f = this.capture.take(40);
        assertTrue(f.transparent().isEmpty());
        assertEquals(0, this.capture.lateCalls());

        this.capture.store(40, param(), false, DhTerrain.view(list));
        assertEquals(1, this.capture.lateCalls());
        assertNull(this.capture.take(40), "nothing is kept for a frame already drawn");

        //The next frame is unaffected
        this.capture.store(41, param(), true, DhTerrain.view(list));
        this.capture.store(41, param(), false, DhTerrain.view(list));
        DhFrameCapture.Frame g = this.capture.take(41);
        assertNotNull(g);
        assertEquals(1, g.transparent().size());
        assertEquals(1, this.capture.lateCalls());
    }
}
