package net.vulkanmod.visuals.vk.shader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a shader file id ("namespace:visuals/path") into one GLSL text: {@code #import <namespace:visuals/path>} lines are
 * replaced by the imported file (each file once, a dependency before its user), every {@code #version} line is dropped and a
 * single {@code #version 460 core} heads the result. {@link #assemble} then adds the program's defines and replacements.
 * A file id "ns:visuals/X" lives on the classpath at {@code assets/ns/visuals/shaders/X}.
 */
public final class ShaderSource {
    public static final String VERSION_HEADER = "#version 460 core";

    private static final Pattern IMPORT = Pattern.compile("^\\s*#\\s*import\\s*<([^>]+)>\\s*$");
    private static final Pattern VERSION = Pattern.compile("^\\s*#\\s*version\\b.*$");

    private ShaderSource() {
    }

    /** The text of a file id with its imports inlined; throws IllegalArgumentException naming a file that does not exist. */
    public static String parse(String id, Function<String, String> reader) {
        StringBuilder out = new StringBuilder(VERSION_HEADER).append('\n');
        expand(id, reader, new HashSet<>(), out);
        return out.toString();
    }

    private static void expand(String id, Function<String, String> reader, Set<String> seen, StringBuilder out) {
        if (!seen.add(id)) return;
        String text = reader.apply(id);
        if (text == null) throw new IllegalArgumentException("shader file not found: " + id);
        for (String line : text.split("\r?\n", -1)) {
            Matcher m = IMPORT.matcher(line);
            if (m.matches()) {
                expand(m.group(1).trim(), reader, seen, out);
            } else if (!VERSION.matcher(line).matches()) {
                out.append(line).append('\n');
            }
        }
    }

    /** parse()'s result with "#define NAME VALUE" lines after the header, then every replacement applied to the text. */
    public static String assemble(String parsed, Map<String, String> defines, Map<String, String> replacements) {
        int firstLineEnd = parsed.indexOf('\n') + 1;
        StringBuilder out = new StringBuilder(parsed.length() + 32 * defines.size());
        out.append(parsed, 0, firstLineEnd);
        for (Map.Entry<String, String> d : defines.entrySet()) {
            out.append("#define ").append(d.getKey());
            if (!d.getValue().isEmpty()) out.append(' ').append(d.getValue());
            out.append('\n');
        }
        out.append(parsed, firstLineEnd, parsed.length());
        String text = out.toString();
        for (Map.Entry<String, String> r : replacements.entrySet()) text = text.replace(r.getKey(), r.getValue());
        return text;
    }

    /** "ns:visuals/path" to the classpath resource "assets/ns/visuals/shaders/path". */
    public static String resourcePath(String id) {
        int colon = id.indexOf(':');
        int slash = id.indexOf('/', colon + 1);
        if (colon <= 0 || slash < 0) throw new IllegalArgumentException("bad shader file id: " + id);
        return "assets/" + id.substring(0, colon) + "/" + id.substring(colon + 1, slash) + "/shaders/" + id.substring(slash + 1);
    }

    /** Reads file ids from the classpath; null for a missing file. */
    public static Function<String, String> classpath() {
        return id -> {
            try (InputStream in = ShaderSource.class.getClassLoader().getResourceAsStream(resourcePath(id))) {
                return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("reading shader file " + id, e);
            }
        };
    }
}
