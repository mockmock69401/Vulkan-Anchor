package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged's LiquidRenderer calls sprite.getU/getV with 0..1 values (1.20.5+ API), but 1.20.1's
 * TextureAtlasSprite.getU(double)/getV(double) take 0..16, so water and lava sampled a 1/16 corner of their sprite.
 */
class LiquidUvFixTest {
    private static final String MIXIN = "reforged.LiquidRendererMixin";

    @Test
    void unitUvMapsToLegacySixteenthRange() {
        assertEquals(0.0, LegacySpriteUv.toLegacy(0.0));
        assertEquals(8.0, LegacySpriteUv.toLegacy(0.5));
        assertEquals(16.0, LegacySpriteUv.toLegacy(1.0));
        // vanilla flowing water: getU(8.0F + (-f19 - f18) * 16.0F) == toLegacy(0.5F + (-f19 - f18))
        double flow = -0.25 * Math.sin(0.7) - 0.25 * Math.cos(0.7);
        assertEquals(8.0 + flow * 16.0, LegacySpriteUv.toLegacy(0.5 + flow), 1e-9);
    }

    @Test
    void registeredAndGatedOnVulkanMod() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"" + MIXIN + "\""));
        }
        assertTrue(CompatGate.shouldApply(MIXIN, Set.of("vulkanmod")::contains));
        assertFalse(CompatGate.shouldApply(MIXIN, Set.<String>of()::contains));
    }

    @Test
    void redirectsBothSrgSpriteUvCallsInReforgedLiquidRenderer() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/reforged/LiquidRendererMixin.class")) {
            assertNotNull(in, "mixin class not compiled");
            new ClassReader(in).accept(node, 0);
        }
        AnnotationNode mixin = annotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertEquals(List.of("net.vulkanmod.render.chunk.build.LiquidRenderer"), value(mixin, "targets"));

        Set<String> redirected = node.methods.stream()
                .map(m -> annotation(m.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Redirect;"))
                .filter(a -> a != null)
                .map(a -> (String) value((AnnotationNode) value(a, "at"), "target"))
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;m_118367_(D)F",
                "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;m_118393_(D)F"), redirected);
    }

    private static AnnotationNode annotation(List<AnnotationNode> list, String desc) {
        return list == null ? null : list.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    private static Object value(AnnotationNode a, String key) {
        return a.values.get(a.values.indexOf(key) + 1);
    }
}
