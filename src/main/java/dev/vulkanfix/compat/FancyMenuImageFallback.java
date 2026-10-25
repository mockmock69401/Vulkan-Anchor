package dev.vulkanfix.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.lang.reflect.Method;

/**
 * FancyMenu draws image elements (rounded or not) through a PostChain in SmoothImageRectangleRenderer. Under VulkanMod that
 * chain, run in the middle of the GUI, leaves the rest of the frame drawing nowhere: the screen keeps the image and loses
 * every widget drawn after it, and a world joined later stays white. Its shader used to fail to load (so FancyMenu drew no
 * image at all); since the comment stripping it loads. Under VulkanMod the image is drawn as a plain textured quad instead,
 * with the same texture region and colour; rounded corners come out square.
 */
public final class FancyMenuImageFallback {
    private FancyMenuImageFallback() {
    }

    /** minU, minV, maxU, maxV of FancyMenu's TextureRegion record (a private type, read by its accessors); the whole texture for null. */
    static float[] uvs(Object region) {
        if (region == null) {
            return new float[]{0, 0, 1, 1};
        }
        try {
            float[] out = new float[4];
            String[] names = {"minU", "minV", "maxU", "maxV"};
            for (int i = 0; i < 4; i++) {
                Method m = region.getClass().getDeclaredMethod(names[i]);
                m.setAccessible(true);
                out[i] = (float) m.invoke(region);
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new float[]{0, 0, 1, 1};
        }
    }

    /** An ARGB colour as red, green, blue, alpha in 0..1. */
    static float[] rgba(int argb) {
        return new float[]{(argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, (argb >>> 24) / 255f};
    }

    public static void draw(GuiGraphics graphics, ResourceLocation texture, float x, float y, float width, float height, Object region, int color) {
        float[] uv = uvs(region);
        float[] c = rgba(color);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        Matrix4f pose = graphics.pose().last().pose();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        builder.addVertex(pose, x, y, 0).setUv(uv[0], uv[1]).setColor(c[0], c[1], c[2], c[3]);
        builder.addVertex(pose, x, y + height, 0).setUv(uv[0], uv[3]).setColor(c[0], c[1], c[2], c[3]);
        builder.addVertex(pose, x + width, y + height, 0).setUv(uv[2], uv[3]).setColor(c[0], c[1], c[2], c[3]);
        builder.addVertex(pose, x + width, y, 0).setUv(uv[2], uv[1]).setColor(c[0], c[1], c[2], c[3]);
        BufferUploader.drawWithShader(builder.buildOrThrow());
        RenderSystem.disableBlend();
    }
}
