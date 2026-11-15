package net.vulkanmod.compat.gl;

import org.apache.logging.log4j.LogManager;

import java.lang.reflect.Field;

/**
 * Keeps vanilla's own record of the blend state ({@code GlStateManager.BLEND}: on/off and the four factors) equal to what VulkanMod
 * draws with. VulkanMod's GlStateManager and RenderSystem overwrites send blend calls straight to the pipeline state, so that record
 * stayed at its initial "off, ONE / ZERO". Mods that save the record and put it back after drawing (HUD batching in ImmediatelyFast,
 * for one) then turned blending off for the next draw: Conquest's crosshair, an opaque black cell that vanilla's inverting blend makes
 * invisible, drew as a black square.
 */
public final class VanillaGlState {
    /** GlStateManager.BlendState, filled in by its mixin when vanilla makes it (once, at class init). */
    public interface BlendRecord {
        void vulkanmod$setFactors(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) throws IllegalAccessException;

        void vulkanmod$setRgbFactors(int srcRgb, int dstRgb) throws IllegalAccessException;
    }

    /** GlStateManager.BooleanState: only the GL_BLEND one is kept. */
    public interface CapRecord {
        void vulkanmod$setEnabled(boolean enabled) throws IllegalAccessException;
    }

    private static BlendRecord blend;
    private static CapRecord blendCap;

    private VanillaGlState() {
    }

    public static void registerBlend(BlendRecord record) {
        blend = record;
    }

    public static void registerBlendCap(CapRecord record) {
        if (blendCap == null) blendCap = record;
    }

    /** The first of names (the development name, then the SRG one) the class declares. */
    public static Field field(Class<?> owner, String[] names) throws NoSuchFieldException {
        for (String name : names) {
            try {
                Field f = owner.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(owner.getName() + " has none of " + String.join(", ", names));
    }

    /** A record that could not be found or written stays as it was: VulkanMod draws the same either way. */
    public static void failed(String what, Throwable t) {
        blend = null;
        blendCap = null;
        LogManager.getLogger("VulkanMod").warn("Vanilla's {} record will not follow VulkanMod's blend state", what, t);
    }

    public static void blendEnabled(boolean enabled) {
        if (blendCap == null) return;
        try {
            blendCap.vulkanmod$setEnabled(enabled);
        } catch (IllegalAccessException | RuntimeException e) {
            failed("GL_BLEND", e);
        }
    }

    public static void blendFunc(int srcRgb, int dstRgb) {
        if (blend == null) return;
        try {
            blend.vulkanmod$setRgbFactors(srcRgb, dstRgb);
        } catch (IllegalAccessException | RuntimeException e) {
            failed("blend function", e);
        }
    }

    public static void blendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (blend == null) return;
        try {
            blend.vulkanmod$setFactors(srcRgb, dstRgb, srcAlpha, dstAlpha);
        } catch (IllegalAccessException | RuntimeException e) {
            failed("blend function", e);
        }
    }
}
