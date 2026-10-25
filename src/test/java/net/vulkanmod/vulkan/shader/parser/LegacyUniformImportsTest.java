package net.vulkanmod.vulkan.shader.parser;

import net.vulkanmod.visuals.vk.shader.ShadercTestUtil;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** A plain uniform in a #moj_import file (Certain Questing Additions' custom_background) reaches the converter. */
class LegacyUniformImportsTest {
    private static final String COMPAT = """
            #ifdef NEW_PIPELINE
            layout(std140) uniform Globals {
                vec2 ScreenSize;
            };
            in vec4 vertexColor;
            #define size ScreenSize
            #else
            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            uniform vec2 size;
            uniform float time;
            #endif
            """;
    private static final String FRAGMENT = """
            #version 330
            in vec2 texCoord0;
            out vec4 fragColor;
            #moj_import <test:compat.glsl>
            #moj_import <fog.glsl>
            void main() {
                fragColor = vec4(texCoord0 * size, sin(time), 1.0);
            }
            """;
    private static final String VERTEX = """
            #version 150
            in vec3 Position;
            in vec2 UV0;
            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            out vec2 texCoord0;
            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                texCoord0 = UV0;
            }
            """;

    private static final LegacyUniformImports.Reader READER = (relative, name) -> Map.of(
            "test:compat.glsl", COMPAT,
            "fog.glsl", "vec4 linear_fog(vec4 c, float d, float s, float e, vec4 f) { return c; }\n").get(name);

    @Test
    void inlinesOnlyImportsWithPlainUniforms() throws IOException {
        String out = LegacyUniformImports.inline(FRAGMENT, READER);
        assertFalse(out.contains("test:compat.glsl"), out);
        assertTrue(out.contains("uniform vec2 size;"), out);
        assertTrue(out.contains("#moj_import <fog.glsl>"), out);
    }

    @Test
    void takesTheBranchThePreprocessorWould() throws IOException {
        String out = LegacyUniformImports.inline(FRAGMENT, READER);
        assertFalse(out.contains("Globals"), out);
        assertFalse(out.contains("vertexColor"), out);
        assertFalse(out.contains("#ifdef"), out);
        assertFalse(out.contains("#endif"), out);

        String defined = LegacyUniformImports.inline("#define NEW_PIPELINE\n" + FRAGMENT, READER);
        assertTrue(defined.contains("Globals"), defined);
        assertFalse(defined.contains("uniform float time;"), defined);
    }

    @Test
    void keepsConditionalsItCannotDecide() {
        String source = "#if VERSION > 2\nuniform float a;\n#else\nuniform float b;\n#endif\n#ifdef X\nuniform float c;\n#endif";
        String out = LegacyUniformImports.resolveConditionals(source, Set.of());
        assertTrue(out.contains("#if VERSION > 2") && out.contains("#else") && out.contains("uniform float a;"), out);
        assertFalse(out.contains("uniform float c;"), out);
    }

    @Test
    void inlinesAFileOnce() throws IOException {
        String out = LegacyUniformImports.inline(FRAGMENT + "#moj_import <test:compat.glsl>\n", READER);
        assertEquals(1, out.split("uniform vec2 size;", -1).length - 1, out);
    }

    @Test
    void convertedShaderCompilesForVulkan() throws IOException {
        GlslConverter converter = new GlslConverter();
        converter.process(VERTEX, LegacyUniformImports.inline(FRAGMENT, READER).replace("#moj_import <fog.glsl>", ""));
        String vertexError = ShadercTestUtil.compile("v.vsh", converter.getVshConverted(), Shaderc.shaderc_glsl_vertex_shader, true);
        String fragmentError = ShadercTestUtil.compile("f.fsh", converter.getFshConverted(), Shaderc.shaderc_glsl_fragment_shader, true);
        assertNull(vertexError, vertexError + "\n" + converter.getVshConverted());
        assertNull(fragmentError, fragmentError + "\n" + converter.getFshConverted());
    }
}
