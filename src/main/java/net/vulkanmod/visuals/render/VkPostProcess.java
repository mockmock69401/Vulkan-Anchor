package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.VkBindings;
import net.vulkanmod.visuals.vk.VkComputeProgram;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGpuBuffer;
import net.vulkanmod.visuals.vk.VkGraphicsProgram;
import net.vulkanmod.visuals.vk.VkImage2D;
import net.vulkanmod.visuals.vk.VkGpuTimer;
import net.vulkanmod.visuals.vk.VkSamplers;
import net.vulkanmod.visuals.vk.VkUploadStream;
import net.vulkanmod.visuals.VisualsLog;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The visuals' full-screen post-process (designs 2026-10-10 P7b, P7c): after the level and before the hand, the main colour
 * is copied (Reforged's colour images cannot be a transfer source) into linear HDR with the atmosphere applied (the main
 * depth can only be read outside the main pass), the bloom chain and the light shafts are built in compute, and one draw
 * in the resumed main pass writes tone mapping, grading, bloom, shafts and vignette over the whole screen. Every compute
 * image is its own VkImage2D so each can be written (GENERAL) while the one before it is read (SHADER_READ_ONLY_OPTIMAL).
 * P7d: a half-resolution lighting pass before the copy, which applies it before the atmosphere; a depth-aware blur of its AO
 * (lighting_blur.comp, light image to blurred image) sits between them.
 * P7e-1: the lighting pass also reads vanilla's depth before and after its translucent terrain and the LOD depth before the LOD translucents.
 * P7e-2: with water reflections the copy writes a lit scene image and reflection.comp writes the scene from it.
 * W10: the ambient occlusion's half-resolution passes (VkAmbientOcclusion) after the lighting pass; the copy reads their result at 13.
 */
public final class VkPostProcess {
    /** Linear HDR before exposure (P7c 3.2). */
    static final int SCENE_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    static final int BLOOM_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    /** Storage and linear filtering are required for this format; the shafts use its red channel. */
    static final int SHAFT_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    /** Session 14 follow-up: the half-size clouds' march and aurora (and its view distance, up to 60000 blocks: rgba16f's range). */
    static final int SKY_HALF_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    static final int LIGHT_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    /**
     * The format of the LOD light attachment: each LOD fragment's light in the low byte (sky in the low nibble, block in the high) and,
     * 2026-10-19, its face in the high byte (PostMath.lodFaceNormal: 0 none, else the face index plus one). The stand-in matches it.
     */
    public static final int LOD_LIGHT_FORMAT = VK_FORMAT_R16_UINT;
    static final int PLANT_SUN_FORMAT = VK_FORMAT_R32_SFLOAT;//2026-10-15: r32f, a storage format every device has
    /** Over the whole screen, replacing it: no depth, no blending. */
    static final VkGraphicsProgram.State STATE = new VkGraphicsProgram.State(false, false, VK_COMPARE_OP_ALWAYS, VkGraphicsProgram.RGBA, false,
            VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP);
    // Compute: the written image at 0, the read one at 1, the main depth at 2 (copy, shaft mask, lighting), the LOD depth at 3 (copy,
    // lighting); the copy also reads the (blurred) lighting at 4 (P7d), the blur reads the lighting at 1 and writes at 0 with no loose uniforms; the copy's and the lighting's loose uniform block is set 1 binding 0. post_final.frag: the scene at 0, the bloom at 1, the shafts at 2; the lighting also reads vanilla's depth copies at 5 (before its translucent terrain) and 6 (after), the LOD opaque depth at 7 (P7e-1); the reflection pass writes at 0 and reads the lit scene at 1 and the depths at 2, 3 (P7e-2); the copy reads the cloud map at 5, the reflection at 4 (P7h); the lighting reads the sun's cascades at 8 and 9 (P7j); P7l: the lighting writes the sun visibility at 1; the copy reads the light volume at 6, 7, the lightmap at 8, the sun visibility at 9, the LOD light at 10; the reflection the volume at 5, 6, the sun visibility at 7, the LOD light at 8
    private static final int DST = 0, SRC = 1, MAIN_DEPTH = 2, LOD_DEPTH = 3, LIGHTING = 4,
    BEFORE_TRANSLUCENT = 5, AFTER_TRANSLUCENT = 6, LOD_OPAQUE = 7, LOOSE_UNIFORMS = 0, FINAL_SCENE = 0, FINAL_BLOOM = 1, FINAL_SHAFTS = 2,
    COPY_CLOUD_MAP = 5, REFLECTION_CLOUD_MAP = 4, SHADOW_NEAR = 8, SHADOW_FAR = 9,
    SUN_OUT = 1, LIGHTING_MAIN_COLOUR = 4, LIGHTING_PLANT_SUN = 10, COPY_PLANT_SUN = 12, COPY_LIGHT_DATA = 6, COPY_LIGHT_READY = 7, COPY_LIGHTMAP = 8, COPY_SUN = 9, COPY_LOD_LIGHT = 10, COPY_HELD_LIGHT = 11,
    REFLECTION_LIGHT_DATA = 5, REFLECTION_LIGHT_READY = 6, REFLECTION_SUN = 7, REFLECTION_LOD_LIGHT = 8,
    //2026-10-19: the lighting reads the LOD light (its face byte) at 15
    LIGHTING_LOD_LIGHT = 15,
    //Sun-step crossfade (2026-10-16): the lighting reads each cascade's second map at 11 and 12
    SHADOW_NEAR_B = 11, SHADOW_FAR_B = 12,
    //Texel crawl pass (2026-10-16): and the close cascade's two maps at 13 and 14
    SHADOW_CLOSE = 13, SHADOW_CLOSE_B = 14,
    //2026-10-29: and the entity shadow map at 16
    SHADOW_ENTITY = 16,
    //W10: the copy reads the ambient occlusion at 13
    COPY_AO = 13,
    //2026-10-23: the copy reads the terrain light at 14, the reflection at 9 (TerrainLightTarget, storage images in GENERAL)
    COPY_TERRAIN_LIGHT = 14, REFLECTION_TERRAIN_LIGHT = 9,
    //and the copy the albedo at 15
    COPY_TERRAIN_ALBEDO = 15,
    //2026-10-29: the copy reads the main colour right after the translucent terrain at 16 (VkDepthCapture.captureColour)
    COPY_COLOUR_AFTER = 16,
    //2026-11-09 (session 2b): and the depth before the translucent terrain at 17 (what lies behind the water's surface seen from below)
    COPY_BEFORE_TRANSLUCENT = 17,
    //2026-11-10 (session 9, PBR): the copy reads the terrain's material images at 18, 19, the reflection the normals at 10 (TerrainLightTarget)
    COPY_TERRAIN_NORMAL = 18, COPY_TERRAIN_SPECULAR = 19, REFLECTION_TERRAIN_NORMAL = 10,
    //2026-11-12 (session 14): the copy reads the frame's sun open share at 20 (postSceneCopy.SUN_OPEN writes it)
    COPY_SUN_OPEN = 20,
    //Session 14 follow-up: the copy flags its tiles with translucent particles at 21, which postSceneCopy.PARTICLES reads
    COPY_PARTICLE_TILES = 21,
    //Session 14 follow-up (Half-resolution Weather & Sky): the copy reads the half-size clouds and aurora at 22, 23 (.SKY_HALF writes them)
    COPY_SKY_HALF_CLOUD = 22, COPY_SKY_HALF_AURORA = 23,
    //2026-11-12 (shader roadmap session 15): the round moon's face (MoonFace, water.glsl moonShape), the copy at 24, the reflection at 11
    COPY_MOON_FACE = 24, REFLECTION_MOON_FACE = 11,
    //2026-11-13 (shader roadmap session 19): .HEAT writes the heat haze's offsets at 25; the final colour reads them at 4
    COPY_HEAT = 25, FINAL_HEAT = 4,
    //Session 19: the End galaxy's baked map (GalaxyMap) at 26
    COPY_GALAXY_MAP = 26,
    //2026-11-13: the reflection reads the frame's sun open share at 12 (sunOpenBuffer, written before the composite)
    REFLECTION_SUN_OPEN = 12,
    //2026-11-09: the lighting reads the terrain's albedo image at 17 for its water marks (TerrainLightTarget, GENERAL)
    LIGHTING_TERRAIN_ALBEDO = 17,
    //2026-11-09 (session 6): weather.comp writes the overlay at 0 and reads the depths at 1, 2, the precipitation map at 3, the light volume
    // at 4, 5, the lightmap at 6; post_final.frag reads the overlay at 3 (post_blit.frag at 1)
    WEATHER_OUT = 0, WEATHER_MAIN_DEPTH = 1, WEATHER_LOD_DEPTH = 2, WEATHER_MAP = 3, WEATHER_LIGHT_DATA = 4, WEATHER_LIGHT_READY = 5,
    WEATHER_LIGHTMAP = 6, FINAL_WEATHER = 3;
    /** 2026-11-09 (session 6): the weather overlay, premultiplied display colour and coverage (weather.comp). */
    static final int WEATHER_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    /** Session 14 follow-up: the composite's loose uniform slot telling it to read the half-size clouds and aurora (halfParams). */
    static final int COPY_HALF_SLOT = 61;
    /**
     * Session 14 follow-up: the aurora from the half-size pass too. Off: at 1280x720 (drawn at 640x360) its march's dither came up as curved
     * bands (one direction a texel) or a diagonal hatching (two) that TAA does not settle, so the aurora stays at full size; the half-size
     * aurora (rays faded where finer than a texel, two directions) is kept for work on it behind this flag.
     */
    static final boolean HALF_AURORA = Boolean.getBoolean("vulkanmod.debug.halfAurora");
    /**
     * 2026-11-12 (session 14 follow-up, Half-resolution Weather & Sky, VisualsConfig.halfResEffects): the shader weather drawn at half the
     * size into weatherHalf and brought up to weatherImage (postWeatherUp), and the far clouds and the aurora marched at half the size into
     * skyHalfCloud (postSceneCopy.SKY_HALF, .TINTED.SKY_HALF) for the composite to read; the aurora stays at full size (HALF_AURORA). On an
     * AMD iGPU at 1280x720: rain 10.5 -> 2.9 ms, the realistic clouds 17.3 -> 7.9 ms. Off (the default): everything as before.
     */
    private VkImage2D skyHalfCloud, skyHalfAurora, weatherHalf;
    private final VkComputeProgram copySkyHalf, copyTintedSkyHalf, weatherUp;
    /**
     * 2026-11-13 (shader roadmap session 19): the Nether's heat haze: postSceneCopy.HEAT writes, at a quarter of the size, the offsets the final
     * colour reads the scene at (heat.glsl) while DimensionUniforms gives it a strength; bound always (not read otherwise).
     */
    private VkImage2D heatImage;
    private final VkComputeProgram copyHeat;
    private boolean heatDrawn;
    private DimensionUniforms.Values dimension = DimensionUniforms.off();
    /**
     * Session 19: the End galaxy's map (GalaxyMap, 32 MB), made the first time the galaxy is wanted and its bake is done, uploaded
     * GALAXY_ROWS rows a frame (a chunk whose frame was dropped goes again); until every chunk is up the composite draws no galaxy.
     * The moon's face stands in for it in the bindings before.
     */
    private VkImage2D galaxyMap;
    private long galaxySampler;
    private long[] galaxyChunks;
    private static final int GALAXY_ROWS = 256;
    private boolean halfOn, skyHalfOn;
    private static final int COMPUTE_AND_FRAGMENT = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    /**
     * The depth views the compute step reads (P7e-1 3.2). A view not valid this frame is the main depth view, a stand-in the
     * shaders do not read (the uniforms say which are valid).
     */
    public record Depths(long main, long lod, long beforeTranslucent, long afterTranslucent, long lodOpaque, long shadowNear, long shadowFar,
                         long shadowNearB, long shadowFarB, long shadowClose, long shadowCloseB) {
    }

    /** P7l design 3.3: the light volume's buffers (null: none), vanilla's lightmap view, the LOD light view (0: none); P7k: the held light (null: none). */
    public record LightInputs(VkGpuBuffer data, VkGpuBuffer ready, long lightmap, long lodLight, VkGpuBuffer held, long terrainLight, long terrainAlbedo,
                              long terrainNormal, long terrainSpecular) {
    }

    private final DeferredFree deferred;
    private final VkSmaa smaa;//P7i: null when it could not be built (the final draw is then as before)
    private boolean smaaDrawn, smaaFailed;
    private final VkTaa taa;//TAA pass (2026-10-15): null when it could not be built
    private boolean taaFailed, taaPrepared, taaDrawn;
    private VkImage2D drawnImage;//the anti-aliased colour post.draw blits (null: post_final draws)
    private Depths lastDepths;
    private final VkComputeProgram copy, prefilter, down, up, shaftMask, shaftBlur;
    private final VkComputeProgram lighting, lightingBlur, reflection;
    /**
     * 2026-11-10 (session 7): postReflection.LEAN (no aurora, no realistic clouds; null when it could not be built or its uniforms differ)
     * and whether this frame may use it (prepare: neither drawn this frame). A lean composite was tried too and measured no faster
     */
    private final VkComputeProgram reflectionLean;
    private boolean leanOn;
    /**
     * 2026-11-10 (session 8): postReflection.TINTED and .LEAN.TINTED (the picked colours' tints applied; null when not built or their
     * uniforms differ: the reflection then keeps the default colours) and whether this frame needs them (prepare: a fog, rainy sky, moon or
     * aurora colour the reflection applies is picked).
     */
    private final VkComputeProgram reflectionTinted, reflectionLeanTinted;
    private boolean tintedOn;
    /** Session 8: postSceneCopy.TINTED (null: not built) and whether this frame needs it (a fog, rainy sky, moon, aurora, water, torch light or (session 12) star colour picked). */
    private final VkComputeProgram copyTinted;
    private boolean copyTintedOn;
    /**
     * 2026-11-10 (session 9): the PBR builds (PBR defined: the terrain's materials lit, pbr.glsl; null when not built) of the composite and
     * the reflection, and whether this frame's terrain wrote its materials (preparePbr): only then do they run, so with PBR off the passes
     * are the builds before it.
     */
    private final VkComputeProgram copyPbr, copyTintedPbr, reflectionPbr, reflectionLeanPbr, reflectionTintedPbr, reflectionLeanTintedPbr;
    /**
     * 2026-11-12 (shader roadmap session 14): postSceneCopy.SUN_OPEN works out the sun's open share (sunopen.glsl, one value a frame)
     * into sunOpenBuffer before the composite, which reads it; every pixel near the sun used to work it out. Null when not built: the
     * buffer is then filled with 1 (the sun open: the glow as before 8be40cc).
     */
    private final VkComputeProgram copySunOpen;
    private final VkGpuBuffer sunOpenBuffer;
    /**
     * 2026-11-12 (session 14 follow-up): postSceneCopy.PARTICLES and .TINTED.PARTICLES lay the translucent particles over their tile's
     * background after the composite, in the tiles it flagged in particleTiles (a uint per 8x8 workgroup, ensureSize); the composite did it
     * in the same pass until then, and its tile and barrier cost every pixel 0.7 ms (11% of the composite) on an AMD iGPU at 1280x720.
     */
    private final VkComputeProgram copyParticles, copyTintedParticles;
    private VkGpuBuffer particleTiles;
    private boolean pbrOn;
    private String reflectionUsed = "";
    //Volumetric light pass (2026-10-15): light shafts through the sun's cascades, into the shaft image
    private final VkComputeProgram volumetric, volumetricBlur;
    private final LooseUniformWriter volumetricUniforms;
    private final VkGpuBuffer volumetricUniformBuffer;
    private boolean volumetricOn;
    private final VkGraphicsProgram finalDraw;
    //2026-11-09 (session 6): the shader weather: its program (null when it could not be built: vanilla's weather stays), uniforms, the
    // precipitation map (PrecipitationMap, R32_UINT) and whether this frame drew the overlay
    private final VkComputeProgram weather;
    /** 2026-11-11: postWeather.ROUND (null: not built; the full pass then draws), dispatched while both kinds are round. */
    private final VkComputeProgram weatherRound;
    private boolean weatherRoundOnly;
    private final LooseUniformWriter weatherUniforms;
    private final VkGpuBuffer weatherUniformBuffer;
    private final VkImage2D precipitationMap;
    private long precipitationSerial = -1;
    private boolean weatherOn, weatherDrawn;
    private VkImage2D weatherImage;
    private final VkAmbientOcclusion ao;//W10
    private final LooseUniformWriter copyUniforms, prefilterUniforms, downUniforms, upUniforms, shaftMaskUniforms, shaftBlurUniforms, finalUniforms;
    private final LooseUniformWriter lightingUniforms, reflectionUniforms;
    /** 2026-11-09 (session 5): this frame's sky slots (prepareSky), written into the reflection's after its own. */
    private CelestialUniforms.Values sky = CelestialUniforms.off();
    private final VkGpuBuffer copyUniformBuffer, lightingUniformBuffer, reflectionUniformBuffer;
    private final long nearest, linear;
    //P7h design 3.3: clouds.png as 256x256 RGBA8 (1 on cloud cells), uploaded once; a dropped upload frame is uploaded again
    private final VkImage2D cloudMap;
    //Session 15: the round moon's face with its mips (MoonFace), uploaded once (again if that frame was dropped), and its sampler
    private final VkImage2D moonFace;
    private final long moonSampler;
    private long moonFaceSerial = -1;
    //P7l design 3.3: bound in place of the light volume (16 bytes) and of the LOD light (1x1) when there is none
    private final VkGpuBuffer lightStandIn;
    private final VkImage2D lodLightStandIn;
    private byte[] cloudMapData;
    private boolean cloudMapReal;
    private long cloudMapSerial = -1;
    private boolean cloudMapWasReady;
    //2026-10-29: this frame's entity shadow map (0: none, the main depth stands in)
    private long entityShadowView;
    //2026-10-29: this frame's colour right after the translucent terrain (0: none, the main colour stands in: nothing differs)
    private long colourAfterView;
    private VkImage2D scene, sceneLit, shaftMaskImage, shaftImage, lightImage, lightBlurImage, sunImage, plantSunImage;
    private VkImage2D[] bloom;
    private int width, height;
    private boolean shaftsOn, lightingOn, reflectionOn;

    public VkPostProcess(DeferredFree deferred) {
        this.deferred = deferred;
        VkComputeProgram copy = null, prefilter = null, down = null, up = null, shaftMask = null, shaftBlur = null;
        VkGraphicsProgram finalDraw = null;
        VkComputeProgram lighting = null, lightingBlur = null, reflection = null, volumetric = null, volumetricBlur = null;
        VkGpuBuffer copyUniformBuffer = null, lightingUniformBuffer = null, reflectionUniformBuffer = null, volumetricUniformBuffer = null;
        VkImage2D cloudMap = null, lodLightStandIn = null, moonFace = null;
        VkGpuBuffer lightStandIn = null, sunOpenBuffer = null;
        VkAmbientOcclusion ao = null;
        long nearest = 0, linear = 0, moonSampler = 0;
        try {
            copy = VkComputeProgram.of("postSceneCopy");
            prefilter = VkComputeProgram.of("bloom.PREFILTER");
            down = VkComputeProgram.of("bloom.DOWN");
            up = VkComputeProgram.of("bloom.UP");
            shaftMask = VkComputeProgram.of("shafts.MASK");
            shaftBlur = VkComputeProgram.of("shafts.BLUR");
            finalDraw = VkGraphicsProgram.of("postFinal", STATE);
            nearest = VkSamplers.nearestClamp();
            linear = VkSamplers.linearClamp();
            cloudMap = VkImage2D.create(VK_FORMAT_R8G8B8A8_UNORM, CloudMath.MAP, CloudMath.MAP, 1, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            MoonFace face = MoonFace.get();
            moonFace = VkImage2D.create(VK_FORMAT_R8G8B8A8_UNORM, face.size, face.size, face.levels.length, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            moonSampler = VkSamplers.linearMipClamp();
            lightStandIn = VkGpuBuffer.device(16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            sunOpenBuffer = VkGpuBuffer.device(16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            lodLightStandIn = VkImage2D.create(LOD_LIGHT_FORMAT, 1, 1, 1, VK_IMAGE_USAGE_SAMPLED_BIT);
            this.copyUniforms = new LooseUniformWriter(copy.meta());
            if (this.copyUniforms.inUniformBuffer()) {
                copyUniformBuffer = VkGpuBuffer.device(this.copyUniforms.buffer().remaining(), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            }
            lighting = VkComputeProgram.of("postLighting");
            this.lightingUniforms = new LooseUniformWriter(lighting.meta());
            if (this.lightingUniforms.inUniformBuffer()) {
                lightingUniformBuffer = VkGpuBuffer.device(this.lightingUniforms.buffer().remaining(), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            }
            lightingBlur = VkComputeProgram.of("postLightingBlur");
            if (lightingBlur.meta().pushSize() != 0) throw new IllegalStateException("postLightingBlur: expected no loose uniforms");
            reflection = VkComputeProgram.of("postReflection");
            this.reflectionUniforms = new LooseUniformWriter(reflection.meta());
            if (this.reflectionUniforms.inUniformBuffer()) {
                reflectionUniformBuffer = VkGpuBuffer.device(this.reflectionUniforms.buffer().remaining(), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            }
            volumetric = VkComputeProgram.of("shafts.VL");
            volumetricBlur = VkComputeProgram.of("shafts.VL_BLUR");
            if (volumetricBlur.meta().pushSize() != 0) throw new IllegalStateException("shafts.VL_BLUR: expected no loose uniforms");
            this.volumetricUniforms = new LooseUniformWriter(volumetric.meta());
            if (this.volumetricUniforms.inUniformBuffer()) {
                volumetricUniformBuffer = VkGpuBuffer.device(this.volumetricUniforms.buffer().remaining(), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            }
            this.prefilterUniforms = pushOnly(new LooseUniformWriter(prefilter.meta()), "bloom.PREFILTER");
            this.downUniforms = pushOnly(new LooseUniformWriter(down.meta()), "bloom.DOWN");
            this.upUniforms = pushOnly(new LooseUniformWriter(up.meta()), "bloom.UP");
            this.shaftMaskUniforms = pushOnly(new LooseUniformWriter(shaftMask.meta()), "shafts.MASK");
            this.shaftBlurUniforms = pushOnly(new LooseUniformWriter(shaftBlur.meta()), "shafts.BLUR");
            this.finalUniforms = pushOnly(finalDraw.looseUniforms(), "postFinal");
            ao = new VkAmbientOcclusion();
        } catch (RuntimeException | LinkageError e) {
            //The render system frees only what its fields hold; nothing here is assigned yet
            for (VkComputeProgram p : new VkComputeProgram[]{copy, prefilter, down, up, shaftMask, shaftBlur, lighting, lightingBlur, reflection, volumetric, volumetricBlur}) if (p != null) p.free();
            if (volumetricUniformBuffer != null) volumetricUniformBuffer.free();
            if (finalDraw != null) finalDraw.free();
            if (copyUniformBuffer != null) copyUniformBuffer.free();
            if (lightingUniformBuffer != null) lightingUniformBuffer.free();
            if (reflectionUniformBuffer != null) reflectionUniformBuffer.free();
            if (nearest != 0) VkSamplers.free(nearest);
            if (linear != 0) VkSamplers.free(linear);
            if (cloudMap != null) cloudMap.free();
            if (moonFace != null) moonFace.free();
            if (moonSampler != 0) VkSamplers.free(moonSampler);
            if (lightStandIn != null) lightStandIn.free();
            if (sunOpenBuffer != null) sunOpenBuffer.free();
            if (lodLightStandIn != null) lodLightStandIn.free();
            if (ao != null) ao.free();
            throw e;
        }
        this.copy = copy;
        this.prefilter = prefilter;
        this.down = down;
        this.up = up;
        this.shaftMask = shaftMask;
        this.shaftBlur = shaftBlur;
        this.finalDraw = finalDraw;
        this.copyUniformBuffer = copyUniformBuffer;
        this.lighting = lighting;
        this.lightingBlur = lightingBlur;
        this.lightingUniformBuffer = lightingUniformBuffer;
        this.reflection = reflection;
        this.reflectionUniformBuffer = reflectionUniformBuffer;
        this.reflectionLean = lean("postReflection", reflection);
        this.reflectionTinted = variant("postReflection.TINTED", reflection);
        this.copyTinted = variant("postSceneCopy.TINTED", copy);
        this.reflectionLeanTinted = variant("postReflection.LEAN.TINTED", reflection);
        this.copyPbr = variant("postSceneCopy.PBR", copy);
        this.copyTintedPbr = variant("postSceneCopy.TINTED.PBR", copy);
        this.copySunOpen = variant("postSceneCopy.SUN_OPEN", copy);
        this.copySkyHalf = variant("postSceneCopy.SKY_HALF", copy);
        this.copyHeat = variant("postSceneCopy.HEAT", copy);
        this.copyTintedSkyHalf = variant("postSceneCopy.TINTED.SKY_HALF", copy);
        this.copyParticles = variant("postSceneCopy.PARTICLES", copy);
        this.copyTintedParticles = variant("postSceneCopy.TINTED.PARTICLES", copy);
        this.sunOpenBuffer = sunOpenBuffer;
        this.reflectionPbr = variant("postReflection.PBR", reflection);
        this.reflectionLeanPbr = variant("postReflection.LEAN.PBR", reflection);
        this.reflectionTintedPbr = variant("postReflection.TINTED.PBR", reflection);
        this.reflectionLeanTintedPbr = variant("postReflection.LEAN.TINTED.PBR", reflection);
        this.volumetric = volumetric;
        this.volumetricBlur = volumetricBlur;
        this.volumetricUniformBuffer = volumetricUniformBuffer;
        this.nearest = nearest;
        this.linear = linear;
        this.cloudMap = cloudMap;
        this.moonFace = moonFace;
        this.moonSampler = moonSampler;
        this.lightStandIn = lightStandIn;
        this.lodLightStandIn = lodLightStandIn;
        this.ao = ao;
        VkSmaa smaa = null;
        try {
            smaa = new VkSmaa();
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.warn("Visuals anti-aliasing (SMAA) not created, the final colour is drawn without it: " + e);
        }
        this.smaa = smaa;
        VkTaa taa = null;
        if (smaa != null) {
            try {
                taa = new VkTaa();
            } catch (RuntimeException | LinkageError e) {
                VisualsLog.warn("Visuals TAA not created, the final colour is drawn without it: " + e);
            }
        }
        this.taa = taa;
        VkComputeProgram weather = null;
        LooseUniformWriter weatherUniforms = null;
        VkGpuBuffer weatherUniformBuffer = null;
        VkImage2D precipitationMap = null;
        try {
            weather = VkComputeProgram.of("postWeather");
            weatherUniforms = new LooseUniformWriter(weather.meta());
            if (weatherUniforms.inUniformBuffer()) {
                weatherUniformBuffer = VkGpuBuffer.device(weatherUniforms.buffer().remaining(), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            }
            precipitationMap = VkImage2D.create(VK_FORMAT_R32_UINT, PrecipitationMap.SIZE, PrecipitationMap.SIZE, 1,
                    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        } catch (RuntimeException | LinkageError e) {
            if (weather != null) weather.free();
            if (weatherUniformBuffer != null) weatherUniformBuffer.free();
            if (precipitationMap != null) precipitationMap.free();
            weather = null;
            weatherUniforms = null;
            weatherUniformBuffer = null;
            precipitationMap = null;
            VisualsLog.warn("Visuals shader weather not created, vanilla's rain and snow stay: " + e);
        }
        this.weather = weather;
        this.weatherRound = weather != null ? variant("postWeather.ROUND", weather) : null;
        this.weatherUp = optional("postWeatherUp");
        this.weatherUniforms = weatherUniforms;
        this.weatherUniformBuffer = weatherUniformBuffer;
        this.precipitationMap = precipitationMap;
    }

    /** 2026-11-09 (session 6): whether the shader weather can be drawn (its program was built). */
    public boolean weatherAvailable() {
        return this.weather != null;
    }

    /**
     * 2026-11-09 (session 6): before the upload commit, the weather overlay's values (off: nothing dispatched, the final draw blends
     * nothing) with the light volume's block, and the precipitation map's texels to upload (null: unchanged since the last upload; see
     * precipitationMapStale).
     */
    public void prepareWeather(VkUploadStream upload, WeatherUniforms.Values values, LightVolumeUniforms.Values light, int[] map) {
        this.weatherOn = false;
        if (this.weather == null || !values.on()) return;
        if (map != null) {
            ByteBuffer pixels = MemoryUtil.memAlloc(map.length * 4).order(java.nio.ByteOrder.nativeOrder());
            try {
                pixels.asIntBuffer().put(map);
                upload.uploadImage(this.precipitationMap, 0, 0, 0, PrecipitationMap.SIZE, PrecipitationMap.SIZE, MemoryUtil.memAddress(pixels));
            } finally {
                MemoryUtil.memFree(pixels);
            }
            this.precipitationSerial = VkFrames.serial();
        } else if (this.precipitationMapStale()) {
            return;
        }
        values.write(this.weatherUniforms);
        light.write(this.weatherUniforms, WeatherUniforms.WEATHER_FIRST);
        if (this.weatherUniformBuffer != null) upload.upload(this.weatherUniformBuffer, 0, this.weatherUniforms.buffer());
        this.weatherOn = true;
        this.weatherRoundOnly = values.roundOnly();
    }

    /** 2026-11-09 (session 6): whether the precipitation map must be sent again (none yet, or its upload's frame was dropped). */
    public boolean precipitationMapStale() {
        return this.precipitationSerial < 0 || VkFrames.isDropped(this.precipitationSerial);
    }

    /** 2026-11-09 (session 6): whether this frame drew the weather overlay (the final draw blends it). */
    public boolean weatherDrawn() {
        return this.weatherDrawn;
    }

    /** Every dispatch but the copy and the draw push their values; only the copy has an uploaded uniform block. */
    private static LooseUniformWriter pushOnly(LooseUniformWriter writer, String program) {
        if (writer == null || writer.inUniformBuffer()) throw new IllegalStateException(program + ": loose uniforms must fit in push constants");
        return writer;
    }

    /** Recreates the images at a new size; the old ones are freed once the frames that may use them have completed. */
    public void ensureSize(int width, int height) {
        if (this.scene != null && this.width == width && this.height == height) return;
        if (this.scene != null) {
            VkImage2D[] old = this.images();
            VkGpuBuffer oldTiles = this.particleTiles;
            this.deferred.retire(() -> {
                destroy(old);
                if (oldTiles != null) oldTiles.free();
            }, VkFrames.serial());
            this.particleTiles = null;
            this.weatherHalf = null;
            this.skyHalfCloud = null;
            this.skyHalfAurora = null;
            this.heatImage = null;
            this.scene = null;
            this.sceneLit = null;
            this.bloom = null;
            this.shaftMaskImage = null;
            this.shaftImage = null;
            this.lightImage = null;
            this.lightBlurImage = null;
            this.sunImage = null;
            this.plantSunImage = null;
            this.weatherImage = null;
        }
        int[][] sizes = PostMath.bloomSizes(width, height);
        VkImage2D[] bloom = new VkImage2D[sizes.length];
        VkImage2D scene = null, sceneLit = null, shaftMaskImage = null, shaftImage = null, lightImage = null, lightBlurImage = null, sunImage = null, plantSunImage = null, weatherImage = null;
        VkImage2D weatherHalf = null, skyHalfCloud = null, skyHalfAurora = null, heatImage = null;
        try {
            int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
            scene = VkImage2D.create(SCENE_FORMAT, width, height, 1, usage);
            sceneLit = VkImage2D.create(SCENE_FORMAT, width, height, 1, usage);
            for (int i = 0; i < sizes.length; i++) bloom[i] = VkImage2D.create(BLOOM_FORMAT, sizes[i][0], sizes[i][1], 1, usage);
            shaftMaskImage = VkImage2D.create(SHAFT_FORMAT, sizes[0][0], sizes[0][1], 1, usage);
            shaftImage = VkImage2D.create(SHAFT_FORMAT, sizes[0][0], sizes[0][1], 1, usage);
            lightImage = VkImage2D.create(LIGHT_FORMAT, (width + 1) / 2, (height + 1) / 2, 1, usage);
            lightBlurImage = VkImage2D.create(LIGHT_FORMAT, (width + 1) / 2, (height + 1) / 2, 1, usage);
            //P7l design 3.3: the sun visibility, on the light image's texel grid (the copy reads both with one coordinate)
            sunImage = VkImage2D.create(LIGHT_FORMAT, (width + 1) / 2, (height + 1) / 2, 1, usage);
            //2026-10-15: plant pixels' own sun visibility, at full resolution
            plantSunImage = VkImage2D.create(PLANT_SUN_FORMAT, width, height, 1, usage);
            //2026-11-09 (session 6): the weather overlay, at full resolution (the streaks are a few pixels wide)
            weatherImage = VkImage2D.create(WEATHER_FORMAT, width, height, 1, usage);
            //Session 14 follow-up: the half-size weather, clouds and aurora (Half-resolution Weather & Sky; bound always)
            weatherHalf = VkImage2D.create(WEATHER_FORMAT, (width + 1) / 2, (height + 1) / 2, 1, usage);
            skyHalfCloud = VkImage2D.create(SKY_HALF_FORMAT, (width + 1) / 2, (height + 1) / 2, 1, usage);
            skyHalfAurora = VkImage2D.create(SKY_HALF_FORMAT, (width + 1) / 2, (height + 1) / 2, 1, usage);
            //Session 19: the heat haze's offsets, at a quarter of the size (bound always)
            heatImage = VkImage2D.create(SKY_HALF_FORMAT, (width + 3) / 4, (height + 3) / 4, 1, usage);
        } catch (RuntimeException e) {
            destroy(concat(new VkImage2D[]{scene, sceneLit, shaftMaskImage, shaftImage, lightImage, lightBlurImage, sunImage, plantSunImage, weatherImage,
                    weatherHalf, skyHalfCloud, skyHalfAurora, heatImage}, bloom));
            throw e;
        }
        this.scene = scene;
        this.sceneLit = sceneLit;
        this.bloom = bloom;
        this.shaftMaskImage = shaftMaskImage;
        this.shaftImage = shaftImage;
        this.lightImage = lightImage;
        this.lightBlurImage = lightBlurImage;
        this.sunImage = sunImage;
        this.plantSunImage = plantSunImage;
        this.weatherImage = weatherImage;
        this.weatherHalf = weatherHalf;
        this.skyHalfCloud = skyHalfCloud;
        this.skyHalfAurora = skyHalfAurora;
        this.heatImage = heatImage;
        //Session 14 follow-up: a flag per composite workgroup (the copy's dispatch, groups(width) x groups(height))
        this.particleTiles = VkGpuBuffer.device(4L * groups(width) * groups(height), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        this.width = width;
        this.height = height;
        this.ao.ensureSize(width, height, this.deferred);
        if (this.smaa != null) this.smaa.ensureSize(width, height, this.deferred);
        if (this.taa != null) this.taa.ensureSize(width, height, this.deferred);
        VisualsLog.info("Visuals post-process target " + width + "x" + height + ", bloom " + sizes.length + " levels");
    }

    private VkImage2D[] images() {
        return concat(new VkImage2D[]{this.scene, this.sceneLit, this.shaftMaskImage, this.shaftImage, this.lightImage, this.lightBlurImage, this.sunImage, this.plantSunImage, this.weatherImage,
                this.weatherHalf, this.skyHalfCloud, this.skyHalfAurora, this.heatImage}, this.bloom);
    }

    private static VkImage2D[] concat(VkImage2D[] fixed, VkImage2D[] rest) {
        VkImage2D[] all = new VkImage2D[fixed.length + (rest == null ? 0 : rest.length)];
        System.arraycopy(fixed, 0, all, 0, fixed.length);
        if (rest != null) System.arraycopy(rest, 0, all, fixed.length, rest.length);
        return all;
    }

    private static void destroy(VkImage2D[] images) {
        for (VkImage2D image : images) if (image != null) image.free();
    }

    public int bloomLevels() {
        return this.bloom == null ? 0 : this.bloom.length;
    }

    /** P7h design 3.3: clouds.png's mask (CloudMath.mask, MAP x MAP RGBA8) to upload; null when it could not be read: an empty map, clouds off. */
    public void queueCloudMap(byte[] rgba) {
        this.cloudMapReal = rgba != null;
        this.cloudMapData = rgba != null ? rgba : new byte[CloudMath.MAP * CloudMath.MAP * 4];
        this.cloudMapSerial = -1;
        this.cloudMapWasReady = false;
    }

    /**
     * 2026-11-09 (after session 3b): the same mask with a new noise map (the cloud size or count changed), uploaded like the first. The
     * map stays ready: the upload stream's barrier orders the copy after earlier frames' reads, and this frame reads the new map.
     */
    public void updateCloudMap(byte[] rgba) {
        if (!this.cloudMapReal || rgba == null || rgba.length != this.cloudMapData.length) return;
        this.cloudMapData = rgba;
        this.cloudMapSerial = -1;
    }

    /** P7h design 3.5: a real map whose upload frame has completed (not dropped); after 3b, once ready it stays ready through updateCloudMap. */
    public boolean cloudMapReady() {
        if (this.cloudMapReal && this.cloudMapWasReady) return true;
        this.cloudMapWasReady = this.cloudMapReal && this.cloudMapSerial >= 0 && VkFrames.completedSerial() >= this.cloudMapSerial && !VkFrames.isDropped(this.cloudMapSerial);
        return this.cloudMapWasReady;
    }

    /**
     * CPU side, before the upload commit that precedes compute(): the copy's atmosphere and lighting, the lighting pass's block,
     * the reflection pass's block, the sun position.
     */
    /** 2026-10-29: before compute(), this frame's colour right after the translucent terrain (0: none). */
    public void colourAfterTranslucent(long view) {
        this.colourAfterView = view;
    }

    /** 2026-11-13 (session 19): before prepare(), the End's galaxy and the Nether's heat haze (DimensionUniforms) for the composite. */
    public void prepareDimension(DimensionUniforms.Values dimension) {
        if (dimension.galaxy() > 0) {
            GalaxyMap.start();
            if (!this.galaxyUploaded()) dimension = dimension.withGalaxy(0);
        }
        this.dimension = dimension;
        dimension.write(this.copyUniforms);
    }

    /** Session 19: every chunk of the galaxy's map was uploaded in a frame that was not dropped. */
    private boolean galaxyUploaded() {
        if (this.galaxyChunks == null) return false;
        for (long serial : this.galaxyChunks) if (serial < 0 || VkFrames.isDropped(serial)) return false;
        return true;
    }

    /** Session 19: once the galaxy's map is baked, its image, then one chunk of rows a frame (again for a chunk in a dropped frame). */
    private void uploadGalaxy(VkUploadStream upload) {
        if (GalaxyMap.ready() == null) return;
        byte[] map = GalaxyMap.ready();
        if (this.galaxyMap == null) {
            this.galaxyMap = VkImage2D.create(VK_FORMAT_R8G8B8A8_UNORM, GalaxyMap.WIDTH, GalaxyMap.HEIGHT, 1,
                    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            this.galaxySampler = VkSamplers.linearClamp();
            this.galaxyChunks = new long[(GalaxyMap.HEIGHT + GALAXY_ROWS - 1) / GALAXY_ROWS];
            java.util.Arrays.fill(this.galaxyChunks, -1);
        }
        for (int i = 0; i < this.galaxyChunks.length; i++) {
            if (this.galaxyChunks[i] >= 0 && !VkFrames.isDropped(this.galaxyChunks[i])) continue;
            int y = i * GALAXY_ROWS, rows = Math.min(GALAXY_ROWS, GalaxyMap.HEIGHT - y);
            int bytes = GalaxyMap.WIDTH * rows * 4;
            ByteBuffer pixels = MemoryUtil.memAlloc(bytes);
            try {
                pixels.put(map, y * GalaxyMap.WIDTH * 4, bytes).flip();
                upload.uploadImage(this.galaxyMap, 0, 0, y, GalaxyMap.WIDTH, rows, MemoryUtil.memAddress(pixels));
            } finally {
                MemoryUtil.memFree(pixels);
            }
            this.galaxyChunks[i] = VkFrames.serial();
            return;
        }
    }

    /** 2026-11-09 (session 5): before prepare(), the discs' shape and the aurora (CelestialUniforms) for the composite and the reflection. */
    public void prepareSky(CelestialUniforms.Values sky) {
        this.sky = sky;
        sky.write(this.copyUniforms);
    }

    /**
     * 2026-11-10 (session 9): before prepare(), whether this frame's terrain wrote its PBR materials (the PBR builds run) and the
     * composite's PBR slot (PbrUniforms).
     */
    public void preparePbr(boolean on) {
        this.pbrOn = on && this.copyPbr != null;
        PbrUniforms.writeCopy(this.copyUniforms);
    }

    /** 2026-11-09 (session 2b): before prepare(), the composite's underwater slots (UnderwaterUniforms). */
    public void prepareUnderwater(UnderwaterUniforms.Values underwater) {
        underwater.write(this.copyUniforms);
    }

    /** 2026-10-29: before prepare(), this frame's entity shadow map (view 0 and entity off: none). */
    public void prepareEntityShadows(EntityShadowUniforms.Values entity, long view) {
        entity.write(this.lightingUniforms);
        this.entityShadowView = entity.on() ? view : 0;
    }

    public void prepare(VkUploadStream upload, AtmosphereUniforms.Values atmosphere, LightingUniforms.Values lighting, ReflectionUniforms.Values reflection,
                        CloudUniforms.Values clouds, ShadowUniforms.Values shadow, LightVolumeUniforms.Values light, HeldLightSystem held,
                        BloomFog.Values bloomFog, VolumetricLight.Values volumetric) {
        //P7h design 3.3: the cloud map goes up once (again if its frame was dropped); the copy and the reflection always bind it
        //VkUploadStream also re-records a dropped frame's copy; uploading again gives the map a serial of its own for cloudMapReady (same bytes)
        if (this.cloudMapData != null && (this.cloudMapSerial < 0 || VkFrames.isDropped(this.cloudMapSerial))) {
            ByteBuffer pixels = MemoryUtil.memAlloc(this.cloudMapData.length);
            try {
                pixels.put(this.cloudMapData).flip();
                upload.uploadImage(this.cloudMap, 0, 0, 0, CloudMath.MAP, CloudMath.MAP, MemoryUtil.memAddress(pixels));
            } finally {
                MemoryUtil.memFree(pixels);
            }
            this.cloudMapSerial = VkFrames.serial();
        }
        //Session 15: the moon's face, every level, once (5.6 MB through the stream)
        if (this.moonFaceSerial < 0 || VkFrames.isDropped(this.moonFaceSerial)) {
            MoonFace face = MoonFace.get();
            for (int level = 0; level < face.levels.length; level++) {
                byte[] bytes = face.levels[level];
                int n = Math.max(face.size >> level, 1);
                ByteBuffer pixels = MemoryUtil.memAlloc(bytes.length);
                try {
                    pixels.put(bytes).flip();
                    upload.uploadImage(this.moonFace, level, 0, 0, n, n, MemoryUtil.memAddress(pixels));
                } finally {
                    MemoryUtil.memFree(pixels);
                }
            }
            this.moonFaceSerial = VkFrames.serial();
        }
        //Session 19: the End galaxy's map, a chunk a frame while it is wanted
        if (this.dimension.galaxy() > 0 || (GalaxyMap.ready() != null && !this.galaxyUploaded())) this.uploadGalaxy(upload);
        if (this.smaa != null) this.smaa.prepare(upload);
        clouds.write(this.copyUniforms, CloudUniforms.COPY_FIRST);
        clouds.write(this.reflectionUniforms, CloudUniforms.REFLECTION_FIRST);
        atmosphere.write(this.copyUniforms);
        lighting.writeCopy(this.copyUniforms);
        shadow.writeCopy(this.copyUniforms);
        light.write(this.copyUniforms, LightVolumeUniforms.COPY_FIRST);
        held.writeCopy(this.copyUniforms, light.on() ? light.camera() : null);
        //Session 14 follow-up: Half-resolution Weather & Sky (the composite reads the half-size clouds and aurora while x is 1)
        this.halfOn = VisualsConfig.get().halfResEffects;
        this.skyHalfOn = this.halfOn && this.copySkyHalf != null;
        if (this.copyUniforms.has(COPY_HALF_SLOT)) this.copyUniforms.putVec4(COPY_HALF_SLOT, this.skyHalfOn ? 1 : 0, this.skyHalfOn && HALF_AURORA ? 1 : 0, 0, 0);
        if (this.copyUniformBuffer != null) upload.upload(this.copyUniformBuffer, 0, this.copyUniforms.buffer());
        lighting.writeLighting(this.lightingUniforms, light.terrain());
        shadow.write(this.lightingUniforms);
        shadow.writeSteps(this.lightingUniforms);
        shadow.writeClose(this.lightingUniforms);
        if (this.lightingUniformBuffer != null) upload.upload(this.lightingUniformBuffer, 0, this.lightingUniforms.buffer());
        reflection.write(this.reflectionUniforms);
        this.sky.writeReflection(this.reflectionUniforms);
        light.write(this.reflectionUniforms, LightVolumeUniforms.REFLECTION_FIRST);
        if (this.reflectionUniformBuffer != null) upload.upload(this.reflectionUniformBuffer, 0, this.reflectionUniforms.buffer());
        this.lightingOn = lighting.on();
        this.ao.prepare(upload, lighting);
        this.reflectionOn = reflection.on();
        this.leanOn = !this.sky.auroraOn() && !clouds.realistic();
        this.tintedOn = reflection.tinted() || this.sky.auroraTinted();
        this.copyTintedOn = atmosphere.tinted() || this.sky.tinted();
        atmosphere.writeShafts(this.shaftMaskUniforms);
        atmosphere.writeShafts(this.shaftBlurUniforms);
        bloomFog.write(this.prefilterUniforms);
        volumetric.write(this.volumetricUniforms);
        shadow.write(this.volumetricUniforms);
        if (this.volumetricUniformBuffer != null) upload.upload(this.volumetricUniformBuffer, 0, this.volumetricUniforms.buffer());
        this.volumetricOn = volumetric.on();
        this.shaftsOn = atmosphere.shafts() > 0;
    }

    /**
     * Outside a render pass, after ensureSize and prepare; the main colour and the depths in SHADER_READ_ONLY_OPTIMAL and
     * visible to compute.
     */
    public void compute(VkCommandBuffer cmd, long mainColourView, Depths depths, LightInputs light) {
        this.lastDepths = depths;
        //P7l design 3.3: the stand-in LOD light is read, never written; it moves to the read layout once
        if (this.lodLightStandIn.layout() != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            this.lodLightStandIn.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        }
        //Every dispatch binds valid light inputs: without a volume or an LOD draw the stand-ins (the uniforms say they are not read)
        VkGpuBuffer data = light.data() != null ? light.data() : this.lightStandIn;
        VkGpuBuffer ready = light.ready() != null ? light.ready() : this.lightStandIn;
        VkGpuBuffer held = light.held() != null ? light.held() : this.lightStandIn;
        long lodLight = light.lodLight() != 0 ? light.lodLight() : this.lodLightStandIn.view();
        VkGpuTimer.mark(cmd, "post.lighting");
        this.computeLighting(cmd, mainColourView, depths, lodLight, light.terrainAlbedo());
        VkGpuTimer.mark(cmd, "post.ao");
        this.ao.compute(cmd, mainColourView, depths.main(), depths.lod(), this.lightImage, this.sunImage, this.nearest);
        VkGpuTimer.mark(cmd, "post.copy");
        //P7e-2: with reflections the copy writes the lit scene and the reflection pass the scene; without, the copy writes the scene
        VkImage2D copyTarget = this.reflectionOn ? this.sceneLit : this.scene;
        copyTarget.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
        VkBindings copyBindings = new VkBindings().storageImage(DST, copyTarget.view()).sampledView(SRC, mainColourView, this.nearest)
                .sampledView(MAIN_DEPTH, depths.main(), this.nearest).sampledView(LOD_DEPTH, depths.lod(), this.nearest)
                .sampled(LIGHTING, this.lightBlurImage, this.nearest).sampled(COPY_CLOUD_MAP, this.cloudMap, this.nearest)
                .storage(COPY_LIGHT_DATA, data).storage(COPY_LIGHT_READY, ready).sampledView(COPY_LIGHTMAP, light.lightmap(), this.linear)
                .sampled(COPY_SUN, this.sunImage, this.nearest).sampledView(COPY_LOD_LIGHT, lodLight, this.nearest).storage(COPY_HELD_LIGHT, held)
                .sampled(COPY_PLANT_SUN, this.plantSunImage, this.nearest).sampled(COPY_AO, this.ao.output(), this.nearest)
                .storageImage(COPY_TERRAIN_LIGHT, light.terrainLight()).storageImage(COPY_TERRAIN_ALBEDO, light.terrainAlbedo())
                .sampledView(COPY_COLOUR_AFTER, this.colourAfterView != 0 ? this.colourAfterView : mainColourView, this.nearest)
                .sampledView(COPY_BEFORE_TRANSLUCENT, depths.beforeTranslucent(), this.nearest)
                .storageImage(COPY_TERRAIN_NORMAL, light.terrainNormal()).storageImage(COPY_TERRAIN_SPECULAR, light.terrainSpecular())
                .storage(COPY_SUN_OPEN, this.sunOpenBuffer).storage(COPY_PARTICLE_TILES, this.particleTiles)
                .storageImage(COPY_SKY_HALF_CLOUD, this.skyHalfCloud.view()).storageImage(COPY_SKY_HALF_AURORA, this.skyHalfAurora.view())
                .sampled(COPY_MOON_FACE, this.moonFace, this.moonSampler).storageImage(COPY_HEAT, this.heatImage.view())
                .sampled(COPY_GALAXY_MAP, this.galaxyMap != null ? this.galaxyMap : this.moonFace, this.galaxyMap != null ? this.galaxySampler : this.moonSampler);
        if (this.copyUniformBuffer != null) copyBindings.uniform(LOOSE_UNIFORMS, this.copyUniformBuffer);
        //Session 14 follow-up: no tile flagged yet (the composite flags its tiles with translucent particles), after the last frame's
        // .PARTICLES pass read them
        memoryBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, 0);
        vkCmdFillBuffer(cmd, this.particleTiles.handle(), 0, VK_WHOLE_SIZE, 0);
        //Session 14 follow-up: the half-size clouds and aurora (bound in every build, written only with Half-resolution Weather & Sky on)
        this.skyHalfCloud.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
        this.skyHalfAurora.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
        //Session 19: the heat haze's offsets (bound to every composite build as a storage image, then read by the final colour)
        this.heatImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
        if (this.skyHalfOn) {
            VkComputeProgram skyHalf = this.copyTintedOn && this.copyTintedSkyHalf != null ? this.copyTintedSkyHalf : this.copySkyHalf;
            skyHalf.dispatchWithPush(cmd, copyBindings, this.copyUniformBuffer == null ? this.copyUniforms.buffer() : null,
                    groups(this.skyHalfCloud.width), groups(this.skyHalfCloud.height), 1);
            memoryBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_READ_BIT);
        }
        memoryBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        //Session 14: the frame's sun open share first (one group), made visible to the composite's reads
        if (this.copySunOpen != null) {
            this.copySunOpen.dispatchWithPush(cmd, copyBindings, this.copyUniformBuffer == null ? this.copyUniforms.buffer() : null, 1, 1, 1);
            bufferBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT);
        } else {
            vkCmdFillBuffer(cmd, this.sunOpenBuffer.handle(), 0, 16, Float.floatToRawIntBits(1f));
            bufferBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        }
        VkComputeProgram copyProgram = this.copyTintedOn && this.copyTinted != null ? this.copyTinted : this.copy;
        if (this.pbrOn) {
            //2026-11-12 (shader roadmap session 14): the TINTED build whatever the colours. At the default colours its tints are exactly 1
            // (the same picture), and with the PBR lighting compiled in it runs faster than the plain build on the RTX 5070 (2560x1440,
            // Embrace Pixels, two alternating pairs: composite -0.12..-0.44 ms on land and at night, the same on the sea); the plain
            // PBR build is kept for a driver that cannot build the tinted one
            copyProgram = this.copyTintedPbr != null ? this.copyTintedPbr : this.copyPbr;
        }
        copyProgram.dispatchWithPush(cmd, copyBindings, this.copyUniformBuffer == null ? this.copyUniforms.buffer() : null,
                groups(this.width), groups(this.height), 1);
        //Session 14 follow-up: the translucent particles over their tiles' background, in place, with the composite's colours (tinted or not:
        // the tinted builds' tints are exactly 1 at the default colours); same groups, so the same tiles
        boolean tinted = copyProgram == this.copyTinted || copyProgram == this.copyTintedPbr;
        VkComputeProgram particles = tinted && this.copyTintedParticles != null ? this.copyTintedParticles : this.copyParticles;
        if (particles != null) {
            memoryBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            particles.dispatchWithPush(cmd, copyBindings, this.copyUniformBuffer == null ? this.copyUniforms.buffer() : null,
                    groups(this.width), groups(this.height), 1);
        }
        toRead(cmd, copyTarget);
        //Session 19: the Nether's heat haze's offsets (reads the main colour's marks and the depth only); the image is read by the final
        // colour only while they were written this frame, but always bound, so it is kept in the read layout
        this.heatDrawn = false;
        if (this.copyHeat != null && this.dimension.heatOn()) {
            VkGpuTimer.mark(cmd, "post.heat");
            this.copyHeat.dispatchWithPush(cmd, copyBindings, this.copyUniformBuffer == null ? this.copyUniforms.buffer() : null,
                    groups(this.heatImage.width), groups(this.heatImage.height), 1);
            this.heatDrawn = true;
        }
        toRead(cmd, this.heatImage);
        VkGpuTimer.mark(cmd, "post.reflection");
        this.computeReflection(cmd, depths, data, ready, lodLight, light.terrainLight(), light.terrainNormal());
        VkGpuTimer.mark(cmd, "post.bloom");
        this.bloom[0].transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT);
        //The bloom prefilter also reads the main depth
        this.prefilterUniforms.putVec4(PostUniforms.BLOOM_PARAMS, 1f / this.scene.width, 1f / this.scene.height, 0, 0);
        this.prefilter.dispatchWithPush(cmd, new VkBindings().storageImage(DST, this.bloom[0].view()).sampled(SRC, this.scene, this.nearest)
                        .sampledView(MAIN_DEPTH, depths.main(), this.nearest), this.prefilterUniforms.buffer(),
                groups(this.bloom[0].width), groups(this.bloom[0].height), 1);
        for (int i = 1; i < this.bloom.length; i++) {
            toRead(cmd, this.bloom[i - 1]);
            this.bloom[i].transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT);
            this.bloomStep(cmd, this.down, this.downUniforms, this.bloom[i - 1], this.linear, this.bloom[i]);
        }
        toRead(cmd, this.bloom[this.bloom.length - 1]);
        for (int i = this.bloom.length - 2; i >= 0; i--) {
            //Keeps the downsample: the upsample adds the smaller level onto it
            this.bloom[i].transition(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            this.bloomStep(cmd, this.up, this.upUniforms, this.bloom[i + 1], this.linear, this.bloom[i]);
            toRead(cmd, this.bloom[i]);
        }
        VkGpuTimer.mark(cmd, "post.shafts");
        this.computeShafts(cmd, depths);
        VkGpuTimer.mark(cmd, "post.weather");
        this.computeWeather(cmd, depths, data, ready, light.lightmap());
    }

    /**
     * 2026-11-09 (session 6): the shader weather overlay (weather.comp) while it rains or snows with the setting on; else the overlay only
     * moves to the read layout the final draw binds it in (and is not read).
     */
    private void computeWeather(VkCommandBuffer cmd, Depths depths, VkGpuBuffer data, VkGpuBuffer ready, long lightmap) {
        this.weatherDrawn = false;
        if (!this.weatherOn) {
            this.weatherImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, COMPUTE_AND_FRAGMENT, COMPUTE_AND_FRAGMENT,
                    VK_ACCESS_SHADER_READ_BIT);
            return;
        }
        this.weatherImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT);
        //Session 14 follow-up (Half-resolution Weather & Sky): at half the size, then brought up
        boolean half = this.halfWeather();
        VkImage2D target = half ? this.weatherHalf : this.weatherImage;
        if (half) this.weatherHalf.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT);
        VkBindings bindings = new VkBindings().storageImage(WEATHER_OUT, target.view()).sampledView(WEATHER_MAIN_DEPTH, depths.main(), this.nearest)
                .sampledView(WEATHER_LOD_DEPTH, depths.lod(), this.nearest).sampled(WEATHER_MAP, this.precipitationMap, this.nearest)
                .storage(WEATHER_LIGHT_DATA, data).storage(WEATHER_LIGHT_READY, ready).sampledView(WEATHER_LIGHTMAP, lightmap, this.linear);
        if (this.weatherUniformBuffer != null) bindings.uniform(LOOSE_UNIFORMS, this.weatherUniformBuffer);
        VkComputeProgram program = this.weatherRoundOnly && this.weatherRound != null ? this.weatherRound : this.weather;
        program.dispatchWithPush(cmd, bindings, this.weatherUniformBuffer == null ? this.weatherUniforms.buffer() : null,
                groups(target.width), groups(target.height), 1);
        if (half) {
            toRead(cmd, this.weatherHalf);
            this.weatherUp.dispatchWithPush(cmd, new VkBindings().storageImage(DST, this.weatherImage.view()).sampled(SRC, this.weatherHalf, this.linear),
                    null, groups(this.width), groups(this.height), 1);
        }
        toRead(cmd, this.weatherImage);
        this.weatherDrawn = true;
    }

    private void computeLighting(VkCommandBuffer cmd, long mainColourView, Depths depths, long lodLight, long terrainAlbedo) {
        if (!this.lightingOn) {
            //The copy skips the lighting (lighting.z 0) but still binds the image, which must be in the read layout
            this.lightBlurImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, COMPUTE_AND_FRAGMENT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            this.sunImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, COMPUTE_AND_FRAGMENT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            this.plantSunImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, COMPUTE_AND_FRAGMENT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
            return;
        }
        this.lightImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT);
        //P7l design 3.3: the sun visibility is written beside the AO
        this.sunImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT);
        this.plantSunImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT);
        VkBindings bindings = new VkBindings().storageImage(DST, this.lightImage.view()).storageImage(SUN_OUT, this.sunImage.view()).sampledView(MAIN_DEPTH, depths.main(), this.nearest)
                .sampledView(LOD_DEPTH, depths.lod(), this.nearest).sampledView(BEFORE_TRANSLUCENT, depths.beforeTranslucent(), this.nearest)
                .sampledView(AFTER_TRANSLUCENT, depths.afterTranslucent(), this.nearest).sampledView(LOD_OPAQUE, depths.lodOpaque(), this.nearest)
                .sampledView(SHADOW_NEAR, depths.shadowNear(), this.nearest).sampledView(SHADOW_FAR, depths.shadowFar(), this.nearest)
                .sampledView(SHADOW_NEAR_B, depths.shadowNearB(), this.nearest).sampledView(SHADOW_FAR_B, depths.shadowFarB(), this.nearest)
                .sampledView(SHADOW_CLOSE, depths.shadowClose(), this.nearest).sampledView(SHADOW_CLOSE_B, depths.shadowCloseB(), this.nearest)
                .sampledView(LIGHTING_MAIN_COLOUR, mainColourView, this.nearest)//2026-10-15: the plant marker
                .storageImage(LIGHTING_PLANT_SUN, this.plantSunImage.view())
                .sampledView(LIGHTING_LOD_LIGHT, lodLight, this.nearest)//2026-10-19: the LOD pixels' faces
                .sampledView(SHADOW_ENTITY, this.entityShadowView != 0 ? this.entityShadowView : depths.main(), this.nearest)
                .storageImage(LIGHTING_TERRAIN_ALBEDO, terrainAlbedo);//2026-11-09: the water marks (read when depthParams.w is 1)
        if (this.lightingUniformBuffer != null) bindings.uniform(LOOSE_UNIFORMS, this.lightingUniformBuffer);
        this.lighting.dispatchWithPush(cmd, bindings, this.lightingUniformBuffer == null ? this.lightingUniforms.buffer() : null,
                groups(this.lightImage.width), groups(this.lightImage.height), 1);
        toRead(cmd, this.lightImage);
        toRead(cmd, this.sunImage);
        toRead(cmd, this.plantSunImage);
        //The AO is smoothed by depth into the second image, which the copy reads
        this.lightBlurImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT);
        this.lightingBlur.dispatchWithPush(cmd, new VkBindings().storageImage(DST, this.lightBlurImage.view()).sampled(SRC, this.lightImage, this.nearest),
                null, groups(this.lightBlurImage.width), groups(this.lightBlurImage.height), 1);
        toRead(cmd, this.lightBlurImage);
    }

    /** P7e-2 design 3.3: the lit scene with the water reflections mixed in, written to the scene the bloom and the final draw read. */
    /** Session 7: NAME.LEAN, or null when it cannot be built or takes other uniforms or bindings than the full program NAME. */
    private static VkComputeProgram lean(String name, VkComputeProgram full) {
        return variant(name + ".LEAN", full);
    }

    /** Session 14 follow-up: whether the shader weather is drawn at half the size (the weather's pixel angle is then the half size's). */
    public boolean halfWeather() {
        return VisualsConfig.get().halfResEffects && this.weatherUp != null;
    }

    /** Session 14 follow-up: a pass of its own (no full build to fall back to), or null when it cannot be built. */
    private static VkComputeProgram optional(String name) {
        try {
            return VkComputeProgram.of(name);
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.warn("Visuals " + name + " could not be built, it stays off", e);
            return null;
        }
    }

    /** Session 8: the program `variant`, or null when it cannot be built or takes other uniforms or bindings than `full`. */
    private static VkComputeProgram variant(String variant, VkComputeProgram full) {
        VkComputeProgram v = null;
        try {
            v = VkComputeProgram.of(variant);
            if (!v.meta().equals(full.meta())) {
                VisualsLog.warn("Visuals " + variant + " differs in its uniforms, the full pass is used");
                v.free();
                return null;
            }
            return v;
        } catch (RuntimeException | LinkageError e) {
            if (v != null) v.free();
            VisualsLog.warn("Visuals " + variant + " not created, the full pass is used: " + e);
            return null;
        }
    }

    private void computeReflection(VkCommandBuffer cmd, Depths depths, VkGpuBuffer data, VkGpuBuffer ready, long lodLight, long terrainLight,
                                   long terrainNormal) {
        if (!this.reflectionOn) return;
        this.scene.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT);
        VkBindings bindings = new VkBindings().storageImage(DST, this.scene.view()).sampled(SRC, this.sceneLit, this.nearest)
                .sampledView(MAIN_DEPTH, depths.main(), this.nearest).sampledView(LOD_DEPTH, depths.lod(), this.nearest)
                .sampled(REFLECTION_CLOUD_MAP, this.cloudMap, this.nearest)
                .storage(REFLECTION_LIGHT_DATA, data).storage(REFLECTION_LIGHT_READY, ready).sampled(REFLECTION_SUN, this.sunImage, this.nearest)
                .sampledView(REFLECTION_LOD_LIGHT, lodLight, this.nearest).storageImage(REFLECTION_TERRAIN_LIGHT, terrainLight)
                .storageImage(REFLECTION_TERRAIN_NORMAL, terrainNormal).sampled(REFLECTION_MOON_FACE, this.moonFace, this.moonSampler)
                .storage(REFLECTION_SUN_OPEN, this.sunOpenBuffer);
        if (this.reflectionUniformBuffer != null) bindings.uniform(LOOSE_UNIFORMS, this.reflectionUniformBuffer);
        VkComputeProgram program = this.tintedOn && this.reflectionTinted != null
                ? (this.leanOn && this.reflectionLeanTinted != null ? this.reflectionLeanTinted : this.reflectionTinted)
                : (this.leanOn && this.reflectionLean != null ? this.reflectionLean : this.reflection);
        //Session 9: the PBR builds where this frame's materials were written, in the same choice
        if (this.pbrOn && this.reflectionPbr != null) {
            VkComputeProgram pbr = program == this.reflectionLeanTinted ? this.reflectionLeanTintedPbr : program == this.reflectionTinted ? this.reflectionTintedPbr
                    : program == this.reflectionLean ? this.reflectionLeanPbr : this.reflectionPbr;
            if (pbr != null) program = pbr;
        }
        //Session 8: the build in use, logged when it changes (which colours and features a frame's reflection draws with)
        String used = program == this.reflectionLeanTinted ? "LEAN.TINTED" : program == this.reflectionTinted ? "TINTED" : program == this.reflectionLean ? "LEAN"
                : program == this.reflectionLeanTintedPbr ? "LEAN.TINTED.PBR" : program == this.reflectionTintedPbr ? "TINTED.PBR"
                : program == this.reflectionLeanPbr ? "LEAN.PBR" : program == this.reflectionPbr ? "PBR" : "full";
        if (!used.equals(this.reflectionUsed)) {
            VisualsLog.info("Visuals reflection pass: postReflection " + used);
            this.reflectionUsed = used;
        }
        program.dispatchWithPush(cmd, bindings, this.reflectionUniformBuffer == null ? this.reflectionUniforms.buffer() : null,
                groups(this.width), groups(this.height), 1);
        toRead(cmd, this.scene);
    }

    private void computeShafts(VkCommandBuffer cmd, Depths depths) {
        long mainDepthView = depths.main();
        if (this.volumetricOn) {
            //Volumetric light pass (2026-10-15): shafts through the cascades replace the screen-space ones; the sum goes
            // to the mask image and is blurred into the shaft image (its dither, which its TAA would average)
            this.shaftMaskImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT);
            VkBindings bindings = new VkBindings().storageImage(DST, this.shaftMaskImage.view()).sampledView(MAIN_DEPTH, mainDepthView, this.nearest)
                    .sampledView(LOD_DEPTH, depths.lod(), this.nearest).sampledView(SHADOW_NEAR, depths.shadowNear(), this.nearest)
                    .sampledView(SHADOW_FAR, depths.shadowFar(), this.nearest);
            if (this.volumetricUniformBuffer != null) bindings.uniform(LOOSE_UNIFORMS, this.volumetricUniformBuffer);
            this.volumetric.dispatchWithPush(cmd, bindings, this.volumetricUniformBuffer == null ? this.volumetricUniforms.buffer() : null,
                    groups(this.shaftMaskImage.width), groups(this.shaftMaskImage.height), 1);
            toRead(cmd, this.shaftMaskImage);
            this.shaftImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT);
            this.volumetricBlur.dispatchWithPush(cmd, new VkBindings().storageImage(DST, this.shaftImage.view()).sampled(SRC, this.shaftMaskImage, this.nearest),
                    null, groups(this.shaftImage.width), groups(this.shaftImage.height), 1);
            toRead(cmd, this.shaftImage);
            return;
        }
        if (!this.shaftsOn) {
            //The final draw (or post_ldr) skips the shafts (strength 0) but still binds the image, which must be in the read layout
            this.shaftImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, COMPUTE_AND_FRAGMENT,
                    COMPUTE_AND_FRAGMENT, VK_ACCESS_SHADER_READ_BIT);
            return;
        }
        this.shaftMaskImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT);
        this.shaftMask.dispatchWithPush(cmd, new VkBindings().storageImage(DST, this.shaftMaskImage.view()).sampled(SRC, this.scene, this.nearest)
                        .sampledView(MAIN_DEPTH, mainDepthView, this.nearest), this.shaftMaskUniforms.buffer(),
                groups(this.shaftMaskImage.width), groups(this.shaftMaskImage.height), 1);
        toRead(cmd, this.shaftMaskImage);
        this.shaftImage.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT);
        this.shaftBlur.dispatchWithPush(cmd, new VkBindings().storageImage(DST, this.shaftImage.view()).sampled(SRC, this.shaftMaskImage, this.linear),
                this.shaftBlurUniforms.buffer(), groups(this.shaftImage.width), groups(this.shaftImage.height), 1);
        toRead(cmd, this.shaftImage);
    }

    private void bloomStep(VkCommandBuffer cmd, VkComputeProgram program, LooseUniformWriter uniforms, VkImage2D src, long sampler, VkImage2D dst) {
        uniforms.putVec4(PostUniforms.BLOOM_PARAMS, 1f / src.width, 1f / src.height, 0, 0);
        program.dispatchWithPush(cmd, new VkBindings().storageImage(DST, dst.view()).sampled(SRC, src, sampler), uniforms.buffer(),
                groups(dst.width), groups(dst.height), 1);
    }

    /** Session 14 follow-up: a global memory barrier (particleTiles, and the composite's image between the composite and .PARTICLES). */
    private static void memoryBarrier(VkCommandBuffer cmd, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkMemoryBarrier.Buffer b = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, b, null, null);
        }
    }

    /** Session 14: a write to a buffer (sunOpenBuffer) becomes readable by the next compute step. */
    private static void bufferBarrier(VkCommandBuffer cmd, int srcStage, int srcAccess) {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkMemoryBarrier.Buffer b = org.lwjgl.vulkan.VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(srcAccess)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(cmd, srcStage, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, b, null, null);
        }
    }

    /** A compute write becomes readable by the next step and by the final draw. */
    private static void toRead(VkCommandBuffer cmd, VkImage2D image) {
        image.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                COMPUTE_AND_FRAGMENT, VK_ACCESS_SHADER_READ_BIT);
    }

    private static int groups(int pixels) {
        return (pixels + 7) / 8;
    }

    /**
     * P7i: outside a render pass, after compute(): SMAA at the given preset (0 off, 1..4 LOW..ULTRA) on the final colour; draw()
     * then blits its result. Off, unavailable or not ready this frame, draw() draws the final colour itself.
     */
    /**
     * TAA pass (2026-10-15): before the frame's upload commit, whether TAA runs this frame (frame null: off). Its uniforms need
     * the last frame's camera, which only a frame that ran TAA leaves.
     */
    public void prepareTaa(VkUploadStream upload, VkTaa.Frame frame) {
        this.taaPrepared = false;
        if (frame == null || this.taa == null || this.taaFailed || this.smaa == null || this.smaaFailed) return;
        this.taa.prepare(upload, frame);
        this.taaPrepared = true;
    }

    /** Whether TAA can run this frame when asked (the jitter follows this). */
    /** 2026-10-30 (audit): whether TAA resolved this frame (antiAlias ran it without failing). */
    public boolean taaDrawn() {
        return this.taaDrawn;
    }

    public boolean taaAvailable() {
        return this.taa != null && !this.taaFailed && this.smaa != null && !this.smaaFailed;
    }

    public void antiAlias(VkCommandBuffer cmd, PostUniforms.Values values, int preset) {
        this.smaaDrawn = false;
        this.taaDrawn = false;
        this.drawnImage = null;
        boolean smaaOn = this.smaa != null && !this.smaaFailed && preset > 0 && preset < VkSmaa.PRESETS.length && this.smaa.usable();
        boolean taaOn = this.taaPrepared && this.lastDepths != null;
        this.taaPrepared = false;
        if (!smaaOn && !taaOn) return;
        try {
            //TAA pass (2026-10-15): the final colour, then TAA, then SMAA when chosen, then the blit
            VkImage2D image = this.smaa.finalColour(cmd, values, this.scene, this.bloom[0], this.shaftImage, this.heatImage, this.heatDrawn,
                    this.nearest, this.linear);
            if (taaOn) {
                try {
                    image = this.taa.compute(cmd, image, this.lastDepths.main(), this.lastDepths.lod(), this.lastDepths.beforeTranslucent(),
                            this.lastDepths.afterTranslucent(), this.nearest, this.linear);
                    this.taaDrawn = true;
                } catch (RuntimeException e) {
                    this.taaFailed = true;
                    this.taa.skipped();
                    VisualsLog.error("Visuals TAA failed, turned off for this session: ", e);
                }
            }
            if (smaaOn) image = this.smaa.antiAlias(cmd, image, this.nearest, this.linear, preset);
            this.smaaDrawn = true;
            this.drawnImage = image;
        } catch (RuntimeException e) {
            this.smaaFailed = true;
            VisualsLog.error("Visuals anti-aliasing (SMAA) failed, turned off for this session: ", e);
        }
    }

    /** Inside the resumed main pass, after compute(): one full-screen strip over Reforged's viewport and scissor; no depth is read. */
    public void draw(VkCommandBuffer cmd, PostUniforms.Values values) {
        if (this.smaaDrawn && this.drawnImage != null) {
            this.smaa.draw(cmd, this.nearest, this.drawnImage, PostMath.sharpenStrength(this.taaDrawn), this.weatherImage, this.weatherDrawn);
            return;
        }
        values.write(this.finalUniforms);
        //2026-11-09 (session 6): screenParams.w 1 blends the weather overlay (0: as before, the overlay is bound but not read)
        values.writeWeather(this.finalUniforms, this.weatherDrawn);
        values.writeHeat(this.finalUniforms, this.heatDrawn);
        VkBindings bindings = new VkBindings().sampled(FINAL_SCENE, this.scene, this.nearest).sampled(FINAL_BLOOM, this.bloom[0], this.linear)
                .sampled(FINAL_SHAFTS, this.shaftImage, this.linear).sampled(FINAL_WEATHER, this.weatherImage, this.nearest)
                .sampled(FINAL_HEAT, this.heatImage, this.linear);
        this.finalDraw.bind(cmd, VkContext.boundRenderPassHandle(), VkContext.boundRenderPassKey(), bindings, this.finalUniforms);
        vkCmdDraw(cmd, 4, 1, 0, 0);
    }

    /** Call when a frame starts recording: transitions of a dropped frame are undone. */
    public void tick() {
        this.cloudMap.tick();
        if (this.precipitationMap != null) this.precipitationMap.tick();
        this.lodLightStandIn.tick();
        this.ao.tick();
        if (this.smaa != null) this.smaa.tick();
        if (this.taa != null) this.taa.tick();
        if (this.scene == null) return;
        for (VkImage2D image : this.images()) if (image != null) image.tick();
    }

    /** Only once no submitted frame can still use it. */
    public void free() {
        if (this.smaa != null) this.smaa.free();
        if (this.taa != null) this.taa.free();
        this.copy.free();
        if (this.copyTinted != null) this.copyTinted.free();
        if (this.copySunOpen != null) this.copySunOpen.free();
        this.sunOpenBuffer.free();
        if (this.copyParticles != null) this.copyParticles.free();
        if (this.copyTintedParticles != null) this.copyTintedParticles.free();
        for (VkComputeProgram p : new VkComputeProgram[]{this.copySkyHalf, this.copyTintedSkyHalf, this.weatherUp, this.copyHeat}) {
            if (p != null) p.free();
        }
        if (this.particleTiles != null) this.particleTiles.free();
        this.prefilter.free();
        this.down.free();
        this.up.free();
        this.shaftMask.free();
        this.shaftBlur.free();
        this.volumetric.free();
        this.volumetricBlur.free();
        if (this.volumetricUniformBuffer != null) this.volumetricUniformBuffer.free();
        this.lighting.free();
        this.lightingBlur.free();
        this.reflection.free();
        if (this.reflectionLean != null) this.reflectionLean.free();
        if (this.reflectionTinted != null) this.reflectionTinted.free();
        if (this.reflectionLeanTinted != null) this.reflectionLeanTinted.free();
        for (VkComputeProgram p : new VkComputeProgram[]{this.copyPbr, this.copyTintedPbr, this.reflectionPbr, this.reflectionLeanPbr, this.reflectionTintedPbr,
                this.reflectionLeanTintedPbr}) {
            if (p != null) p.free();
        }
        this.finalDraw.free();
        this.ao.free();
        if (this.copyUniformBuffer != null) this.copyUniformBuffer.free();
        if (this.lightingUniformBuffer != null) this.lightingUniformBuffer.free();
        if (this.reflectionUniformBuffer != null) this.reflectionUniformBuffer.free();
        VkSamplers.free(this.nearest);
        VkSamplers.free(this.linear);
        this.cloudMap.free();
        this.moonFace.free();
        VkSamplers.free(this.moonSampler);
        if (this.galaxyMap != null) this.galaxyMap.free();
        if (this.galaxySampler != 0) VkSamplers.free(this.galaxySampler);
        if (this.weather != null) this.weather.free();
        if (this.weatherRound != null) this.weatherRound.free();
        if (this.weatherUniformBuffer != null) this.weatherUniformBuffer.free();
        if (this.precipitationMap != null) this.precipitationMap.free();
        this.lightStandIn.free();
        this.lodLightStandIn.free();
        if (this.scene != null) destroy(this.images());
        this.scene = null;
        this.sceneLit = null;
        this.bloom = null;
        this.shaftMaskImage = null;
        this.shaftImage = null;
        this.lightImage = null;
        this.lightBlurImage = null;
        this.sunImage = null;
        this.plantSunImage = null;
        this.weatherImage = null;
    }
}
