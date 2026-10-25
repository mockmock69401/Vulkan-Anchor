package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * DH's matrices in the fork's terms. DH builds its FORWARD_Z projection with GL's depth range (-1 at the near plane, 1 at the
 * far one); the LOD target is drawn with Vulkan's 0..1, so the depth row becomes half of itself plus half of the w row
 * (z' = 0.5 z + 0.5 w), the same remap Reforged gives vanilla's projection.
 */
public final class DhDepth {
    private DhDepth() {
    }

    /** The projection with Vulkan's depth range; x, y and w are untouched. */
    public static Matrix4f toVulkan(Matrix4fc projection) {
        Matrix4f vk = new Matrix4f(projection);
        vk.m02(0.5f * projection.m02() + 0.5f * projection.m03());
        vk.m12(0.5f * projection.m12() + 0.5f * projection.m13());
        vk.m22(0.5f * projection.m22() + 0.5f * projection.m23());
        vk.m32(0.5f * projection.m32() + 0.5f * projection.m33());
        return vk;
    }

    /** DH names a matrix's elements row then column (m23: row 2, column 3); JOML's are column then row. */
    public static Matrix4f fromDh(DhApiMat4f m) {
        return new Matrix4f(
                m.m00, m.m10, m.m20, m.m30,
                m.m01, m.m11, m.m21, m.m31,
                m.m02, m.m12, m.m22, m.m32,
                m.m03, m.m13, m.m23, m.m33);
    }

    /** Whether every element is a number (the first frame's projection was seen holding NaN). */
    public static boolean finite(DhApiMat4f m) {
        for (float v : new float[]{m.m00, m.m01, m.m02, m.m03, m.m10, m.m11, m.m12, m.m13, m.m20, m.m21, m.m22, m.m23, m.m30, m.m31, m.m32, m.m33}) {
            if (!Float.isFinite(v)) return false;
        }
        return true;
    }
}
