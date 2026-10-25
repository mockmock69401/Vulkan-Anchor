package net.vulkanmod.visuals.framegen;

import net.vulkanmod.visuals.Bytecode;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FG design 4, 7: where frame generation hooks the frame. The capture runs before the hand's depth clear (so Forge's AFTER_LEVEL
 * overlays are in the world image), the camera comes from Reforged's frame, the vignette and crosshair leave the GUI while the UI layer
 * is bound, and the frame loop, the pipeline state and F3 call the facade.
 */
class FrameGenWiringTest {
    private static final String GAME_RENDERER_MIXIN = "net/vulkanmod/mixin/visuals/GameRendererFrameGenMixin";
    private static final String GUI_MIXIN = "net/vulkanmod/mixin/visuals/GuiFrameGenMixin";
    private static final String CLEAR = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V";
    private static final String VIGNETTE_BLIT = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIFFIIII)V";
    private static final String CROSSHAIR_BLIT = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V";

    private static Object value(AnnotationNode a, String key) {
        if (a.values == null) return null;
        for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(key)) return a.values.get(i + 1);
        return null;
    }

    private static List<AnnotationNode> annotations(MethodNode m, String desc) {
        List<AnnotationNode> out = new ArrayList<>();
        if (m.visibleAnnotations != null) for (AnnotationNode a : m.visibleAnnotations) if (a.desc.equals(desc)) out.add(a);
        if (m.invisibleAnnotations != null) for (AnnotationNode a : m.invisibleAnnotations) if (a.desc.equals(desc)) out.add(a);
        return out;
    }

    private static String mixinsJson() throws Exception {
        try (InputStream in = FrameGenWiringTest.class.getClassLoader().getResourceAsStream("vulkanmod.mixins.json")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The calls (owner/name/desc) inside one method of a game class. */
    private static List<String> invokes(String owner, String method) throws Exception {
        List<String> out = new ArrayList<>();
        for (MethodNode m : Bytecode.read(owner).methods) {
            if (!m.name.equals(method)) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) out.add("L" + c.owner + ";" + c.name + c.desc);
            }
        }
        return out;
    }

    @Test void theCaptureRunsBeforeTheHandsDepthClear() throws Exception {
        assertTrue(mixinsJson().contains("\"visuals.GameRendererFrameGenMixin\""));
        boolean beforeClear = false, atReturn = false;
        for (MethodNode m : Bytecode.read(GAME_RENDERER_MIXIN).methods) {
            for (AnnotationNode inject : annotations(m, "Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                assertEquals(List.of("renderLevel"), value(inject, "method"));
                @SuppressWarnings("unchecked")
                List<AnnotationNode> ats = (List<AnnotationNode>) value(inject, "at");
                AnnotationNode at = ats.get(0);
                if ("INVOKE".equals(value(at, "value")) && CLEAR.equals(value(at, "target")) && Integer.valueOf(0).equals(value(at, "ordinal"))) {
                    beforeClear = true;
                } else if ("RETURN".equals(value(at, "value"))) {
                    atReturn = true;
                }
                assertTrue(Bytecode.calls(GAME_RENDERER_MIXIN, m.name).contains("FrameGen.beforeHand"), m.name);
            }
        }
        assertTrue(beforeClear, "an @Inject at RenderSystem.clear(IZ)V ordinal 0");
        assertTrue(atReturn, "an @Inject at RETURN for a frame without the hand");
        //renderLevel's first clear is the hand's depth clear, right before renderItemInHand
        List<String> calls = invokes("net/minecraft/client/renderer/GameRenderer", "renderLevel");
        assertTrue(calls.contains(CLEAR), calls.toString());
        int clear = calls.indexOf(CLEAR);
        int level = -1, hand = -1;
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).startsWith("Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(")) level = i;
            if (calls.get(i).startsWith("Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(")) hand = i;
        }
        assertTrue(level >= 0 && level < clear && clear < hand, calls.toString());
    }

    @Test void worldRendererReportsTheCamera() throws Exception {
        String worldRenderer = "net/vulkanmod/render/chunk/WorldRenderer";
        ClassNode node = Bytecode.read(worldRenderer);
        int found = 0;
        for (MethodNode m : node.methods) {
            List<String> calls = Bytecode.calls(worldRenderer, m.name);
            if (!calls.contains("Visuals.frame")) continue;
            found++;
            assertTrue(calls.contains("FrameGen.onCamera"), m.name + ": " + calls);
        }
        assertTrue(found > 0, "a WorldRenderer method calls Visuals.frame");
    }

    @Test void theVignetteAndCrosshairAreRedirected() throws Exception {
        assertTrue(mixinsJson().contains("\"visuals.GuiFrameGenMixin\""));
        boolean vignette = false, crosshair = false;
        for (MethodNode m : Bytecode.read(GUI_MIXIN).methods) {
            for (AnnotationNode redirect : annotations(m, "Lorg/spongepowered/asm/mixin/injection/Redirect;")) {
                AnnotationNode at = (AnnotationNode) value(redirect, "at");
                List<?> method = (List<?>) value(redirect, "method");
                if (method.equals(List.of("renderVignette")) && VIGNETTE_BLIT.equals(value(at, "target"))) {
                    vignette = true;
                    assertTrue(Bytecode.calls(GUI_MIXIN, m.name).contains("FrameGen.vignette"), m.name);
                }
                if (method.equals(List.of("renderCrosshair")) && CROSSHAIR_BLIT.equals(value(at, "target"))) {
                    crosshair = true;
                    assertNull(value(at, "ordinal"), "every crosshair blit (the crosshair and the attack indicator)");
                    assertTrue(Bytecode.calls(GUI_MIXIN, m.name).contains("FrameGen.crosshairQuad"), m.name);
                }
                assertTrue(Bytecode.calls(GUI_MIXIN, m.name).contains("FrameGen.uiLayerActive"), m.name);
            }
        }
        assertTrue(vignette, "renderVignette's blit is redirected");
        assertTrue(crosshair, "renderCrosshair's blits are redirected");
        //the game's Gui really makes those calls there
        assertTrue(invokes("net/minecraft/client/gui/Gui", "renderVignette").contains(VIGNETTE_BLIT));
        assertTrue(invokes("net/minecraft/client/gui/Gui", "renderCrosshair").contains(CROSSHAIR_BLIT));
    }

    /** The frame loop, the end of the main pass, the UI pass's blend state, F3 and the game's close call the facade. */
    @Test void theCoreCallsTheFacade() throws Exception {
        List<String> begin = Bytecode.calls("net/vulkanmod/vulkan/Renderer", "beginFrame");
        assertTrue(Bytecode.inOrder(begin, List.of("FrameGen.beginFrame", "FrameGen.paced")), begin.toString());
        assertTrue(Bytecode.inOrder(begin, List.of("FrameGen.beginFrame", "MemoryManager.initFrame")), "textures are checked before freed images go: " + begin);
        List<String> end = Bytecode.calls("net/vulkanmod/vulkan/pass/DefaultMainPass", "end");
        assertTrue(Bytecode.inOrder(end, List.of("Renderer.endRenderPass", "FrameGen.endFrame", "FrameGenTargets.uiToShaderRead")), end.toString());
        List<String> state = Bytecode.calls("net/vulkanmod/vulkan/shader/PipelineState", "getCurrentPipelineState");
        assertTrue(Bytecode.inOrder(state, List.of("MainPass.isUiLayerPass", "FrameGen.reportBlend", "UiBlend.remap")), state.toString());
        assertTrue(Bytecode.calls("net/vulkanmod/visuals/Visuals", "addDebugInfo").contains("FrameGen.addDebugInfo"));
        List<String> close = Bytecode.calls("net/vulkanmod/mixin/render/MinecraftMixin", "close");
        assertTrue(Bytecode.inOrder(close, List.of("FrameGen.shutdown", "Vulkan.waitIdle")), close.toString());
    }

    /**
     * Fix round 1: the capture dispatch reads the entry of this render frame (fence-protected), never the slot's block, which is only the
     * pacer's CPU-side copy; the pause rules see the loading overlay.
     */
    @Test void theCaptureReadsThePerFrameEntry() throws Exception {
        String fg = "net/vulkanmod/visuals/framegen/FrameGen";
        List<String> calls = Bytecode.calls(fg, "writeCaptureParams");
        assertTrue(Bytecode.inOrder(calls, List.of("Renderer.getCurrentFrame", "FrameGenTargets.captureFrameParams")), calls.toString());
        List<String> capture = Bytecode.calls("net/vulkanmod/visuals/framegen/VkWarp", "capture");
        assertFalse(capture.contains("FrameGenTargets.captureParams"), capture.toString());
        assertTrue(Bytecode.inOrder(Bytecode.calls(fg, "capture"), List.of("FrameGen.writeCaptureParams", "VkWarp.capture")));
        assertTrue(Bytecode.inOrder(Bytecode.calls(fg, "endFrame"), List.of("FrameGen.writeCaptureParams", "VkWarp.capture")));
        assertTrue(Bytecode.calls(fg, "pauseReason").contains("Minecraft.getOverlay"));
    }

    /** Task 10: the latency stamp is taken where the game turns the player, and the direct path and the recorder reach the log. */
    @Test void theLatencyStampAndTheRecordedPresentsAreWired() throws Exception {
        String mixin = "net/vulkanmod/mixin/visuals/MouseHandlerLatencyMixin";
        assertTrue(mixinsJson().contains("\"visuals.MouseHandlerLatencyMixin\""));
        boolean head = false;
        for (MethodNode m : Bytecode.read(mixin).methods) {
            for (AnnotationNode inject : annotations(m, "Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                assertEquals(List.of("turnPlayer"), value(inject, "method"));
                @SuppressWarnings("unchecked")
                List<AnnotationNode> ats = (List<AnnotationNode>) value(inject, "at");
                head |= "HEAD".equals(value(ats.get(0), "value"));
                assertTrue(Bytecode.calls(mixin, m.name).contains("FrameGen.inputSampled"), m.name);
            }
        }
        assertTrue(head, "an @Inject at HEAD of turnPlayer");
        assertTrue(invokes("net/minecraft/client/MouseHandler", "turnPlayer").size() > 0, "the game has MouseHandler.turnPlayer");
        assertTrue(Bytecode.calls("net/vulkanmod/vulkan/Renderer", "submitFrame").contains("FrameGen.directPresented"));
        assertTrue(Bytecode.calls("net/vulkanmod/visuals/framegen/FrameGen", "count").contains("FrameRecorder.present"));
    }

    /** Crosshair quads: GUI units times the GUI scale in swapchain pixels, the uv rect normalised by the texture size. */
    @Test void crosshairQuadsAreSwapchainPixelsWithNormalisedUvs() {
        float[] quads = new float[FrameGenParams.MAX_QUADS * 8];
        Matrix4f identity = new Matrix4f();
        assertTrue(FrameGen.putQuad(quads, 0, identity, 10, 20, 15, 15, 0, 0, 256, 256, 3.0));
        assertArrayEquals(new float[]{30, 60, 45, 45, 0, 0, 15 / 256f, 15 / 256f}, java.util.Arrays.copyOf(quads, 8));
        assertTrue(FrameGen.putQuad(quads, 1, identity, 1, 2, 16, 4, 36, 94, 256, 256, 2.0));
        assertArrayEquals(new float[]{2, 4, 32, 8, 36 / 256f, 94 / 256f, 16 / 256f, 4 / 256f}, java.util.Arrays.copyOfRange(quads, 8, 16));
        assertFalse(FrameGen.putQuad(quads, FrameGenParams.MAX_QUADS, identity, 0, 0, 1, 1, 0, 0, 256, 256, 1.0), "no room past the last quad");
        assertFalse(FrameGen.putQuad(quads, 2, identity, 0, 0, 0, 4, 0, 0, 256, 256, 1.0), "an empty quad is dropped");
    }

    /**
     * Final review I5: the quad's corners go through the GUI pose first (Shoulder Surfing's dynamic crosshair translates it), the rect
     * being the transformed corners' bounds; the uv rect is unchanged.
     */
    @Test void crosshairQuadsFollowTheGuiPose() throws Exception {
        float[] quads = new float[FrameGenParams.MAX_QUADS * 8];
        assertTrue(FrameGen.putQuad(quads, 0, new Matrix4f().translation(5, -3, 0), 10, 20, 15, 15, 0, 0, 256, 256, 3.0));
        assertArrayEquals(new float[]{45, 51, 45, 45, 0, 0, 15 / 256f, 15 / 256f}, java.util.Arrays.copyOf(quads, 8), 1e-4f);
        //scaled about (2, 2) by 2: the corners (10, 20) and (25, 35) land on (18, 38) and (48, 68)
        Matrix4f scaled = new Matrix4f().translation(2, 2, 0).scale(2, 2, 1).translate(-2, -2, 0);
        assertTrue(FrameGen.putQuad(quads, 1, scaled, 10, 20, 15, 15, 0, 0, 256, 256, 1.0));
        assertArrayEquals(new float[]{18, 38, 30, 30}, java.util.Arrays.copyOfRange(quads, 8, 12), 1e-4f);
        //a mirrored pose: the bounds are still the min/max of the corners
        assertTrue(FrameGen.putQuad(quads, 2, new Matrix4f().scaling(-1, 1, 1), 10, 20, 15, 15, 0, 0, 256, 256, 2.0));
        assertArrayEquals(new float[]{-50, 40, 30, 30}, java.util.Arrays.copyOfRange(quads, 16, 20), 1e-4f);
        //the mixin reads the pose of the GuiGraphics it redirects
        List<String> calls = new ArrayList<>();
        for (MethodNode m : Bytecode.read(GUI_MIXIN).methods) {
            if (Bytecode.calls(GUI_MIXIN, m.name).contains("FrameGen.crosshairQuad")) calls.addAll(Bytecode.calls(GUI_MIXIN, m.name));
        }
        assertTrue(Bytecode.inOrder(calls, List.of("GuiGraphics.pose", "PoseStack.last", "FrameGen.crosshairQuad")), calls.toString());
    }

    /** The fields (Owner.name, simple owner) one method of a class reads or writes. */
    private static List<String> fields(String owner, String method) throws Exception {
        List<String> out = new ArrayList<>();
        for (MethodNode m : Bytecode.read(owner).methods) {
            if (!m.name.equals(method)) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof FieldInsnNode f) out.add(f.owner.substring(f.owner.lastIndexOf('/') + 1) + "." + f.name);
            }
        }
        return out;
    }

    /** Final review M5: F3's debug charts and Alt+F3's FPS chart both pause generation. */
    @Test void bothDebugChartsPause() throws Exception {
        List<String> read = fields("net/vulkanmod/visuals/framegen/FrameGen", "pauseReason");
        assertTrue(read.contains("Options.renderDebugCharts"), read.toString());
        assertTrue(read.contains("Options.renderFpsChart"), read.toString());
    }

    /**
     * Final review I2: a failing pacer start (an allocation, the descriptor pools, the compose pipeline) turns frame generation off
     * instead of leaving Renderer.beginFrame by an exception; on the recreation path, where the device is idle, it frees what it built.
     */
    @Test void aPacerStartFailureTurnsFrameGenerationOff() throws Exception {
        String fg = "net/vulkanmod/visuals/framegen/FrameGen";
        Set<String> caught = new HashSet<>();
        for (TryCatchBlockNode t : Bytecode.method(fg, "startPacer").tryCatchBlocks) caught.add(t.type);
        assertTrue(caught.contains("java/lang/RuntimeException") && caught.contains("java/lang/LinkageError"), caught.toString());
        List<String> after = Bytecode.calls(fg, "afterSwapchainRecreate");
        assertTrue(Bytecode.inOrder(after, List.of("FrameGen.startPacer", "FrameGen.freeResources")), after.toString());
    }

    /**
     * Final review I3, M6: the UI layer's framebuffers live per swapchain size; when they are replaced, and when frame generation turns
     * off, the main pass lets go of the UI passes and unbinds the UI layer's GL ids.
     */
    @Test void theUiLayerIsReleasedWithItsFramebuffers() throws Exception {
        String fg = "net/vulkanmod/visuals/framegen/FrameGen";
        List<String> free = Bytecode.calls(fg, "freeResources");
        assertTrue(Bytecode.inOrder(free, List.of("MainPass.releaseUiLayer", "FrameGenTargets.free")), free.toString());
        List<String> start = Bytecode.calls(fg, "startPacer");
        assertTrue(Bytecode.inOrder(start, List.of("FrameGenTargets.ensure", "MainPass.releaseUiLayer")), start.toString());
        List<String> release = Bytecode.calls("net/vulkanmod/vulkan/pass/DefaultMainPass", "releaseUiLayer");
        assertTrue(release.contains("RenderPass.cleanUp"), release.toString());
        assertTrue(release.contains("GlTexture.setVulkanImage"), release.toString());
    }

    /**
     * Final review I4: while paced, F2's grab waits one frame. The grab is stored, that frame pauses ("screenshot") so the hand, GUI,
     * vignette and crosshair draw into the world framebuffer, and the grab runs right after that frame's endFrame, where F2 runs.
     */
    @Test void aPacedScreenshotIsDeferredByOneFrame() throws Exception {
        String mixin = "net/vulkanmod/mixin/visuals/ScreenshotFrameGenMixin";
        String grab = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V";
        assertTrue(mixinsJson().contains("\"visuals.ScreenshotFrameGenMixin\""));
        boolean found = false;
        for (MethodNode m : Bytecode.read(mixin).methods) {
            for (AnnotationNode inject : annotations(m, "Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                assertEquals(List.of(grab), value(inject, "method"));
                assertEquals(Boolean.TRUE, value(inject, "cancellable"));
                @SuppressWarnings("unchecked")
                List<AnnotationNode> ats = (List<AnnotationNode>) value(inject, "at");
                assertEquals("HEAD", value(ats.get(0), "value"));
                assertTrue(Bytecode.calls(mixin, m.name).contains("FrameGen.deferScreenshot"), m.name);
                found = true;
            }
        }
        assertTrue(found, "an @Inject at HEAD of Screenshot.grab");
        //F2 (KeyboardHandler.keyPress) calls the short grab, which calls the one intercepted
        String shortGrab = "Lnet/minecraft/client/Screenshot;grab(Ljava/io/File;Lcom/mojang/blaze3d/pipeline/RenderTarget;Ljava/util/function/Consumer;)V";
        assertTrue(invokes("net/minecraft/client/KeyboardHandler", "keyPress").contains(shortGrab));
        assertTrue(invokes("net/minecraft/client/Screenshot", "grab").contains("Lnet/minecraft/client/Screenshot;" + grab));
        //the stored grab runs after the frame's endFrame, and that frame pauses for it
        List<String> submit = Bytecode.calls("net/vulkanmod/mixin/render/MinecraftMixin", "submitRender");
        assertTrue(Bytecode.inOrder(submit, List.of("Renderer.endFrame", "FrameGen.afterEndFrame")), submit.toString());
        assertTrue(Bytecode.calls("net/vulkanmod/visuals/framegen/FrameGen", "afterEndFrame").contains("Screenshot.grab"));
        assertTrue(fields("net/vulkanmod/visuals/framegen/FrameGen", "pauseReason").contains("FrameGen.screenshotFrame"));
    }
}
