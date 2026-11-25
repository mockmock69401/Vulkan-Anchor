package net.vulkanmod.render.chunk.build;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import net.vulkanmod.render.vertex.VertexUtil;
import net.vulkanmod.visuals.render.FoliageMarker;
import net.vulkanmod.vulkan.util.ColorUtil;

/**
 * Session 23: vanilla's vertex sink in front of our terrain buffer, for the blocks drawn by vanilla's block renderer (FrapiModels). The
 * position arrives section-relative (BlockRenderer.renderFrapi's pose); the colour already holds the tint, shade and AO, as ours does;
 * the alpha becomes the block's visuals mark as in BlockRenderer.putQuadData (the plant / leaf height marks by the vertex's height in
 * its block). The overlay coordinates mean nothing to terrain. 2026-11-25: a quad is written once its 4 vertices are in, so that an
 * overlay lying on an earlier quad of the block keeps its transparency in a solid-layer call (QuadOverlay: Continuity's emissive layers).
 */
final class TerrainVertexConsumer implements VertexConsumer {
    private TerrainBufferBuilder out;
    private float blockY;
    private int count;

    private float x, y, z, u, v;
    private int r = 255, g = 255, b = 255;
    private int light;
    private float nx, ny = 1, nz;

    //the quad being gathered, and the block's quads written so far (their corners, for QuadOverlay)
    private final float[] quad = new float[12];
    private final float[] quadU = new float[4], quadV = new float[4];
    private final int[] quadColour = new int[4], quadLight = new int[4], quadNormal = new int[4];
    private final float[] seen = new float[12 * QuadOverlay.MAX_QUADS];
    private int seenCount;

    TerrainVertexConsumer begin(TerrainBufferBuilder out, float blockY) {
        this.out = out;
        this.blockY = blockY;
        this.count = 0;
        this.seenCount = 0;
        return this;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        this.x = (float) x;
        this.y = (float) y;
        this.z = (float) z;
        return this;
    }

    @Override
    public VertexConsumer color(int r, int g, int b, int a) {
        this.r = r;
        this.g = g;
        this.b = b;
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        this.u = u;
        this.v = v;
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        this.light = (u & 0xFFFF) | (v & 0xFFFF) << 16;
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        this.nx = x;
        this.ny = y;
        this.nz = z;
        return this;
    }

    @Override
    public void endVertex() {
        int i = this.count & 3;
        float alpha = FoliageMarker.vertexAlpha(null, this.y - this.blockY);
        this.quad[i * 3] = this.x;
        this.quad[i * 3 + 1] = this.y;
        this.quad[i * 3 + 2] = this.z;
        this.quadColour[i] = ColorUtil.RGBA.pack(this.r / 255f, this.g / 255f, this.b / 255f, alpha);
        this.quadU[i] = this.u;
        this.quadV[i] = this.v;
        this.quadLight[i] = this.light;
        this.quadNormal[i] = VertexUtil.packNormal(this.nx, this.ny, this.nz);
        this.count++;
        if (i == 3) writeQuad();
    }

    private void writeQuad() {
        boolean overlay = false;
        if (this.out.isOpaque()) {
            overlay = QuadOverlay.coversEarlier(this.seen, this.seenCount, this.quad, 0);
            if (!overlay && this.seenCount < QuadOverlay.MAX_QUADS) {
                System.arraycopy(this.quad, 0, this.seen, this.seenCount * 12, 12);
                this.seenCount++;
            }
        }
        if (overlay) this.out.setOpaque(false);
        this.out.ensureCapacity();
        for (int i = 0; i < 4; i++) {
            this.out.vertex(this.quad[i * 3], this.quad[i * 3 + 1], this.quad[i * 3 + 2], this.quadColour[i], this.quadU[i], this.quadV[i],
                    this.quadLight[i], this.quadNormal[i]);
        }
        if (overlay) this.out.setOpaque(true);
    }

    @Override
    public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v, int overlay, int light,
                       float nx, float ny, float nz) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.r = (int) (red * 255f);
        this.g = (int) (green * 255f);
        this.b = (int) (blue * 255f);
        this.u = u;
        this.v = v;
        this.light = light;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        endVertex();
    }

    @Override
    public void defaultColor(int r, int g, int b, int a) {
    }

    @Override
    public void unsetDefaultColor() {
    }
}
