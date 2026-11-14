package net.vulkanmod.compat.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.opengl.GL11;

public final class GuiItemRenderState {
    private static final ThreadLocal<RenderStateSnapshot> DEFERRED_DRAW_STATE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> TOOLTIP_OVERLAY_DEPTH = ThreadLocal.withInitial(() -> 0);

    private GuiItemRenderState() {
    }

    public static void prepareDeferredDraw() {
        if (DEFERRED_DRAW_STATE.get() == null) {
            DEFERRED_DRAW_STATE.set(new RenderStateSnapshot());
        }

        prepare();
    }

    public static boolean hasDeferredDrawState() {
        return DEFERRED_DRAW_STATE.get() != null;
    }

    public static void beginTooltipOverlay() {
        TOOLTIP_OVERLAY_DEPTH.set(TOOLTIP_OVERLAY_DEPTH.get() + 1);
    }

    public static void endTooltipOverlay() {
        int depth = TOOLTIP_OVERLAY_DEPTH.get();

        if (depth <= 1) {
            TOOLTIP_OVERLAY_DEPTH.remove();
        } else {
            TOOLTIP_OVERLAY_DEPTH.set(depth - 1);
        }
    }

    public static boolean isTooltipOverlayActive() {
        return TOOLTIP_OVERLAY_DEPTH.get() > 0;
    }

    public static void restoreDeferredDrawState() {
        RenderStateSnapshot snapshot = DEFERRED_DRAW_STATE.get();
        DEFERRED_DRAW_STATE.remove();

        if (snapshot != null) {
            snapshot.restore();
        }
    }

    public static void prepare() {
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.colorMask(true, true, true, true);

        if (isTooltipOverlayActive()) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(true);
            VRenderSystem.disableCull();
        } else {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            //2026-11-14 (session 21): face culling as the game and mods last set it, as GL has it here (no RenderType turns it on), not
            //always on: VulkanMod's own framebuffer binds leave it off, but Iceberg turns it off on purpose to draw Advancement Plaques'
            //icons with a transform that winds the item's front face clockwise. Forced on, that drew the dark back face, or nothing under
            //ModernFix's faster item rendering (which draws only the front face).
            VRenderSystem.cull = VRenderSystem.glCull;
        }

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        VRenderSystem.setPolygonModeGL(GL11.GL_FILL);
    }
}
