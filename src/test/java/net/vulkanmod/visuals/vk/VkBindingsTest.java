package net.vulkanmod.visuals.vk;

import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VkBindingsTest {
    private static final VulkanShaderMeta META = new VulkanShaderMeta(List.of(), 0, List.of(
            new VulkanShaderMeta.Binding(0, 2, VulkanShaderMeta.Kind.STORAGE_BUFFER),
            new VulkanShaderMeta.Binding(1, 1, VulkanShaderMeta.Kind.UNIFORM_BUFFER),
            new VulkanShaderMeta.Binding(2, 0, VulkanShaderMeta.Kind.SAMPLER)));

    private static VkBindings.Entry storage(int binding) {
        return new VkBindings.Entry(VulkanShaderMeta.Kind.STORAGE_BUFFER, binding, 1, 0, -1, 0, 0);
    }

    private static VkBindings complete() {
        return new VkBindings().add(storage(2))
                .add(new VkBindings.Entry(VulkanShaderMeta.Kind.UNIFORM_BUFFER, 1, 2, 0, -1, 0, 0))
                .add(new VkBindings.Entry(VulkanShaderMeta.Kind.SAMPLER, 0, 0, 0, 0, 3, 4));
    }

    @Test
    void everyDeclaredResourceProvidedOnceIsValid() {
        assertDoesNotThrow(() -> complete().validate("p", META));
    }

    @Test
    void aMissingResourceIsNamed() {
        VkBindings b = new VkBindings().add(storage(2)).add(new VkBindings.Entry(VulkanShaderMeta.Kind.SAMPLER, 0, 0, 0, 0, 3, 4));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> b.validate("traversal", META));
        assertTrue(e.getMessage().contains("traversal") && e.getMessage().contains("1:1"), e.getMessage());
    }

    @Test
    void undeclaredDuplicateOrWrongKindIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> complete().add(storage(7)).validate("p", META));
        assertThrows(IllegalArgumentException.class, () -> complete().add(storage(2)).validate("p", META));
        VkBindings wrongKind = new VkBindings().add(new VkBindings.Entry(VulkanShaderMeta.Kind.IMAGE, 2, 0, 0, 0, 3, 0));
        assertThrows(IllegalArgumentException.class, () -> wrongKind.validate("p", META));
    }

    /** Reforged's lightmap and main depth are bound by view; they count as the sampler the shader declares. */
    @Test
    void externalViewsBindAsSamplers() {
        VulkanShaderMeta meta = new VulkanShaderMeta(List.of(), 0,
                List.of(new VulkanShaderMeta.Binding(GlslForVulkan.SET_SAMPLER, 1, VulkanShaderMeta.Kind.SAMPLER)));
        new VkBindings().sampledView(1, 42L, 7L).validate("lightmap", meta);
    }

    /** NodeCleaner binds two ranges of one output buffer. */
    @Test
    void storageRangesBindAsStorageBuffers() {
        VulkanShaderMeta meta = new VulkanShaderMeta(List.of(), 0,
                List.of(new VulkanShaderMeta.Binding(GlslForVulkan.SET_STORAGE, 2, VulkanShaderMeta.Kind.STORAGE_BUFFER)));
        new VkBindings().add(new VkBindings.Entry(VulkanShaderMeta.Kind.STORAGE_BUFFER, 2, 1L, 1024, 2048, 0, 0)).validate("range", meta);
    }

    /** ssao.comp writes its output through a storage image (set 3). */
    @Test
    void storageImagesBindAsImages() {
        VulkanShaderMeta meta = new VulkanShaderMeta(List.of(), 0,
                List.of(new VulkanShaderMeta.Binding(GlslForVulkan.SET_IMAGE, 0, VulkanShaderMeta.Kind.IMAGE)));
        new VkBindings().storageImage(0, 42L).validate("ssao", meta);
    }
}
