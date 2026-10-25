package net.vulkanmod.visuals.render;

import java.nio.FloatBuffer;

/**
 * 2026-10-16 (CTE2 report: an x-ray look through chunks not built yet; edge cover design 2026-10-16): VulkanMod
 * culls a block's face against its neighbour's block data, so next to a section it has not built yet a built section shows its
 * caves, water and insides through the gap. Each cell of the built section's boundary layer below the surface is covered on that
 * side by a quad just outside the face: stone where the cell is open (air, water, plants), the cell's own texture where it is a
 * full block whose face vanilla culled. Where the cell across is open, vanilla draws the face itself and nothing is added.
 */
public final class EdgeCover {
    public static final int NEG_X = 0, POS_X = 1, NEG_Z = 2, POS_Z = 3;
    /** How far outside the face the cover sits (blocks), so it does not fight a face vanilla draws in the same plane. */
    public static final float OFFSET = 1 / 64f;
    /** Vanilla's directional face shade (X faces, Z faces). */
    public static final float SHADE_X = 0.6f, SHADE_Z = 0.8f;
    /**
     * A quad: its lower corner xyz (origin relative; on the face's plane, at the column's low horizontal edge), height (blocks),
     * sprite u0 v0 u1 v1, sky light, block light, shade, side. Gate GEC: a quad a cell overflowed the upload stream, so a column's
     * run of like cells (sprite and light) is one quad, its texture repeated per block by the shader.
     */
    public static final int FLOATS_PER_QUAD = 12;
    private static final int[] DX = {-1, 1, 0, 0}, DZ = {0, 0, -1, 1};

    /** VulkanMod's section grid, section coordinates. */
    public interface Sections {
        boolean compiled(int sx, int sy, int sz);

        /**
         * Seen by VulkanMod's traversal this frame, not built yet and not empty air: a hole the camera looks into. Gate GEC:
         * sections it never sees (deep underground) or never builds (empty air) stay unbuilt for good and are not holes.
         */
        boolean pending(int sx, int sy, int sz);
    }

    /** The client level's blocks, block coordinates. */
    public interface Blocks {
        /** A full, opaque cube (its faces cull their neighbours'). */
        boolean full(int x, int y, int z);

        /** The column's surface: the first y above its highest motion-blocking block, leaves left out. */
        int surface(int x, int z);

        int sky(int x, int y, int z);

        int block(int x, int y, int z);

        /** The block's atlas sprite {u0, v0, u1, v1}; null for an open cell. */
        float[] sprite(int x, int y, int z);
    }

    public record Face(int sx, int sy, int sz, int dir) {
    }

    private EdgeCover() {
    }

    /**
     * Whether VulkanMod can build chunk (cx, cz) with the camera in chunk (camX, camZ) at render distance r: the chunks at the
     * render distance itself never build (their outer neighbours are not loaded), so they are not holes (gate GEC).
     */
    public static boolean buildable(int cx, int cz, int camX, int camZ, int r) {
        return Math.max(Math.abs(cx - camX), Math.abs(cz - camZ)) < r;
    }

    /** Whether built section (sx, sy, sz) is covered on side dir: its neighbour there is pending. */
    public static boolean covers(Sections sections, int sx, int sy, int sz, int dir) {
        return sections.compiled(sx, sy, sz) && sections.pending(sx + DX[dir], sy, sz + DZ[dir]);
    }

    /**
     * Writes the face's cover quads relative to (ox, oy, oz); returns the quads written. 2026-10-17 (second CTE2 report: the caves
     * still showed through the per-block cover): one stone quad a column from the section's bottom to the surface, lit by the
     * surface's sky light, so nothing of what is behind it shows.
     */
    public static int quads(Face face, Blocks blocks, float[] stone, int ox, int oy, int oz, FloatBuffer out) {
        int dir = face.dir();
        boolean alongX = dir == NEG_X || dir == POS_X;
        int baseX = face.sx() * 16, baseY = face.sy() * 16, baseZ = face.sz() * 16;
        int layer = alongX ? (dir == POS_X ? baseX + 15 : baseX) : (dir == POS_Z ? baseZ + 15 : baseZ);
        float plane = dir == POS_X || dir == POS_Z ? layer + 1 + OFFSET : layer - OFFSET;
        float shade = alongX ? SHADE_X : SHADE_Z;
        int quads = 0;
        for (int u = 0; u < 16; u++) {
            int x = alongX ? layer : baseX + u, z = alongX ? baseZ + u : layer;
            int surface = blocks.surface(x, z);
            int top = Math.min(baseY + 16, surface);
            if (top <= baseY) continue;//sky above the surface
            float a = (alongX ? z : x) - (alongX ? oz : ox);
            float p = plane - (alongX ? ox : oz);
            if (alongX) out.put(p).put(baseY - oy).put(a);
            else out.put(a).put(baseY - oy).put(p);
            out.put(top - baseY).put(stone[0]).put(stone[1]).put(stone[2]).put(stone[3])
                    .put(blocks.sky(x, surface, z)).put(0).put(shade).put(dir);
            quads++;
        }
        return quads;
    }
}
