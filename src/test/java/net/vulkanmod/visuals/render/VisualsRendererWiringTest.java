package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static net.vulkanmod.visuals.Bytecode.calls;
import static net.vulkanmod.visuals.Bytecode.count;
import static net.vulkanmod.visuals.Bytecode.inOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the visuals run in Reforged's frame and how they give the main pass back (the render-system part of the old post-process
 * wiring tests, now against VisualsRenderer, Visuals and the fork's mixins).
 */
class VisualsRendererWiringTest {
    private static final String SYSTEM = "net/vulkanmod/visuals/render/VisualsRenderer";
    private static final String VISUALS = "net/vulkanmod/visuals/Visuals";
    private static final String MIXIN = "net/vulkanmod/mixin/visuals/GameRendererPostMixin";
    private static final String WEATHER = "net/vulkanmod/visuals/render/DeferredWeather";

    @Test
    void theMixinRunsRightAfterTheLevelRendererInsideRenderLevel() throws Exception {
        boolean found = false;
        for (MethodNode m : Bytecode.read(MIXIN).methods) {
            if (m.visibleAnnotations == null) continue;
            for (AnnotationNode a : m.visibleAnnotations) {
                String method = null, value = null, target = null, shift = null;
                for (int i = 0; i < a.values.size(); i += 2) {
                    Object v = a.values.get(i + 1);
                    if (a.values.get(i).equals("method")) method = String.valueOf(((List<?>) v).get(0));
                    if (a.values.get(i).equals("at")) {
                        AnnotationNode at = (AnnotationNode) ((List<?>) v).get(0);
                        for (int j = 0; j < at.values.size(); j += 2) {
                            Object av = at.values.get(j + 1);
                            switch ((String) at.values.get(j)) {
                                case "value" -> value = (String) av;
                                case "target" -> target = (String) av;
                                case "shift" -> shift = ((String[]) av)[1];
                                default -> { }
                            }
                        }
                    }
                }
                assertEquals("renderLevel", method);
                assertEquals("INVOKE", value);
                assertTrue(target != null && target.startsWith("Lnet/minecraft/client/renderer/LevelRenderer;renderLevel("), target);
                assertEquals("AFTER", shift);
                found = true;
            }
        }
        assertTrue(found, "the @Inject");
        assertTrue(calls(MIXIN, "visuals$afterLevel").contains("Visuals.postProcess"));
        assertTrue(calls(VISUALS, "postProcess").contains("VisualsRenderer.postProcess"));
    }

    /** The fork has no GL path: the mixins are in the main config's client list. */
    @Test
    void theMixinsAreRegistered() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanmod.mixins.json")) {
            assertNotNull(in);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String m : List.of("GameRendererPostMixin", "GameRendererJitterMixin", "LightTextureVisualsMixin", "LevelRendererCloudsMixin",
                    "LevelRendererWeatherMixin", "LevelRendererTicksAccessor", "LevelRendererWeatherInvoker", "ClientLevelLightMixin",
                    "ClientChunkCacheLightMixin", "LightTextureAccessor")) {
                assertTrue(json.contains("\"visuals." + m + "\""), m);
            }
        }
    }

    /** Review focus 2: nothing touches the pass before knowing Reforged records this frame and the target has a size. */
    @Test
    void postProcessChecksTheFrameBeforeTouchingThePass() throws Exception {
        List<String> c = calls(SYSTEM, "postProcess");
        assertTrue(inOrder(c, List.of("VkContext.recordingFrame", "PostGate.shouldRun", "VkContext.resumeMainPass", "VkContext.captureMainTarget",
                "VkContext$MainTarget.width", "VkContext.endMainPass")), c.toString());
    }

    /** Design 3.1: break the pass, compute on the main colour, give it back, resume, draw, then Reforged rebinds its pipeline. */
    @Test
    void postProcessBreaksThePassAroundTheComputeAndDrawsInTheResumedOne() throws Exception {
        List<String> c = calls(SYSTEM, "postProcess");
        assertTrue(inOrder(c, List.of("VkPostProcess.ensureSize", "PostUniforms.compute", "VkContext.endMainPass",
                "VkContext.mainColourToShaderRead", "VkBarriers.mainColourForCompute", "VkContext.mainColourView", "VkPostProcess.compute",
                "VkBarriers.mainColourAfterCompute", "VkContext.mainColourToAttachment", "VkContext.resumeMainPass", "VkPostProcess.draw",
                "VkContext.invalidatePipelineCache")), c.toString());
    }

    /** Review focus 3: javac copies a finally block onto the exception path, so the pass is given back on both. */
    @Test
    void theMainPassIsResumedOnTheFailurePathToo() throws Exception {
        List<String> c = calls(SYSTEM, "postProcess");
        assertTrue(count(c, "VkContext.resumeMainPass") >= 3, c.toString());
        assertTrue(count(c, "VkContext.mainColourToAttachment") >= 2, c.toString());
        assertTrue(count(c, "VkContext.invalidatePipelineCache") >= 2, c.toString());
    }

    @Test
    void theRendererTicksAndFreesThePostProcess() throws Exception {
        assertTrue(calls(SYSTEM, "onRecordingStarted").contains("VkPostProcess.tick"));
        List<String> shutdown = calls(SYSTEM, "free");
        int idle = shutdown.indexOf("VkContext.waitIdle");
        int free = shutdown.indexOf("VkPostProcess.free");
        assertTrue(idle >= 0 && free > idle && free < shutdown.indexOf("DeferredFree.freeAll"), shutdown.toString());
    }

    /** P7c design 3.1: the atmosphere is computed before the break, uploaded and read with the depths outside the pass. */
    @Test
    void postProcessReadsTheDepthsOutsideThePass() throws Exception {
        List<String> c = calls(SYSTEM, "postProcess");
        assertTrue(inOrder(c, List.of("AtmosphereUniforms.compute", "PostUniforms.compute", "VkPostProcess.prepare", "VkContext.endMainPass",
                "VkUploadStream.commit", "VkContext.mainDepthToShaderRead", "VkBarriers.mainDepthForCompute", "ImageViewCache.get",
                "PostInputs.depths", "VkPostProcess.compute", "VkContext.resumeMainPass", "VkPostProcess.draw")), c.toString());
    }

    /** Final review: frame() stops once it failed, so the light volume's centre is read from the camera in postProcess. */
    @Test
    void postProcessReadsTheCameraItselfForTheLightVolume() throws Exception {
        List<String> c = calls(SYSTEM, "postProcess");
        assertTrue(inOrder(c, List.of("GameRenderer.getMainCamera", "Camera.getPosition", "LightVolumeSystem.update", "LightVolumeUniforms.compute")), c.toString());
        ClassNode node = Bytecode.read(SYSTEM);
        assertTrue(node.fields.stream().noneMatch(f -> f.name.startsWith("camera")), "no camera cached by frame()");
    }

    @Test
    void frameCapturesTheAtmosphere() throws Exception {
        assertTrue(calls(SYSTEM, "frame").contains("VisualsRenderer.captureAtmosphere"));
        assertTrue(calls(SYSTEM, "captureAtmosphere").contains("AtmosphereFrame.of"));
        //Final fix R2: the camera's fluid from the game camera
        assertTrue(inOrder(calls(SYSTEM, "captureAtmosphere"), List.of("Camera.getFluidInCamera", "VisualsRenderer.cameraFluid", "AtmosphereFrame.withCameraFluid")),
                calls(SYSTEM, "captureAtmosphere").toString());
        assertEquals(AtmosphereFrame.CameraFluid.WATER, VisualsRenderer.cameraFluid(net.minecraft.world.level.material.FogType.WATER));
        assertEquals(AtmosphereFrame.CameraFluid.LAVA, VisualsRenderer.cameraFluid(net.minecraft.world.level.material.FogType.LAVA));
        assertEquals(AtmosphereFrame.CameraFluid.POWDER_SNOW, VisualsRenderer.cameraFluid(net.minecraft.world.level.material.FogType.POWDER_SNOW));
        assertEquals(AtmosphereFrame.CameraFluid.NONE, VisualsRenderer.cameraFluid(net.minecraft.world.level.material.FogType.NONE));
        assertEquals(AtmosphereFrame.CameraFluid.NONE, VisualsRenderer.cameraFluid(null));
    }

    /**
     * Review focus 2, Task 4: without this frame's LOD inputs (or when the atmosphere does not take them) every LOD slot is the main
     * depth; the depth copies stand in by the main depth when they were not taken this frame.
     */
    @Test
    void theLodDepthViewFallsBackToTheMainDepth() throws Exception {
        List<String> c = calls(SYSTEM, "postProcess");
        assertTrue(inOrder(c, List.of("AtmosphereUniforms.compute", "AtmosphereUniforms$Values.lodValid", "PostInputs.depths")), c.toString());
        assertTrue(c.contains("LodInputs.sizeMatches") && c.contains("LodInputs.lightView") && c.contains("VkImage2D.sampledView"), c.toString());
        assertTrue(calls(SYSTEM, "lodInputs").contains("VisualsExtension.postInputs"));
    }

    /** Final review: a dimension whose sunrise colour or effects throw only loses the atmosphere. */
    @Test
    void aFailingAtmosphereCaptureOnlyLosesTheAtmosphere() throws Exception {
        MethodNode m = Bytecode.method(SYSTEM, "captureAtmosphere");
        assertTrue(m.tryCatchBlocks.stream().anyMatch(t -> "java/lang/RuntimeException".equals(t.type)), "captureAtmosphere catches");
        assertTrue(calls(SYSTEM, "captureAtmosphere").contains("VisualsLog.warn"));
    }

    /** Final review M5: a sky colour that cannot be read only loses the reflections' zenith colour, not the atmosphere. */
    @Test
    void aFailingSkyColourOnlyLosesTheZenith() throws Exception {
        MethodNode m = Bytecode.method(SYSTEM, "skyColour");
        assertTrue(m.tryCatchBlocks.stream().anyMatch(t -> "java/lang/RuntimeException".equals(t.type)), "skyColour catches");
        assertTrue(calls(SYSTEM, "postProcess").contains("AtmosphereUniforms$Values.night"), calls(SYSTEM, "postProcess").toString());
    }

    /** P7e-2 design 3.4: the frame's sky colour is captured with the atmosphere, and the reflection uniforms follow the lighting's. */
    @Test
    void theFrameCapturesTheSkyAndThePostProcessComputesTheReflection() throws Exception {
        List<String> capture = calls(SYSTEM, "captureAtmosphere");
        assertTrue(capture.contains("VisualsRenderer.skyColour") && capture.contains("AtmosphereFrame.withSky"), capture.toString());
        assertTrue(capture.contains("GameRenderer.getRenderDistance"), capture.toString());
        assertTrue(calls(SYSTEM, "skyColour").contains("ClientLevel.getSkyColor"), calls(SYSTEM, "skyColour").toString());
        assertTrue(inOrder(calls(SYSTEM, "postProcess"), List.of("LightingUniforms.compute", "ReflectionUniforms.compute", "VkPostProcess.prepare")),
                calls(SYSTEM, "postProcess").toString());
    }

    /** P7e-3 design 3.3: the capture advances the flow clock with the game time and the rain, and hands the offsets on. */
    @Test
    void theCaptureAdvancesTheRipples() throws Exception {
        List<String> capture = calls(SYSTEM, "captureAtmosphere");
        //2026-10-23: by FlowClock's real time (stopped while paused), not the game time
        assertFalse(capture.contains("ClientLevel.getGameTime"), capture.toString());
        assertTrue(capture.contains("FlowClock.advance") && capture.contains("Minecraft.isPaused"), capture.toString());
        assertTrue(capture.indexOf("FlowClock.advance") < capture.indexOf("RippleClock.advance")
                && capture.indexOf("FlowClock.advance") < capture.indexOf("FoliageWind.update"), capture.toString());
        assertTrue(capture.contains("RippleMath.speedFactor") && capture.contains("RippleClock.advance"), capture.toString());
        assertTrue(capture.contains("PostProcessSettings.waterRippleSpeed"), capture.toString());
        assertTrue(capture.contains("RippleClock.phase") && capture.contains("RippleMath.offsets"), capture.toString());
        assertTrue(capture.indexOf("RippleClock.advance") < capture.indexOf("RippleClock.phase"), capture.toString());
        assertTrue(capture.contains("AtmosphereFrame.withRipple"), capture.toString());
    }

    /** P7h final review: weather and border wait for the post-process while the far clouds are drawn, and are always drawn after it. */
    @Test
    void weatherAndBorderWaitForThePostProcessWhileTheFarCloudsAreDrawn() throws Exception {
        List<String> after = calls(MIXIN, "visuals$afterLevel");
        assertTrue(inOrder(after, List.of("Visuals.postProcess", "DeferredWeather.replay")), after.toString());
        //The replay is in the mixin's finally: on the failure path too
        assertEquals(2, count(after, "DeferredWeather.replay"), after.toString());
        List<String> defer = calls(WEATHER, "deferRain");
        assertTrue(defer.contains("FarClouds.replacesVanilla") && defer.contains("Minecraft.useShaderTransparency")
                && defer.contains("RenderSystem.getModelViewMatrix"), defer.toString());
        List<String> replay = calls(WEATHER, "replay");
        assertTrue(inOrder(replay, List.of("Matrix4fStack.pushMatrix", "RenderSystem.applyModelViewMatrix", "LevelRendererWeatherInvoker.visuals$renderSnowAndRain",
                "LevelRendererWeatherInvoker.visuals$renderWorldBorder", "Matrix4fStack.popMatrix")), replay.toString());
        assertTrue(calls("net/vulkanmod/mixin/visuals/LevelRendererWeatherMixin", "visuals$deferWeather").contains("DeferredWeather.deferRain"));
        assertTrue(calls("net/vulkanmod/mixin/visuals/LevelRendererWeatherMixin", "visuals$deferBorder").contains("DeferredWeather.deferBorder"));
    }

    /** P7h design 3.3, 3.5, review focus 2, 5: the renderer loads the map, captures the cloud layer, sets the flag every frame and clears it on shutdown. */
    @Test
    void theRendererDrivesTheFarClouds() throws Exception {
        List<String> init = calls(SYSTEM, "<init>");
        assertTrue(init.contains("VkPostProcess.queueCloudMap") && init.contains("VisualsRenderer.loadCloudMask") && init.contains("CloudMapCache.withNoise"), init.toString());
        List<String> load = calls(SYSTEM, "loadCloudMask");
        assertTrue(load.contains("NativeImage.read") && load.contains("NativeImage.getPixelRGBA") && load.contains("CloudMath.mask"), load.toString());
        List<String> frame = calls(SYSTEM, "cloudFrame");
        assertTrue(frame.contains("DimensionSpecialEffects.getCloudHeight") && frame.contains("Options.getCloudsType")
                && frame.contains("LevelRendererTicksAccessor.visuals$getTicks") && frame.contains("ClientLevel.getCloudColor")
                && frame.contains("CloudMath.offset"), frame.toString());
        assertTrue(calls(SYSTEM, "captureAtmosphere").contains("AtmosphereFrame.withClouds"), calls(SYSTEM, "captureAtmosphere").toString());
        List<String> post = calls(SYSTEM, "postProcess");
        assertTrue(post.contains("CloudUniforms.compute") && post.contains("VkPostProcess.cloudMapReady"), post.toString());
        //After 3b: the cloud size and count rebuild the noise map off the render thread, before the block is computed
        assertTrue(post.indexOf("VisualsRenderer.refreshCloudMap") >= 0 && post.indexOf("VisualsRenderer.refreshCloudMap") < post.indexOf("CloudUniforms.compute"), post.toString());
        List<String> refresh = calls(SYSTEM, "refreshCloudMap");
        assertTrue(refresh.contains("CompletableFuture.supplyAsync") && refresh.contains("VkPostProcess.updateCloudMap"), refresh.toString());
        assertTrue(post.lastIndexOf("FarClouds.set") > post.indexOf("CloudUniforms.compute"), post.toString());
        //P7h task 4 review: a dimension that draws its own clouds keeps them; a skipped or failed frame and a shutdown clear the flag
        assertTrue(frame.contains("VisualsRenderer$OwnClouds.get"), frame.toString());
        assertTrue(calls(SYSTEM + "$OwnClouds", "computeValue").contains("FarClouds.overrides"));
        assertTrue(count(post, "FrameFlags.resetForSkippedPost") >= 3, post.toString());
        assertTrue(calls("net/vulkanmod/visuals/render/FrameFlags", "resetForSkippedPost").contains("FarClouds.set"));
        assertEquals("FrameFlags.resetForSkippedPost", calls(SYSTEM, "close").get(0), calls(SYSTEM, "close").toString());
        assertTrue(calls("net/vulkanmod/mixin/visuals/LevelRendererCloudsMixin", "visuals$farClouds").contains("FarClouds.replacesVanilla"));
    }

    /** P7j design 3.2, rulings R12/R13: the sun's cascades are prepared before the break and drawn outside the pass, then the downloads. */
    @Test
    void theShadowMapsRideTheFrame() throws Exception {
        List<String> frame = calls(SYSTEM, "frame");
        assertTrue(inOrder(frame, List.of("ShadowFrame.of", "VisualsRenderer.prepareShadows", "VkContext.endMainPass", "VkUploadStream.commit",
                "VisualsRenderer.drawShadows", "VkDownloadStream.commit", "VkShadowMaps.afterDownloads", "VkContext.resumeMainPass")), frame.toString());
        assertTrue(frame.contains("VkShadowMaps.endOpenPass"), frame.toString());
        assertTrue(calls(SYSTEM, "prepareShadows").contains("VkShadowMaps.prepare"));
        assertTrue(calls(SYSTEM, "drawShadows").contains("VkShadowMaps.draw"));
        assertTrue(calls(SYSTEM, "onRecordingStarted").contains("VkShadowMaps.tick"));
        assertTrue(inOrder(calls(SYSTEM, "<init>"), List.of("DimensionType.hasSkyLight", "VisualsRenderer.createShadowMaps")), calls(SYSTEM, "<init>").toString());
    }

    /** Design 3.3, review focus 5: the lighting reads this frame's cascades, or the main depth in their place (PostInputs). */
    @Test
    void thePostProcessBindsTheShadowMapsOrTheMainDepth() throws Exception {
        List<String> post = calls(SYSTEM, "postProcess");
        assertTrue(inOrder(post, List.of("LightingUniforms.compute", "VkShadowMaps.near", "VkShadowMaps.far", "VkShadowMaps.close",
                "ShadowUniforms.compute", "VkPostProcess.prepare")), post.toString());
        assertTrue(post.contains("ShadowUniforms$Values.near") && post.contains("ShadowUniforms$Values.far") && post.contains("ShadowUniforms$Values.close"),
                post.toString());
        List<String> inputs = calls("net/vulkanmod/visuals/render/PostInputs", "viewA");
        assertTrue(inputs.contains("VkShadowMaps$Maps.viewA"), inputs.toString());
        //Review focus 4: the lightmap follows this frame's shadows, vanilla when they are not read
        assertTrue(post.contains("SunShadows.strength") && post.contains("SunShadows.set"), post.toString());
        List<String> shutdown = calls(SYSTEM, "free");
        assertTrue(shutdown.contains("VkShadowMaps.free"), shutdown.toString());
    }

    /** Task 5: a shadow failure ends the open pass and keeps the maps unread until the world is reloaded. */
    @Test
    void aShadowFailureTurnsTheShadowsOffForTheWorld() throws Exception {
        for (String method : List.of("prepareShadows", "drawShadows")) {
            MethodNode m = Bytecode.method(SYSTEM, method);
            assertTrue(m.tryCatchBlocks.stream().anyMatch(t -> "java/lang/RuntimeException".equals(t.type)), method + " catches");
            assertTrue(calls(SYSTEM, method).contains("VisualsRenderer.shadowsFailed"), method);
        }
        assertTrue(calls(SYSTEM, "drawShadows").contains("VkShadowMaps.endOpenPass"));
        boolean sets = false;
        for (AbstractInsnNode insn : Bytecode.method(SYSTEM, "shadowsFailed").instructions) {
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("shadowsFailed")
                    && Bytecode.previous(f).getOpcode() == Opcodes.ICONST_1) sets = true;
        }
        assertTrue(sets, "shadowsFailed = true");
        assertTrue(calls(SYSTEM, "postProcess").contains("VisualsRenderer.shadowsOn"));
        assertTrue(calls(SYSTEM, "frame").contains("VisualsRenderer.shadowsOn"));
    }

    /** The brief's frame order, with the extension calls (R13: afterDownloads once). */
    @Test
    void theFrameRunsInTheOrderOfTheExtensionApi() throws Exception {
        List<String> frame = calls(SYSTEM, "frame");
        assertTrue(inOrder(frame, List.of("VisualsRenderer$Plan.of", "LightingChoice.of", "ShadowFrame.of", "VisualsExtension.prepare",
                "VisualsRenderer.prepareShadows", "VkContext.endMainPass", "VkUploadStream.commit", "VisualsExtension.recordOffscreen",
                "VisualsRenderer.drawShadows", "VkDownloadStream.commit", "VisualsExtension.afterDownloads", "VkShadowMaps.afterDownloads",
                "VkContext.resumeMainPass", "VisualsExtension.drawIntoMain", "VisualsRenderer.lodInputs", "VkEdgeCover.draw",
                "VisualsRenderer.captureAtmosphere")), frame.toString());
        assertEquals(1, count(frame, "VisualsExtension.afterDownloads"), "ruling R13: once a frame");
        assertEquals(1, count(frame, "VisualsExtension.prepare"));
        assertEquals(1, count(frame, "VisualsExtension.recordOffscreen"));
        assertEquals(1, count(frame, "VisualsExtension.drawIntoMain"));
        assertTrue(calls(SYSTEM, "frame").contains("FrameContext.<init>"));
    }

    /** Task 3: without LODs the edge cover's stand-in depth is moved to the read layout outside the pass, before it resumes. */
    @Test
    void theEdgeCoverStandInIsRecordedWhileTheMainPassIsEnded() throws Exception {
        List<String> frame = calls(SYSTEM, "frame");
        int end = frame.indexOf("VkContext.endMainPass"), standIn = frame.indexOf("VkEdgeCover.recordStandIn"),
                resume = frame.indexOf("VkContext.resumeMainPass");
        assertTrue(end >= 0 && end < standIn && standIn < resume, frame.toString());
        assertTrue(frame.indexOf("VkEdgeCover.prepare") < end, frame.toString());
        assertTrue(resume < frame.indexOf("VkEdgeCover.draw"), frame.toString());
        assertTrue(calls(SYSTEM, "<init>").contains("VisualsRenderer.createEdgeCover"));
    }

    /** The F3 lines: the renderer's, then each extension's; the fork's debug screen shows them. */
    @Test
    void theDebugLinesReachTheExtensions() throws Exception {
        assertTrue(calls(SYSTEM, "addDebugInfo").contains("VisualsRenderer.addExtensionDebugInfo"));
        assertTrue(calls(SYSTEM, "addExtensionDebugInfo").contains("VisualsExtension.addDebugInfo"));
        assertTrue(calls(VISUALS, "addDebugInfo").contains("VisualsRenderer.addDebugInfo"));
        assertTrue(calls("net/vulkanmod/mixin/debug/DebugScreenOverlayM", "redirectList").contains("Visuals.addDebugInfo"));
    }
}
