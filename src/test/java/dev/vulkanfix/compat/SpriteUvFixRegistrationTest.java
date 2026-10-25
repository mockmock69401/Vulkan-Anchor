package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged's SpriteCoordinateExpanderM fast path calls sprite.getU(u) with 0..1 UVs, but 1.20.1's getU expects
 * 0..16, so block-entity models (chests, beds, signs...) sample a 1/16 corner of their sprite. vulkanfix turns
 * that fast path off by overriding canUseFastVertex() at a higher mixin priority than Reforged's (1000).
 */
class SpriteUvFixRegistrationTest {
    private static final String MIXIN = "reforged.SpriteCoordinateExpanderMixin";
    private static final int REFORGED_PRIORITY = 1000;

    @Test
    void registeredAsClientMixin() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"" + MIXIN + "\""), "missing from vulkanfix.mixins.json: " + MIXIN);
        }
    }

    @Test
    void appliesWheneverVulkanModIsLoaded() {
        assertTrue(CompatGate.shouldApply(MIXIN, Set.of("vulkanmod")::contains));
        assertFalse(CompatGate.shouldApply(MIXIN, Set.<String>of()::contains));
    }

    @Test
    void outranksReforgedAndDisablesFastVertex() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/reforged/SpriteCoordinateExpanderMixin.class")) {
            assertNotNull(in, "mixin class not compiled");
            new ClassReader(in).accept(node, 0);
        }
        AnnotationNode mixin = node.invisibleAnnotations.stream()
                .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
        int priority = (Integer) mixin.values.get(mixin.values.indexOf("priority") + 1);
        assertTrue(priority > REFORGED_PRIORITY, "must outrank Reforged's merged canUseFastVertex()");

        MethodNode m = node.methods.stream().filter(x -> x.name.equals("canUseFastVertex") && x.desc.equals("()Z"))
                .findFirst().orElseThrow();
        assertTrue((m.access & Opcodes.ACC_PUBLIC) != 0);
        assertEquals(Opcodes.ICONST_0, firstRealOpcode(m), "canUseFastVertex() must return false");
    }

    private static int firstRealOpcode(MethodNode m) {
        for (var insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) {
                return insn.getOpcode();
            }
        }
        return -1;
    }
}
