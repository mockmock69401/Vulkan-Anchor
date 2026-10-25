package dev.vulkanfix.compat;

/** Resource paths in the exact form VulkanMod-Reforged's ShaderInstanceM expects for vsPath / fsName. */
public final class ShaderStagePaths {
    private ShaderStagePaths() {
    }

    /** "ns:name" or "name" (minecraft namespace) -> "ns:shaders/core/name" (no extension; Reforged appends it). */
    public static String forStage(String stageName) {
        int colon = stageName.indexOf(':');
        String namespace = colon >= 0 ? stageName.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? stageName.substring(colon + 1) : stageName;
        return namespace + ":shaders/core/" + path;
    }

    /** Location of the shader program JSON, as vanilla ShaderInstance builds it. */
    public static String jsonFor(String namespace, String path) {
        return namespace + ":shaders/core/" + path + ".json";
    }
}
