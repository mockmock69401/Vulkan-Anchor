package net.vulkanmod.vulkan.shader.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An in / out interface block ("out DATA { vec4 vertexColor; ... } data_out;", Lodestone's nine-slice shaders) is flattened into
 * one plain declaration per member before the converter assigns locations, and "data_out.vertexColor" / "data_in[i].vertexColor"
 * become "vk_DATA_vertexColor" / "vk_DATA_vertexColor[i]". Stages match blocks by block and member name, so every stage flattens
 * the same block to the same names. A block without an instance name keeps its member names. Built-in blocks (gl_PerVertex)
 * and uniform / buffer blocks are left alone. The source must be free of comments.
 */
public final class LegacyInterfaceBlocks {
    private static final Pattern HEADER = Pattern.compile(
            "(?m)^[ \\t]*((?:(?:flat|smooth|noperspective|centroid|sample|invariant)\\s+)*)(in|out)\\s+([A-Za-z_]\\w*)\\s*\\{");
    private static final Pattern TAIL = Pattern.compile("\\A\\s*([A-Za-z_]\\w*)?\\s*(\\[\\s*[0-9]*\\s*\\])?\\s*;");
    private static final Pattern MEMBER = Pattern.compile(
            "\\s*((?:(?:flat|smooth|noperspective|centroid|sample|invariant)\\s+)*)([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)\\s*(\\[\\s*[0-9]+\\s*\\])?\\s*");
    private static final String PREFIX = "vk_";

    private LegacyInterfaceBlocks() {
    }

    public static String flatten(String source) {
        if (source.indexOf('{') < 0) return source;
        String result = source;
        Matcher header = HEADER.matcher(result);
        int from = 0;
        while (header.find(from)) {
            String block = header.group(3);
            if (block.startsWith("gl_")) {
                from = header.end();
                continue;
            }
            int close = result.indexOf('}', header.end());
            if (close < 0) throw unsupported(block, "unterminated block");
            Matcher tail = TAIL.matcher(result.substring(close + 1));
            if (!tail.find()) throw unsupported(block, "no declaration end");
            String instance = tail.group(1);
            String instanceArray = tail.group(2) == null ? "" : tail.group(2).replaceAll("\\s", "");

            List<String[]> members = new ArrayList<>();
            StringBuilder declarations = new StringBuilder();
            for (String member : result.substring(header.end(), close).split(";")) {
                if (member.isBlank()) continue;
                Matcher parsed = MEMBER.matcher(member);
                if (!parsed.matches()) throw unsupported(block, member.trim());
                String name = parsed.group(3);
                String flat = instance == null ? name : PREFIX + block + "_" + name;
                String memberArray = parsed.group(4) == null ? "" : parsed.group(4).replaceAll("\\s", "");
                if (!memberArray.isEmpty() && !instanceArray.isEmpty()) throw unsupported(block, "array member of an arrayed block");
                members.add(new String[]{name, flat});
                declarations.append(header.group(1)).append(parsed.group(1)).append(header.group(2)).append(' ')
                        .append(parsed.group(2)).append(' ').append(flat).append(memberArray).append(instanceArray).append(";\n");
            }
            if (members.isEmpty()) throw unsupported(block, "empty block");

            int start = header.start(), end = close + 1 + tail.end();
            result = result.substring(0, start) + declarations + result.substring(end);
            //A block is declared before it is used: the accesses all follow the declarations
            if (instance != null) result = rewriteAccesses(result, instance, members, block);
            header = HEADER.matcher(result);
            from = start + declarations.length();
        }
        return result;
    }

    /** "instance.member" and "instance[index].member" to the flattened name; any other use of the instance is unsupported. */
    private static String rewriteAccesses(String source, String instance, List<String[]> members, String block) {
        Pattern use = Pattern.compile("(?<![\\w.])" + Pattern.quote(instance) + "\\b");
        StringBuilder out = new StringBuilder(source.length());
        Matcher matcher = use.matcher(source);
        int copied = 0;
        while (matcher.find()) {
            int cursor = matcher.end();
            String index = "";
            int afterSpace = skipSpaces(source, cursor);
            if (afterSpace < source.length() && source.charAt(afterSpace) == '[') {
                int closing = matchingBracket(source, afterSpace);
                if (closing < 0) throw unsupported(block, "unbalanced index on " + instance);
                index = source.substring(afterSpace, closing + 1);
                cursor = closing + 1;
            }
            cursor = skipSpaces(source, cursor);
            if (cursor >= source.length() || source.charAt(cursor) != '.') throw unsupported(block, "whole-block use of " + instance);
            cursor = skipSpaces(source, cursor + 1);
            int nameEnd = cursor;
            while (nameEnd < source.length() && (Character.isLetterOrDigit(source.charAt(nameEnd)) || source.charAt(nameEnd) == '_')) nameEnd++;
            String member = source.substring(cursor, nameEnd);
            String flat = members.stream().filter(m -> m[0].equals(member)).map(m -> m[1]).findFirst()
                    .orElseThrow(() -> unsupported(block, "no member " + member));
            out.append(source, copied, matcher.start()).append(flat).append(index);
            copied = nameEnd;
        }
        return out.append(source.substring(copied)).toString();
    }

    private static int skipSpaces(String source, int at) {
        while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++;
        return at;
    }

    private static int matchingBracket(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return i;
        }
        return -1;
    }

    private static IllegalArgumentException unsupported(String block, String detail) {
        return new IllegalArgumentException("Unsupported legacy interface block " + block + ": " + detail);
    }
}
