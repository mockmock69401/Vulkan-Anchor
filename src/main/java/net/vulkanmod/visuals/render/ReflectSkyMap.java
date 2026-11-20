package net.vulkanmod.visuals.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * 2026-11-20 (frame-drop follow-up; the user: "반사속 하늘과 구름 계산을 계속 줄여봐줘"): the reflected sky map of reflection.comp (skyMapAt).
 * The sky, the aurora and the far clouds a level surface mirrors, by direction, made once a frame by a first dispatch of the reflection pass
 * from the camera's mirror image under the water below it, so the water's pixels read them instead of marching the clouds each. This class
 * sets the map's window (the azimuths the view's below-horizon directions mirror into, with a margin for the ripples; all around when the
 * view looks steeply down; off when the view sees nothing below the horizon) and the mirror image's height.
 */
public final class ReflectSkyMap {
    /** The reflection pass's loose uniform slot (skyMapParams) and its bindings: the map read (sampler) and written (storage image). */
    public static final int SLOT = 36, BINDING_READ = 15, BINDING_WRITE = 16;
    /**
     * The map's tiles (TILE x TILE texels, one work group of its dispatch) at BINDING_TILES: a uint each made this frame with its neighbours,
     * then two shares of a uint each the pass read, by the frame's parity (skyMapParams.w 1 or 2). A frame's map makes only the tiles the frame
     * before read, and their neighbours.
     */
    public static final int BINDING_TILES = 17, TILE = 8;
    /** Azimuth columns over the window and elevation rows (0..90 degrees as the row's square); the image is two of these side by side. */
    public static final int COLUMNS = 1536, ROWS = 384;
    /** Radians added on each side of the view's azimuths (the ripples and wakes turn a ray off its mirror direction). */
    public static final float MARGIN = 0.35f;
    /** A view direction this close to straight down (its horizontal part below this) mirrors every azimuth: the map goes all around. */
    public static final float STEEP = 0.26f;
    static final float TAU = (float) (2 * Math.PI);
    /** NDC samples a side over the view when its azimuths are found. */
    static final int GRID = 9;
    /** Blocks under the camera searched for the water's surface; below that the sea level stands in. */
    static final int SEARCH = 96;

    private ReflectSkyMap() {
    }

    /**
     * The map's {first azimuth, span} (radians, atan2(x, z) of world directions) for the view whose camera-relative world directions dirs
     * gives (x, y, z triples), or null when none of them looks below the horizon. All around ({0, 2 pi}) when one looks steeply down or the
     * window would cover it anyway.
     */
    public static float[] window(float[] dirs) {
        double[] az = new double[dirs.length / 3];
        int n = 0;
        for (int i = 0; i + 2 < dirs.length; i += 3) {
            float x = dirs[i], y = dirs[i + 1], z = dirs[i + 2];
            if (!(y < 0)) continue;
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (!(len > 0)) continue;
            if (Math.sqrt(x * x + z * z) / len < STEEP) return new float[]{0, TAU};
            az[n++] = Math.atan2(x, z);
        }
        if (n == 0) return null;
        //The smallest arc holding them all: the circle less the widest gap between neighbouring azimuths
        java.util.Arrays.sort(az, 0, n);
        double gap = az[0] + TAU - az[n - 1];
        int after = 0;
        for (int i = 1; i < n; i++) {
            if (az[i] - az[i - 1] > gap) {
                gap = az[i] - az[i - 1];
                after = i;
            }
        }
        double span = TAU - gap + 2 * MARGIN;
        if (span >= TAU) return new float[]{0, TAU};
        return new float[]{(float) (az[after] - MARGIN), (float) span};
    }

    /** The view's directions (camera relative, world space) at GRID x GRID NDC points: invProjection to view space, invView's rotation to world. */
    public static float[] viewDirections(Matrix4fc invProjection, Matrix4fc invView) {
        float[] out = new float[GRID * GRID * 3];
        Vector3f v = new Vector3f();
        int k = 0;
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                float x = -1 + 2f * i / (GRID - 1), y = -1 + 2f * j / (GRID - 1);
                invProjection.transformProject(x, y, 0.5f, v);
                invView.transformDirection(v);
                out[k++] = v.x;
                out[k++] = v.y;
                out[k++] = v.z;
            }
        }
        return out;
    }

    /** The mirror image's height relative to the camera for a water surface at surfaceY: 2 (surfaceY - cameraY), never above the camera. */
    public static float mirrorY(double surfaceY, double cameraY) {
        return (float) Math.min(2 * (surfaceY - cameraY), 0);
    }

    /**
     * The water's surface under the camera: the top of the first water block (with none above it) within SEARCH blocks down the camera's
     * column, else the level's sea level. NaN when the camera is under that surface (it sees no level water from above).
     */
    public static double surfaceBelow(ClientLevel level, double x, double y, double z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bx = Mth.floor(x), bz = Mth.floor(z), top = Mth.floor(y);
        for (int by = top; by > top - SEARCH && by >= level.getMinBuildHeight(); by--) {
            FluidState f = level.getFluidState(pos.set(bx, by, bz));
            if (!f.is(FluidTags.WATER)) continue;
            if (by == top) return Double.NaN;//the camera is in the water
            return by + f.getHeight(level, pos);
        }
        double sea = level.getSeaLevel();
        return sea <= y ? sea : Double.NaN;
    }

    /** The pass's skyMapParams: {first azimuth, span, mirror height, 1 + the frame's parity (0 or 1)} or all 0 (off). */
    public static float[] params(float[] window, float mirrorY, int parity) {
        return window == null || Float.isNaN(mirrorY) ? new float[4] : new float[]{window[0], window[1], mirrorY, 1 + (parity & 1)};
    }

    /** The tiles of the map (each half). */
    public static int tiles() {
        return ((COLUMNS + TILE - 1) / TILE) * ((ROWS + TILE - 1) / TILE);
    }

    /** The byte offset of the share in which a frame of this parity's pass flags the tiles it reads (reflection.comp's skyMapReadShare). */
    public static long readShareOffset(int parity) {
        return 4L * tiles() * ((parity & 1) == 1 ? 2 : 1);
    }
}
