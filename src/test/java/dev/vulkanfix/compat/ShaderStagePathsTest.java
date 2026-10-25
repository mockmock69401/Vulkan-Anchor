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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged converts mod shaders through createLegacyShader(), which reads vsPath/fsName + ".vsh"/".fsh". Those
 * fields are meant to be filled by a @Redirect on ShaderInstance.getOrCreate that does not take effect on Forge
 * 47.4.23, so every mod shader failed with "minecraft:null.vsh" and fell back (e.g. exile_overlay:orb_fill: the
 * empty HUD orbs). vulkanfix fills them from the shader JSON before Reforged's own constructor hook runs.
 */
class ShaderStagePathsTest {
    private static final String MIXIN = "reforged.ShaderInstancePathsMixin";
    private static final String CTOR =
            "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V";

    @Test
    void namespacedStageMatchesReforgedLayout() {
        // Reforged: ResourceLocation.parse(name).withPath("shaders/core/%s".formatted(path)).toString()
        assertEquals("exile_overlay:shaders/core/orb_fill", ShaderStagePaths.forStage("exile_overlay:orb_fill"));
    }

    @Test
    void bareStageDefaultsToMinecraftNamespace() {
        assertEquals("minecraft:shaders/core/fancymenu_gui_alpha_texture", ShaderStagePaths.forStage("fancymenu_gui_alpha_texture"));
        assertEquals("minecraft:shaders/core/xaerolib/map", ShaderStagePaths.forStage("xaerolib/map"));
    }

    @Test
    void shaderJsonLocationFollowsVanilla() {
        assertEquals("exile_overlay:shaders/core/orb_fill.json", ShaderStagePaths.jsonFor("exile_overlay", "orb_fill"));
    }

    @Test
    void runsBeforeReforgedConstructorHookOnTheResourceLocationConstructor() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"" + MIXIN + "\""));
        }
        assertTrue(CompatGate.shouldApply(MIXIN, Set.of("vulkanmod")::contains));
        assertFalse(CompatGate.shouldApply(MIXIN, Set.<String>of()::contains));

        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/reforged/ShaderInstancePathsMixin.class")) {
            assertNotNull(in, "mixin class not compiled");
            new ClassReader(in).accept(node, 0);
        }
        AnnotationNode mixin = annotation(node.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertTrue((Integer) value(mixin, "priority") < 900, "must run before Reforged's ShaderInstanceM (900)");
        MethodNode handler = node.methods.stream()
                .filter(m -> annotation(m.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;") != null)
                .findFirst().orElseThrow();
        AnnotationNode inject = annotation(handler.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of(CTOR), value(inject, "method"));
    }

    private static AnnotationNode annotation(List<AnnotationNode> list, String desc) {
        return list == null ? null : list.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    private static Object value(AnnotationNode a, String key) {
        return a.values.get(a.values.indexOf(key) + 1);
    }
}
