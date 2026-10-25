package net.vulkanmod.visuals.dev;

import net.vulkanmod.visuals.framegen.FramePacing;

/**
 * The present log's CSV (frame generation phase 1, Task 10): one row per PresentLog record {kind, renderValue, scheduledNs, callNs,
 * acquireWaitNs, decisionOrdinal, inputNs, gpuNs}. Pure, so the format is tested without the game; fgana.py reads it.
 */
public final class PresentCsv {
    public static final String HEADER = "kind,renderValue,scheduledNs,callNs,acquireWaitNs,decision,inputNs,gpuNs";

    private PresentCsv() {
    }

    /** The row for record: kind as real or gen, the decision by its FramePacing.Decision name (its number when out of range). */
    public static String line(long[] r) {
        FramePacing.Decision[] decisions = FramePacing.Decision.values();
        String decision = r[5] >= 0 && r[5] < decisions.length ? decisions[(int) r[5]].name() : Long.toString(r[5]);
        return (r[0] == 0 ? "real" : "gen") + ',' + r[1] + ',' + r[2] + ',' + r[3] + ',' + r[4] + ',' + decision + ',' + r[6] + ',' + r[7];
    }
}
