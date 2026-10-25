package net.vulkanmod.mixin;

import net.vulkanmod.visuals.Bytecode;
import net.vulkanmod.visuals.dh.DhPresence.State;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which renderSectionLayer mixins load, and how the three differ. */
class SectionLayerChoiceTest {
    private static final String PKG = "net.vulkanmod.mixin.chunk.";
    private static final String PLAIN = PKG + "SectionLayerMixin";
    private static final String GUARD = PKG + "SectionLayerGuardMixin";
    private static final String LATE = PKG + "SectionLayerLateMixin";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";

    @Test
    void renderApiLoadsTheGuardAndTheLateDrawer() {
        assertEquals(Set.of(GUARD, LATE), MixinPlugin.sectionLayerMixins(State.RENDER_API));
    }

    @Test
    void legacyDhLoadsTheGuardAndTheLateDrawer() {
        assertEquals(Set.of(GUARD, LATE), MixinPlugin.sectionLayerMixins(State.LEGACY_RENDER_DEF));
    }

    @Test
    void theLegacyDhMixinsLoadOnlyWithALegacyDhAndItsDepthMethod() {
        String all = "net.vulkanmod.mixin.visuals.legacydh.GlRenderDefinitionMixin";
        String depth = "net.vulkanmod.mixin.visuals.legacydh.GlRenderDefinitionRenderDepthMixin";
        String direction = "net.vulkanmod.mixin.visuals.legacydh.GlRenderDefinitionDepthDirectionMixin";
        for (State s : List.of(State.ABSENT, State.NO_RENDER_API, State.RENDER_API)) {
            assertFalse(MixinPlugin.legacyDhMixin(all, s, false), s.name());
            assertFalse(MixinPlugin.legacyDhMixin(depth, s, true), s.name());
            assertFalse(MixinPlugin.legacyDhMixin(direction, s, false), s.name());
        }
        assertTrue(MixinPlugin.legacyDhMixin(all, State.LEGACY_RENDER_DEF, true));
        assertTrue(MixinPlugin.legacyDhMixin(all, State.LEGACY_RENDER_DEF, false));
        //DH 3.3.0 and 3.3.1 have getRenderDepth, DH 3.3.2 to 3.3.4 getDepthDirection
        assertTrue(MixinPlugin.legacyDhMixin(depth, State.LEGACY_RENDER_DEF, true));
        assertFalse(MixinPlugin.legacyDhMixin(direction, State.LEGACY_RENDER_DEF, true));
        assertFalse(MixinPlugin.legacyDhMixin(depth, State.LEGACY_RENDER_DEF, false));
        assertTrue(MixinPlugin.legacyDhMixin(direction, State.LEGACY_RENDER_DEF, false));
    }

    @Test
    void withoutTheRenderApiOnlyThePlainDrawerLoads() {
        assertEquals(Set.of(PLAIN), MixinPlugin.sectionLayerMixins(State.ABSENT));
        assertEquals(Set.of(PLAIN), MixinPlugin.sectionLayerMixins(State.NO_RENDER_API));
    }

    private static int priority(ClassNode c) {
        // @Mixin has class retention, so ASM lists it as invisible.
        for (AnnotationNode a : c.invisibleAnnotations) {
            if (!a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            int i = a.values.indexOf("priority");
            return i < 0 ? 1000 : (Integer) a.values.get(i + 1);
        }
        throw new AssertionError("no @Mixin on " + c.name);
    }

    private static MethodNode injectOn(ClassNode c, String method) {
        for (MethodNode m : c.methods) {
            if (m.visibleAnnotations == null) continue;
            for (AnnotationNode a : m.visibleAnnotations) {
                if (a.desc.equals(INJECT) && a.values.contains(List.of(method))) return m;
            }
        }
        throw new AssertionError("no @Inject on " + method + " in " + c.name);
    }

    private static List<String> calls(MethodNode m) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode c) calls.add(Bytecode.name(c));
        }
        return calls;
    }

    @Test
    void thePrioritiesOrderGuardPlainLate() throws Exception {
        assertEquals(1000, priority(Bytecode.read("net/vulkanmod/mixin/chunk/SectionLayerMixin")));
        assertEquals(900, priority(Bytecode.read("net/vulkanmod/mixin/chunk/SectionLayerGuardMixin")));
        assertEquals(1100, priority(Bytecode.read("net/vulkanmod/mixin/chunk/SectionLayerLateMixin")));
    }

    /** 1.21.1: the plain drawer draws before vanilla's body without cancelling it (as upstream 1.21.1); the DH pair cancels. */
    @Test
    void allThreeDrawTheDhPairCancelsAndOnlyTheGuardBranchesOnDhBeingActive() throws Exception {
        for (String name : List.of("SectionLayerMixin", "SectionLayerGuardMixin", "SectionLayerLateMixin")) {
            ClassNode c = Bytecode.read("net/vulkanmod/mixin/chunk/" + name);
            List<String> calls = calls(injectOn(c, "renderSectionLayer"));
            assertTrue(calls.contains("WorldRenderer.renderSectionLayer"), name + " " + calls);
            assertEquals(!name.equals("SectionLayerMixin"), Bytecode.inOrder(calls, List.of("WorldRenderer.renderSectionLayer", "CallbackInfo.cancel")),
                    name + " " + calls);
            assertEquals(name.equals("SectionLayerGuardMixin"), calls.contains("DhPresence.isActive"), name + " " + calls);
        }
    }

    @Test
    void theGuardChecksBeforeItDraws() throws Exception {
        List<String> calls = calls(injectOn(Bytecode.read("net/vulkanmod/mixin/chunk/SectionLayerGuardMixin"), "renderSectionLayer"));
        assertTrue(Bytecode.inOrder(calls, List.of("DhPresence.isActive", "WorldRenderer.renderSectionLayer", "CallbackInfo.cancel")), calls.toString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void levelRendererMixinNoLongerInjectsTheSectionLayer() throws Exception {
        ClassNode c = Bytecode.read("net/vulkanmod/mixin/chunk/LevelRendererMixin");
        List<String> targets = new ArrayList<>();
        for (MethodNode m : c.methods) {
            if (m.visibleAnnotations == null) continue;
            for (AnnotationNode a : m.visibleAnnotations) {
                if (!a.desc.equals(INJECT) && !a.desc.equals(REDIRECT)) continue;
                targets.addAll((List<String>) a.values.get(a.values.indexOf("method") + 1));
            }
        }
        assertFalse(targets.contains("renderSectionLayer"), targets.toString());
        for (String kept : List.of("setupRender", "applyFrustum", "allChanged", "<init>", "setLevel", "renderLevel")) {
            assertTrue(targets.contains(kept), kept + " in " + targets);
        }
    }
}
