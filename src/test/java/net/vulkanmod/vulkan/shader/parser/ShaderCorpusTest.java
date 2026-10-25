package net.vulkanmod.vulkan.shader.parser;

import net.vulkanmod.visuals.vk.shader.ShadercTestUtil;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Mod shader programs that failed to convert in the 1.20.1 pack runs (a corpus outside the repository, extracted from the packs'
 * mod jars: -Dshader.corpus=DIR, one directory per program with program.json, vertex.vsh, fragment.fsh and include/NAMESPACE/FILE):
 * each converted as the game converts it and compiled for Vulkan; the report goes to build/shader-corpus.txt. A measurement, not a
 * gate: it never fails, and it is skipped without a corpus.
 */
class ShaderCorpusTest {
    private static final Pattern IMPORT = Pattern.compile("^\\s*#\\s*(?:moj_import|include)\\s*[<\"]([^>\"]+)[>\"].*$", Pattern.MULTILINE);

    @Test
    void corpus() throws Exception {
        String corpus = System.getProperty("shader.corpus");
        assumeTrue(corpus != null && Files.isDirectory(Path.of(corpus)), "no shader corpus (-Dshader.corpus=DIR)");
        Path root = Path.of(corpus);
        StringBuilder report = new StringBuilder();
        int ok = 0, total = 0;
        List<Path> programs;
        try (Stream<Path> s = Files.list(root)) {
            programs = s.filter(Files::isDirectory).sorted().toList();
        }
        for (Path dir : programs) {
            total++;
            String result;
            try {
                String vertex = Files.readString(dir.resolve("vertex.vsh"));
                String fragment = Files.readString(dir.resolve("fragment.fsh"));
                vertex = inlineUniformImports(vertex, dir);
                fragment = inlineUniformImports(fragment, dir);
                GlslConverter converter = new GlslConverter();
                converter.process(vertex, fragment);
                String v = ShadercTestUtil.compile("v.vsh", expand(converter.getVshConverted(), dir, new HashSet<>()),
                        Shaderc.shaderc_glsl_vertex_shader, true);
                String f = ShadercTestUtil.compile("f.fsh", expand(converter.getFshConverted(), dir, new HashSet<>()),
                        Shaderc.shaderc_glsl_fragment_shader, true);
                result = v == null && f == null ? "OK" : "COMPILE " + firstLine(v != null ? v : f);
            } catch (Exception | Error e) {
                result = "CONVERT " + e.getClass().getSimpleName() + ": " + firstLine(String.valueOf(e.getMessage()));
            }
            if (result.equals("OK")) ok++;
            report.append(String.format("%-48s %s%n", dir.getFileName(), result));
        }
        report.append(String.format("%d / %d OK%n", ok, total));
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "shader-corpus.txt"), report.toString(), StandardCharsets.UTF_8);
        System.out.print(report);
    }

    /** The game inlines imports that declare plain uniforms before converting (LegacyUniformImports), where that class exists. */
    private static String inlineUniformImports(String source, Path dir) throws Exception {
        Class<?> imports;
        try {
            imports = Class.forName("net.vulkanmod.vulkan.shader.parser.LegacyUniformImports");
        } catch (ClassNotFoundException e) {
            return source;
        }
        Class<?> reader = Class.forName("net.vulkanmod.vulkan.shader.parser.LegacyUniformImports$Reader");
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(reader.getClassLoader(), new Class<?>[]{reader},
                (self, method, args) -> method.getName().equals("read") ? include(dir, (String) args[1]) : null);
        Method inline = imports.getMethod("inline", String.class, reader);
        return (String) inline.invoke(null, source, proxy);
    }

    private static String include(Path dir, String name) throws IOException {
        String ns = name.contains(":") ? name.substring(0, name.indexOf(':')) : "minecraft";
        String path = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
        //As SPIRVUtils looks: VulkanMod's own include directory first, then the game's
        Path own = Path.of("src/main/resources/assets/vulkanmod/shaders/include").resolve(path);
        if (!name.contains(":") && Files.exists(own)) return Files.readString(own);
        Path file = dir.resolve("include").resolve(ns).resolve(path);
        return Files.exists(file) ? Files.readString(file) : null;
    }

    /** As shaderc's include callback does in the game: each import once, its #version dropped. */
    private static String expand(String code, Path dir, Set<String> seen) throws IOException {
        Matcher m = IMPORT.matcher(code);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String body = seen.add(m.group(1)) ? include(dir, m.group(1)) : "";
            if (body == null) throw new IOException("missing include " + m.group(1));
            body = expand(body.replaceAll("(?m)^\\s*#\\s*version.*$", ""), dir, seen);
            m.appendReplacement(out, Matcher.quoteReplacement(body));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String firstLine(String s) {
        String t = s.strip();
        int nl = t.indexOf('\n');
        return (nl < 0 ? t : t.substring(0, nl)).replaceAll("\\s+", " ");
    }
}
