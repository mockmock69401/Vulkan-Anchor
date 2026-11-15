package net.vulkanmod.vulkan.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-15 (the user: Tensura (Vulkan) froze on launch): Great Sage's legacy core shader is bigger than LWJGL's thread stack (64 KB
 * in the game). Preprocessing it encoded the source on that stack ("OutOfMemoryError: Out of stack space"), its RegisterShadersEvent
 * failed and Minecraft dropped every selected resource pack. The tests run with a 1 MB LWJGL stack (build.gradle), so the source
 * here is bigger than that.
 */
class LargeShaderSourceTest {
    private static String bigFragmentShader() {
        StringBuilder sb = new StringBuilder(1_400_000);
        sb.append("#version 450\n\nlayout(location = 0) out vec4 fragColor;\n\n");
        int i = 0;
        while (sb.length() < 1_300_000) {
            sb.append("// padding line ").append(i).append(" so the source outgrows the LWJGL thread stack .......................\n");
            if (i % 50 == 0) sb.append("float f").append(i).append("(float x) { return x * ").append(i).append(".0 + 1.0; }\n");
            i++;
        }
        sb.append("void main() {\n    fragColor = vec4(f0(0.5), f50(0.25), 0.0, 1.0);\n}\n");
        return sb.toString();
    }

    /** JUnit rethrows an OutOfMemoryError as unrecoverable (the test shows as skipped), so it becomes a plain failure here. */
    private static <T> T withoutStackOverflow(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (OutOfMemoryError e) {
            return fail("encoded on the LWJGL thread stack: " + e.getMessage(), e);
        }
    }

    @Test
    void aSourceBiggerThanTheThreadStackPreprocesses() {
        String source = bigFragmentShader();
        assertTrue(source.length() > 1024 * 1024);
        String out = withoutStackOverflow(() -> SPIRVUtils.preprocessShader("big.fsh", source, SPIRVUtils.ShaderKind.FRAGMENT_SHADER));
        assertTrue(out.contains("void main()"), "preprocessed text keeps main");
        assertFalse(out.contains("padding line"), "comments are stripped by the preprocessor");
    }

    @Test
    void aSourceBiggerThanTheThreadStackCompiles() {
        assertNotNull(withoutStackOverflow(() -> SPIRVUtils.compileShader("big.fsh", bigFragmentShader(), SPIRVUtils.ShaderKind.FRAGMENT_SHADER)));
    }
}
