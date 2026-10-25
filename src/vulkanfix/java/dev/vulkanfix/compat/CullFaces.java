package dev.vulkanfix.compat;

/** Vulkan cull modes (NONE 0, FRONT 1, BACK 2, FRONT_AND_BACK 3) under a mirrored winding. */
public final class CullFaces {
    private CullFaces() {
    }

    public static int forGlMemoryLayout(int vkCullMode) {
        return switch (vkCullMode) {
            case 1 -> 2;
            case 2 -> 1;
            default -> vkCullMode;
        };
    }
}
