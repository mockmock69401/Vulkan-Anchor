package net.vulkanmod.visuals.dev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The present log's CSV rows (Task 10): column order, the kind and decision names. */
class PresentCsvTest {
    @Test
    void theCsvLineHasEveryColumnInOrder() {
        assertEquals("gen,7,100,105,3,PRESENT,90,250000", PresentCsv.line(new long[]{1, 7, 100, 105, 3, 0, 90, 250000}));
        assertEquals("kind,renderValue,scheduledNs,callNs,acquireWaitNs,decision,inputNs,gpuNs", PresentCsv.HEADER);
    }

    @Test
    void realRowsAndEveryDecisionAreNamed() {
        assertEquals("real,2,0,50,0,PRESENT,0,0", PresentCsv.line(new long[]{0, 2, 0, 50, 0, 0, 0, 0}));
        assertEquals("gen,4,9,0,0,SKIP_PAUSED,1,0", PresentCsv.line(new long[]{1, 4, 9, 0, 0, 1, 1, 0}));
        assertEquals("gen,4,9,0,0,SKIP_NEWER_REAL,1,0", PresentCsv.line(new long[]{1, 4, 9, 0, 0, 2, 1, 0}));
        assertEquals("gen,4,9,0,0,SKIP_FAST,1,0", PresentCsv.line(new long[]{1, 4, 9, 0, 0, 3, 1, 0}));
        assertEquals("gen,4,9,0,0,SKIP_TOO_FAR,1,0", PresentCsv.line(new long[]{1, 4, 9, 0, 0, 4, 1, 0}));
    }

    @Test
    void anOutOfRangeDecisionPrintsItsNumber() {
        assertEquals("gen,1,0,0,0,9,0,0", PresentCsv.line(new long[]{1, 1, 0, 0, 0, 9, 0, 0}));
    }
}
