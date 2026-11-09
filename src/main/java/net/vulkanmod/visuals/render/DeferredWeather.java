package net.vulkanmod.visuals.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.vulkanmod.mixin.visuals.LevelRendererWeatherInvoker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;

/**
 * P7h final review: vanilla draws rain, snow and the world border after the clouds without writing depth, so over the sky the
 * scene copy's far clouds would cover them. While the visuals draw the clouds (FarClouds, not on Fabulous) they are recorded instead and
 * drawn after the post-process with the level's model-view matrix. The render thread records and draws.
 */
public final class DeferredWeather {
    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    private static boolean pending, replaying, rain;
    private static LightTexture lightTexture;
    private static float partialTick;
    private static double camX, camY, camZ;
    private static Camera border;

    private DeferredWeather() {
    }

    /**
     * LevelRendererWeatherMixin at renderSnowAndRain's head: true when it is recorded or, 2026-11-09 (session 6), left out because the
     * shader draws the weather (ShaderWeather); the caller then cancels vanilla's draw. With the shader weather the call is still
     * recorded (without the rain) while the far clouds are drawn, so the world border after it stays deferred as before.
     */
    public static boolean deferRain(LightTexture light, float tick, double x, double y, double z) {
        if (replaying) return false;
        boolean shader = ShaderWeather.replacesVanilla();
        if (!FarClouds.replacesVanilla() || Minecraft.useShaderTransparency()) return shader;
        rain = !shader;
        lightTexture = light;
        partialTick = tick;
        camX = x;
        camY = y;
        camZ = z;
        MODEL_VIEW.set(RenderSystem.getModelViewMatrix());
        border = null;
        pending = true;
        return true;
    }

    /** At renderWorldBorder's head, right after the weather: deferred with it. */
    public static boolean deferBorder(Camera camera) {
        if (replaying || !pending) return false;
        border = camera;
        return true;
    }

    /**
     * GameRendererPostMixin, after the post-process (whether or not it drew): the recorded weather and border, in the resumed main pass.
     * 2026-10-30 (audit): with the level's TAA jitter taken off the projection, since TAA does not resolve what is drawn after it
     * (the rain and the border shook by a fraction of a pixel every frame).
     */
    public static void replay() {
        if (!pending) return;
        pending = false;
        replaying = true;
        LevelRendererWeatherInvoker renderer = (LevelRendererWeatherInvoker) Minecraft.getInstance().levelRenderer;
        PoseStack stack = RenderSystem.getModelViewStack();
        stack.pushPose();
        stack.last().pose().set(MODEL_VIEW);
        RenderSystem.applyModelViewMatrix();
        float[] jitter = TaaJitter.appliedNdc();
        boolean unjitter = jitter[0] != 0 || jitter[1] != 0;
        if (unjitter) {
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(TaaJitter.unjittered(RenderSystem.getProjectionMatrix(), jitter), RenderSystem.getVertexSorting());
        }
        RenderSystem.depthMask(false);
        try {
            if (rain) renderer.visuals$renderSnowAndRain(lightTexture, partialTick, camX, camY, camZ);
            if (border != null) renderer.visuals$renderWorldBorder(border);
        } finally {
            RenderSystem.depthMask(true);
            if (unjitter) RenderSystem.restoreProjectionMatrix();
            stack.popPose();
            RenderSystem.applyModelViewMatrix();
            replaying = false;
            lightTexture = null;
            border = null;
        }
    }
}
