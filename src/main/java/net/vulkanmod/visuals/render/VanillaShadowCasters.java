package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * P7o design 4.1: the vanilla sections that cast into the near sun shadow cascade: compiled, not empty, inside the cascade's
 * box (in view of the camera or not: a tree behind it still shades the ground in front), grouped by VulkanMod chunk area,
 * whose vertex buffers they share.
 */
public final class VanillaShadowCasters {
    /** Half the diagonal of a 16-block section: its bounding sphere's radius. */
    static final float SECTION_RADIUS = (float) (8 * Math.sqrt(3));
    /**
     * The horizontal reach of the vanilla casters around the camera that was fixed before it became an argument. Grass and leaf
     * detail is under a texel of the near cascade farther out (where its LOD shadows stay); the whole cascade (160) cost a third
     * of the frame rate at noon.
     */
    public static final float REACH = 64;

    /** A VulkanMod section as the collector sees it: block origin (world), whether it has anything to draw, its chunk area. */
    public interface Section {
        boolean drawable();

        int blockX();

        int blockY();

        int blockZ();

        Object area();
    }

    /** A chunk area and its casting sections, in grid order. */
    public record Area(Object area, List<Section> sections) {
    }

    private VanillaShadowCasters() {
    }

    /** terrain.vsh's section offset in the instance index: x bits 0-7, y bits 16-23, z bits 8-15 (blocks from the area origin). */
    static int[] baseOffset(int baseInstance) {
        return new int[]{baseInstance & 0xFF, (baseInstance >>> 16) & 0xFF, (baseInstance >>> 8) & 0xFF};
    }

    /**
     * Whether a section whose centre is at (cx, cy, cz), camera-relative, can reach the cascade: its bounding sphere overlaps
     * the orthographic box (|ndc x|, |ndc y| within 1, depth within 0..1).
     */
    static boolean inLightBox(Matrix4f viewProjection, float cx, float cy, float cz) {
        Matrix4f m = viewProjection;
        float x = m.m00() * cx + m.m10() * cy + m.m20() * cz + m.m30();
        float y = m.m01() * cx + m.m11() * cy + m.m21() * cz + m.m31();
        float z = m.m02() * cx + m.m12() * cy + m.m22() * cz + m.m32();
        float rx = SECTION_RADIUS * (float) Math.sqrt(m.m00() * m.m00() + m.m10() * m.m10() + m.m20() * m.m20());
        float ry = SECTION_RADIUS * (float) Math.sqrt(m.m01() * m.m01() + m.m11() * m.m11() + m.m21() * m.m21());
        float rz = SECTION_RADIUS * (float) Math.sqrt(m.m02() * m.m02() + m.m12() * m.m12() + m.m22() * m.m22());
        return Math.abs(x) <= 1 + rx && Math.abs(y) <= 1 + ry && z >= -rz && z <= 1 + rz;
    }

    /**
     * The casting sections, by area in first-seen order; viewProjection takes positions relative to (camX, camY, camZ). Only
     * sections whose centre is within casterRadius (+ half a section) of the camera, on both horizontal axes, cast.
     */
    public static List<Area> collect(Iterable<? extends Section> sections, Matrix4f viewProjection, double camX, double camY, double camZ,
                                     float casterRadius) {
        Map<Object, Area> areas = new IdentityHashMap<>();
        List<Area> order = new ArrayList<>();
        for (Section s : sections) {
            if (s == null || !s.drawable()) continue;
            float cx = (float) (s.blockX() + 8 - camX), cy = (float) (s.blockY() + 8 - camY), cz = (float) (s.blockZ() + 8 - camZ);
            if (Math.max(Math.abs(cx), Math.abs(cz)) > casterRadius + 8) continue;
            if (!inLightBox(viewProjection, cx, cy, cz)) continue;
            Area a = areas.get(s.area());
            if (a == null) {
                a = new Area(s.area(), new ArrayList<>());
                areas.put(s.area(), a);
                order.add(a);
            }
            a.sections().add(s);
        }
        return order;
    }
}
