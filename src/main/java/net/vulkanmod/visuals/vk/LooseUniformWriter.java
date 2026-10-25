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

    public LooseUniformWriter(VulkanShaderMeta meta) {
        if (meta.pushSize() == 0) throw new IllegalArgumentException("shader has no loose uniforms");
        this.meta = meta;
        this.buffer = ByteBuffer.allocateDirect(meta.pushSize()).order(ByteOrder.nativeOrder());
    }

    public boolean inUniformBuffer() {
        return this.meta.pushInUniformBuffer();
    }

    public boolean has(int glLocation) {
        for (VulkanShaderMeta.PushMember m : this.meta.push()) {
            if (m.location() == glLocation) return true;
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

    /** The whole block, position 0, limit pushSize. */
    public ByteBuffer buffer() {
        return this.buffer.duplicate().order(ByteOrder.nativeOrder()).position(0).limit(this.meta.pushSize());
    }

    private int offsetOf(int glLocation, String type) {
        for (VulkanShaderMeta.PushMember m : this.meta.push()) {
            if (m.location() != glLocation) continue;
            if (!m.type().equals(type)) {
                throw new IllegalArgumentException("GL location " + glLocation + " (" + m.name() + ") is " + m.type() + ", not " + type);
            }
            return m.offset();
        }
        throw new IllegalArgumentException("no loose uniform at GL location " + glLocation);
    }
}
