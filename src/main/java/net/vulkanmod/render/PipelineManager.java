package net.vulkanmod.render;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.compat.external.ExternalRenderPathSupport;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.SPIRVUtils;

import java.util.function.Function;

import static net.vulkanmod.vulkan.shader.SPIRVUtils.compileShaderAbsoluteFile;

public abstract class PipelineManager {
    private static final String shaderPath = "/assets/vulkanmod/shaders/";
    public static VertexFormat TERRAIN_VERTEX_FORMAT;

    public static void setTerrainVertexFormat(VertexFormat format) {
        TERRAIN_VERTEX_FORMAT = format;
    }

    static GraphicsPipeline terrainShaderEarlyZ, terrainShader, fastBlitPipeline, renderScaleBlitPipeline, externalLodPipeline;
    private static GraphicsPipeline glFramebufferBlitPipeline;
    /**
     * 2026-11-10 (shader roadmap session 9): the terrain's PBR build (terrain.vsh / terrain.fsh with TERRAIN_PBR, terrain_pbr.json): the
     * LabPBR materials into the visuals' material images (pbr_terrain.glsl). Null when it could not be built (PBR then stays off).
     */
    static GraphicsPipeline terrainPbrShader;

    private static Function<TerrainRenderType, GraphicsPipeline> shaderGetter;

    public static void init() {
        setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
        createBasicPipelines();
        setDefaultShader();
        ThreadBuilderPack.defaultTerrainBuilderConstructor();
    }

    public static void setDefaultShader() {
        //Session 9: the PBR build while this frame's terrain draws its materials (PbrUniforms.terrainActive)
        setShaderGetter(renderType -> terrainPbrShader != null && net.vulkanmod.visuals.render.PbrUniforms.terrainActive() ? terrainPbrShader : terrainShader);
    }

    private static void createBasicPipelines() {
        terrainShaderEarlyZ = createPipeline("terrain","terrain", "terrain_z", TERRAIN_VERTEX_FORMAT);
        terrainShader = createPipeline("terrain", "terrain", "terrain", TERRAIN_VERTEX_FORMAT);
        try {
            terrainPbrShader = createPipeline("terrain", "terrain_pbr", "terrain", "terrain", TERRAIN_VERTEX_FORMAT, "TERRAIN_PBR");
        } catch (RuntimeException e) {
            terrainPbrShader = null;
            net.vulkanmod.Initializer.LOGGER.warn("The terrain's PBR shader could not be built, PBR stays off", e);
        }
        fastBlitPipeline = createPipeline("blit", "blit", "blit", CustomVertexFormat.NONE);
        glFramebufferBlitPipeline = createPipeline("blit", "blit_gl", "blit", CustomVertexFormat.NONE);
        renderScaleBlitPipeline = createPipeline("render_scale_blit", "render_scale_blit", "render_scale_blit", CustomVertexFormat.NONE);
        if (ExternalRenderPathSupport.shouldCreateExternalLodPipeline()) {
            externalLodPipeline = createPipeline("external_lod", "lod", "lod", CustomVertexFormat.EXTERNAL_LOD);
        }
    }

    private static GraphicsPipeline createPipeline(String baseName, String vertName, String fragName,VertexFormat vertexFormat) {
        return createPipeline(baseName, baseName, vertName, fragName, vertexFormat, null);
    }

    /** Session 9: with the bindings of jsonName in baseName's folder and, with define, the shaders compiled with it defined. */
    private static GraphicsPipeline createPipeline(String baseName, String jsonName, String vertName, String fragName, VertexFormat vertexFormat, String define) {
        String pathB = String.format("basic/%s/%s", baseName, jsonName);
        String pathV = String.format("basic/%s/%s", baseName, vertName);
        String pathF = String.format("basic/%s/%s", baseName, fragName);

        Pipeline.Builder pipelineBuilder = new Pipeline.Builder(vertexFormat, pathB);
        pipelineBuilder.parseBindingsJSON();

        SPIRVUtils.SPIRV vertShaderSPIRV = compile(String.format("%s%s.vsh", shaderPath, pathV), SPIRVUtils.ShaderKind.VERTEX_SHADER, define);
        SPIRVUtils.SPIRV fragShaderSPIRV = compile(String.format("%s%s.fsh", shaderPath, pathF), SPIRVUtils.ShaderKind.FRAGMENT_SHADER, define);
        pipelineBuilder.setSPIRVs(vertShaderSPIRV, fragShaderSPIRV);

        return pipelineBuilder.createGraphicsPipeline();
    }

    private static SPIRVUtils.SPIRV compile(String file, SPIRVUtils.ShaderKind kind, String define) {
        if (define == null) return compileShaderAbsoluteFile(file, kind);
        String source = SPIRVUtils.readShaderSource(file);
        //The define right after the #version line
        int eol = source.indexOf('\n');
        return SPIRVUtils.compileShader(file, source.substring(0, eol + 1) + "#define " + define + "\n" + source.substring(eol + 1), kind);
    }

    public static GraphicsPipeline getTerrainPbrShader() {
        return terrainPbrShader;
    }

    public static GraphicsPipeline getTerrainShader(TerrainRenderType renderType) {
        return shaderGetter.apply(renderType);
    }

    public static void setShaderGetter(Function<TerrainRenderType, GraphicsPipeline> consumer) {
        shaderGetter = consumer;
    }

    public static GraphicsPipeline getTerrainDirectShader(RenderType renderType) {
        return terrainShader;
    }

    public static GraphicsPipeline getTerrainIndirectShader(RenderType renderType) {
        return terrainShaderEarlyZ;
    }

    public static GraphicsPipeline getFastBlitPipeline() { return fastBlitPipeline; }

    public static GraphicsPipeline getGlFramebufferBlitPipeline() { return glFramebufferBlitPipeline; }

    public static GraphicsPipeline getRenderScaleBlitPipeline() { return renderScaleBlitPipeline; }

    public static GraphicsPipeline getExternalLodPipeline() { return externalLodPipeline; }

    public static void destroyPipelines() {
        terrainShaderEarlyZ.cleanUp();
        terrainShader.cleanUp();
        if (terrainPbrShader != null) {
            terrainPbrShader.cleanUp();
        }
        fastBlitPipeline.cleanUp();
        glFramebufferBlitPipeline.cleanUp();
        renderScaleBlitPipeline.cleanUp();
        if (externalLodPipeline != null) {
            externalLodPipeline.cleanUp();
        }
    }
}
