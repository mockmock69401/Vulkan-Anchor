package net.vulkanmod.render.chunk;

import net.vulkanmod.Initializer;

/**
 * 2026-10-31 (the user: doors open and close late in Deceased): how long a block the player changed (a door, a placed or broken
 * block) takes to show, from its section marked dirty by the player to the upload of a mesh built after that. Logged every
 * LOG_EVERY_NANOS while there were any. Render thread.
 */
public final class PlayerChangeLatency {
    static final long LOG_EVERY_NANOS = 5_000_000_000L;

    private static int count;
    private static double sumMs, maxMs;
    private static long lastLog;

    private PlayerChangeLatency() {
    }

    /** A player change at now: the section's pending time (0 = none), which a later change before the mesh keeps. */
    static long dirtied(long pending, long now) {
        return pending != 0 ? pending : now;
    }

    /** A mesh of a task created at taskCreated uploaded at now: the section's pending time after it (0 once one built after it lands). */
    static long uploaded(long pending, long taskCreated, long now) {
        if (pending == 0 || taskCreated < pending) return pending;
        double ms = (now - pending) / 1e6;
        count++;
        sumMs += ms;
        maxMs = Math.max(maxMs, ms);
        return 0;
    }

    /** {count, average ms, max ms} since the last log. */
    static double[] stats() {
        return new double[]{count, count == 0 ? 0 : sumMs / count, maxMs};
    }

    static void reset() {
        count = 0;
        sumMs = 0;
        maxMs = 0;
    }

    /** Each frame: logs the changes shown since the last log, every LOG_EVERY_NANOS. */
    public static void tick(long now, int pendingUploads) {
        if (count == 0 || now - lastLog < LOG_EVERY_NANOS) return;
        lastLog = now;
        double[] s = stats();
        Initializer.LOGGER.info("Player-changed sections: {} shown, avg {} ms, max {} ms (uploads waiting {})", count,
                String.format("%.1f", s[1]), String.format("%.1f", s[2]), pendingUploads);
        reset();
    }
}
