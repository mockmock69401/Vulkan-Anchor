package net.vulkanmod.visuals.particle;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.vulkanmod.interfaces.ExtendedVertexBuilder;
import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.render.TaaJitter;
import org.joml.Matrix4f;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * 2026-11-13 (session 13 follow-up, the user: "the realistic particles look low-effort: use real images, as the moon does"): the realistic
 * particles drawn after the post-process with their images (ParticleSprites, the particle atlas), as the deferred weather is.
 *
 * <p>Why after: the images are soft (a puff's wisps, a flame's licks, a glow's halo), and the composite takes a particle that covers only
 * part of a pixel apart from what lies behind it tile by tile (scene_copy.comp translucentParticle): a soft image there showed tile-sized
 * steps and its faint texels hid what was drawn behind them (the depth). Here each quad is recorded while the particles draw
 * (SingleQuadParticleM: the camera-relative corners, the atlas tile, its colour and light) instead of being drawn, and drawn over the
 * finished picture with the particle shader (its lightmap, fog and the look's tint; particle.fsh's sprite mode writes no scene light),
 * tested against the level's depth without writing it: smoke, drops, bubbles, motes and petals far to near over what is behind them, then
 * flames, embers, orbs and glints added as light. The level's model-view and the projection without the TAA jitter (TAA does not
 * resolve what is drawn after it), as DeferredWeather. Only while the realistic type is on for a group: nothing is recorded otherwise.
 */
public final class DeferredParticles {
    private DeferredParticles() {
    }

    public static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath("vulkanmod", "visuals/textures/particles/particle_atlas.png");
    /** Floats per recorded quad: 4 corners x (x, y, z, u, v), then the colour, the light (as float bits) and the sort distance. */
    private static final int QUAD = 4 * 5 + 3;
    /** The most quads kept a frame (the rest are dropped): vanilla's particle limit per sheet. */
    private static final int MAX_QUADS = 16384;

    private static final Batch COVER = new Batch(), LIGHT = new Batch();
    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    private static boolean recorded;
    private static boolean atlasLoaded;

    private static final class Batch {
        float[] data = new float[QUAD * 256];
        int quads;

        void clear() {
            this.quads = 0;
        }

        int add() {
            if (this.quads >= MAX_QUADS) return -1;
            if ((this.quads + 1) * QUAD > this.data.length) this.data = Arrays.copyOf(this.data, this.data.length * 2);
            return this.quads++ * QUAD;
        }
    }

    /**
     * Records one quad: corners (x, y, z camera-relative, in SingleQuadParticleM's order), the atlas uv, the packed colour and light
     * (ParticleLookMath.light, with the sprite flag), drawn as light or over what is behind.
     */
    public static void add(float[] corners, float u0, float u1, float v0, float v1, int colour, int light, boolean additive) {
        if (!recorded) {
            MODEL_VIEW.set(RenderSystem.getModelViewMatrix());
            recorded = true;
        }
        Batch b = additive ? LIGHT : COVER;
        int o = b.add();
        if (o < 0) return;
        float[] uv = {u1, v1, u1, v0, u0, v0, u0, v1};
        float cx = 0, cy = 0, cz = 0;
        for (int k = 0; k < 4; k++) {
            int c = o + k * 5;
            b.data[c] = corners[k * 3];
            b.data[c + 1] = corners[k * 3 + 1];
            b.data[c + 2] = corners[k * 3 + 2];
            b.data[c + 3] = uv[k * 2];
            b.data[c + 4] = uv[k * 2 + 1];
            cx += corners[k * 3];
            cy += corners[k * 3 + 1];
            cz += corners[k * 3 + 2];
        }
        b.data[o + 20] = Float.intBitsToFloat(colour);
        b.data[o + 21] = Float.intBitsToFloat(light);
        b.data[o + 22] = cx * cx + cy * cy + cz * cz;
    }

    /** GameRendererPostMixin, after the post-process and the deferred weather: draws and forgets this frame's quads. */
    public static void replay() {
        if (!recorded) return;
        recorded = false;
        try {
            if (COVER.quads > 0 || LIGHT.quads > 0) draw();
        } catch (RuntimeException e) {
            VisualsLog.warn("Visuals realistic particles could not be drawn", e);
        } finally {
            COVER.clear();
            LIGHT.clear();
        }
    }

    private static void draw() {
        Minecraft mc = Minecraft.getInstance();
        loadAtlas(mc);
        org.joml.Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.set(MODEL_VIEW);
        RenderSystem.applyModelViewMatrix();
        float[] jitter = TaaJitter.appliedNdc();
        boolean unjitter = jitter[0] != 0 || jitter[1] != 0;
        if (unjitter) {
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(TaaJitter.unjittered(RenderSystem.getProjectionMatrix(), jitter), RenderSystem.getVertexSorting());
        }
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.setShader(GameRenderer::getParticleShader);
        RenderSystem.setShaderTexture(0, ATLAS);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        try {
            //Far to near over what is behind them
            RenderSystem.blendFuncSeparate(GlConst.SRC_ALPHA, GlConst.ONE_MINUS_SRC_ALPHA, GlConst.ONE, GlConst.ONE_MINUS_SRC_ALPHA);
            drawBatch(COVER, true);
            //Light: added (order does not matter)
            RenderSystem.blendFuncSeparate(GlConst.SRC_ALPHA, GlConst.ONE, GlConst.ZERO, GlConst.ONE);
            drawBatch(LIGHT, false);
        } finally {
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            mc.gameRenderer.lightTexture().turnOffLightLayer();
            if (unjitter) RenderSystem.restoreProjectionMatrix();
            stack.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /** GL's blend factors (RenderSystem's int overloads). */
    private static final class GlConst {
        static final int ZERO = 0, ONE = 1, SRC_ALPHA = 0x0302, ONE_MINUS_SRC_ALPHA = 0x0303;
    }

    private static void drawBatch(Batch b, boolean sort) {
        if (b.quads == 0) return;
        Integer[] order = new Integer[b.quads];
        for (int i = 0; i < b.quads; i++) order[i] = i;
        if (sort) {
            float[] d = b.data;
            Arrays.sort(order, (x, y) -> Float.compare(d[y * QUAD + 22], d[x * QUAD + 22]));
        }
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        ExtendedVertexBuilder vb = (ExtendedVertexBuilder) builder;
        for (int i : order) {
            int o = i * QUAD;
            int colour = Float.floatToRawIntBits(b.data[o + 20]);
            int light = Float.floatToRawIntBits(b.data[o + 21]);
            for (int k = 0; k < 4; k++) {
                int c = o + k * 5;
                vb.vertex(b.data[c], b.data[c + 1], b.data[c + 2], b.data[c + 3], b.data[c + 4], colour, light);
            }
        }
        BufferUploader.drawWithShader(builder.buildOrThrow());
    }

    private static void loadAtlas(Minecraft mc) {
        if (atlasLoaded) return;
        atlasLoaded = true;
        mc.getTextureManager().register(ATLAS, new AtlasTexture());
    }

    /** The atlas with a full mip chain (made here: the bake stores level 0), linear filtered, clamped. */
    private static final class AtlasTexture extends AbstractTexture {
        @Override
        public void load(ResourceManager manager) throws IOException {
            NativeImage image;
            try (InputStream in = manager.open(ATLAS)) {
                image = NativeImage.read(in);
            }
            int levels = 0;
            for (int s = Math.min(image.getWidth(), image.getHeight()); s > ParticleSprites.TILE / 16; s >>= 1) levels++;
            levels = Math.max(0, levels - 1);
            TextureUtil.prepareImage(this.getId(), levels, image.getWidth(), image.getHeight());
            this.setFilter(true, true);
            NativeImage level = image;
            for (int l = 0; l <= levels; l++) {
                level.upload(l, 0, 0, 0, 0, level.getWidth(), level.getHeight(), true, true, true, false);
                NativeImage next = l < levels ? halve(level) : null;
                if (level != image) level.close();
                if (next == null) break;
                level = next;
            }
            image.close();
        }
    }

    /** A box-filtered half-size level, premultiplied while averaging (no dark fringes around the soft images). */
    private static NativeImage halve(NativeImage src) {
        int w = Math.max(1, src.getWidth() / 2), h = Math.max(1, src.getHeight() / 2);
        NativeImage dst = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float r = 0, g = 0, b = 0, a = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int p = src.getPixelRGBA(Math.min(src.getWidth() - 1, x * 2 + dx), Math.min(src.getHeight() - 1, y * 2 + dy));
                        float pa = ((p >>> 24) & 0xFF) / 255f;
                        r += (p & 0xFF) * pa;
                        g += ((p >> 8) & 0xFF) * pa;
                        b += ((p >> 16) & 0xFF) * pa;
                        a += pa;
                    }
                }
                int out;
                if (a <= 0) {
                    out = 0x00FFFFFF;
                } else {
                    int ri = Math.min(255, Math.round(r / a)), gi = Math.min(255, Math.round(g / a)), bi = Math.min(255, Math.round(b / a));
                    out = Math.round(a / 4f * 255f) << 24 | bi << 16 | gi << 8 | ri;
                }
                dst.setPixelRGBA(x, y, out);
            }
        }
        return dst;
    }
}
