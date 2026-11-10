package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reforged-facing calls: transitions go through Reforged's VulkanImage so its tracked layout stays true. */
class VkContextTest {
    private static List<String> calls(String method) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = VkContextTest.class.getClassLoader().getResourceAsStream("net/vulkanmod/visuals/vk/VkContext.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, 0);
        }
        List<String> calls = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (!m.name.equals(method)) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.name);
            }
        }
        return calls;
    }

    @Test
    void mainTargetComesFromTheBoundFramebuffer() throws Exception {
        List<String> calls = calls("captureMainTarget");
        assertTrue(calls.contains("getBoundFramebuffer") && calls.contains("getDepthAttachment"), calls.toString());
    }

    @Test
    void mainDepthAndLightmapUseReforgedsLayoutTracking() throws Exception {
        assertTrue(calls("mainDepthToShaderRead").contains("transitionImageLayout"));
        List<String> lightmap = calls("lightmapView");
        assertTrue(lightmap.indexOf("readOnlyLayout") >= 0 && lightmap.indexOf("readOnlyLayout") < lightmap.indexOf("getImageView"), lightmap.toString());
    }

    /** Review focus 5: the main depth may be D24S8; the visuals samples it through a depth-only view. */
    @Test
    void mainDepthViewIsDepthOnly() throws Exception {
        assertTrue(calls("createMainDepthView").contains("createView"));
    }

    /** Review focus 5: the largest DEVICE_LOCAL heap, not the shared system memory heap. */
    @Test
    void picksTheLargestDeviceLocalHeap() {
        int deviceLocal = org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
        assertEquals(12L << 30, VkContext.largestDeviceLocal(new long[]{12L << 30, 32L << 30, 256L << 20}, new int[]{deviceLocal, 0, deviceLocal}));
        assertEquals(0L, VkContext.largestDeviceLocal(new long[]{8L << 30}, new int[]{0}));
    }

    /** The free memory of the heap the geometry pool lands in: budget less usage of the largest DEVICE_LOCAL heap. */
    @Test
    void freeMemoryIsTheLargestDeviceLocalHeapsBudgetLessUsage() {
        int deviceLocal = org.lwjgl.vulkan.VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT;
        long[] sizes = {12L << 30, 32L << 30, 256L << 20};
        int[] flags = {deviceLocal, 0, deviceLocal};
        assertEquals(9L << 30, VkContext.freeOfLargestDeviceLocal(sizes, flags, new long[]{11L << 30, 16L << 30, 200L << 20}, new long[]{2L << 30, 1L << 30, 0}));
        assertEquals(0L, VkContext.freeOfLargestDeviceLocal(sizes, flags, new long[]{1L << 30, 0, 0}, new long[]{2L << 30, 0, 0}));
        assertEquals(-1L, VkContext.freeOfLargestDeviceLocal(new long[]{8L << 30}, new int[]{0}, new long[]{8L << 30}, new long[]{0}));
    }

    /** P4a final review I1: SSAO's AUTO mode needs to know whether the GPU has its own memory. */
    @Test
    void discreteGpusAreToldApart() {
        assertTrue(VkContext.isDiscrete(org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU));
        assertFalse(VkContext.isDiscrete(org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU));
    }

    /** P5b deferred minor: a loader or driver failure in the budget query falls back to half the heap instead of no LODs. */
    @Test
    void aFailingMemoryQueryMeansUnknown() {
        assertEquals(-1L, VkContext.guardedQuery(() -> { throw new IllegalStateException("no entry point"); }));
        assertEquals(42L, VkContext.guardedQuery(() -> 42L));
    }

    /** Session 7: compiled SPIR-V is reused for the same stage kind, name and source; any change in one of them compiles again. */
    @Test
    void theSpirvCacheKeyTakesTheKindTheNameAndTheWholeSource() {
        String src = "#version 450\n#define REFLECT_LEAN\nvoid main() {}\n";
        assertEquals(VkContext.spirvKey("postReflection.LEAN.comp", src, "COMPUTE_SHADER"), VkContext.spirvKey("postReflection.LEAN.comp", new String(src), "COMPUTE_SHADER"));
        assertFalse(VkContext.spirvKey("a.comp", src, "COMPUTE_SHADER").equals(VkContext.spirvKey("a.comp", src.replace("REFLECT_LEAN", "OTHER"), "COMPUTE_SHADER")));
        assertFalse(VkContext.spirvKey("a.comp", src, "COMPUTE_SHADER").equals(VkContext.spirvKey("b.comp", src, "COMPUTE_SHADER")));
        assertFalse(VkContext.spirvKey("a", src, "VERTEX_SHADER").equals(VkContext.spirvKey("a", src, "FRAGMENT_SHADER")));
    }
}
