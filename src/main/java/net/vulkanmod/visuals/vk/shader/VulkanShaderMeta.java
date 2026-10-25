package net.vulkanmod.visuals.vk.shader;

import java.util.List;

/**
 * What the Vulkan backend needs to know about a converted shader: the loose-uniform layout and descriptor bindings.
 * Loose uniforms live in push constants, or in the std140 block VisualsLooseUniforms (set 1) when pushInUniformBuffer.
 */
public record VulkanShaderMeta(List<PushMember> push, int pushSize, boolean pushInUniformBuffer, List<Binding> bindings) {
    public enum Kind { STORAGE_BUFFER, UNIFORM_BUFFER, SAMPLER, IMAGE }

    /** location is the GL uniform location the Java side sets; offset is its offset in the push constant (or uniform) block. */
    public record PushMember(String name, String type, int location, int offset) {
    }

    public record Binding(int set, int binding, Kind kind) {
    }

    public VulkanShaderMeta(List<PushMember> push, int pushSize, List<Binding> bindings) {
        this(push, pushSize, false, bindings);
    }

    /** Bytes of push constants the pipeline layout needs (0 when the loose uniforms live in a uniform block). */
    public int pushConstantBytes() {
        return this.pushInUniformBuffer ? 0 : this.pushSize;
    }

    public VulkanShaderMeta withBindings(List<Binding> bindings) {
        return new VulkanShaderMeta(this.push, this.pushSize, this.pushInUniformBuffer, bindings);
    }
}
