package net.vulkanmod.visuals.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.vulkanmod.visuals.vk.LooseUniformWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 2026-11-13 (shader roadmap session 19, the user: "portal light, as AstraLex draws it"): the nether portals around the camera, as boxes
 * of portal blocks the composite colours the block light by and makes glow (scene_copy.comp portalShareAt). Every SCAN_FRAMES frames the
 * chunk sections within SCAN_SECTIONS of the camera's that may hold a portal block (their palette says so: LevelChunkSection.maybeHas) are
 * read; touching portal blocks merge into one box; the MAX_BOXES nearest are kept.
 * <p>
 * Slots (composite): PORTAL_FIRST .. PORTAL_FIRST + 2 MAX_BOXES - 1, two per box: xyz its low corner relative to the camera (blocks),
 * w 1 when the box is there; xyz its high corner (the far side of its last blocks) relative to the camera, w the portal glow strength.
 */
public final class PortalLights {
    public static final int PORTAL_FIRST = 63;
    public static final int MAX_BOXES = 4;
    /** Sections around the camera's section scanned (2: a 5x5x5 section cube, 80 blocks across). */
    public static final int SCAN_SECTIONS = 2;
    public static final int SCAN_FRAMES = 20;
    private static final Predicate<BlockState> PORTAL = s -> s.is(Blocks.NETHER_PORTAL);

    /** A box of portal blocks, block coordinates, high bounds exclusive. */
    public record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
        boolean touches(int x, int y, int z) {
            return x >= this.x0 - 1 && x <= this.x1 && y >= this.y0 - 1 && y <= this.y1 && z >= this.z0 - 1 && z <= this.z1;
        }

        Box with(int x, int y, int z) {
            return new Box(Math.min(this.x0, x), Math.min(this.y0, y), Math.min(this.z0, z), Math.max(this.x1, x + 1), Math.max(this.y1, y + 1),
                    Math.max(this.z1, z + 1));
        }

        Box merge(Box o) {
            return new Box(Math.min(this.x0, o.x0), Math.min(this.y0, o.y0), Math.min(this.z0, o.z0), Math.max(this.x1, o.x1), Math.max(this.y1, o.y1),
                    Math.max(this.z1, o.z1));
        }

        boolean overlapsGrown(Box o) {
            return o.x0 <= this.x1 && o.x1 >= this.x0 && o.y0 <= this.y1 && o.y1 >= this.y0 && o.z0 <= this.z1 && o.z1 >= this.z0;
        }

        /** The squared distance from (x, y, z) to the box. */
        double distanceSq(double x, double y, double z) {
            double dx = Math.max(Math.max(this.x0 - x, x - this.x1), 0), dy = Math.max(Math.max(this.y0 - y, y - this.y1), 0),
                    dz = Math.max(Math.max(this.z0 - z, z - this.z1), 0);
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private List<Box> boxes = List.of();
    private int frames = SCAN_FRAMES;
    private ClientLevel level;

    /** Blocks (x, y, z) merged into boxes of touching blocks (a box absorbs every block next to it or inside it). */
    public static List<Box> boxes(List<int[]> blocks) {
        List<Box> out = new ArrayList<>();
        for (int[] b : blocks) {
            Box hit = null;
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).touches(b[0], b[1], b[2])) {
                    hit = out.remove(i).with(b[0], b[1], b[2]);
                    break;
                }
            }
            if (hit == null) hit = new Box(b[0], b[1], b[2], b[0] + 1, b[1] + 1, b[2] + 1);
            //A grown box may now reach another
            boolean merged = true;
            while (merged) {
                merged = false;
                for (int i = 0; i < out.size(); i++) {
                    if (out.get(i).overlapsGrown(hit)) {
                        hit = hit.merge(out.remove(i));
                        merged = true;
                        break;
                    }
                }
            }
            out.add(hit);
        }
        return out;
    }

    /** The up to MAX_BOXES boxes nearest (x, y, z), nearest first. */
    public static List<Box> nearest(List<Box> boxes, double x, double y, double z) {
        List<Box> sorted = new ArrayList<>(boxes);
        sorted.sort((a, b) -> Double.compare(a.distanceSq(x, y, z), b.distanceSq(x, y, z)));
        return sorted.size() > MAX_BOXES ? List.copyOf(sorted.subList(0, MAX_BOXES)) : List.copyOf(sorted);
    }

    /** This frame's boxes around the camera (rescanned every SCAN_FRAMES frames and in a new level); none without a level. */
    public List<Box> update(ClientLevel level, double x, double y, double z) {
        if (level == null) {
            this.level = null;
            this.boxes = List.of();
            return this.boxes;
        }
        if (level != this.level || ++this.frames >= SCAN_FRAMES) {
            this.level = level;
            this.frames = 0;
            try {
                this.boxes = nearest(boxes(scan(level, x, y, z)), x, y, z);
            } catch (RuntimeException e) {
                this.boxes = List.of();
            }
        }
        return this.boxes;
    }

    private static List<int[]> scan(ClientLevel level, double x, double y, double z) {
        List<int[]> found = new ArrayList<>();
        int cx = (int) Math.floor(x) >> 4, cy = (int) Math.floor(y) >> 4, cz = (int) Math.floor(z) >> 4;
        for (int sx = cx - SCAN_SECTIONS; sx <= cx + SCAN_SECTIONS; sx++) {
            for (int sz = cz - SCAN_SECTIONS; sz <= cz + SCAN_SECTIONS; sz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
                if (chunk == null) continue;
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = cy - SCAN_SECTIONS; sy <= cy + SCAN_SECTIONS; sy++) {
                    int index = level.getSectionIndexFromSectionY(sy);
                    if (index < 0 || index >= sections.length) continue;
                    LevelChunkSection s = sections[index];
                    if (s == null || s.hasOnlyAir() || !s.maybeHas(PORTAL)) continue;
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int lx = 0; lx < 16; lx++) {
                                if (PORTAL.test(s.getBlockState(lx, ly, lz))) found.add(new int[]{(sx << 4) + lx, (sy << 4) + ly, (sz << 4) + lz});
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    /** The composite's slots for boxes around a camera at (x, y, z) with the glow strength (0: every box absent). */
    public static void write(LooseUniformWriter w, List<Box> boxes, double x, double y, double z, float strength) {
        for (int i = 0; i < MAX_BOXES; i++) {
            int slot = PORTAL_FIRST + 2 * i;
            if (!w.has(slot) || !w.has(slot + 1)) continue;
            if (i < boxes.size() && strength > 0) {
                Box b = boxes.get(i);
                w.putVec4(slot, (float) (b.x0() - x), (float) (b.y0() - y), (float) (b.z0() - z), 1);
                w.putVec4(slot + 1, (float) (b.x1() - x), (float) (b.y1() - y), (float) (b.z1() - z), strength);
            } else {
                w.putVec4(slot, 0, 0, 0, 0);
                w.putVec4(slot + 1, 0, 0, 0, 0);
            }
        }
    }
}
