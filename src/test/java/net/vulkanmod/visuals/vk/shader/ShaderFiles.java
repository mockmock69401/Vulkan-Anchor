package net.vulkanmod.visuals.vk.shader;

/** Test helper: the text of a visuals shader file, given its path under {@code assets/vulkanmod/visuals/shaders/}. */
public final class ShaderFiles {
    private ShaderFiles() {
    }

    public static String read(String path) {
        String text = ShaderSource.classpath().apply("vulkanmod:visuals/" + path);
        if (text == null) throw new IllegalArgumentException("shader file not found: " + path);
        return text;
    }
}
