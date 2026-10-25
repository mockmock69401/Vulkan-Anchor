package net.vulkanmod.visuals.vk.shader;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ShaderSourceTest {
    private static Function<String, String> files(Map<String, String> files) {
        return files::get;
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
        return n;
    }

    @Test
    void importedTwiceIsInlinedOnce() {
        String out = ShaderSource.parse("vulkanmod:visuals/main.comp", files(Map.of(
                "vulkanmod:visuals/main.comp", "#version 450\n#import <vulkanmod:visuals/a.glsl>\n#import <vulkanmod:visuals/a.glsl>\nvoid main(){}\n",
                "vulkanmod:visuals/a.glsl", "float a_body = 1.0;\n")));
        assertEquals(1, count(out, "float a_body"), out);
        assertFalse(out.contains("#import"), out);
    }

    @Test
    void nestedImportsAreExpanded() {
        String out = ShaderSource.parse("vulkanmod:visuals/main.comp", files(Map.of(
                "vulkanmod:visuals/main.comp", "#import <vulkanmod:visuals/a.glsl>\nvoid main(){}\n",
                "vulkanmod:visuals/a.glsl", "#import <vulkanmod:visuals/b.glsl>\nfloat from_a;\n",
                "vulkanmod:visuals/b.glsl", "float from_b;\n")));
        assertTrue(out.contains("float from_a;") && out.contains("float from_b;"), out);
        assertTrue(out.indexOf("from_b") < out.indexOf("from_a"), "a dependency comes before its user: " + out);
    }

    @Test
    void sharedDependencyOfTwoImportsIsInlinedOnce() {
        String out = ShaderSource.parse("vulkanmod:visuals/main.comp", files(Map.of(
                "vulkanmod:visuals/main.comp", "#import <vulkanmod:visuals/a.glsl>\n#import <vulkanmod:visuals/c.glsl>\n",
                "vulkanmod:visuals/a.glsl", "#import <vulkanmod:visuals/b.glsl>\nfloat from_a;\n",
                "vulkanmod:visuals/c.glsl", "#import <vulkanmod:visuals/b.glsl>\nfloat from_c;\n",
                "vulkanmod:visuals/b.glsl", "float from_b;\n")));
        assertEquals(1, count(out, "float from_b;"), out);
    }

    @Test
    void firstLineIsTheOnlyVersionHeader() {
        String out = ShaderSource.parse("vulkanmod:visuals/main.comp", files(Map.of(
                "vulkanmod:visuals/main.comp", "// leading comment\n#version 330 core\n#import <vulkanmod:visuals/a.glsl>\nvoid main(){}\n",
                "vulkanmod:visuals/a.glsl", "#version 150\nfloat a;\n")));
        assertEquals("#version 460 core", out.lines().findFirst().orElseThrow(), out);
        assertEquals(1, count(out, "#version"), out);
        assertTrue(out.contains("void main(){}") && out.contains("float a;"), out);
    }

    @Test
    void missingImportNamesTheFile() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ShaderSource.parse("vulkanmod:visuals/main.comp", files(Map.of(
                "vulkanmod:visuals/main.comp", "#import <vulkanmod:visuals/gone.glsl>\n"))));
        assertTrue(e.getMessage().contains("vulkanmod:visuals/gone.glsl"), e.getMessage());
    }

    @Test
    void missingRootNamesTheFile() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ShaderSource.parse("vulkanmod:visuals/none.comp", files(Map.of())));
        assertTrue(e.getMessage().contains("vulkanmod:visuals/none.comp"), e.getMessage());
    }

    @Test
    void assembleInsertsDefinesAfterTheHeaderAndAppliesReplacements() {
        String parsed = ShaderSource.parse("vulkanmod:visuals/main.comp", files(Map.of("vulkanmod:visuals/main.comp", "int x = OLD;\n")));
        Map<String, String> defines = new java.util.LinkedHashMap<>();
        defines.put("B", "2");
        defines.put("FLAG", "");
        String out = ShaderSource.assemble(parsed, defines, Map.of("OLD", "NEW"));
        List<String> lines = out.lines().toList();
        assertEquals("#version 460 core", lines.get(0));
        assertEquals("#define B 2", lines.get(1));
        assertEquals("#define FLAG", lines.get(2));
        assertTrue(out.contains("int x = NEW;"), out);
    }

    @Test
    void classpathReaderMapsIdsUnderTheShadersRoot() {
        assertEquals("assets/vulkanmod/visuals/shaders/util/depthutils.glsl", ShaderSource.resourcePath("vulkanmod:visuals/util/depthutils.glsl"));
        assertNull(ShaderSource.classpath().apply("vulkanmod:visuals/definitely/not/here.glsl"));
    }

    @Test
    void catalogMapsProgramFilesUnderItsRoot() {
        ShaderCatalog catalog = new ShaderCatalog("vulkanmod", "visuals/shaders");
        assertEquals("vulkanmod:visuals/post/x.comp", catalog.fileId("post/x.comp"));
        assertFalse(ShaderCatalog.visuals().all().isEmpty());
        ShaderCatalog.Program p = new ShaderCatalog.Program("x", List.of(catalog.compute("post/x.comp")), Map.of(), Map.of());
        assertSame(p, catalog.add("x", p).get("x"));
        assertThrows(IllegalArgumentException.class, () -> catalog.get("y"));
    }
}
