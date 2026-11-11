package net.vulkanmod.visuals.vk.shader;

import net.vulkanmod.visuals.vk.shader.ShaderCatalog.Program;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog.Stage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The visuals' shader programs and the defines their Java call sites use (zero-to-one depth for Vulkan). Registered once, when
 * {@link ShaderCatalog#visuals()} is first used.
 */
final class VisualsPrograms {
    private VisualsPrograms() {
    }

    static ShaderCatalog register(ShaderCatalog c) {
        //Near sun shadow cascade: VulkanMod's vanilla terrain
        add(c, "vanillaShadow", defs(), c.vertex("shadow/vanilla.vert"), c.fragment("shadow/vanilla.frag"));
        //2026-10-29: the entities this frame drew into the entity shadow map (VkEntityShadows)
        add(c, "entityShadow", defs(), c.vertex("shadow/entity.vert"), c.fragment("shadow/entity.frag"));
        //Covers over built sections' sides next to ones VulkanMod has not built yet (VkEdgeCover)
        add(c, "edgeCover", defs(), c.vertex("post/edge_cover.vert"), c.fragment("post/edge_cover.frag"));
        //The full-screen post-process
        add(c, "postDepthCapture", defs(), c.compute("post/depth_capture.comp"));
        //2026-10-29: the main colour right after the translucent terrain (VkDepthCapture.captureColour)
        add(c, "postColourCapture", defs(), c.compute("post/colour_capture.comp"));
        add(c, "postLighting", defs("USE_ZERO_ONE_DEPTH", ""), c.compute("post/lighting.comp"));
        add(c, "postLightingBlur", defs(), c.compute("post/lighting_blur.comp"));
        //W10: the ambient occlusion (VkAmbientOcclusion): view distances, horizon search, blur, all at half resolution
        add(c, "postAoDepth", defs("USE_ZERO_ONE_DEPTH", ""), c.compute("post/ao_depth.comp"));
        add(c, "postAo", defs("USE_ZERO_ONE_DEPTH", ""), c.compute("post/ao.comp"));
        add(c, "postAoBlur", defs(), c.compute("post/ao_blur.comp"));
        add(c, "postSceneCopy", defs("USE_ZERO_ONE_DEPTH", ""), c.compute("post/scene_copy.comp"));
        //Session 8: with the picked colours' tints (ATMOSPHERE_TINTS), run only while one the shader applies is picked (VkPostProcess)
        add(c, "postSceneCopy.TINTED", defs("USE_ZERO_ONE_DEPTH", "", "ATMOSPHERE_TINTS", ""), c.compute("post/scene_copy.comp"));
        //Session 14: the frame's sun open share (SUN_OPEN_PASS: one invocation into the composite's buffer at 20), before the composite
        add(c, "postSceneCopy.SUN_OPEN", defs("USE_ZERO_ONE_DEPTH", "", "SUN_OPEN_PASS", ""), c.compute("post/scene_copy.comp"));
        add(c, "postReflection", defs("USE_ZERO_ONE_DEPTH", ""), c.compute("post/reflection.comp"));
        //Session 7: the reflection without the aurora and the realistic clouds, for the frames that draw neither (VkPostProcess)
        add(c, "postReflection.LEAN", defs("USE_ZERO_ONE_DEPTH", "", "REFLECT_LEAN", ""), c.compute("post/reflection.comp"));
        //Session 8: both with the picked colours' tints (ATMOSPHERE_TINTS), run only while one the shader applies is picked (VkPostProcess):
        // the tint arithmetic cost the composite and the reflection about 0.2 ms at 1440p together, even at the defaults
        add(c, "postReflection.TINTED", defs("USE_ZERO_ONE_DEPTH", "", "ATMOSPHERE_TINTS", ""), c.compute("post/reflection.comp"));
        add(c, "postReflection.LEAN.TINTED", defs("USE_ZERO_ONE_DEPTH", "", "REFLECT_LEAN", "", "ATMOSPHERE_TINTS", ""), c.compute("post/reflection.comp"));
        //Session 9 (PBR): the composite and the reflection lighting the terrain's LabPBR materials (PBR), run only while this frame's terrain
        // wrote them (VkPostProcess.preparePbr): with PBR off the builds above run unchanged
        add(c, "postSceneCopy.PBR", defs("USE_ZERO_ONE_DEPTH", "", "PBR", ""), c.compute("post/scene_copy.comp"));
        add(c, "postSceneCopy.TINTED.PBR", defs("USE_ZERO_ONE_DEPTH", "", "ATMOSPHERE_TINTS", "", "PBR", ""), c.compute("post/scene_copy.comp"));
        add(c, "postReflection.PBR", defs("USE_ZERO_ONE_DEPTH", "", "PBR", ""), c.compute("post/reflection.comp"));
        add(c, "postReflection.LEAN.PBR", defs("USE_ZERO_ONE_DEPTH", "", "REFLECT_LEAN", "", "PBR", ""), c.compute("post/reflection.comp"));
        add(c, "postReflection.TINTED.PBR", defs("USE_ZERO_ONE_DEPTH", "", "ATMOSPHERE_TINTS", "", "PBR", ""), c.compute("post/reflection.comp"));
        add(c, "postReflection.LEAN.TINTED.PBR", defs("USE_ZERO_ONE_DEPTH", "", "REFLECT_LEAN", "", "ATMOSPHERE_TINTS", "", "PBR", ""), c.compute("post/reflection.comp"));
        add(c, "bloom.PREFILTER", defs("USE_ZERO_ONE_DEPTH", "", "PREFILTER", ""), c.compute("post/bloom.comp"));
        add(c, "bloom.DOWN", defs("DOWN", ""), c.compute("post/bloom.comp"));
        add(c, "bloom.UP", defs("UP", ""), c.compute("post/bloom.comp"));
        add(c, "shafts.MASK", defs("USE_ZERO_ONE_DEPTH", "", "MASK", ""), c.compute("post/shafts.comp"));
        add(c, "shafts.BLUR", defs("USE_ZERO_ONE_DEPTH", ""), c.compute("post/shafts.comp"));
        add(c, "shafts.VL", defs("USE_ZERO_ONE_DEPTH", ""), c.compute("post/volumetric.comp"));
        add(c, "shafts.VL_BLUR", defs(), c.compute("post/volumetric_blur.comp"));
        //2026-11-09 (session 6): the shader weather overlay (VkPostProcess.weather), blended by postFinal or postBlit
        add(c, "postWeather", defs("USE_ZERO_ONE_DEPTH", ""), c.compute("post/weather.comp"));
        //2026-11-11: the same while snow and rain are both round (WEATHER_ROUND_ONLY: the pixel sprites compiled out)
        add(c, "postWeather.ROUND", defs("USE_ZERO_ONE_DEPTH", "", "WEATHER_ROUND_ONLY", ""), c.compute("post/weather.comp"));
        add(c, "postFinal", defs("USE_ZERO_ONE_DEPTH", ""),c.vertex("post/fullscreen.vert"), c.fragment("post/post_final.frag"));
        //SMAA after the tone mapping (the final colour into an image, three passes, a blit into the main pass) and the TAA before it
        add(c, "postLdr", defs(), c.compute("post/post_ldr.comp"));
        add(c, "postTaa", defs("USE_ZERO_ONE_DEPTH", ""), c.compute("post/post_taa.comp"));
        add(c, "smaaEdges", defs(), c.compute("post/smaa_edges.comp"));
        add(c, "smaaWeights", defs(), c.compute("post/smaa_weights.comp"));
        add(c, "smaaBlend", defs(), c.compute("post/smaa_blend.comp"));
        add(c, "postBlit", defs("USE_ZERO_ONE_DEPTH", ""), c.vertex("post/fullscreen.vert"), c.fragment("post/post_blit.frag"));
        //The LOD composite: the LOD target's colour and depth into the main pass
        add(c, "lodComposite", defs(), c.vertex("lod/lod_composite.vert"), c.fragment("lod/lod_composite.frag"));
        //Distant Horizons' opaque LOD terrain into the LOD target (DhExtension)
        add(c, "dhTerrain", defs(), c.vertex("dh/terrain.vert"), c.fragment("dh/terrain.frag"));
        //Distant Horizons' opaque LODs into the sun's shadow cascades, depth only (DhExtension.drawCasters)
        add(c, "dhShadow", defs(), c.vertex("dh/shadow.vert"), c.fragment("dh/shadow.frag"));
        //Frame generation (FG design 5): the world capture, the warp (splat, resolve, fill) and the composite into the swapchain image
        add(c, "fgCapture", defs("USE_ZERO_ONE_DEPTH", ""), c.compute("fg/fg_capture.comp"));
        add(c, "fgSplat", defs(), c.compute("fg/fg_splat.comp"));
        add(c, "fgResolve", defs(), c.compute("fg/fg_resolve.comp"));
        add(c, "fgFill", defs(), c.compute("fg/fg_fill.comp"));
        add(c, "fgCompose", defs(), c.vertex("post/fullscreen.vert"), c.fragment("fg/fg_compose.frag"));
        //FSR frame generation (FSR design 4.5): the depth and camera motion vectors FSR reads (FsrInputs), the HUD-less colour (FsrCapture)
        add(c, "fsrInputs", defs("USE_ZERO_ONE_DEPTH", ""), c.compute("fsr/fsr_inputs.comp"));
        add(c, "fsrHudless", defs(), c.compute("fsr/fsr_hudless.comp"));
        return c;
    }

    private static void add(ShaderCatalog c, String name, Map<String, String> defines, Stage... stages) {
        c.add(name, new Program(name, List.of(stages), defines, Map.of()));
    }

    private static Map<String, String> defs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
