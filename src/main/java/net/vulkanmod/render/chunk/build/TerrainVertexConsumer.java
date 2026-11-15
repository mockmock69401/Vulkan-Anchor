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
 * its block). The overlay coordinates mean nothing to terrain. A vertex is complete when the next one starts or at finish (the 1.21
 * vertex API has no end call).
 */
final class TerrainVertexConsumer implements VertexConsumer {
    private TerrainBufferBuilder out;
    private float blockY;
    private int count;
    private boolean pending;

    private float x, y, z, u, v;
    private int r = 255, g = 255, b = 255;
    private int light;
    private float nx, ny = 1, nz;

    TerrainVertexConsumer begin(TerrainBufferBuilder out, float blockY) {
        this.out = out;
        this.blockY = blockY;
        this.count = 0;
        this.pending = false;
        return this;
    }

    /** Writes the vertex still open, if any. */
    void finish() {
        if (this.pending) emit();
    }

    private void emit() {
        this.pending = false;
        if ((this.count & 3) == 0) this.out.ensureCapacity();
        float alpha = FoliageMarker.vertexAlpha(null, this.y - this.blockY);
        int colour = ColorUtil.RGBA.pack(this.r / 255f, this.g / 255f, this.b / 255f, alpha);
        this.out.vertex(this.x, this.y, this.z, colour, this.u, this.v, this.light, VertexUtil.packNormal(this.nx, this.ny, this.nz));
        this.count++;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        if (this.pending) emit();
        this.x = x;
        this.y = y;
        this.z = z;
        this.pending = true;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        this.r = r;
        this.g = g;
        this.b = b;
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        this.u = u;
        this.v = v;
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        this.light = (u & 0xFFFF) | (v & 0xFFFF) << 16;
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        this.nx = x;
        this.ny = y;
        this.nz = z;
        return this;
    }

    @Override
    public void addVertex(float x, float y, float z, int colour, float u, float v, int overlay, int light, float nx, float ny, float nz) {
        if (this.pending) emit();
        this.x = x;
        this.y = y;
        this.z = z;
        this.r = colour >> 16 & 0xFF;
        this.g = colour >> 8 & 0xFF;
        this.b = colour & 0xFF;
        this.u = u;
        this.v = v;
        this.light = light;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        emit();
    }
}
