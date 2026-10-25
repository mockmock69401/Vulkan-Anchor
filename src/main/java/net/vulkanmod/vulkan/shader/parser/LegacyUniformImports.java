package net.vulkanmod.vulkan.shader.parser;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The converter collects a shader's plain uniforms into one block, line by line, but leaves #moj_import for shaderc to expand
 * afterwards: a plain uniform declared in an imported file (Certain Questing Additions' background_compat.glsl, written for
 * newer Minecraft) then reaches shaderc as "non-opaque uniforms outside a block". Such imports are inlined before the
 * conversion; imports without a plain uniform stay as they are.
 * <p>
 * An inlined file's #ifdef / #ifndef / #if defined(X) on a macro the shader never defines is resolved here, as the preprocessor
 * would, so the converter does not take the branch not taken for interface declarations. Any other conditional is kept.
 */
public final class LegacyUniformImports {
    public interface Reader {
        /** The imported file's text: {@code relative} for "name", else &lt;name&gt;. */
        String read(boolean relative, String name) throws IOException;
    }

    private static final Pattern IMPORT = Pattern.compile("^\\s*#\\s*moj_import\\s*(?:\"([^\"]*)\"|<([^>]*)>)\\s*$");
    private static final Pattern PLAIN_UNIFORM = Pattern.compile(
            "(?m)^\\s*uniform\\s+(?:(?:lowp|mediump|highp)\\s+)?(?!sampler|isampler|usampler|image|iimage|uimage)[A-Za-z_]\\w*\\s+[A-Za-z_]");
    private static final Pattern DEFINE = Pattern.compile("(?m)^\\s*#\\s*define\\s+([A-Za-z_]\\w*)");
    private static final Pattern IFDEF = Pattern.compile("^\\s*#\\s*(ifdef|ifndef)\\s+([A-Za-z_]\\w*)\\s*$");
    private static final Pattern IF_DEFINED = Pattern.compile("^\\s*#\\s*if\\s+(!?)\\s*defined\\s*\\(?\\s*([A-Za-z_]\\w*)\\s*\\)?\\s*$");
    private static final Pattern IF = Pattern.compile("^\\s*#\\s*if(?:n?def)?\\b.*");
    private static final Pattern ELSE = Pattern.compile("^\\s*#\\s*else\\b.*");
    private static final Pattern ELIF = Pattern.compile("^\\s*#\\s*elif\\b.*");
    private static final Pattern ENDIF = Pattern.compile("^\\s*#\\s*endif\\b.*");
    private static final int MAX_DEPTH = 16;

    private LegacyUniformImports() {
    }

    public static String inline(String source, Reader reader) throws IOException {
        if (source == null || !source.contains("moj_import")) return source;
        return inline(source, reader, new HashSet<>(), 0);
    }

    private static String inline(String source, Reader reader, Set<String> inlined, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Shader import depth exceeded " + MAX_DEPTH);
        StringBuilder out = new StringBuilder(source.length());
        for (String line : source.split("\n", -1)) {
            Matcher directive = IMPORT.matcher(line);
            if (!directive.matches()) {
                out.append(line).append('\n');
                continue;
            }
            boolean relative = directive.group(1) != null;
            String name = relative ? directive.group(1) : directive.group(2);
            String imported = reader.read(relative, name);
            if (imported == null || !PLAIN_UNIFORM.matcher(imported).find()) {
                out.append(line).append('\n');
                continue;
            }
            //As #moj_import does, a file is inlined once
            if (inlined.add((relative ? '"' : '<') + name)) {
                String body = withoutVersion(inline(imported, reader, inlined, depth + 1));
                out.append(resolveConditionals(body, definedMacros(out + body))).append('\n');
            }
        }
        out.setLength(out.length() - 1);
        return out.toString();
    }

    private static String withoutVersion(String source) {
        return source.replaceAll("(?m)^\\s*#\\s*version\\b.*$", "");
    }

    private static Set<String> definedMacros(String source) {
        Set<String> defined = new HashSet<>();
        Matcher define = DEFINE.matcher(source);
        while (define.find()) defined.add(define.group(1));
        return defined;
    }

    /** Lines of a branch not taken are blanked; a conditional on anything but a macro's definedness is kept whole. */
    static String resolveConditionals(String source, Set<String> defined) {
        //Per open conditional: KEEP (not resolved, directives kept), TAKEN, SKIPPED
        record Frame(int state, boolean parentActive) {}
        final int keep = 0, taken = 1, skipped = 2;
        Deque<Frame> frames = new ArrayDeque<>();
        StringBuilder out = new StringBuilder(source.length());
        for (String line : source.split("\n", -1)) {
            boolean active = frames.isEmpty() || frames.peek().parentActive() && frames.peek().state() != skipped;
            Matcher ifdef = IFDEF.matcher(line), ifDefined = IF_DEFINED.matcher(line);
            String emitted = line;
            if (ifdef.matches() || ifDefined.matches()) {
                boolean negated = ifdef.matches() ? ifdef.group(1).equals("ifndef") : !ifDefined.group(1).isEmpty();
                String macro = ifdef.matches() ? ifdef.group(2) : ifDefined.group(2);
                boolean condition = defined.contains(macro) != negated;
                frames.push(new Frame(condition ? taken : skipped, active));
                emitted = "";
            } else if (IF.matcher(line).matches()) {
                frames.push(new Frame(keep, active));
            } else if (ELIF.matcher(line).matches() && !frames.isEmpty() && frames.peek().state() != keep) {
                throw new IllegalArgumentException("Unsupported #elif after a resolved conditional in an imported shader file");
            } else if (ELSE.matcher(line).matches() && !frames.isEmpty() && frames.peek().state() != keep) {
                Frame frame = frames.pop();
                frames.push(new Frame(frame.state() == taken ? skipped : taken, frame.parentActive()));
                emitted = "";
            } else if (ENDIF.matcher(line).matches() && !frames.isEmpty()) {
                if (frames.pop().state() != keep) emitted = "";
            }
            if (!active && emitted == line) emitted = "";
            out.append(emitted).append('\n');
        }
        if (!frames.isEmpty()) throw new IllegalArgumentException("Unterminated conditional in an imported shader file");
        out.setLength(out.length() - 1);
        return out.toString();
    }
}
