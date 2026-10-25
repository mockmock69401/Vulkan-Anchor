package net.vulkanmod.visuals.dh;

import net.vulkanmod.visuals.Bytecode;
import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.PerRenderPassCache;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D24_UNORM_S8_UINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

/**
 * Stage 3: DH's opaque LODs cast into the sun's cascades. The casters are drawn in the visuals' shadow pass (VkShadowMaps begins
 * and ends it), from the frame prepare took, and only after recordOffscreen flushed DH's staged copies in the same frame (ruling
 * R15); the shadow upload stream is closed by then (visuals R12), so the hook neither uploads nor places a barrier.
 */
class DhShadowWiringTest {
    private static final String EXTENSION = "net/vulkanmod/visuals/dh/DhExtension";
    private static final String RENDERER = "net/vulkanmod/visuals/render/VisualsRenderer";

    /** The calls of method and of every DhExtension method it reaches (its own helpers), as Bytecode.calls names them. */
    private static List<String> reachable(String method) throws Exception {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(method));
        while (!todo.isEmpty()) {
            String m = todo.pop();
            if (!seen.add(m)) continue;
            for (String c : Bytecode.calls(EXTENSION, m)) {
                out.add(c);
                if (c.startsWith("DhExtension.")) todo.push(c.substring("DhExtension.".length()));
            }
        }
        return out;
    }

    @Test
    void theCastersOnlyDrawInsideThePassTheyAreGiven() throws Exception {
        List<String> calls = reachable("drawShadowCasters");
        assertTrue(calls.contains("VK10.vkCmdDrawIndexed"), calls.toString());
        assertTrue(calls.contains("VkGraphicsProgram.bind"), "the depth-only program is bound in the cascade's pass: " + calls);
        for (String c : calls) {
            String method = c.substring(c.indexOf('.') + 1);
            assertFalse(method.equals("vkCmdPipelineBarrier") || c.startsWith("VkBarriers.") || method.equals("transition"), "a barrier: " + c);
            assertFalse(method.equals("vkCmdBeginRenderPass") || method.equals("vkCmdEndRenderPass") || c.startsWith("LodTarget."), "a pass: " + c);
            assertFalse(c.startsWith("VkUploadStream.") || c.startsWith("DhStaging.") || method.startsWith("vkCmdCopy")
                    || method.equals("vkCmdUpdateBuffer") || c.equals("QuadIndexBuffer.ensure"), "an upload: " + c);
            assertFalse(c.equals("DhFrameCapture.take"), "the frame is taken once, by prepare: " + c);
        }
    }

    /** Ruling R15: recordOffscreen notes the serial it flushed right after the flush; the casters draw only for that serial. */
    @Test
    void theCastersWaitForThisFramesFlush() throws Exception {
        MethodNode record = Bytecode.method(EXTENSION, "recordOffscreen");
        boolean flushed = false, noted = false;
        for (AbstractInsnNode insn : record.instructions) {
            if (insn instanceof MethodInsnNode c && Bytecode.name(c).equals("DhStaging.flush")) flushed = true;
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("flushedSerial")) {
                assertTrue(flushed, "the serial is noted after the flush");
                noted = true;
            }
        }
        assertTrue(noted, "recordOffscreen notes the flushed serial");
        Set<String> read = new HashSet<>();
        for (AbstractInsnNode insn : Bytecode.method(EXTENSION, "drawShadowCasters").instructions) {
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD) read.add(f.name);
        }
        assertTrue(read.contains("flushedSerial") && read.contains("casterFrame"), read.toString());
    }

    /** prepare keeps the frame it took for the shadow pass of the same frame; nothing else takes one. */
    @Test
    void prepareKeepsItsFrameForTheCasters() throws Exception {
        boolean kept = false;
        for (AbstractInsnNode insn : Bytecode.method(EXTENSION, "prepare").instructions) {
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("casterFrame")) kept = true;
        }
        assertTrue(kept);
        for (MethodNode m : Bytecode.read(EXTENSION).methods) {
            if (m.name.equals("prepare")) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) assertFalse(Bytecode.name(c).equals("DhFrameCapture.take"), m.name + " takes a frame");
            }
        }
    }

    /**
     * The visuals frame's order the casters rely on: the extensions' prepare (step 2) before the cascades' prepare (step 3), and
     * the extensions' recordOffscreen (step 6, where DH's copies are flushed) before the cascades are drawn (step 7).
     */
    @Test
    void theVisualsFrameRecordsTheOffscreenWorkBeforeTheCascades() throws Exception {
        List<String> frame = Bytecode.calls(RENDERER, "frame");
        assertTrue(Bytecode.inOrder(frame, List.of("VisualsExtension.prepare", "VisualsRenderer.prepareShadows", "VkUploadStream.commit",
                "VisualsExtension.recordOffscreen", "VisualsRenderer.drawShadows")), frame.toString());
        int offscreen = frame.lastIndexOf("VisualsExtension.recordOffscreen");
        int shadows = frame.indexOf("VisualsRenderer.drawShadows");
        assertTrue(offscreen < shadows, frame.toString());
    }

    /**
     * The main view draws only what can show (DhCulling.mainView: DH's camera frustum, the buffer's box against the view, the near
     * discard); the shadow pass draws the rest too, culled per cascade. Task 5b: the culling's matrix is the one the draws are
     * recorded with, made once per pass from the frame drawAll is given (the frame taken for this serial).
     */
    @Test
    void theMainViewSkipsDrawsThatCannotShow() throws Exception {
        List<String> calls = Bytecode.calls(EXTENSION, "drawAll");
        assertTrue(calls.contains("DhCulling.mainView"), calls.toString());
        assertEquals(1, Bytecode.count(calls, "DhCulling.viewProjection"), calls.toString());
        assertTrue(Bytecode.inOrder(calls, List.of("DhCulling.viewProjection", "LooseUniformWriter.putMat4", "DhCulling.mainView",
                "VK10.vkCmdDrawIndexed")), calls.toString());
        assertTrue(Bytecode.calls("net/vulkanmod/visuals/dh/DhCulling", "mainView").contains("DhFrameCapture$Draw.inView"),
                "DH's own answer still counts");
        assertTrue(reachable("drawShadowCasters").contains("DhShadowCasters.casts"));
        //Performance (2026-10-21): only this frame's slice of a spread refresh, before the box tests
        assertTrue(Bytecode.inOrder(Bytecode.calls(EXTENSION, "drawCasters"), List.of("ShadowCascadeContext.slice", "ShadowCascadeContext.slices",
                "DhShadowCasters.inSlice", "DhShadowCasters.casts")));
        //One local carries the matrix to both the push constants and the culling
        MethodNode m = Bytecode.method(EXTENSION, "drawAll");
        int stored = -1;
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode c && Bytecode.name(c).equals("DhCulling.viewProjection")
                    && c.getNext() instanceof org.objectweb.asm.tree.VarInsnNode v && v.getOpcode() == Opcodes.ASTORE) {
                stored = v.var;
            }
        }
        assertTrue(stored >= 0, "the view projection is kept in a local");
        int loads = 0;
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof org.objectweb.asm.tree.VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == stored) loads++;
        }
        assertTrue(loads >= 2, "read for the push constants and for the culling: " + loads);
    }

    /**
     * Ruling R26b: a cascade redrawn on a frame DH gave no frame for draws the casters of the latest frame DH gave, within
     * CASTER_REUSE_FRAMES (its buffers are read when drawn: a closed one has no handle and is skipped), never before this frame's
     * flush, and nothing past the window (DH's rendering turned off: no shadows of LODs no longer drawn).
     */
    @Test
    void aCascadeWithoutThisFramesDhFrameDrawsTheLatestOne() {
        DhFrameCapture.Frame f = new DhFrameCapture.Frame(100, new org.joml.Matrix4f(), new org.joml.Matrix4f(), 99f, 9216f, -64, List.of(), List.of());
        assertSame(f, DhExtension.casterSource(f, 100, 100), "this frame's");
        assertSame(f, DhExtension.casterSource(f, 101, 101), "the last frame's, on a frame without one");
        assertSame(f, DhExtension.casterSource(f, 100 + DhExtension.CASTER_REUSE_FRAMES, 100 + DhExtension.CASTER_REUSE_FRAMES));
        assertNull(DhExtension.casterSource(f, 101 + DhExtension.CASTER_REUSE_FRAMES, 101 + DhExtension.CASTER_REUSE_FRAMES), "too old");
        assertNull(DhExtension.casterSource(f, 101, 100), "this frame's copies not flushed yet (R15)");
        assertNull(DhExtension.casterSource(f, 99, 99), "a frame from after the cascade's");
        assertNull(DhExtension.casterSource(null, 101, 101));
    }

    /** The hook picks its frame through casterSource and counts the redraws without this frame's DH frame. */
    @Test
    void theHookPicksItsFrameThroughCasterSource() throws Exception {
        List<String> calls = Bytecode.calls(EXTENSION, "drawShadowCasters");
        assertTrue(calls.contains("DhExtension.casterSource"), calls.toString());
        Set<String> written = new HashSet<>();
        for (AbstractInsnNode insn : Bytecode.method(EXTENSION, "drawShadowCasters").instructions) {
            if (insn instanceof FieldInsnNode fi && fi.getOpcode() == Opcodes.PUTFIELD) written.add(fi.name);
        }
        assertTrue(written.contains("reusedCasterRedraws") && written.contains("emptyCasterRedraws"), written.toString());
    }

    /** Depth only, as every sun shadow draw: tested and written, LESS_OR_EQUAL, no colour attachment. */
    @Test
    void theCasterPipelineIsDepthOnly() {
        assertTrue(DhExtension.SHADOW_STATE.depthOnly());
        assertTrue(DhExtension.SHADOW_STATE.depthTest());
        assertTrue(DhExtension.SHADOW_STATE.depthWrite());
        assertEquals(VK_COMPARE_OP_LESS_OR_EQUAL, DhExtension.SHADOW_STATE.depthCompare());
        assertFalse(DhExtension.SHADOW_STATE.blend());
        //2026-10-21 (performance): back faces culled with the main view's front face (both passes have a positive viewport)
        assertTrue(DhExtension.SHADOW_STATE.cullBackFaces());
        assertEquals(DhExtension.STATE.cullBackFaces(), DhExtension.SHADOW_STATE.cullBackFaces());
    }

    /** The program pairs shadow.vert with its fragment stage, and the vertex stage's three uniforms are push constants. */
    @Test
    void theCasterProgramIsRegistered() {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(DhExtension.SHADOW_PROGRAM);
        assertEquals("dhShadow", p.name());
        assertEquals(2, p.stages().size());
        assertTrue(p.stages().get(0).id().endsWith("dh/shadow.vert"));
        assertTrue(p.stages().get(1).id().endsWith("dh/shadow.frag"));
        LooseUniformWriter w = new LooseUniformWriter(GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).meta());
        assertFalse(w.inUniformBuffer());
        for (int location : List.of(DhExtension.VIEW_PROJECTION, DhExtension.MODEL_OFFSET, DhExtension.PARAMS)) {
            assertTrue(w.has(location), "location " + location);
        }
    }

    /** The fragment stage discards inside the cutoff square (vanilla's casters) and writes no colour. */
    @Test
    void theFragmentStageDiscardsInsideTheCutoffSquare() {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(DhExtension.SHADOW_PROGRAM);
        String frag = p.source(p.stages().get(1), ShaderSource.classpath());
        assertTrue(frag.contains("max(abs(horizontal.x), abs(horizontal.y)) < cutoff"), frag);
        assertTrue(frag.contains("discard"));
        assertFalse(frag.contains("out vec4"), "depth only");
    }

    /** Activation holds DH's vanilla-graphics override off (ruling R22) and binds the fork's frustum, both before DH's first frame. */
    @Test
    void activationHoldsOffTheOverrideAndBindsTheFrustum() throws Exception {
        List<String> calls = Bytecode.calls("net/vulkanmod/visuals/dh/DhRegistration", "activateSteps");
        assertTrue(calls.contains("DhInternals.holdOffVanillaGraphicsOverride"), calls.toString());
        assertTrue(calls.contains("DhCasterFrustum.bind"), calls.toString());
    }

    /**
     * M7: VkShadowMaps makes a new render pass on every world join, render-distance change and dimension change. The depth-only
     * pipeline is found by the pass's compatibility (a single depth attachment of the device's depth format), so a rebuilt pass
     * reuses it instead of adding one per rebuild, and a recycled handle cannot pick a stale entry.
     */
    @Test
    void theShadowPipelineIsKeyedByThePassCompatibilityNotItsHandle() throws Exception {
        assertEquals(DhExtension.shadowPassKey(VK_FORMAT_D32_SFLOAT), DhExtension.shadowPassKey(VK_FORMAT_D32_SFLOAT));
        assertNotEquals(DhExtension.shadowPassKey(VK_FORMAT_D32_SFLOAT), DhExtension.shadowPassKey(VK_FORMAT_D24_UNORM_S8_UINT));
        assertNotEquals(PerRenderPassCache.compatibilityKey(VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT), DhExtension.shadowPassKey(VK_FORMAT_D32_SFLOAT),
                "a depth-only pass never shares a colour pass's key");
        int[] builds = {0};
        PerRenderPassCache cache = new PerRenderPassCache(pass -> ++builds[0]);
        long key = DhExtension.shadowPassKey(VK_FORMAT_D32_SFLOAT);
        assertEquals(cache.get(key, 0x1111L), cache.get(key, 0x2222L), "the rebuilt pass gets the pipeline built for the first");
        assertEquals(1, builds[0]);

        boolean keyed = false;
        for (AbstractInsnNode insn : Bytecode.method(EXTENSION, "drawCasters").instructions) {
            if (!(insn instanceof MethodInsnNode c) || !Bytecode.name(c).equals("VkGraphicsProgram.bind")) continue;
            assertEquals("(Lorg/lwjgl/vulkan/VkCommandBuffer;JJLnet/vulkanmod/visuals/vk/VkBindings;Lnet/vulkanmod/visuals/vk/LooseUniformWriter;)V", c.desc,
                    "the bind with a compatibility key");
            keyed = true;
        }
        assertTrue(keyed);
    }
}
