package dev.vulkanfix.compat;

/**
 * Removes GLSL comments before Reforged's GlslConverter tokenizes a shader. Its uniform parser takes a trailing
 * "// note" for part of the declaration ("not admitted type: //particle"). Block comments keep their line breaks,
 * so compiler line numbers still match the source.
 */
public final class GlslComments {
    private GlslComments() {
    }

    public static String strip(String src) {
        if (src == null || src.indexOf('/') < 0) {
            return src;
        }
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                out.append(' ');
                for (int j = i; j < stop; j++) {
                    if (src.charAt(j) == '\n') {
                        out.append('\n');
                    }
                }
                i = stop;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
