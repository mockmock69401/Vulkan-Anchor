package net.vulkanmod.visuals.vk;

import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Push constant values set by the GL uniform location the Java side used with glUniform*. */
public final class PushConstantWriter {
    private final VulkanShaderMeta meta;
    private final ByteBuffer buffer;

    public PushConstantWriter(VulkanShaderMeta meta) {
        if (meta.pushInUniformBuffer() || meta.pushSize() == 0) {
            throw new IllegalArgumentException("shader has no push constants");
        }
        this.meta = meta;
        this.buffer = ByteBuffer.allocateDirect(meta.pushSize()).order(ByteOrder.nativeOrder());
    }

    public PushConstantWriter putInt(int glLocation, int value) {
        this.buffer.putInt(this.offsetOf(glLocation), value);
        return this;
    }

    public PushConstantWriter putFloat(int glLocation, float value) {
        this.buffer.putFloat(this.offsetOf(glLocation), value);
        return this;
    }

    /** The whole block, position 0, limit pushSize. */
    public ByteBuffer buffer() {
        return this.buffer.duplicate().order(ByteOrder.nativeOrder()).position(0).limit(this.meta.pushSize());
    }

    private int offsetOf(int glLocation) {
        for (VulkanShaderMeta.PushMember m : this.meta.push()) {
            if (m.location() == glLocation) return m.offset();
        }
        throw new IllegalArgumentException("no push constant at GL location " + glLocation);
    }
}
