package net.vulkanmod.visuals.render;

import org.joml.Vector3f;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sun shadow cascades without the LODs: their order per redrawn cascade and their bookkeeping, pinned in bytecode (no device in the tests). */
class VkShadowMapsWiringTest {
    private static final String SHADOW = "net/vulkanmod/visuals/render/VkShadowMaps";

    private static List<MethodNode> methods(String internalName, String method) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = VkShadowMapsWiringTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, internalName);
            new ClassReader(in).accept(node, 0);
        }
        List<MethodNode> out = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (m.name.equals(method)) out.add(m);
        }
        assertFalse(out.isEmpty(), internalName + "." + method);
        return out;
    }

    private static List<String> calls(String internalName, String method) throws Exception {
        List<String> calls = new ArrayList<>();
        for (MethodNode m : methods(internalName, method)) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) calls.add(c.owner.substring(c.owner.lastIndexOf('/') + 1) + "." + c.name);
            }
        }
        return calls;
    }

    private static boolean inOrder(List<String> calls, List<String> expected) {
        int next = 0;
        for (String c : calls) if (next < expected.size() && c.equals(expected.get(next))) next++;
        return next == expected.size();
    }

    @Test
    void theExtensionsComputeIsRecordedBeforeThePassBegins() throws Exception {
        List<String> draw = calls(SHADOW, "draw");
        int compute = draw.indexOf("VisualsExtension.recordShadowCompute"), begin = draw.indexOf("VK10.vkCmdBeginRenderPass");
        assertTrue(compute >= 0 && compute < begin, draw.toString());
    }

    @Test
    void theCastersAreDrawnInsideThePass() throws Exception {
        List<String> draw = calls(SHADOW, "draw");
        int begin = draw.indexOf("VK10.vkCmdBeginRenderPass"), end = draw.indexOf("VK10.vkCmdEndRenderPass");
        int lods = draw.indexOf("VisualsExtension.drawShadowCasters"), vanilla = draw.indexOf("VkVanillaShadows.draw");
        assertTrue(begin >= 0 && begin < lods && lods < end, draw.toString());
        assertTrue(begin < vanilla && vanilla < end, draw.toString());
        //the map is left readable by the lighting pass after the pass
        assertTrue(inOrder(draw, List.of("VK10.vkCmdEndRenderPass", "VkImage2D.assumeLayout", "VkImage2D.transition")), draw.toString());
        assertTrue(VkShadowMaps.SHADOW_STATE.depthOnly());
    }

    /** Fork design 4.3: vanilla terrain casts into every cascade, the far one too (an earlier draw skipped it with c != this.far). */
    @Test
    void noCascadeIsLeftOutOfTheVanillaCasters() throws Exception {
        for (MethodNode m : methods(SHADOW, "draw")) {
            for (AbstractInsnNode insn : m.instructions) {
                int op = insn.getOpcode();
                assertFalse(op == Opcodes.IF_ACMPEQ || op == Opcodes.IF_ACMPNE, "a reference comparison in draw (a cascade left out)");
                if (insn instanceof FieldInsnNode f) {
                    assertFalse(f.owner.equals(SHADOW) && (f.name.equals("far") || f.name.equals("near") || f.name.equals("close")),
                            "draw reads the cascade field " + f.name);
                    assertFalse(f.owner.endsWith("ShadowMath$Which"), "draw singles out the cascade " + f.name);
                }
            }
        }
        List<String> draw = calls(SHADOW, "draw");
        int radius = draw.indexOf("ShadowMath.vanillaRadius"), vanilla = draw.indexOf("VkVanillaShadows.draw");
        assertTrue(radius >= 0 && radius < vanilla, draw.toString());
    }

    /**
     * The extension prepares a redrawn cascade before the upload commit. Ruling R13: its afterDownloads is the frame flow's, once
     * a frame; the maps' afterDownloads only resets their own state.
     */
    @Test
    void theExtensionIsCalledAroundTheCascade() throws Exception {
        List<String> prepare = calls(SHADOW, "prepare");
        assertTrue(inOrder(prepare, List.of("VkShadowMaps.plan", "VisualsExtension.prepareShadow")), prepare.toString());
        List<String> after = calls(SHADOW, "afterDownloads");
        assertFalse(after.contains("VisualsExtension.afterDownloads"), after.toString());
        for (MethodNode m : methods(SHADOW, "afterDownloads")) {
            assertEquals("(Lorg/lwjgl/vulkan/VkCommandBuffer;)V", m.desc);
        }
    }

    /** Ruling R12: every shadow phase's context carries both streams, never null. */
    @Test
    void everyCascadeContextCarriesBothStreams() throws Exception {
        int built = 0;
        for (String method : List.of("prepare", "draw")) {
            int here = 0;
            for (MethodNode m : methods(SHADOW, method)) {
                for (AbstractInsnNode insn : m.instructions) {
                    if (!(insn instanceof MethodInsnNode c) || !c.owner.endsWith("ShadowCascadeContext") || !c.name.equals("<init>")) continue;
                    here++;
                    //the last two arguments (upload, download) are the two instructions right before the call
                    AbstractInsnNode download = previous(insn), upload = previous(download);
                    assertFalse(download.getOpcode() == Opcodes.ACONST_NULL || upload.getOpcode() == Opcodes.ACONST_NULL, method + ": a null stream");
                    assertTrue(download.getOpcode() == Opcodes.ALOAD && upload.getOpcode() == Opcodes.ALOAD, method + ": the streams are its arguments");
                }
            }
            assertTrue(here > 0, method + " builds a context");
            built += here;
        }
        assertTrue(built >= 2);
    }

    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && p.getOpcode() < 0) p = p.getPrevious();//labels, line numbers, frames
        assertNotNull(p);
        return p;
    }

    /** Review focus 2: a map is drawn again when due or when its last frame was dropped. */
    @Test
    void aCascadeIsRedrawnWhenDueOrLost() throws Exception {
        List<String> prepare = calls(SHADOW, "prepare");
        //Sun-step crossfade (2026-10-16): per cascade the map to draw from the steps, each map's validity first
        assertTrue(prepare.contains("ShadowFrame.farDue") && prepare.contains("ShadowFrame.timeOfDay") && prepare.contains("VkShadowMaps.plan"), prepare.toString());
        List<String> plan = calls(SHADOW, "plan");
        assertTrue(inOrder(plan, List.of("VkShadowMaps$Cascade.ready", "VkShadowMaps.mapValid", "ShadowSteps.plan")), plan.toString());
        assertTrue(calls(SHADOW + "$Cascade", "ready").contains("VkFrames.isDropped"));
        assertTrue(calls(SHADOW, "tick").contains("VkShadowMaps$Cascade.tick"));
        assertTrue(calls(SHADOW + "$Cascade", "tick").contains("VkImage2D.tick"));
    }

    /** A dropped map is forgotten while VkFrames still remembers it. */
    @Test
    void aDroppedMapIsForgottenBeforeTheDropExpires() throws Exception {
        List<String> tick = calls(SHADOW + "$Cascade", "tick");
        assertTrue(inOrder(tick, List.of("VkFrames.isDropped", "VkImage2D.tick")), tick.toString());
    }

    /**
     * Sun-step crossfade (2026-10-16): a map is read (rebased) while it ran, the frame before prepared maps too and the camera
     * stayed within the cascade's reach; the sun's turn is the steps' business, not a reason to drop a map.
     */
    @Test
    void aMapIsReadUntilThePauseOrTheCameraMovedFar() {
        Vector3f sun = new Vector3f(0.6f, 0.8f, 0);
        ShadowMath.Cascade drawn = ShadowMath.cascade(sun, 100, 70, 200, ShadowMath.NEAR_RADIUS);
        ShadowMath.Cascade still = ShadowMath.cascade(sun, 103, 70, 200, ShadowMath.NEAR_RADIUS);
        assertTrue(VkShadowMaps.mapValid(true, true, drawn, still, ShadowMath.NEAR_MAX_MOVE));
        assertFalse(VkShadowMaps.mapValid(false, true, drawn, still, ShadowMath.NEAR_MAX_MOVE), "the frame before drew no maps");
        assertFalse(VkShadowMaps.mapValid(true, false, drawn, still, ShadowMath.NEAR_MAX_MOVE), "dropped or never drawn");
        assertFalse(VkShadowMaps.mapValid(true, true, null, still, ShadowMath.NEAR_MAX_MOVE));
        assertFalse(VkShadowMaps.mapValid(true, true, drawn, ShadowMath.cascade(sun, 109, 70, 200, ShadowMath.NEAR_RADIUS), ShadowMath.NEAR_MAX_MOVE), "moved 9 blocks");
        double turn = Math.toRadians(1);
        Vector3f turned = new Vector3f((float) Math.cos(Math.atan2(0.8, 0.6) + turn), (float) Math.sin(Math.atan2(0.8, 0.6) + turn), 0);
        assertTrue(VkShadowMaps.mapValid(true, true, drawn, ShadowMath.cascade(turned, 100, 70, 200, ShadowMath.NEAR_RADIUS), ShadowMath.NEAR_MAX_MOVE),
                "the turn is the steps'");
        ShadowMath.Cascade far = ShadowMath.cascade(sun, 100, 70, 200, ShadowMath.FAR_RADIUS);
        assertTrue(VkShadowMaps.mapValid(true, true, far, ShadowMath.cascade(sun, 140, 70, 200, ShadowMath.FAR_RADIUS), ShadowMath.FAR_MAX_MOVE), "40 blocks: rebased");
        assertFalse(VkShadowMaps.mapValid(true, true, far, ShadowMath.cascade(sun, 1000, 70, 200, ShadowMath.FAR_RADIUS), ShadowMath.FAR_MAX_MOVE), "a teleport");
        for (int frame = 0; frame < 16; frame++) {
            boolean near = Math.floorMod(frame, ShadowMath.NEAR_INTERVAL) == ShadowMath.NEAR_PHASE, farDue = Math.floorMod(frame, ShadowMath.FAR_INTERVAL) == 0;
            assertFalse(near && farDue, "the near refresh never on the far one's frame");
        }
    }

    /**
     * Performance (2026-10-21): a sliced refresh carries on only while its slices were drawn one frame after another, none of their
     * frames was dropped and the camera stayed within the map's reach; the far cascade's refresh is spread over its interval.
     */
    @Test
    void aSlicedRefreshCarriesOnOnlyFrameAfterFrame() {
        Vector3f sun = new Vector3f(0.6f, 0.8f, 0);
        ShadowMath.Cascade started = ShadowMath.cascade(sun, 100, 70, 200, ShadowMath.FAR_RADIUS);
        ShadowMath.Cascade here = ShadowMath.cascade(sun, 120, 70, 200, ShadowMath.FAR_RADIUS);
        long[] serials = {41, 42, 43, 0, 0, 0, 0, 0};
        assertTrue(VkShadowMaps.refreshAlive(serials, 3, 44, s -> false, started, here, ShadowMath.FAR_MAX_MOVE));
        assertTrue(VkShadowMaps.refreshAlive(serials, 0, 90, s -> false, started, here, ShadowMath.FAR_MAX_MOVE), "nothing drawn yet");
        assertFalse(VkShadowMaps.refreshAlive(serials, 3, 45, s -> false, started, here, ShadowMath.FAR_MAX_MOVE), "a frame without its slice");
        assertFalse(VkShadowMaps.refreshAlive(serials, 3, 44, s -> s == 42, started, here, ShadowMath.FAR_MAX_MOVE), "a slice's frame was dropped");
        assertFalse(VkShadowMaps.refreshAlive(serials, 3, 44, s -> false, started, ShadowMath.cascade(sun, 300, 70, 200, ShadowMath.FAR_RADIUS),
                ShadowMath.FAR_MAX_MOVE), "the camera left the map's reach");
        assertTrue(ShadowMath.FAR_SLICES > 1 && ShadowMath.FAR_SLICES <= ShadowMath.FAR_INTERVAL, "one refresh at a time, spread over the interval");
    }

    /**
     * A refresh slice draws into the spare image: the first with the clearing pass, the later ones with the loading pass (both made
     * by the constructor and freed), and the casters are given the pass that is open.
     */
    @Test
    void laterSlicesLoadTheDepthTheEarlierOnesDrew() throws Exception {
        List<String> init = calls(SHADOW, "<init>");
        assertEquals(2, init.stream().filter("VkShadowMaps.createRenderPass"::equals).count(), init.toString());
        List<String> free = calls(SHADOW, "free");
        assertEquals(2, free.stream().filter("VK10.vkDestroyRenderPass"::equals).count(), free.toString());
        boolean readsLoad = false;
        for (MethodNode m : methods(SHADOW, "draw")) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof FieldInsnNode f && f.owner.equals(SHADOW) && f.name.equals("loadPass")) readsLoad = true;
            }
        }
        assertTrue(readsLoad, "draw begins the loading pass for later slices");
    }

    /** The caller runs without shadows when the constructor throws, so what it built is freed before the rethrow. */
    @Test
    void aFailedConstructorFreesWhatItBuilt() throws Exception {
        List<String> shadow = calls(SHADOW, "<init>");
        for (String free : List.of("VkShadowMaps$Cascade.free", "VK10.vkDestroyRenderPass")) {
            assertTrue(shadow.contains(free), free + " in " + shadow);
        }
        List<String> cascade = calls(SHADOW + "$Cascade", "<init>");
        for (String free : List.of("VkImage2D.free", "VK10.vkDestroyFramebuffer")) {
            assertTrue(cascade.contains(free), free + " in " + cascade);
        }
    }

    /** P7o design 3.7: the vanilla caster drawer is built on its own, so its failure only warns and the cascades stay. */
    @Test
    void aFailedVanillaDrawerOnlyWarns() throws Exception {
        List<String> init = calls(SHADOW, "<init>");
        assertTrue(inOrder(init, List.of("VkShadowMaps.createRenderPass", "VkVanillaShadows.<init>", "VisualsLog.warn")), init.toString());
        boolean caught = false;
        for (MethodNode m : methods(SHADOW, "<init>")) {
            //a multi-catch shares one handler: count the handlers, not the table's entries
            caught |= m.tryCatchBlocks.stream().map(t -> t.handler).distinct().count() >= 2;
        }
        assertTrue(caught, "a handler for the cascades and one for the vanilla drawer");
    }
}
