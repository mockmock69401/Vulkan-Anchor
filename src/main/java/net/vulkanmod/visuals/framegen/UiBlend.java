package net.vulkanmod.visuals.framegen;

import net.vulkanmod.vulkan.shader.PipelineState.BlendState;

import static net.vulkanmod.vulkan.shader.PipelineState.BlendState.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Blend remap for the frame generation UI layer (FG design 4). The HUD and hand draw into a
 * transparent layer that is composed over the world later, so alpha must accumulate coverage as
 * premultiplied alpha. Works on the packed {@link BlendState} int, whose factors and ops are Vulkan values. Alpha blending and additive
 * light are known with the colour op ADD only. A state it does not know (a multiply, an inversion, a subtract, MIN or MAX: anything that
 * needs the colour under it) keeps its colour factors and op and adds no coverage, so such a draw can at worst be missing from the
 * layer, never cover the world (black where it multiplied the transparent layer).
 */
public final class UiBlend {
    private UiBlend() {
    }

    /** The blend state to use inside the UI layer; an unrecognised one keeps its colour factors with alpha ZERO, ONE (no coverage). */
    public static int remap(int blendState) {
        if (!enable(blendState)) return blendState;
        if (isAlphaBlend(blendState)) {
            // coverage: a' = a + a_dst * (1 - a)
            return withAlpha(blendState, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
        }
        // additive light adds no coverage; nor does a state the layer cannot hold (it must never hide the world)
        return withAlpha(blendState, VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE);
    }

    /** False when {@link #remap} does not know a blending state (it then only takes its coverage away). */
    public static boolean handled(int blendState) {
        return !enable(blendState) || isAlphaBlend(blendState) || isAdditive(blendState);
    }

    /** "SRC_RGB,DST_RGB/SRC_A,DST_A" for the one-time log of an unhandled state, with the colour op after it when that is not ADD. */
    public static String describe(int blendState) {
        int op = getColorBlendOp(blendState);
        return name(getSrcRgbFactor(blendState)) + "," + name(getDstRgbFactor(blendState))
                + "/" + name(getSrcAlphaFactor(blendState)) + "," + name(getDstAlphaFactor(blendState))
                + (op == VK_BLEND_OP_ADD ? "" : " " + opName(op));
    }

    private static String opName(int op) {
        return switch (op) {
            case VK_BLEND_OP_SUBTRACT -> "SUBTRACT";
            case VK_BLEND_OP_REVERSE_SUBTRACT -> "REVERSE_SUBTRACT";
            case VK_BLEND_OP_MIN -> "MIN";
            case VK_BLEND_OP_MAX -> "MAX";
            default -> "op " + op;
        };
    }

    private static boolean isAlphaBlend(int s) {
        int src = getSrcRgbFactor(s);
        return getColorBlendOp(s) == VK_BLEND_OP_ADD && getDstRgbFactor(s) == VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
                && (src == VK_BLEND_FACTOR_SRC_ALPHA || src == VK_BLEND_FACTOR_ONE);
    }

    /** Light added onto what is there (destination factor ONE, op ADD): no coverage, nothing it hides. */
    private static boolean isAdditive(int s) {
        return getColorBlendOp(s) == VK_BLEND_OP_ADD && getDstRgbFactor(s) == VK_BLEND_FACTOR_ONE;
    }

    private static int withAlpha(int state, int srcA, int dstA) {
        int clear = (FACTOR_MASK << SRC_A_OFFSET) | (FACTOR_MASK << DST_A_OFFSET) | (OP_MASK << ALPHA_FUN_OFFSET);
        return (state & ~clear)
                | encode(srcA, SRC_A_OFFSET, FACTOR_MASK)
                | encode(dstA, DST_A_OFFSET, FACTOR_MASK)
                | encode(VK_BLEND_OP_ADD, ALPHA_FUN_OFFSET, OP_MASK);
    }

    private static String name(int f) {
        return switch (f) {
            case VK_BLEND_FACTOR_ZERO -> "ZERO";
            case VK_BLEND_FACTOR_ONE -> "ONE";
            case VK_BLEND_FACTOR_SRC_COLOR -> "SRC_COLOR";
            case VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR -> "ONE_MINUS_SRC_COLOR";
            case VK_BLEND_FACTOR_DST_COLOR -> "DST_COLOR";
            case VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR -> "ONE_MINUS_DST_COLOR";
            case VK_BLEND_FACTOR_SRC_ALPHA -> "SRC_ALPHA";
            case VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA -> "ONE_MINUS_SRC_ALPHA";
            case VK_BLEND_FACTOR_DST_ALPHA -> "DST_ALPHA";
            case VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA -> "ONE_MINUS_DST_ALPHA";
            case VK_BLEND_FACTOR_CONSTANT_COLOR -> "CONSTANT_COLOR";
            case VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR -> "ONE_MINUS_CONSTANT_COLOR";
            case VK_BLEND_FACTOR_CONSTANT_ALPHA -> "CONSTANT_ALPHA";
            case VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA -> "ONE_MINUS_CONSTANT_ALPHA";
            case VK_BLEND_FACTOR_SRC_ALPHA_SATURATE -> "SRC_ALPHA_SATURATE";
            default -> String.valueOf(f);
        };
    }
}
