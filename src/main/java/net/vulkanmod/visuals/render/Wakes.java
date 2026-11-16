package net.vulkanmod.visuals.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.visuals.config.VisualsConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 2026-11-16 (shader roadmap session 26): the wakes' trails (WakeMath) of the mobs, players and boats within RANGE blocks of the camera whose
 * body crosses the water's surface; updated once a frame by VisualsRenderer (captureAtmosphere) with the flow clock, packed for the reflection
 * pass by VkPostProcess. Riders leave none (their boat does); a trail outlives its entity until its last ring has faded. Nothing with the
 * setting off.
 */
public final class Wakes {
    /** Blocks around the camera an entity leaves a wake in. */
    public static final double RANGE = 48;
    private static final Map<Integer, WakeMath.Trail> TRAILS = new HashMap<>();
    private static final Map<WakeMath.Trail, Double> DISTANCE = new HashMap<>();
    private static final float[] DATA = new float[WakeMath.VEC4S * 4];
    private static ClientLevel level;
    private static double now;
    private static int packed, chunks;

    private Wakes() {
    }

    /** The setting's strength (ratio; 0 = off). */
    public static float strength() {
        VisualsConfig c = VisualsConfig.get();
        return c != null && c.enabled && c.waterWakes ? Math.max(c.waterWakeStrength, 0) / 100f : 0;
    }

    /** One frame at the flow clock's seconds: every entity at the surface moves its trail on. */
    public static void update(ClientLevel lvl, double seconds, float partialTick, Vec3 camera) {
        now = seconds;
        if (lvl == null || strength() <= 0) {
            TRAILS.clear();
            level = null;
            return;
        }
        if (lvl != level) {
            TRAILS.clear();
            level = lvl;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (Entity e : lvl.entitiesForRendering()) {
            if (!(e instanceof LivingEntity || e instanceof Boat) || e.isPassenger() || e.isRemoved()) continue;
            if (e instanceof Player p && p.isSpectator()) continue;
            Vec3 at = e.getPosition(partialTick);
            if (at.distanceToSqr(camera) > RANGE * RANGE) continue;
            AABB box = e.getBoundingBox().move(at.x - e.getX(), at.y - e.getY(), at.z - e.getZ());
            double surface = surfaceAt(lvl, pos, at.x, box.minY, box.maxY, at.z);
            boolean touching = !Double.isNaN(surface) && surface >= box.minY - 0.15 && surface <= box.maxY + 0.05;
            WakeMath.Trail trail = TRAILS.get(e.getId());
            if (trail == null) {
                if (!touching) continue;
                trail = new WakeMath.Trail();
                TRAILS.put(e.getId(), trail);
            }
            seen.add(e.getId());
            trail.advance(at.x, at.y, at.z, seconds, touching, surface, e.getBbWidth());
        }
        //Trails whose entity is gone or out of range: their rings fade out
        for (Iterator<Map.Entry<Integer, WakeMath.Trail>> it = TRAILS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, WakeMath.Trail> en = it.next();
            WakeMath.Trail t = en.getValue();
            if (!seen.contains(en.getKey())) {
                t.expire(seconds);
                t.touching = false;
            }
            if (t.size() == 0 && !t.touching) it.remove();
        }
    }

    /**
     * The water's surface (its y) in the column at (x, z) between minY and maxY (a little beyond), the top of the first water block from the
     * top with no water above it; NaN when there is none or the water goes on above the box (under the surface).
     */
    static double surfaceAt(ClientLevel lvl, BlockPos.MutableBlockPos pos, double x, double minY, double maxY, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        int top = Mth.floor(maxY + 0.2), bottom = Mth.floor(minY - 0.3);
        for (int y = top; y >= bottom; y--) {
            FluidState f = lvl.getFluidState(pos.set(bx, y, bz));
            if (!f.is(FluidTags.WATER)) continue;
            if (lvl.getFluidState(pos.set(bx, y + 1, bz)).is(FluidTags.WATER)) return Double.NaN;
            return y + f.getHeight(lvl, pos.set(bx, y, bz));
        }
        return Double.NaN;
    }

    /**
     * Packs the trails, nearest the camera first, for this frame (camera relative); returns the data (WakeMath.VEC4S vec4s) and sets
     * count(). The flow clock's seconds of the last update are the trails' now.
     */
    public static float[] pack(Vec3 camera) {
        if (TRAILS.isEmpty() || strength() <= 0) {
            packed = 0;
            chunks = 0;
            return DATA;
        }
        List<WakeMath.Trail> list = new ArrayList<>(TRAILS.values());
        DISTANCE.clear();
        for (WakeMath.Trail t : list) DISTANCE.put(t, t.size() > 0 ? camera.distanceToSqr(t.get(0).x(), camera.y, t.get(0).z()) : Double.MAX_VALUE);
        list.sort(Comparator.comparingDouble(DISTANCE::get));
        int[] n = WakeMath.pack(list, camera.x, camera.y, camera.z, now, DATA);
        packed = n[0];
        chunks = n[1];
        return DATA;
    }

    /** The trails the last pack() put in. */
    public static int count() {
        return packed;
    }

    /** The chunks the last pack() put in (the pass's count). */
    public static int chunks() {
        return chunks;
    }
}
