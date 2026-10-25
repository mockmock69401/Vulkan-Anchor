package net.vulkanmod.visuals.lod;

import net.vulkanmod.visuals.render.VkPostProcess;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.*;

class LodTargetTest {
    private static final String BEGIN_CLEAR = "(Lorg/lwjgl/vulkan/VkCommandBuffer;)V";
    private static final String BEGIN_FLAG = "(Lorg/lwjgl/vulkan/VkCommandBuffer;Z)V";

    /** GL's memory layout (row 0 = bottom); Reforged flips its own viewport, flipping here too would turn LODs upside down. */
    @Test
    void viewportIsNotFlipped() {
        assertArrayEquals(new float[]{0, 0, 1920, 1080}, LodTarget.viewport(1920, 1080));
    }

    /** The frame starts with a clear pass; the translucents after the snapshot draw on what it left (load). */
    @Test
    void clearPassDiscardsAndLoadPassKeeps() {
        assertEquals(VK_ATTACHMENT_LOAD_OP_CLEAR, LodTarget.loadOp(true));
        assertEquals(VK_IMAGE_LAYOUT_UNDEFINED, LodTarget.initialLayout(true, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL));
        assertEquals(VK_ATTACHMENT_LOAD_OP_LOAD, LodTarget.loadOp(false));
        assertEquals(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                LodTarget.initialLayout(false, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL));
    }

    /** R2: the light attachment is the format the post-process's stand-in and its LOD light bindings expect. */
    @Test
    void lightAttachmentUsesThePostProcessLightFormat() {
        assertEquals(VkPostProcess.LOD_LIGHT_FORMAT, LodTarget.LIGHT_FORMAT);
        assertEquals(VK_FORMAT_R16_UINT, LodTarget.LIGHT_FORMAT);
        assertEquals(VK_FORMAT_R8G8B8A8_UNORM, LodTarget.COLOUR_FORMAT);
    }

    /** The LodInputs contract is Reforged's depth format. */
    @Test
    void depthFormatIsReforgedsDefault() throws Exception {
        List<String> init = new ArrayList<>();
        for (AbstractInsnNode insn : method("<init>", null).instructions) {
            if (insn instanceof MethodInsnNode call) init.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
        }
        assertTrue(init.contains("VkContext.depthFormat"), init.toString());
        assertTrue(fieldsRead("ensureSize").contains("depthFormat"));
    }

    /** Frames in flight may still sample the old images; a resize retires them instead of freeing. */
    @Test
    void resizeRetiresInsteadOfFreeing() throws Exception {
        List<String> calls = callsOf("ensureSize", null);
        assertTrue(calls.contains("retire"), calls.toString());
        assertFalse(calls.contains("free"), calls.toString());
        assertFalse(calls.contains("vkDestroyFramebuffer"), calls.toString());
    }

    /** beginPass marks the pass open, endPass and endOpenPass close it; endOpenPass ends only an open pass. */
    @Test
    void tracksWhetherAPassIsOpen() throws Exception {
        for (MethodNode m : List.of(method("beginPass", BEGIN_FLAG), method("endPass", null))) {
            boolean writes = false;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof FieldInsnNode f && f.name.equals("open") && f.getOpcode() == Opcodes.PUTFIELD) writes = true;
            }
            assertTrue(writes, m.name + " does not write open");
        }
        List<String> end = new ArrayList<>();
        for (AbstractInsnNode insn : method("endOpenPass", null).instructions) {
            if (insn instanceof FieldInsnNode f && f.name.equals("open")) end.add("open");
            if (insn instanceof MethodInsnNode call) end.add(call.name);
        }
        assertTrue(end.indexOf("open") >= 0 && end.indexOf("open") < end.indexOf("endPass"), end.toString());
    }

    /** The one-argument beginPass is the clearing one: it calls the flagged one with true. */
    @Test
    void beginPassClearsByDefault() throws Exception {
        AbstractInsnNode prev = null;
        boolean checked = false;
        for (AbstractInsnNode insn : method("beginPass", BEGIN_CLEAR).instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("beginPass")) {
                assertEquals(Opcodes.ICONST_1, prev.getOpcode());
                checked = true;
            }
            if (insn.getOpcode() >= 0) prev = insn;
        }
        assertTrue(checked);
    }

    /** Every image follows the target's size and lifetime, the light attachment and the band copies included. */
    @Test
    void everyImageFollowsTheTargetSize() throws Exception {
        for (String method : List.of("ensureSize", "tick", "free")) {
            List<String> read = fieldsRead(method);
            assertTrue(read.containsAll(List.of("colour", "depth", "bandColour", "bandDepth", "light")), method + " " + read);
        }
        assertEquals(1, callsOf("ensureSize", null).stream().filter("createFramebuffer"::equals).count(), "one framebuffer: colour, depth, light");
    }

    /** The depth is copied and made readable first, so the lighting's water detection has it with the band off; the colour only for the band. */
    @Test
    void theSnapshotCopiesTheDepthFirstAndTheColourForTheBand() throws Exception {
        List<String> calls = callsOf("snapshotBeforeTranslucents", null);
        assertEquals(2, calls.stream().filter("copy"::equals).count(), calls.toString());
        assertTrue(callsOf("copy", null).contains("vkCmdCopyImage"));
        assertEquals(2, calls.stream().filter("transitionFromUndefined"::equals).count(), calls.toString());
        int first = calls.indexOf("copy"), second = calls.lastIndexOf("copy");
        assertTrue(calls.subList(first, second).contains("transition"), "the depth copy is readable before the colour copy: " + calls);
        assertTrue(calls.subList(second, calls.size()).contains("transition"), "the colour copy too: " + calls);
        //After the colour copy: the band colour to shader read and the colour back to its attachment layout (the depth went back before)
        assertTrue(calls.subList(second, calls.size()).stream().filter("transition"::equals).count() == 2, calls.toString());
    }

    /** The band flag gates exactly the colour copy: one branch in the method. */
    @Test
    void theBandFlagGatesTheColourCopy() throws Exception {
        int branches = 0;
        for (AbstractInsnNode insn : method("snapshotBeforeTranslucents", null).instructions) {
            if (insn.getOpcode() == Opcodes.IFNE || insn.getOpcode() == Opcodes.IFEQ) branches++;
        }
        assertEquals(1, branches);
    }

    /** The lighting's compute and a fragment consumer read the depth snapshot. */
    @Test
    void theSnapshotIsReadByFragmentAndCompute() {
        assertEquals(VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, LodTarget.SNAPSHOT_READERS);
    }

    /** The composite samples colour and depth, the post-process the light: all three go to shader read. */
    @Test
    void toShaderReadCoversColourDepthAndLight() throws Exception {
        List<String> read = fieldsRead("toShaderRead");
        assertTrue(read.containsAll(List.of("colour", "depth", "light")), read.toString());
    }

    /** The older target's inputs of the boundary band, the SSAO and the bounds pass are not carried over. */
    @Test
    void theBandAndSsaoInputsAreGone() throws Exception {
        ClassNode node = node();
        List<String> fields = new ArrayList<>();
        node.fields.forEach(f -> fields.add(f.name));
        List<String> methods = new ArrayList<>();
        node.methods.forEach(m -> methods.add(m.name));
        for (String gone : List.of("ssao", "bounds", "vanillaDistance", "vanillaFade")) {
            assertFalse(fields.contains(gone), gone);
        }
        for (String gone : List.of("beginBounds", "endBounds", "beginTranslucent", "prepareSsao", "prepareVanillaDistance", "depthToShaderRead")) {
            assertFalse(methods.contains(gone), gone);
        }
    }

    private static ClassNode node() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = LodTargetTest.class.getClassLoader().getResourceAsStream("net/vulkanmod/visuals/lod/LodTarget.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    private static MethodNode method(String name, String desc) throws Exception {
        for (MethodNode m : node().methods) if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
        throw new AssertionError(name);
    }

    private static List<String> fieldsRead(String name) throws Exception {
        List<String> fields = new ArrayList<>();
        for (AbstractInsnNode insn : method(name, null).instructions) {
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD) fields.add(f.name);
        }
        return fields;
    }

    private static List<String> callsOf(String name, String desc) throws Exception {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method(name, desc).instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.name);
        }
        return calls;
    }
}
