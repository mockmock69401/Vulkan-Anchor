package net.vulkanmod.render.target;

/**
 * 2026-10-30 (audit): which offscreen MainTarget a mod made Minecraft's main render target for good. MainTargetMixin treats a
 * MainTarget made after Minecraft's own as an ordinary render target (watut, Iceberg); one that replaces Minecraft's and is still its
 * main target at two frame starts in a row (a resolution or screenshot mod) is the window again, as Minecraft's own was. A target
 * swapped in only while a mod draws into it is back out before the next frame starts, so it stays offscreen.
 */
public final class MainTargets {
    private static Object seen, adopted;

    private MainTargets() {
    }

    /** Renderer.beginFrame, with Minecraft's main render target. */
    public static void onFrameStart(Object main) {
        adopted = main != null && main == seen ? main : null;
        seen = main;
    }

    public static boolean adopted(Object target) {
        return target != null && target == adopted;
    }
}
