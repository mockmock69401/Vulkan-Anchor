package net.vulkanmod.vulkan.shader.parser;

import net.vulkanmod.visuals.vk.shader.ShadercTestUtil;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lodestone's nine-slice programs: the vertex stage writes an interface block, a geometry stage reads it per triangle and writes the
 * fragment inputs (one of them flat, computed from the whole triangle).
 */
class LegacyGeometryStageTest {
    private static final String VERTEX = """
            #version 400 core
            in vec3 Position;
            in vec4 Color;
            in vec2 UV0;
            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            out PASS {
                vec3 corner;
                vec4 tint;
                vec2 uv;
            } pass_out;
            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                pass_out.corner = Position;
                pass_out.tint = Color;
                pass_out.uv = UV0;
            }
            """;
    private static final String GEOMETRY = """
            #version 400 core
            layout (triangles) in;
            layout (triangle_strip, max_vertices = 3) out;
            in PASS {
                vec3 corner;
                vec4 tint;
                vec2 uv;
            } pass_in[];
            uniform float Scale;
            out vec4 vertexColor;
            out vec2 texCoord;
            flat out vec2 extent;
            void main() {
                extent = Scale * vec2(distance(pass_in[0].corner, pass_in[1].corner), distance(pass_in[1].corner, pass_in[2].corner));
                for (int i = 0; i < gl_in.length(); i++) {
                    gl_Position = gl_in[i].gl_Position;
                    vertexColor = pass_in[i].tint;
                    texCoord = pass_in[i].uv;
                    EmitVertex();
                }
                EndPrimitive();
            }
            """;
    private static final String FRAGMENT = """
            #version 150
            uniform sampler2D Sampler0;
            uniform vec4 ColorModulator;
            in vec4 vertexColor;
            in vec2 texCoord;
            flat in vec2 extent;
            out vec4 fragColor;
            void main() {
                fragColor = texture(Sampler0, fract(texCoord * extent)) * vertexColor * ColorModulator;
            }
            """;

    @Test
    void flattensBlocksAndTheirAccesses() {
        String vertex = LegacyInterfaceBlocks.flatten(VERTEX);
        assertTrue(vertex.contains("out vec4 vk_PASS_tint;"), vertex);
        assertTrue(vertex.contains("vk_PASS_tint = Color;"), vertex);
        assertFalse(vertex.contains("pass_out"), vertex);

        String geometry = LegacyInterfaceBlocks.flatten(GEOMETRY);
        assertTrue(geometry.contains("in vec3 vk_PASS_corner[];"), geometry);
        assertTrue(geometry.contains("vk_PASS_corner[0]"), geometry);
        assertTrue(geometry.contains("vertexColor = vk_PASS_tint[i];"), geometry);
        assertFalse(geometry.contains("pass_in"), geometry);
    }

    @Test
    void leavesBuiltInAndAnonymousBlocksAlone() {
        String perVertex = "out gl_PerVertex { vec4 gl_Position; };\nvoid main() {}\n";
        assertEquals(perVertex, LegacyInterfaceBlocks.flatten(perVertex));
        String anonymous = LegacyInterfaceBlocks.flatten("out Varyings { flat int id; vec2 uv; };\nvoid main() { uv = vec2(id); }\n");
        assertTrue(anonymous.contains("flat out int id;") && anonymous.contains("out vec2 uv;") && anonymous.contains("uv = vec2(id);"),
                anonymous);
    }

    @Test
    void rejectsAWholeBlockUse() {
        assertThrows(IllegalArgumentException.class,
                () -> LegacyInterfaceBlocks.flatten("out B { vec2 a; } b;\nvoid f(B x) {}\nvoid main() { f(b); }\n"));
    }

    @Test
    void geometryStageLinksBothSides() {
        GlslConverter converter = new GlslConverter();
        converter.process(VERTEX, GEOMETRY, FRAGMENT);
        String vertex = converter.getVshConverted(), geometry = converter.getGshConverted(), fragment = converter.getFshConverted();

        for (String name : new String[]{"vk_PASS_corner", "vk_PASS_tint", "vk_PASS_uv"}) {
            assertEquals(location(vertex, "out \\w+ " + name + ";"), location(geometry, "in \\w+ " + name + "\\[\\];"), name);
        }
        for (String name : new String[]{"vertexColor", "texCoord", "extent"}) {
            assertEquals(location(geometry, "out \\w+ " + name + ";"), location(fragment, "in \\w+ " + name + ";"), name);
        }
        assertTrue(fragment.contains("flat in vec2 extent;"), fragment);
        //One block for every stage: the geometry stage's uniform sits beside the others
        assertTrue(geometry.contains("float Scale;") && vertex.contains("float Scale;") && fragment.contains("float Scale;"), geometry);

        assertCompiles("v.vsh", vertex, Shaderc.shaderc_glsl_vertex_shader);
        assertCompiles("g.gsh", geometry, Shaderc.shaderc_glsl_geometry_shader);
        assertCompiles("f.fsh", fragment, Shaderc.shaderc_glsl_fragment_shader);
    }

    @Test
    void withoutGeometryTheFragmentReadsTheVertexBlock() {
        String fragment = FRAGMENT.replace("in vec4 vertexColor;\nin vec2 texCoord;\nflat in vec2 extent;",
                "in PASS {\n    vec3 corner;\n    vec4 tint;\n    vec2 uv;\n} pass_in;")
                .replace("texCoord * extent", "pass_in.uv").replace("* vertexColor", "* pass_in.tint");
        GlslConverter converter = new GlslConverter();
        converter.process(VERTEX, fragment);
        assertNull(converter.getGshConverted());
        assertEquals(location(converter.getVshConverted(), "out \\w+ vk_PASS_tint;"),
                location(converter.getFshConverted(), "in \\w+ vk_PASS_tint;"));
        assertCompiles("f.fsh", converter.getFshConverted(), Shaderc.shaderc_glsl_fragment_shader);
    }

    @Test
    void aGeometryInputWithoutAVertexOutputFails() {
        String geometry = GEOMETRY.replace("vec2 uv;\n} pass_in[];", "vec2 uv;\n} pass_in[];\nin float missing[];");
        assertThrows(IllegalArgumentException.class, () -> new GlslConverter().process(VERTEX, geometry, FRAGMENT));
    }

    private static int location(String code, String declaration) {
        Matcher matcher = Pattern.compile("layout\\(location = (\\d+)\\) (?:flat )?" + declaration).matcher(code);
        assertTrue(matcher.find(), declaration + " in\n" + code);
        return Integer.parseInt(matcher.group(1));
    }

    private static void assertCompiles(String name, String code, int kind) {
        String error = ShadercTestUtil.compile(name, code, kind, true);
        assertNull(error, error + "\n" + code);
    }
}
