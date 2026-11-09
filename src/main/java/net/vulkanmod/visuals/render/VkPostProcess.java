package net.vulkanmod.visuals.render;

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
    //2026-11-09: the lighting reads the terrain's albedo image at 17 for its water marks (TerrainLightTarget, GENERAL)
    LIGHTING_TERRAIN_ALBEDO = 17;
    private static final int COMPUTE_AND_FRAGMENT = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    /**
     * The depth views the compute step reads (P7e-1 3.2). A view not valid this frame is the main depth view, a stand-in the
     * shaders do not read (the uniforms say which are valid).
     */
    public record Depths(long main, long lod, long beforeTranslucent, long afterTranslucent, long lodOpaque, long shadowNear, long shadowFar,
                         long shadowNearB, long shadowFarB, long shadowClose, long shadowCloseB) {
    }

    /** P7l design 3.3: the light volume's buffers (null: none), vanilla's lightmap view, the LOD light view (0: none); P7k: the held light (null: none). */
    public record LightInputs(VkGpuBuffer data, VkGpuBuffer ready, long lightmap, long lodLight, VkGpuBuffer held, long terrainLight, long terrainAlbedo) {
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
    //Volumetric light pass (2026-10-15): light shafts through the sun's cascades, into the shaft image
    private final VkComputeProgram volumetric, volumetricBlur;
    private final LooseUniformWriter volumetricUniforms;
    private final VkGpuBuffer volumetricUniformBuffer;
    private boolean volumetricOn;
    private final VkGraphicsProgram finalDraw;
    private final VkAmbientOcclusion ao;//W10
    private final LooseUniformWriter copyUniforms, prefilterUniforms, downUniforms, upUniforms, shaftMaskUniforms, shaftBlurUniforms, finalUniforms;
    private final LooseUniformWriter lightingUniforms, reflectionUniforms;
    /** 2026-11-09 (session 5): this frame's sky slots (prepareSky), written into the reflection's after its own. */
    private CelestialUniforms.Values sky = CelestialUniforms.off();
    private final VkGpuBuffer copyUniformBuffer, lightingUniformBuffer, reflectionUniformBuffer;
    private final long nearest, linear;
    //P7h design 3.3: clouds.png as 256x256 RGBA8 (1 on cloud cells), uploaded once; a dropped upload frame is uploaded again
    private final VkImage2D cloudMap;
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
        VkImage2D cloudMap = null, lodLightStandIn = null;
        VkGpuBuffer lightStandIn = null;
        VkAmbientOcclusion ao = null;
        long nearest = 0, linear = 0;
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
            lightStandIn = VkGpuBuffer.device(16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
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
            if (lightStandIn != null) lightStandIn.free();
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
        this.volumetric = volumetric;
        this.volumetricBlur = volumetricBlur;
        this.volumetricUniformBuffer = volumetricUniformBuffer;
        this.nearest = nearest;
        this.linear = linear;
        this.cloudMap = cloudMap;
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
            this.deferred.retire(() -> destroy(old), VkFrames.serial());
            this.scene = null;
            this.sceneLit = null;
            this.bloom = null;
            this.shaftMaskImage = null;
            this.shaftImage = null;
            this.lightImage = null;
            this.lightBlurImage = null;
            this.sunImage = null;
            this.plantSunImage = null;
        }
        int[][] sizes = PostMath.bloomSizes(width, height);
        VkImage2D[] bloom = new VkImage2D[sizes.length];
        VkImage2D scene = null, sceneLit = null, shaftMaskImage = null, shaftImage = null, lightImage = null, lightBlurImage = null, sunImage = null, plantSunImage = null;
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
        } catch (RuntimeException e) {
            destroy(concat(new VkImage2D[]{scene, sceneLit, shaftMaskImage, shaftImage, lightImage, lightBlurImage, sunImage, plantSunImage}, bloom));
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
        this.width = width;
        this.height = height;
        this.ao.ensureSize(width, height, this.deferred);
        if (this.smaa != null) this.smaa.ensureSize(width, height, this.deferred);
        if (this.taa != null) this.taa.ensureSize(width, height, this.deferred);
        VisualsLog.info("Visuals post-process target " + width + "x" + height + ", bloom " + sizes.length + " levels");
    }

    private VkImage2D[] images() {
        return concat(new VkImage2D[]{this.scene, this.sceneLit, this.shaftMaskImage, this.shaftImage, this.lightImage, this.lightBlurImage, this.sunImage, this.plantSunImage}, this.bloom);
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

    /** 2026-11-09 (session 5): before prepare(), the discs' shape and the aurora (CelestialUniforms) for the composite and the reflection. */
    public void prepareSky(CelestialUniforms.Values sky) {
        this.sky = sky;
        sky.write(this.copyUniforms);
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
        if (this.smaa != null) this.smaa.prepare(upload);
        clouds.write(this.copyUniforms, CloudUniforms.COPY_FIRST);
        clouds.write(this.reflectionUniforms, CloudUniforms.REFLECTION_FIRST);
        atmosphere.write(this.copyUniforms);
        lighting.writeCopy(this.copyUniforms);
        shadow.writeCopy(this.copyUniforms);
        light.write(this.copyUniforms, LightVolumeUniforms.COPY_FIRST);
        held.writeCopy(this.copyUniforms, light.on() ? light.camera() : null);
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
                .sampledView(COPY_BEFORE_TRANSLUCENT, depths.beforeTranslucent(), this.nearest);
        if (this.copyUniformBuffer != null) copyBindings.uniform(LOOSE_UNIFORMS, this.copyUniformBuffer);
        this.copy.dispatchWithPush(cmd, copyBindings, this.copyUniformBuffer == null ? this.copyUniforms.buffer() : null,
                groups(this.width), groups(this.height), 1);
        toRead(cmd, copyTarget);
        VkGpuTimer.mark(cmd, "post.reflection");
        this.computeReflection(cmd, depths, data, ready, lodLight, light.terrainLight());
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
    private void computeReflection(VkCommandBuffer cmd, Depths depths, VkGpuBuffer data, VkGpuBuffer ready, long lodLight, long terrainLight) {
        if (!this.reflectionOn) return;
        this.scene.transitionFromUndefined(cmd, VK_IMAGE_LAYOUT_GENERAL, COMPUTE_AND_FRAGMENT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT);
        VkBindings bindings = new VkBindings().storageImage(DST, this.scene.view()).sampled(SRC, this.sceneLit, this.nearest)
                .sampledView(MAIN_DEPTH, depths.main(), this.nearest).sampledView(LOD_DEPTH, depths.lod(), this.nearest)
                .sampled(REFLECTION_CLOUD_MAP, this.cloudMap, this.nearest)
                .storage(REFLECTION_LIGHT_DATA, data).storage(REFLECTION_LIGHT_READY, ready).sampled(REFLECTION_SUN, this.sunImage, this.nearest)
                .sampledView(REFLECTION_LOD_LIGHT, lodLight, this.nearest).storageImage(REFLECTION_TERRAIN_LIGHT, terrainLight);
        if (this.reflectionUniformBuffer != null) bindings.uniform(LOOSE_UNIFORMS, this.reflectionUniformBuffer);
        this.reflection.dispatchWithPush(cmd, bindings, this.reflectionUniformBuffer == null ? this.reflectionUniforms.buffer() : null,
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
            VkImage2D image = this.smaa.finalColour(cmd, values, this.scene, this.bloom[0], this.shaftImage, this.nearest, this.linear);
            if (taaOn) {
                try {
                    image = this.taa.compute(cmd, image, this.lastDepths.main(), this.lastDepths.lod(), this.lastDepths.beforeTranslucent(), this.nearest, this.linear);
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
            this.smaa.draw(cmd, this.nearest, this.drawnImage, PostMath.sharpenStrength(this.taaDrawn));
            return;
        }
        values.write(this.finalUniforms);
        VkBindings bindings = new VkBindings().sampled(FINAL_SCENE, this.scene, this.nearest).sampled(FINAL_BLOOM, this.bloom[0], this.linear)
                .sampled(FINAL_SHAFTS, this.shaftImage, this.linear);
        this.finalDraw.bind(cmd, VkContext.boundRenderPassHandle(), VkContext.boundRenderPassKey(), bindings, this.finalUniforms);
        vkCmdDraw(cmd, 4, 1, 0, 0);
    }

    /** Call when a frame starts recording: transitions of a dropped frame are undone. */
    public void tick() {
        this.cloudMap.tick();
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
        this.finalDraw.free();
        this.ao.free();
        if (this.copyUniformBuffer != null) this.copyUniformBuffer.free();
        if (this.lightingUniformBuffer != null) this.lightingUniformBuffer.free();
        if (this.reflectionUniformBuffer != null) this.reflectionUniformBuffer.free();
        VkSamplers.free(this.nearest);
        VkSamplers.free(this.linear);
        this.cloudMap.free();
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
    }
}
