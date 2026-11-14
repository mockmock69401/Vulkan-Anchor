package net.vulkanmod.visuals.camera;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;

/**
 * 2026-11-14 (session 22): the step smoothing (StepSmoothMath) for the local player's camera. CameraStepSmoothMixin calls
 * {@link #setup} at the start of every Camera.setup and adds {@link #offset} to the camera's height; nothing else moves. It works in
 * first and third person alike (the third-person camera is placed from the smoothed eye, so its wall check uses that point too; the
 * player model itself steps as before). A camera on another entity (spectating a mob), a new player object (respawn, another
 * dimension), another level or the setting off puts the camera back at the real position at once. Render thread only.
 */
public final class StepSmoothing {
    private static final StepSmoothMath MATH = new StepSmoothMath();
    /** Kept clear between the camera and a ceiling above it (the near plane is 0.05 away and as wide). */
    private static final double CEILING_MARGIN = 0.15;
    private static Entity entity;
    private static BlockGetter level;
    private static int lastTick;
    private static double k = StepSmoothMath.tickFactor(StepSmoothMath.STRENGTH_DEFAULT);
    /** This frame's offset (blocks), for the camera and the frame generation's eye path. */
    private static double offset;
    private static float partialTick;

    private StepSmoothing() {
    }

    public static boolean enabled() {
        Config c = Initializer.CONFIG;
        return c != null && c.smoothSteps;
    }

    /** Camera.setup's start: catches up with the player's ticks since the last frame and works out this frame's offset. */
    public static void setup(BlockGetter level, Entity e, float pt) {
        partialTick = pt;
        Minecraft mc = Minecraft.getInstance();
        if (!enabled() || !(e instanceof LocalPlayer player) || e != mc.player) {
            forget();
            return;
        }
        k = StepSmoothMath.tickFactor(Initializer.CONFIG.stepSmoothing);
        if (e != entity || level != StepSmoothing.level || e.tickCount < lastTick) {
            MATH.reset();
            entity = e;
            StepSmoothing.level = level;
            lastTick = e.tickCount;
        }
        int ticks = e.tickCount - lastTick;
        if (ticks > 0) {
            lastTick = e.tickCount;
            MATH.tick(ticks, new StepSmoothMath.Tick(e.getY() - e.yo, e.onGround(), eligible(player),
                    StepSmoothMath.stepLimit(e.maxUpStep())), k);
        }
        offset = MATH.offset(k, pt);
        if (offset > 0.0) offset = MATH.limitAbove(headroom(level, e, pt, offset), k, pt);
    }

    /** Space above the real eye (blocks) for the camera, up to want, with CEILING_MARGIN kept clear for the near plane. */
    private static double headroom(BlockGetter level, Entity e, float pt, double want) {
        double x = Mth.lerp(pt, e.xo, e.getX()), z = Mth.lerp(pt, e.zo, e.getZ());
        double y = Mth.lerp(pt, e.yo, e.getY()) + e.getEyeHeight();
        Vec3 from = new Vec3(x, y, z);
        BlockHitResult hit = level.clip(new ClipContext(from, new Vec3(x, y + want + CEILING_MARGIN, z), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, e));
        if (hit.getType() == HitResult.Type.MISS) return want;
        return hit.getLocation().y - y - CEILING_MARGIN;
    }

    /** Whether the player moves the way a step can: on its feet, not flying, swimming, riding, climbing, gliding or asleep. */
    private static boolean eligible(LocalPlayer p) {
        return !p.isPassenger() && !p.getAbilities().flying && !p.isFallFlying() && !p.isSpectator() && !p.isSleeping()
                && !p.isInWater() && !p.isInLava() && !p.isSwimming() && !p.onClimbable() && !p.isAutoSpinAttack();
    }

    private static void forget() {
        MATH.reset();
        entity = null;
        level = null;
        offset = 0.0;
    }

    /** This frame's offset of the camera's height from the real eye (0 when the camera is not the smoothed player's). */
    public static double offset() {
        return offset;
    }

    /**
     * The offset at the start (pt 0) or end (pt 1) of the current tick for entity e, for FrameGen's eye path (0 for any other entity), so
     * generated frames between two real ones follow the smoothed height.
     */
    public static double offsetAt(Entity e, float pt) {
        if (e == null || e != entity) return 0.0;
        return MATH.offset(k, pt);
    }
}
