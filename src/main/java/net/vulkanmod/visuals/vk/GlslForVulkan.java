package net.vulkanmod.visuals.vk;

import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns the visuals' GLSL into Vulkan GLSL (see toVulkan). */
public final class GlslForVulkan {
    /** Vulkan's guaranteed maxPushConstantsSize; larger loose-uniform sets go to a uniform block instead. */
    public static final int MAX_PUSH_CONSTANT_BYTES = 128;

    private GlslForVulkan() {
    }

    public record Converted(String source, VulkanShaderMeta meta) {
    }

    /**
     * 2026-11-12 (shader roadmap session 14): each stage's conversion, kept for the game run by catalog, program (with its defines and
     * platform) and stage. The renderer is rebuilt on a render distance or graphics change and made every program again: expanding the
     * imports and converting the composite's and the reflection's large sources for each of their builds was most of that hitch
     * (JFR: 64 of 75 samples; 80 ms at 2b3c115, 200-265 ms with the builds added since). The sources are the mod's classpath files, which
     * do not change while the game runs; VkContext.spirv already keeps the compiled SPIR-V the same way.
     */
    private static final Map<String, Converted> CONVERTED = new java.util.concurrent.ConcurrentHashMap<>();

    /** toVulkan of program p's stage (its classpath source and defines), once a game run per catalog, program and stage. */
    public static Converted converted(net.vulkanmod.visuals.vk.shader.ShaderCatalog catalog, net.vulkanmod.visuals.vk.shader.ShaderCatalog.Program p,
                                      net.vulkanmod.visuals.vk.shader.ShaderCatalog.Stage stage) {
        String key = System.identityHashCode(catalog) + "|" + p.name() + "|" + stage.kind() + "|" + stage.id() + "|" + p.defines();
        return CONVERTED.computeIfAbsent(key, k -> toVulkan(p.source(stage, net.vulkanmod.visuals.vk.shader.ShaderSource.classpath()), p.defines()));
    }

    /** Full the visuals GLSL → Vulkan GLSL conversion, with the merged metadata. */
    public static Converted toVulkan(String source, Map<String, String> defines) {
        String prepared = varyingLocations(vulkanBuiltins(resolveConditionals(stripComments(source), VULKAN_PREDEFINED)));
        Converted push = pushConstants(prepared, defines);
        Converted sets = descriptorSets(push.source(), defines);
        return new Converted(sets.source(), push.meta().withBindings(sets.meta().bindings()));
    }

    /** Removes // and block comments, keeping their newlines (a comment-only span becomes one space). */
    public static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? source.length() : end + 2;
                long newlines = source.substring(i, end).chars().filter(c -> c == '\n').count();
                out.append(newlines == 0 ? " " : "\n".repeat((int) newlines));
                i = end;
            } else {
                out.append(source.charAt(i++));
            }
        }
        return out.toString();
    }

    /**
     * Extension macros GL drivers predefine that the visuals' shaders branch on. The Vulkan backend requires shaderInt64
     * (VkFeaturePlan), so quads3.vert takes the same 64-bit quad path it takes on GL drivers with int64 support.
     */
    public static final Set<String> VULKAN_PREDEFINED = Set.of("GL_ARB_gpu_shader_int64");

    private static final Pattern DIRECTIVE = Pattern.compile("^[ \\t]*#[ \\t]*(ifdef|ifndef|else|endif|define|undef|if|elif)\\b[ \\t]*(\\w*)");

    /**
     * Evaluates #ifdef/#ifndef/#else/#endif with the #define/#undef seen so far (the only conditionals the visuals' shaders
     * use; #if/#elif are rejected), blanking inactive lines and the resolved directives so line numbers stay the same.
     */
    public static String resolveConditionals(String source) {
        return resolveConditionals(source, Set.of());
    }

    public static String resolveConditionals(String source, Set<String> predefined) {
        Set<String> defined = new HashSet<>(predefined);
        ArrayDeque<boolean[]> stack = new ArrayDeque<>(); // {parentActive, branchActive}
        boolean active = true;
        StringBuilder out = new StringBuilder(source.length());
        String[] lines = source.split("\n", -1);
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            Matcher d = DIRECTIVE.matcher(line);
            String kept = "";
            if (d.find()) {
                switch (d.group(1)) {
                    case "if", "elif" -> throw new IllegalArgumentException("#" + d.group(1) + " is not supported at line " + (n + 1));
                    case "ifdef", "ifndef" -> {
                        boolean cond = defined.contains(d.group(2)) == d.group(1).equals("ifdef");
                        stack.push(new boolean[]{active, cond});
                        active = active && cond;
                    }
                    case "else" -> {
                        boolean[] top = stack.peek();
                        if (top == null) throw new IllegalArgumentException("#else without #ifdef at line " + (n + 1));
                        top[1] = !top[1];
                        active = top[0] && top[1];
                    }
                    case "endif" -> {
                        boolean[] top = stack.poll();
                        if (top == null) throw new IllegalArgumentException("#endif without #ifdef at line " + (n + 1));
                        active = top[0];
                    }
                    case "define" -> {
                        if (active) {
                            defined.add(d.group(2));
                            kept = line;
                        }
                    }
                    case "undef" -> {
                        if (active) {
                            defined.remove(d.group(2));
                            kept = line;
                        }
                    }
                    default -> throw new IllegalStateException(d.group(1));
                }
            } else if (active) {
                kept = line;
            }
            out.append(kept);
            if (n < lines.length - 1) out.append('\n');
        }
        if (!stack.isEmpty()) throw new IllegalArgumentException("unterminated #ifdef");
        return out.toString();
    }

    private static final String INTERP = "(?:flat|noperspective|smooth|centroid)[ \\t]+";
    private static final Pattern IMPLICIT_VARYING = Pattern.compile(
            "^([ \\t]*)((?:" + INTERP + ")*)(in|out)([ \\t]+(?:" + INTERP + ")*\\w+[ \\t]+\\w+(?:\\[\\w*\\])?[ \\t]*;)", Pattern.MULTILINE);
    private static final Pattern EXPLICIT_VARYING = Pattern.compile(
            "layout\\s*\\([^)]*\\blocation\\s*=\\s*(\\d+)[^)]*\\)[ \\t]*(?:\\w+[ \\t]+)*?(in|out)\\b");

    /**
     * SPIR-V needs a location on every user in/out; implicit ones get the next locations not used explicitly, in declaration order.
     * Vulkan matches a vertex output to a fragment input by location, not by name as GL does, so a program's stages declare their
     * implicit varyings in the same order (ShaderCatalogTest checks every visuals program). 2026-10-30 (audit): a matrix or an
     * array takes as many locations as it fills (it overlapped the next varying before).
     */
    public static String varyingLocations(String source) {
        Set<Integer> usedIn = new HashSet<>();
        Set<Integer> usedOut = new HashSet<>();
        Matcher e = EXPLICIT_VARYING.matcher(source);
        while (e.find()) {
            (e.group(2).equals("in") ? usedIn : usedOut).add(Integer.parseInt(e.group(1)));
        }
        Matcher m = IMPLICIT_VARYING.matcher(source);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            Set<Integer> used = m.group(3).equals("in") ? usedIn : usedOut;
            int count = locationCount(m.group(4));
            int location = 0;
            while (!free(used, location, count)) location++;
            for (int i = 0; i < count; i++) used.add(location + i);
            out.append(source, last, m.start()).append(m.group(1)).append("layout(location = ").append(location).append(") ")
                    .append(m.group(2)).append(m.group(3)).append(m.group(4));
            last = m.end();
        }
        out.append(source.substring(last));
        return out.toString();
    }

    private static boolean free(Set<Integer> used, int location, int count) {
        for (int i = 0; i < count; i++) {
            if (used.contains(location + i)) return false;
        }
        return true;
    }

    private static final Pattern VARYING_DECL = Pattern.compile("(\\w+)[ \\t]+(\\w+)(?:\\[(\\w*)\\])?[ \\t]*;\\s*$");

    /** The locations an implicit varying's declaration (" type name[n];") fills: a matrix one per column, an array n times. */
    static int locationCount(String declaration) {
        Matcher d = VARYING_DECL.matcher(declaration.trim());
        if (!d.find()) return 1;
        String type = d.group(1);
        int each = 1;
        Matcher mat = Pattern.compile("^d?mat([234])(?:x[234])?$").matcher(type);
        if (mat.find()) each = Integer.parseInt(mat.group(1));
        String size = d.group(3);
        int n = size != null && size.matches("\\d+") ? Integer.parseInt(size) : 1;
        return Math.max(1, each * n);
    }

    /** The names of the implicit (no layout) varyings in direction ("in" or "out"), in declaration order. */
    public static List<String> implicitVaryings(String source, String direction) {
        List<String> names = new ArrayList<>();
        Matcher m = IMPLICIT_VARYING.matcher(source);
        while (m.find()) {
            if (!m.group(3).equals(direction)) continue;
            Matcher d = VARYING_DECL.matcher(m.group(4).trim());
            if (d.find()) names.add(d.group(2));
        }
        return names;
    }

    private static final Pattern LOOSE_UNIFORM = Pattern.compile(
            "^[ \\t]*(?:layout\\s*\\(\\s*location\\s*=\\s*([^)]*?)\\s*\\)\\s*)?uniform\\s+(float|int|uint|bool|vec[234]|ivec[234]|uvec[234]|mat3|mat4)\\s+(\\w+)\\s*;[ \\t]*$",
            Pattern.MULTILINE);

    /**
     * Non-opaque uniforms outside a block are not allowed in Vulkan GLSL; they become one anonymous push constant block,
     * or an std140 uniform block (same offsets for these member types) when they exceed the guaranteed push constant size.
     */
    public static Converted pushConstants(String source, Map<String, String> defines) {
        Matcher m = LOOSE_UNIFORM.matcher(source);
        record Found(String name, String type, int location, int order) {}
        List<Found> found = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        int last = 0;
        int insertAt = -1;
        while (m.find()) {
            if (insertAt < 0) insertAt = out.length() + (m.start() - last);
            out.append(source, last, m.start());
            Integer location = m.group(1) == null ? null : resolveInt(m.group(1), defines, source);
            found.add(new Found(m.group(3), m.group(2), location == null ? Integer.MAX_VALUE : location, found.size()));
            last = m.end();
        }
        out.append(source.substring(last));
        if (found.isEmpty()) {
            return new Converted(source, new VulkanShaderMeta(List.of(), 0, List.of()));
        }
        found.sort(Comparator.comparingInt(Found::location).thenComparingInt(Found::order));
        StringBuilder members = new StringBuilder();
        List<VulkanShaderMeta.PushMember> meta = new ArrayList<>();
        int offset = 0;
        for (Found f : found) {
            int align = std430Align(f.type());
            offset = (offset + align - 1) / align * align;
            meta.add(new VulkanShaderMeta.PushMember(f.name(), f.type(), f.location() == Integer.MAX_VALUE ? -1 : f.location(), offset));
            offset += std430Size(f.type());
            members.append("    ").append(f.type()).append(' ').append(f.name()).append(";\n");
        }
        int size = (offset + 15) / 16 * 16;
        boolean inUniformBuffer = size > MAX_PUSH_CONSTANT_BYTES;
        String header = inUniformBuffer ? "layout(std140) uniform VisualsLooseUniforms {\n" : "layout(push_constant) uniform VisualsPushConstants {\n";
        out.insert(insertAt, header + members + "};");
        return new Converted(out.toString(), new VulkanShaderMeta(List.copyOf(meta), size, inUniformBuffer, List.of()));
    }

    public static final int SET_STORAGE = 0, SET_UNIFORM = 1, SET_SAMPLER = 2, SET_IMAGE = 3;

    private static final Pattern RESOURCE = Pattern.compile(
            "^([ \\t]*)(?:layout\\s*\\(([^)]*)\\)[ \\t]*)?((?:\\w+[ \\t]+)*?)(buffer|uniform)[ \\t]+((?:(?:restrict|readonly|writeonly|coherent|volatile|highp|mediump|lowp)[ \\t]+)*)(\\w+)",
            Pattern.MULTILINE);

    /** Gives storage buffers, uniform blocks, samplers and images separate sets so GL's per-kind binding numbers cannot collide. */
    public static Converted descriptorSets(String source, Map<String, String> defines) {
        record Decl(int start, int end, String prefix, String layout, int set, Integer binding, boolean explicit, VulkanShaderMeta.Kind kind) {}
        List<Decl> decls = new ArrayList<>();
        Matcher m = RESOURCE.matcher(source);
        while (m.find()) {
            String layout = m.group(2);
            String storage = m.group(4);
            String typeOrBlock = m.group(6);
            VulkanShaderMeta.Kind kind;
            if (storage.equals("buffer")) {
                kind = VulkanShaderMeta.Kind.STORAGE_BUFFER;
            } else if (typeOrBlock.matches("[iu]?sampler\\w*|texture\\w*")) {
                kind = VulkanShaderMeta.Kind.SAMPLER;
            } else if (typeOrBlock.matches("[iu]?image\\w*")) {
                kind = VulkanShaderMeta.Kind.IMAGE;
            } else if (layout != null && !layout.contains("push_constant") && source.substring(m.end()).stripLeading().startsWith("{")) {
                kind = VulkanShaderMeta.Kind.UNIFORM_BUFFER;
            } else {
                continue;
            }
            if (layout != null && layout.matches("(?s).*\\bset\\s*=.*")) {
                continue;
            }
            int set = switch (kind) {
                case STORAGE_BUFFER -> SET_STORAGE;
                case UNIFORM_BUFFER -> SET_UNIFORM;
                case SAMPLER -> SET_SAMPLER;
                case IMAGE -> SET_IMAGE;
            };
            Matcher b = layout == null ? null : Pattern.compile("\\bbinding\\s*=\\s*([^,)]+)").matcher(layout);
            boolean explicit = b != null && b.find();
            Integer binding = explicit ? resolveInt(b.group(1), defines, source) : null;
            String rest = m.group(3) + storage + ' ' + m.group(5) + typeOrBlock;
            decls.add(new Decl(m.start(), m.end(), m.group(1) + "\u0000" + rest, layout, set, binding, explicit, kind));
        }

        Set<String> taken = new HashSet<>();
        for (Decl d : decls) {
            if (d.explicit() && d.binding() != null) taken.add(d.set() + ":" + d.binding());
        }
        StringBuilder out = new StringBuilder();
        List<VulkanShaderMeta.Binding> bindings = new ArrayList<>();
        int last = 0;
        for (Decl d : decls) {
            String newLayout;
            Integer binding = d.binding();
            if (d.explicit()) {
                newLayout = "layout(set = " + d.set() + ", " + d.layout().trim() + ") ";
            } else {
                int assigned = 0;
                while (taken.contains(d.set() + ":" + assigned)) assigned++;
                taken.add(d.set() + ":" + assigned);
                binding = assigned;
                newLayout = "layout(set = " + d.set() + ", binding = " + assigned + (d.layout() == null || d.layout().isBlank() ? "" : ", " + d.layout().trim()) + ") ";
            }
            int split = d.prefix().indexOf('\u0000');
            out.append(source, last, d.start()).append(d.prefix(), 0, split).append(newLayout).append(d.prefix(), split + 1, d.prefix().length());
            last = d.end();
            if (binding != null) {
                bindings.add(new VulkanShaderMeta.Binding(d.set(), binding, d.kind()));
            }
        }
        out.append(source.substring(last));
        return new Converted(out.toString(), new VulkanShaderMeta(List.of(), 0, List.copyOf(bindings)));
    }

    private static int std430Align(String type) {
        return switch (type) {
            case "vec2", "ivec2", "uvec2" -> 8;
            case "vec3", "ivec3", "uvec3", "vec4", "ivec4", "uvec4", "mat3", "mat4" -> 16;
            default -> 4;
        };
    }

    private static int std430Size(String type) {
        return switch (type) {
            case "vec2", "ivec2", "uvec2" -> 8;
            case "vec3", "ivec3", "uvec3" -> 12;
            case "vec4", "ivec4", "uvec4" -> 16;
            case "mat3" -> 48;
            case "mat4" -> 64;
            default -> 4;
        };
    }

    /** An integer literal, a program define, or an in-source "#define NAME value"; null if none resolves. */
    static Integer resolveInt(String expr, Map<String, String> defines, String source) {
        String e = expr.trim();
        for (int depth = 0; depth < 8; depth++) {
            if (e.matches("\\d+")) return Integer.parseInt(e);
            String next = defines.get(e);
            if (next == null) {
                Matcher d = Pattern.compile("^[ \\t]*#define[ \\t]+" + Pattern.quote(e) + "[ \\t]+(\\S+)", Pattern.MULTILINE).matcher(source);
                next = d.find() ? d.group(1) : null;
            }
            if (next == null) return null;
            e = next.trim();
        }
        return null;
    }

    /**
     * GL built-ins with no Vulkan GLSL equivalent, rewritten to keep GL's meaning. 2026-10-30 (audit): without the draw parameters
     * capability where it can (a device without shaderDrawParameters could not make the edge cover's and the vanilla shadows'
     * pipelines): the visuals' instanced draws start at instance 0, so gl_InstanceID is gl_InstanceIndex, and the vanilla shadows'
     * gl_InstanceID + gl_BaseInstance is gl_InstanceIndex whatever the first instance. Only a gl_BaseInstance left on its own takes
     * the extension.
     */
    public static String vulkanBuiltins(String source) {
        String out = source.replaceAll("\\bgl_InstanceID\\s*\\+\\s*gl_BaseInstance\\b", "gl_InstanceIndex")
                .replaceAll("\\bgl_VertexID\\b", "gl_VertexIndex")
                .replaceAll("\\bgl_InstanceID\\b", "gl_InstanceIndex")
                .replace("gl_DepthRange.diff", "1.0")
                .replace("gl_DepthRange.near", "0.0")
                .replace("gl_DepthRange.far", "1.0");
        if (out.matches("(?s).*\\bgl_BaseInstance\\b.*") && !out.contains("GL_ARB_shader_draw_parameters")) {
            int firstLine = out.indexOf('\n') + 1;
            out = out.substring(0, firstLine) + "#extension GL_ARB_shader_draw_parameters : require\n" + out.substring(firstLine);
        }
        return out;
    }

    /** Removes printf(...) statements (the visuals debug output, GL-only PrintfInjector), keeping their newlines. */
    public static String stripPrintf(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            int at = source.indexOf("printf", i);
            if (at < 0) {
                out.append(source, i, source.length());
                break;
            }
            boolean wordStart = at == 0 || !Character.isJavaIdentifierPart(source.charAt(at - 1));
            int paren = at + 6;
            while (paren < source.length() && Character.isWhitespace(source.charAt(paren))) paren++;
            if (!wordStart || paren >= source.length() || source.charAt(paren) != '(') {
                out.append(source, i, at + 6);
                i = at + 6;
                continue;
            }
            int depth = 0;
            int end = paren;
            boolean inString = false;
            for (; end < source.length(); end++) {
                char c = source.charAt(end);
                if (inString) {
                    if (c == '\\') end++;
                    else if (c == '"') inString = false;
                } else if (c == '"') {
                    inString = true;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    break;
                }
            }
            int semicolon = end + 1;
            while (semicolon < source.length() && Character.isWhitespace(source.charAt(semicolon)) && source.charAt(semicolon) != '\n') semicolon++;
            if (semicolon < source.length() && source.charAt(semicolon) == ';') end = semicolon;
            out.append(source, i, at);
            source.substring(at, Math.min(end + 1, source.length())).chars().filter(c -> c == '\n').forEach(c -> out.append('\n'));
            i = end + 1;
        }
        return out.toString();
    }
}
