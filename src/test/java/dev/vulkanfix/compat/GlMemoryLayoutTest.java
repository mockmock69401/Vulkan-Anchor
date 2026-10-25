package dev.vulkanfix.compat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
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
 * Reforged's _viewport always uses a negative-height (top-down) viewport, so a texture rendered through a GL
 * framebuffer comes out upside down compared with GL, while CPU-uploaded textures do not. Xaero mixes both kinds with
 * the same UVs: zoomed-out map tiles (rendered from leaf tiles) were flipped, and the minimap, which goes through two
 * framebuffers and samples a corner of the second, read the empty side. Xaero's framebuffers get GL's memory layout
 * (Vulkan's native, positive-height viewport) instead.
 */
class GlMemoryLayoutTest {
    @BeforeEach
    void reset() {
        GlMemoryLayoutFramebuffers.resetForTest();
    }

    @Test
    void onlyRegisteredFramebuffersUseGlMemoryLayout() {
        GlMemoryLayoutFramebuffers.register(21);
        assertTrue(GlMemoryLayoutFramebuffers.uses(21));
        assertFalse(GlMemoryLayoutFramebuffers.uses(22));
        assertFalse(GlMemoryLayoutFramebuffers.uses(0), "the window keeps Reforged's layout");
        GlMemoryLayoutFramebuffers.register(-1);
        assertFalse(GlMemoryLayoutFramebuffers.uses(-1));
    }

    @Test
    void xaeroFramebuffersRegisterWhenTheirBuffersAreCreated() throws Exception {
        for (String mixin : List.of("xaero.minimap.MinimapFramebufferLayoutMixin", "xaero.worldmap.WorldMapFramebufferLayoutMixin")) {
            assertRegistered(mixin);
            AnnotationNode inject = injects(mixin).get(0);
            assertEquals(List.of("m_83950_(IIZ)V"), value(inject, "method"));
            @SuppressWarnings("unchecked")
            List<AnnotationNode> at = (List<AnnotationNode>) value(inject, "at");
            assertEquals("RETURN", value(at.get(0), "value"));
        }
        assertTrue(CompatGate.shouldApply("xaero.minimap.MinimapFramebufferLayoutMixin", Set.of("vulkanmod", "xaerominimap")::contains));
        assertFalse(CompatGate.shouldApply("xaero.minimap.MinimapFramebufferLayoutMixin", Set.of("vulkanmod", "xaeroworldmap")::contains));
        assertTrue(CompatGate.shouldApply("xaero.worldmap.WorldMapFramebufferLayoutMixin", Set.of("vulkanmod", "xaeroworldmap")::contains));
        assertFalse(CompatGate.shouldApply("xaero.worldmap.WorldMapFramebufferLayoutMixin", Set.of("vulkanmod", "xaerominimap")::contains));
    }

    /**
     * Gnetum caches the HUD in two TextureTargets and composites them with vanilla blitToScreen, whose UVs assume GL's
     * bottom-up rows: under VulkanMod the whole HUD came out upside down (NightfallCraft).
     */
    @Test
    void gnetumHudFramebuffersRegisterWhenCreated() throws Exception {
        String mixin = "gnetum.HudFramebufferLayoutMixin";
        assertRegistered(mixin);
        AnnotationNode inject = injects(mixin).get(0);
        assertEquals(List.of("reset"), value(inject, "method"));
        @SuppressWarnings("unchecked")
        List<AnnotationNode> at = (List<AnnotationNode>) value(inject, "at");
        assertEquals("RETURN", value(at.get(0), "value"));
        assertTrue(CompatGate.shouldApply(mixin, Set.of("vulkanmod", "gnetum")::contains));
        assertFalse(CompatGate.shouldApply(mixin, Set.of("vulkanmod")::contains));
    }

    /**
     * 2026-10-17 (NightfallCraft: the world turned upside down while hitting mobs): VIX runs its post effects (Epic Fight Nightfall,
     * Nightfall Enhance, Fancy Trail) as chains of passes copied from vanilla's PostPass, GL ortho and UVs from the position, which
     * Reforged draws with the native viewport only for its own PostPass. Each VIX pass flipped the image; the bloom's eleven left it
     * upside down. While VIX's RenderPost runs, every framebuffer keeps GL's memory layout, as the passes expect.
     */
    @Test
    void everyFramebufferKeepsGlMemoryLayoutInsideAScope() {
        GlMemoryLayoutFramebuffers.enterScope();
        GlMemoryLayoutFramebuffers.enterScope();
        assertTrue(GlMemoryLayoutFramebuffers.uses(40));
        GlMemoryLayoutFramebuffers.exitScope();
        assertTrue(GlMemoryLayoutFramebuffers.uses(40), "nested");
        GlMemoryLayoutFramebuffers.exitScope();
        assertFalse(GlMemoryLayoutFramebuffers.uses(40));
        GlMemoryLayoutFramebuffers.exitScope();
        GlMemoryLayoutFramebuffers.enterScope();
        assertTrue(GlMemoryLayoutFramebuffers.uses(40), "an extra exit does not leave the count below zero");
        assertTrue(GlMemoryLayoutFramebuffers.uses(0), "the main target too (Reforged's id 0): the last pass copies into it");
        assertTrue(GlMemoryLayoutFramebuffers.inScope());
        GlMemoryLayoutFramebuffers.exitScope();
        assertFalse(GlMemoryLayoutFramebuffers.inScope());
    }

    /**
     * VIX sets each pass's viewport while the window is bound (after unbinding the input) and binds the output without a viewport,
     * where Reforged's top-down one stands; inside the scope each draw puts back the last viewport for the framebuffer bound now.
     */
    @Test
    void vixDrawsReapplyTheViewportForTheBoundFramebuffer() throws Exception {
        String mixin = "vix.PassViewportMixin";
        assertRegistered(mixin);
        AnnotationNode inject = injects(mixin).get(0);
        assertEquals(List.of("m_231209_(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V"), value(inject, "method"));
        assertTrue(CompatGate.shouldApply(mixin, Set.of("vulkanmod", "vix")::contains));
        assertFalse(CompatGate.shouldApply(mixin, Set.of("vulkanmod")::contains));
    }

    @Test
    void vixPostEffectsRunInAGlLayoutScope() throws Exception {
        String mixin = "vix.PostEffectLayoutMixin";
        assertRegistered(mixin);
        List<AnnotationNode> injects = injects(mixin);
        assertEquals(2, injects.size());
        for (AnnotationNode inject : injects) {
            assertEquals(List.of("RenderPost"), value(inject, "method"));
        }
        @SuppressWarnings("unchecked")
        List<AnnotationNode> head = (List<AnnotationNode>) value(injects.get(0), "at");
        @SuppressWarnings("unchecked")
        List<AnnotationNode> tail = (List<AnnotationNode>) value(injects.get(1), "at");
        assertEquals("HEAD", value(head.get(0), "value"));
        assertEquals("RETURN", value(tail.get(0), "value"));
        assertTrue(CompatGate.shouldApply(mixin, Set.of("vulkanmod", "vix")::contains));
        assertFalse(CompatGate.shouldApply(mixin, Set.of("vulkanmod")::contains));
    }

    @Test
    void viewportCallsAreRoutedThroughTheLayoutRule() throws Exception {
        AnnotationNode inject = injects("reforged.GlStateViewportMixin").get(0);
        assertEquals(Boolean.TRUE, value(inject, "cancellable"));
        assertTrue(calls("dev/vulkanfix/compat/ReforgedViewport.class", "setInvertedViewport"));
        assertTrue(calls("dev/vulkanfix/compat/ReforgedViewport.class", "setViewport"));
        assertFalse(calls("dev/vulkanfix/mixin/reforged/GlFramebufferMixin.class", "setViewport"), "restores go through ReforgedViewport");
        assertFalse(calls("dev/vulkanfix/compat/ReforgedReadback.class", "setViewport"), "restores go through ReforgedViewport");
    }

    private void assertRegistered(String mixin) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"" + mixin + "\""), mixin);
        }
    }

    private List<AnnotationNode> injects(String mixin) throws Exception {
        ClassNode node = read("dev/vulkanfix/mixin/" + mixin.replace('.', '/') + ".class");
        return node.methods.stream()
                .map(m -> m.visibleAnnotations == null ? null : m.visibleAnnotations.stream()
                        .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().orElse(null))
                .filter(a -> a != null).toList();
    }

    private boolean calls(String resource, String name) throws Exception {
        for (MethodNode m : read(resource).methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals("net/vulkanmod/vulkan/Renderer") && call.name.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ClassNode read(String resource) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " not compiled");
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    private static Object value(AnnotationNode a, String key) {
        int i = a.values.indexOf(key);
        return i < 0 ? null : a.values.get(i + 1);
    }
}
