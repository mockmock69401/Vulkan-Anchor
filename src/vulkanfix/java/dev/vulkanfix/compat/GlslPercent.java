package dev.vulkanfix.compat;

/**
 * Hides "%" from Reforged's CodeParser. It wraps the whitespace-split tokens around each "%" in mod(), which breaks
 * any remainder whose operands are not lone tokens ("COLORS[i % 16]" becomes "mod(COLORS[i, 16])"), and GLSL's mod()
 * is float-only anyway. The Vulkan GLSL Reforged compiles has an integer "%", so the operator is kept as written.
 */
public final class GlslPercent {
    /** An identifier-like stand-in: no delimiter CodeParser splits on, nothing its token mapping knows. */
    public static final String PLACEHOLDER = "vulkanfix_percent_";

    private GlslPercent() {
    }

    public static String protect(String line) {
        return line == null || line.indexOf('%') < 0 ? line : line.replace("%", PLACEHOLDER);
    }

    public static String restore(String line) {
        return line == null || !line.contains(PLACEHOLDER) ? line : line.replace(PLACEHOLDER, "%");
    }
}
