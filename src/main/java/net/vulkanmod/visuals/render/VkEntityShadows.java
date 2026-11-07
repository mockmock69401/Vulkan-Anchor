package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.VkBindings;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkGpuBuffer;
import net.vulkanmod.visuals.vk.VkGpuTimer;
import net.vulkanmod.visuals.vk.VkGraphicsProgram;
import net.vulkanmod.visuals.vk.VkImage2D;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkViewport;

import java.nio.FloatBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * 2026-10-29: the entity shadow map. Every frame with captured entities (EntityShadowCapture) its depth map, a square of
 * EntityShadowCapture.RADIUS blocks around the camera seen from this frame's sun, is cleared and the captured triangles drawn into
 * it; the lighting pass multiplies the cascades' sun visibility by its own (lighting.comp's entityVisibility). Drawn in the
 * post-process, outside a render pass, before the lighting; the vertices go through a host-visible buffer per frame in flight.
 */
public final class VkEntityShadows {
    /** The map is MAP_SIZE x MAP_SIZE (0.03 blocks a texel at RADIUS 32). */
    public static final int MAP_SIZE = 2048;
    private static final int TEST_STAGES = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
    static final int STRIDE = 12;
    static final VkGraphicsProgram.VertexLayout POSITION = new VkGraphicsProgram.VertexLayout(STRIDE, new int[]{0},
            new int[]{VK_FORMAT_R32G32B32_SFLOAT}, new int[]{0});
    static final int VIEW_PROJECTION = 0;

    private final long renderPass;
    private final VkImage2D depth;
    private final long framebuffer;
    private final VkGraphicsProgram program;
    private final LooseUniformWriter loose;
    private final VkSubLevelShadows subLevels;//null when its program could not be built: the map holds the entities only
    private VkGpuBuffer[] vertices;
    private ShadowMath.Cascade drawn;
    private long drawnSerial = -1;

    /** Built step by step: a failure frees what exists and rethrows (the renderer then runs without entity shadows). */
    public VkEntityShadows() {
        int depthFormat = VkContext.depthFormat();
        long renderPass = 0, framebuffer = 0;
        VkImage2D depth = null;
        VkGraphicsProgram program = null;
        VkGpuBuffer[] vertices = new VkGpuBuffer[Math.max(1, VkContext.framesInFlight())];
        try {
            renderPass = VkShadowMaps.createRenderPass(depthFormat, true);
            depth = VkImage2D.createDepth(depthFormat, MAP_SIZE, MAP_SIZE, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
            framebuffer = VkShadowMaps.createFramebuffer(renderPass, depth.view(), MAP_SIZE, MAP_SIZE);
            for (int i = 0; i < vertices.length; i++) {
                vertices[i] = VkGpuBuffer.host((long) EntityShadowCapture.MAX_VERTICES * STRIDE, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            }
            program = VkGraphicsProgram.of("entityShadow", VkShadowMaps.SHADOW_STATE, POSITION);
        } catch (RuntimeException | LinkageError e) {
            if (program != null) program.free();
            for (VkGpuBuffer b : vertices) if (b != null) b.free();
            if (framebuffer != 0) vkDestroyFramebuffer(VkContext.device(), framebuffer, null);
            if (depth != null) depth.free();
            if (renderPass != 0) vkDestroyRenderPass(VkContext.device(), renderPass, null);
            throw e;
        }
        this.renderPass = renderPass;
        this.depth = depth;
        this.framebuffer = framebuffer;
        this.vertices = vertices;
        this.program = program;
        this.loose = new LooseUniformWriter(program.meta());
        this.subLevels = VkSubLevelShadows.create();
    }

    /**
     * 2026-10-30 (audit): one vertex buffer per frame in flight, also after the frame queue size changed in game (two frames shared
     * one before). The count changes only in Renderer.recreateSwapChain with the device idle, and this runs at the first draw after
     * it, so no submitted frame still reads the old buffers.
     */
    private void matchFramesInFlight() {
        int frames = Math.max(1, VkContext.framesInFlight());
        if (this.vertices.length == frames) return;
        VkGpuBuffer[] grown = new VkGpuBuffer[frames];
        try {
            for (int i = 0; i < frames; i++) {
                grown[i] = VkGpuBuffer.host((long) EntityShadowCapture.MAX_VERTICES * STRIDE, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            }
        } catch (RuntimeException | LinkageError e) {
            for (VkGpuBuffer b : grown) if (b != null) b.free();
            throw e;
        }
        for (VkGpuBuffer b : this.vertices) b.free();
        this.vertices = grown;
    }

    /** This frame's map's view of the sun (toSun) around the camera, the same the lighting reads it by. */
    public static ShadowMath.Cascade cascade(Vector3fc toSun, double cameraX, double cameraY, double cameraZ) {
        return ShadowMath.cascade(toSun, cameraX, cameraY, cameraZ, EntityShadowCapture.RADIUS, MAP_SIZE, MAP_SIZE);
    }

    /**
     * Whether this frame's map has anything to draw: a cascade, and captured entity corners or kept sub-level draws (2026-11-07).
     * With nothing the map is not drawn and the lighting does not read it.
     */
    static boolean drawsAnything(boolean hasCascade, int entityCorners, int subLevelCasters) {
        return hasCascade && (entityCorners > 0 || subLevelCasters > 0);
    }

    /** The sub-level draws this frame's map can take: 0 without their program. */
    public int subLevelCasters() {
        return this.subLevels == null || this.subLevels.disabled() ? 0 : SubLevelShadowCasters.count();
    }

    /**
     * Outside a render pass: clears the map and draws count captured corners (data, xyz floats) and the kept sub-level draws for cascade,
     * then leaves it readable by the lighting pass. Nothing when there are neither (the lighting then does not read it, drawn() is
     * null). invModelView: this frame's view space to camera-relative world.
     */
    public void draw(VkCommandBuffer cmd, ShadowMath.Cascade cascade, FloatBuffer data, int count, Matrix4fc invModelView) {
        this.drawn = null;
        this.depth.tick();
        int n = data == null ? 0 : Math.min(count, EntityShadowCapture.MAX_VERTICES);
        int casters = this.subLevelCasters();
        if (!drawsAnything(cascade != null, n, casters)) return;
        VkGpuBuffer buffer = null;
        if (n > 0) {
            this.matchFramesInFlight();
            buffer = this.vertices[Math.floorMod(VkFrames.recordingSlot(), this.vertices.length)];
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), MemoryUtil.memAddress(buffer.mapped()), (long) n * STRIDE);
        }
        VkGpuTimer.mark(cmd, "shadows.entities");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearValue.Buffer clears = VkClearValue.calloc(1, stack);
            clears.get(0).depthStencil().set(1.0f, 0);
            VkRenderPassBeginInfo info = VkRenderPassBeginInfo.calloc(stack).sType$Default().renderPass(this.renderPass)
                    .framebuffer(this.framebuffer).pClearValues(clears);
            info.renderArea().offset().set(0, 0);
            info.renderArea().extent().set(MAP_SIZE, MAP_SIZE);
            vkCmdBeginRenderPass(cmd, info, VK_SUBPASS_CONTENTS_INLINE);
            try {
                //A positive viewport, as the cascades (row 0 is ndc y -1)
                vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, stack).x(0).y(0).width(MAP_SIZE).height(MAP_SIZE).minDepth(0).maxDepth(1));
                VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
                scissor.get(0).offset().set(0, 0);
                scissor.get(0).extent().set(MAP_SIZE, MAP_SIZE);
                vkCmdSetScissor(cmd, 0, scissor);
                if (n > 0) {
                    this.loose.putMat4(VIEW_PROJECTION, cascade.viewProjection());
                    this.program.bind(cmd, this.renderPass, new VkBindings(), this.loose);
                    vkCmdBindVertexBuffers(cmd, 0, stack.longs(buffer.handle()), stack.longs(0));
                    vkCmdDraw(cmd, n, 1, 0, 0);
                }
                if (casters > 0) this.subLevels.draw(cmd, this.renderPass, cascade, invModelView);
            } finally {
                vkCmdEndRenderPass(cmd);
            }
        }
        this.depth.assumeLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        this.depth.transition(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, TEST_STAGES, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        this.drawn = cascade;
        this.drawnSerial = VkFrames.serial();
    }

    /** The cascade this frame's map was drawn for, null when it was not drawn this frame. */
    public ShadowMath.Cascade drawn() {
        return this.drawnSerial == VkFrames.serial() ? this.drawn : null;
    }

    public long view() {
        return this.depth.sampledView();
    }

    /** Only once no submitted frame can still use it (after the device is idle). */
    public void free() {
        for (VkGpuBuffer b : this.vertices) b.free();
        if (this.subLevels != null) this.subLevels.free();
        this.program.free();
        vkDestroyFramebuffer(VkContext.device(), this.framebuffer, null);
        this.depth.free();
        vkDestroyRenderPass(VkContext.device(), this.renderPass, null);
    }

    /** Built once the device is up; null (logged) when it cannot be. */
    static VkEntityShadows create() {
        try {
            return new VkEntityShadows();
        } catch (RuntimeException | LinkageError e) {
            VisualsLog.warn("Visuals entity shadows not created: " + e);
            return null;
        }
    }

    /** The lighting pass's matrix for a cascade drawn at the camera now: view space to the entity map's clip. */
    static Matrix4f lightingMatrix(ShadowMath.Cascade c, ShadowMath.Cascade now, AtmosphereFrame frame) {
        return ShadowMath.rebase(c.viewProjection(), c.cameraX(), c.cameraY(), c.cameraZ(), now.cameraX(), now.cameraY(), now.cameraZ())
                .mul(frame.invModelView());
    }
}
