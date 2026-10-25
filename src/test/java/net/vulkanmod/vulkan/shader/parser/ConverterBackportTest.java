package net.vulkanmod.vulkan.shader.parser;

import net.vulkanmod.visuals.vk.shader.ShadercTestUtil;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;
import net.vulkanmod.vulkan.shader.layout.Uniform;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import static org.junit.jupiter.api.Assertions.*;

/** What Reforged 0.3.5 converts on 1.21.1 and this 1.20.1 build now does too: qualified varyings, integer vector uniforms, mat3. */
class ConverterBackportTest {
    private static final String VERTEX = """
            #version 150
            in vec3 Position;
            in vec2 UV0;
            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            uniform ivec2 LightUV;
            out vec2 texCoord0;
            flat out int power;
            flat out vec2 corner;
            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                texCoord0 = UV0 + vec2(LightUV) / 256.0;
                power = LightUV.x;
                corner = UV0;
            }
            """;
    private static final String FRAGMENT = """
            #version 150
            in vec2 texCoord0;
            flat in int power;
            flat in vec2 corner;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(texCoord0 + corner, float(power), 1.0);
            }
            """;

    @Test
    void qualifiedVaryingsAndIntegerUniformsCompile() {
        GlslConverter converter = new GlslConverter();
        converter.process(VERTEX, FRAGMENT);
        String vertex = converter.getVshConverted(), fragment = converter.getFshConverted();
        assertTrue(vertex.matches("(?s).*layout\\(location = \\d+\\) flat out int power;.*"), vertex);
        assertTrue(fragment.matches("(?s).*layout\\(location = \\d+\\) flat in int power;.*"), fragment);
        assertTrue(vertex.contains("ivec2 LightUV;"), vertex);
        String error = ShadercTestUtil.compile("v.vsh", vertex, Shaderc.shaderc_glsl_vertex_shader, true);
        assertNull(error, error + "\n" + vertex);
        error = ShadercTestUtil.compile("f.fsh", fragment, Shaderc.shaderc_glsl_fragment_shader, true);
        assertNull(error, error + "\n" + fragment);
    }

    /** Mods' 1.20.1 shaders call fog_distance(ModelViewMat, Position, FogShape); VulkanMod's fog.glsl is found before the game's. */
    @Test
    void fogIncludeHasBothForms() throws Exception {
        String fog = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/assets/vulkanmod/shaders/include/fog.glsl"));
        String shader = "#version 450\nlayout(location = 0) in vec3 Position;\nlayout(location = 0) out float d;\n"
                + "layout(binding = 0) uniform U { mat4 ModelViewMat; int FogShape; };\n" + fog
                + "\nvoid main() { d = fog_distance(ModelViewMat, Position, FogShape) + fog_distance(Position, FogShape);"
                + " gl_Position = vec4(Position, 1.0); }\n";
        String error = ShadercTestUtil.compile("fog.vsh", shader, Shaderc.shaderc_glsl_vertex_shader, true);
        assertNull(error, error);
    }

    @Test
    void aMat3TakesThreeFullColumns() {
        AlignedStruct.Builder builder = new AlignedStruct.Builder();
        builder.addUniformInfo("mat3", "Normal");
        builder.addUniformInfo("float", "After");
        var uniforms = builder.buildUBO(0, 0).getUniforms();
        Uniform after = uniforms.get(1);
        //Mat3.update writes its third column at 32 bytes (words 8 to 10): the next uniform starts at word 12
        assertEquals(12, after.getOffset());
    }

    @Test
    void integerVectorsFromProgramJson() {
        AlignedStruct.Builder builder = new AlignedStruct.Builder();
        builder.addUniformInfo("float", "Before", 1);
        builder.addUniformInfo("int", "OverlayUV", 2);
        builder.addUniformInfo("float", "After", 1);
        var uniforms = builder.buildUBO(0, 0).getUniforms();
        assertEquals(2, uniforms.get(1).getOffset(), "an ivec2 aligns to 2 words");
        assertEquals(2, uniforms.get(1).getSize());
        assertEquals(4, uniforms.get(2).getOffset());
    }
}
