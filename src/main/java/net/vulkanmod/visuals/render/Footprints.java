package net.vulkanmod.visuals.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.visuals.config.VisualsConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-11-15 (shader roadmap session 25): where the players walk on snow (snow layers, snow blocks) or sand (2026-11-16: every sand, isSand),
 * a print every FootprintMath.STRIDE blocks, the feet alternating; the map FootprintMath draws them into, and the texel boxes to upload.
 * Every player the client sees leaves prints (the local one and, in multiplayer, the others: their position and ground state come from
 * the server); mobs do not. Updated once a frame by VisualsRenderer (captureAtmosphere) with the flow clock; nothing with the setting at 0.
 */
public final class Footprints {
    private static final FootprintMath.Ring RING = new FootprintMath.Ring(FootprintMath.RING);
    private static final Map<UUID, double[]> LAST = new HashMap<>();
    private static final List<int[]> DIRTY = new ArrayList<>();
    private static int[] map;
    private static ClientLevel level;
    private static boolean fullUpload = true;
    private static int prints;
    private static double now;
    /** Session 25 debugging (-Dvulkanmod.visuals.biomeLog=true): what the last player step found, logged with BiomeSky's shares. */
    static final boolean LOG = Boolean.getBoolean("vulkanmod.visuals.biomeLog");
    static String debug = "none";
    /** The post-process's view: whether the map is whole and the composite reads it (VkPostProcess). */
    static String uploadDebug = "none";

    private Footprints() {
    }

    /** 2026-11-16: the common tags mods put their sands in (besides vanilla's #minecraft:sand); made on first use (the game's registries). */
    private static final class Tags {
        static final TagKey<Block> C_SANDS = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "sands"));
        static final TagKey<Block> FORGE_SAND = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("forge", "sand"));
    }

    /** 2026-11-16: sand underfoot: tagged sand (vanilla's, c:, forge:) or a listed one (FootprintMath.SAND_BLOCKS). */
    static boolean isSand(BlockState state) {
        if (state.isAir()) return false;
        if (state.is(BlockTags.SAND) || state.is(Tags.C_SANDS) || state.is(Tags.FORGE_SAND)) return true;
        return FootprintMath.isSandId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
    }

    /** The map (MAP x MAP words), or null while the setting is off (nothing made yet). */
    public static int[] map() {
        return map;
    }

    /** Debugging text for the log (BiomeSky's share line). */
    public static String describe() {
        return "prints " + prints + ", " + debug + ", " + uploadDebug;
    }

    /** The flow clock's seconds at the last update (the prints' clock). */
    public static double now() {
        return now;
    }

    /** Prints alive now (the composite skips the footprints with none). */
    public static int count() {
        return prints;
    }

    /** Whether the whole map must be uploaded (first, a new level, a dropped upload); clears the request. */
    public static boolean takeFullUpload() {
        boolean f = fullUpload;
        fullUpload = false;
        //Only a full upload makes the boxes moot (it sends every texel); otherwise they are still to go
        if (f) DIRTY.clear();
        return f;
    }

    /** A changed texel box to upload (null: nothing changed). */
    static void markDirty(int[] box) {
        if (box != null) DIRTY.add(box);
    }

    /** Asks for the whole map again (an upload's frame was dropped). */
    public static void requestFullUpload() {
        fullUpload = true;
    }

    /** The world texel boxes {x0, z0, x1, z1} changed since the last call. */
    public static List<int[]> takeDirty() {
        List<int[]> d = new ArrayList<>(DIRTY);
        DIRTY.clear();
        return d;
    }

    /** One frame: new prints from the players, old ones cleared. seconds the flow clock. */
    public static void update(ClientLevel lvl, double seconds) {
        now = seconds;
        VisualsConfig c = VisualsConfig.get();
        boolean on = c != null && c.enabled && c.footprints > 0 && lvl != null;
        if (!on) {
            if (prints > 0 || level != null) reset(null);
            return;
        }
        if (map == null) {
            map = new int[FootprintMath.MAP * FootprintMath.MAP];
            fullUpload = true;
        }
        if (lvl != level) reset(lvl);
        FootprintMath.Print old;
        while ((old = RING.expire(seconds, c.footprintDuration)) != null) {
            int[] box = FootprintMath.clear(map, old);
            markDirty(box);
        }
        for (Player p : lvl.players()) {
            if (p.isSpectator() || !p.onGround() || p.isPassenger() || p.isSwimming() || p.isFallFlying() || p.getAbilities().flying) {
                continue;
            }
            double[] last = LAST.get(p.getUUID());
            double x = p.getX(), z = p.getZ();
            if (last == null) {
                LAST.put(p.getUUID(), new double[]{x, z, 0, x, z});
                continue;
            }
            double dx = x - last[0], dz = z - last[1];
            double d2 = dx * dx + dz * dz;
            //A teleport or a respawn starts over
            if (d2 > 16) {
                last[0] = x;
                last[1] = z;
                continue;
            }
            if (d2 < FootprintMath.STRIDE * FootprintMath.STRIDE) continue;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            boolean left = ((long) last[2] & 1) == 0;
            last[0] = x;
            last[1] = z;
            last[2]++;
            float[] off = FootprintMath.side(yaw, left);
            double fx = x + off[0], fz = z + off[1];
            int surface = Integer.MIN_VALUE;
            boolean sand = false;
            BlockPos feet = BlockPos.containing(fx, p.getY() + 0.01, fz);
            BlockState at = lvl.getBlockState(feet);
            if (at.is(Blocks.SNOW)) {
                surface = feet.getY();
            } else if (isSand(at)) {
                //2026-11-16: standing in a sand layer (a full sand block is never where the feet are)
                surface = feet.getY();
                sand = true;
            } else if (at.isAir()) {
                BlockState below = lvl.getBlockState(feet.below());
                if (below.is(Blocks.SNOW_BLOCK) || below.is(Blocks.POWDER_SNOW)) {
                    surface = feet.getY() - 1;
                } else if (isSand(below)) {
                    surface = feet.getY() - 1;
                    sand = true;
                } else if (below.is(Blocks.SNOW)) {
                    //a full stack of eight layers
                    surface = feet.getY() - 1;
                }
            }
            if (LOG) debug = String.format(java.util.Locale.ROOT, "step at %.2f %.2f %.2f feet %s (%s) surface %d sand %b", fx, p.getY(), fz,
                    feet.toShortString(), at.getBlock(), surface, sand);
            if (surface == Integer.MIN_VALUE) continue;
            FootprintMath.Print print = new FootprintMath.Print(fx, fz, surface, yaw, seconds, sand);
            FootprintMath.Print gone = RING.add(print);
            if (gone != null) {
                int[] box = FootprintMath.clear(map, gone);
                markDirty(box);
            }
            int[] box = FootprintMath.raster(map, print);
            markDirty(box);
        }
        prints = RING.size();
    }

    private static void reset(ClientLevel lvl) {
        RING.clear();
        LAST.clear();
        DIRTY.clear();
        if (map != null) java.util.Arrays.fill(map, 0);
        if (lvl == null) map = null;
        level = lvl;
        prints = 0;
        fullUpload = true;
    }
}
