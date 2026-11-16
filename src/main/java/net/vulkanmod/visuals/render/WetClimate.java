package net.vulkanmod.visuals.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * 2026-11-16 (the user: no rain-wet or drying ground in deserts): the client level's WetClimateMap, kept while it rains or the ground is
 * still wet (PbrUniforms.update), and whether its texels changed since VkPostProcess last took them. Render thread only.
 */
public final class WetClimate {
    /** The map is updated at most this often (nanoseconds, 30 a second): its biomes do not change, the ground's height seldom. */
    static final long UPDATE_NANOS = 1_000_000_000L / 30;
    private static final WetClimateMap MAP = new WetClimateMap();
    private static ClientLevel level;
    private static LevelPrecipitation columns;
    private static long lastNanos = Long.MIN_VALUE;
    private static boolean ready, changed, rainsAtCamera;
    private static double cameraX, cameraZ;

    private WetClimate() {
    }

    /**
     * Once a frame: while wanted (it rains, or the ground is wet) the map around the camera; otherwise it stays as it is (its cells stay
     * right: biomes do not move). A failure (a modded level) leaves no map: the wetness is then the camera's alone, as before.
     */
    static void update(ClientLevel lvl, BlockPos camera, boolean wanted, boolean rainsHere) {
        rainsAtCamera = rainsHere;
        cameraX = camera.getX() + 0.5;
        cameraZ = camera.getZ() + 0.5;
        if (lvl != level) {
            level = lvl;
            columns = lvl != null ? new LevelPrecipitation(lvl) : null;
            MAP.clear();
            ready = false;
            changed = true;
            lastNanos = Long.MIN_VALUE;
        }
        if (!wanted || columns == null) return;
        long now = System.nanoTime();
        if (lastNanos != Long.MIN_VALUE && now - lastNanos < UPDATE_NANOS && now >= lastNanos) return;
        lastNanos = now;
        try {
            columns.newFrame();
            changed |= MAP.update(columns, camera.getX(), camera.getZ(), WetClimateMap.BUDGET);
            ready = true;
        } catch (RuntimeException e) {
            MAP.clear();
            ready = false;
            changed = true;
        }
    }

    /** Whether the map answers for the ground around the camera (it was updated in this level). */
    public static boolean ready() {
        return ready;
    }

    /** Whether the rain falls anywhere in the map. */
    public static boolean anyRain() {
        return MAP.anyRain();
    }

    /** The texels when they changed since the last call (then to be uploaded), else null. */
    public static int[] takeChanged() {
        if (!changed) return null;
        changed = false;
        return MAP.texels();
    }

    /** Marks the texels for another upload (the last one's frame was dropped). */
    public static void resend() {
        changed = true;
    }

    /** The composite's wetClimate (WetClimateMap.params) for this frame. */
    public static float[] params() {
        return WetClimateMap.params(ready, cameraX, cameraZ, rainsAtCamera);
    }
}
