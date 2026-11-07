package net.vulkanmod.visuals.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
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

import static org.junit.jupiter.api.Assertions.*;

/** Sable's sub-levels into the sun shadows: where the draws are kept. */
class SubLevelShadowWiringTest {
    private static final String CASTERS = "SubLevelShadowCasters.";

    static List<String> calls(String clazz, String method) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = SubLevelShadowWiringTest.class.getClassLoader().getResourceAsStream(clazz + ".class")) {
            assertNotNull(in, clazz);
            new ClassReader(in).accept(node, 0);
        }
        List<String> calls = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (!m.name.equals(method)) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) calls.add(c.owner.substring(c.owner.lastIndexOf('/') + 1) + "." + c.name);
            }
        }
        return calls;
    }

    @Test
    void theVboKeepsASubLevelsDrawWhileALayerRecords() throws Exception {
        List<String> draw = calls("net/vulkanmod/render/VBO", "draw");
        int asked = draw.indexOf(CASTERS + "recording"), kept = draw.indexOf(CASTERS + "record");
        assertTrue(asked >= 0 && asked < kept, draw.toString());
    }

    /** Review focus 1: only the opaque and cutout layers cast; translucent, tripwire and Aeronautics' levitite layers do not. */
    @Test
    void onlyTheOpaqueLayersOpenARecording() throws Exception {
        List<String> scope = calls("net/vulkanmod/mixin/compatibility/sable/VanillaSubLevelRenderDispatcherMixin", "vulkanmod$sectionLayerScope");
        assertTrue(scope.containsAll(List.of("RenderType.solid", "RenderType.cutoutMipped", "RenderType.cutout")), scope.toString());
        assertFalse(scope.contains("RenderType.tripwire"), scope.toString());
        int open = scope.indexOf(CASTERS + "beginLayer"), draw = scope.indexOf("Operation.call"), close = scope.lastIndexOf(CASTERS + "endLayer");
        assertTrue(open >= 0 && open < draw && draw < close, scope.toString());
    }

    /** Review focus 3: the map is drawn for sub-levels whatever the Entity Shadows setting says. */
    @Test
    void theMapIsDrawnForSubLevelsWithoutTheEntitySetting() throws Exception {
        List<String> post = calls("net/vulkanmod/visuals/render/VisualsRenderer", "postProcess");
        assertTrue(post.contains("VkEntityShadows.drawsAnything") && post.contains("VkEntityShadows.subLevelCasters"), post.toString());
        assertTrue(calls("net/vulkanmod/visuals/render/VkEntityShadows", "subLevelCasters").contains(CASTERS + "count"));
        ClassNode node = new ClassNode();
        try (InputStream in = SubLevelShadowWiringTest.class.getClassLoader().getResourceAsStream("net/vulkanmod/visuals/render/VisualsRenderer.class")) {
            new ClassReader(in).accept(node, 0);
        }
        MethodNode postProcess = node.methods.stream().filter(m -> m.name.equals("postProcess")).findFirst().orElseThrow();
        for (AbstractInsnNode insn : postProcess.instructions) {
            assertFalse(insn instanceof FieldInsnNode f && f.owner.endsWith("VisualsConfig") && f.name.equals("entityShadows"),
                    "postProcess does not read the Entity Shadows setting");
        }
    }

    /**
     * The map's draw reads this frame's atmosphere, which a failed capture leaves null: such a frame must not draw the map, or the
     * post-process of a frame with entities and no structure at all would be skipped by the exception.
     */
    @Test
    void theMapIsNotDrawnWithoutAnAtmosphere() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = SubLevelShadowWiringTest.class.getClassLoader().getResourceAsStream("net/vulkanmod/visuals/render/VisualsRenderer.class")) {
            new ClassReader(in).accept(node, 0);
        }
        MethodNode postProcess = node.methods.stream().filter(m -> m.name.equals("postProcess")).findFirst().orElseThrow();
        boolean guarded = false;
        for (AbstractInsnNode insn : postProcess.instructions) {
            if (insn instanceof MethodInsnNode c && c.owner.endsWith("VkEntityShadows") && c.name.equals("drawsAnything")) break;
            if (insn instanceof FieldInsnNode f && f.name.equals("atmosphere") && f.getNext() != null && f.getNext().getOpcode() == Opcodes.IFNULL) guarded = true;
        }
        assertTrue(guarded, "the map's cascade is decided only with an atmosphere");
    }

    @Test
    void theSubLevelsAreDrawnInsideTheMapsPass() throws Exception {
        List<String> draw = calls("net/vulkanmod/visuals/render/VkEntityShadows", "draw");
        int begin = draw.indexOf("VK10.vkCmdBeginRenderPass"), subLevels = draw.indexOf("VkSubLevelShadows.draw"), end = draw.indexOf("VK10.vkCmdEndRenderPass");
        assertTrue(begin >= 0 && begin < subLevels && subLevels < end, draw.toString());
    }

    @Test
    void theMapDrawsForEitherKindOfCaster() {
        assertTrue(VkEntityShadows.drawsAnything(true, 6, 0));
        assertTrue(VkEntityShadows.drawsAnything(true, 0, 3), "sub-levels alone");
        assertFalse(VkEntityShadows.drawsAnything(true, 0, 0));
        assertFalse(VkEntityShadows.drawsAnything(false, 6, 3), "no cascade this frame");
    }

    /** Sable's section buffers: Position at 0, UV0 at 16 in the 32-byte block vertex. */
    @Test
    void theVertexLayoutIsTheBlockFormat() {
        assertEquals(DefaultVertexFormat.BLOCK.getVertexSize(), VkSubLevelShadows.BLOCK.stride());
        assertEquals(DefaultVertexFormat.BLOCK.getOffset(VertexFormatElement.UV0), VkSubLevelShadows.BLOCK.offsets()[1]);
        assertEquals(DefaultVertexFormat.BLOCK.getOffset(VertexFormatElement.POSITION), VkSubLevelShadows.BLOCK.offsets()[0]);
    }

    /** Review focus 5: a frame without a post-process does not leave its draws to the next one. */
    @Test
    void everyLevelRenderStartsAnEmptyList() throws Exception {
        assertTrue(calls("net/vulkanmod/mixin/chunk/LevelRendererMixin", "prepareLevelRenderState").contains(CASTERS + "beginLevel"));
    }
}
