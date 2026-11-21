package net.vulkanmod.visuals.vk;

import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A graphics shader's loose uniforms (glUniform* on GL) set by GL location. The block is push constants, or the std140
 * VisualsLooseUniforms uniform block when inUniformBuffer(); the offsets come from the converted shader either way.
 */
public final class LooseUniformWriter {
    private final VulkanShaderMeta meta;
    private final ByteBuffer buffer;
    /**
     * 2026-11-21 (the user's 1% lows, JFR): buffer()'s view, kept. A new duplicate every push and upload was 3% of the render thread's
     * garbage; every caller copies the block at once (vkCmdPushConstants, the upload stream), so one view a writer serves them all.
     */
    private final ByteBuffer view;

    public LooseUniformWriter(VulkanShaderMeta meta) {
        if (meta.pushSize() == 0) throw new IllegalArgumentException("shader has no loose uniforms");
        this.meta = meta;
        this.buffer = ByteBuffer.allocateDirect(meta.pushSize()).order(ByteOrder.nativeOrder());
        this.view = this.buffer.duplicate().order(ByteOrder.nativeOrder());
    }

    public boolean inUniformBuffer() {
        return this.meta.pushInUniformBuffer();
    }

    public boolean has(int glLocation) {
        java.util.List<VulkanShaderMeta.PushMember> push = this.meta.push();
        for (int i = 0; i < push.size(); i++) {
            if (push.get(i).location() == glLocation) return true;
        }
        return false;
    }

    public LooseUniformWriter putFloat(int glLocation, float value) {
        this.buffer.putFloat(this.offsetOf(glLocation, "float"), value);
        return this;
    }

    public LooseUniformWriter putVec2(int glLocation, float x, float y) {
        int o = this.offsetOf(glLocation, "vec2");
        this.buffer.putFloat(o, x).putFloat(o + 4, y);
        return this;
    }

    public LooseUniformWriter putVec4(int glLocation, float x, float y, float z, float w) {
        int o = this.offsetOf(glLocation, "vec4");
        this.buffer.putFloat(o, x).putFloat(o + 4, y).putFloat(o + 8, z).putFloat(o + 12, w);
        return this;
    }

    /** Column-major, like glUniformMatrix4fv without transpose. */
    public LooseUniformWriter putMat4(int glLocation, Matrix4fc m) {
        m.get(this.offsetOf(glLocation, "mat4"), this.buffer);
        return this;
    }

    /** The whole block, position 0, limit pushSize: the writer's one view, reset on every call; copy it before the next. */
    public ByteBuffer buffer() {
        return this.view.limit(this.meta.pushSize()).position(0);
    }

    private int offsetOf(int glLocation, String type) {
        //2026-11-12 (session 14): every uniform of every pass each frame comes through here; an indexed loop leaves no iterator behind
        java.util.List<VulkanShaderMeta.PushMember> push = this.meta.push();
        for (int i = 0; i < push.size(); i++) {
            VulkanShaderMeta.PushMember m = push.get(i);
            if (m.location() != glLocation) continue;
            if (!m.type().equals(type)) {
                throw new IllegalArgumentException("GL location " + glLocation + " (" + m.name() + ") is " + m.type() + ", not " + type);
            }
            return m.offset();
        }
        throw new IllegalArgumentException("no loose uniform at GL location " + glLocation);
    }
}
