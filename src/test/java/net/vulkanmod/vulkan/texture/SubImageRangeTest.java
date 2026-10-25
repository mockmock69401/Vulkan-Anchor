package net.vulkanmod.vulkan.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The bytes a sub-image upload reads: GL's unpack skip rows, skip pixels and row length. */
class SubImageRangeTest {
    @Test
    void aTightImageIsReadWhole() {
        assertEquals(new SubImageRange(0, 16 * 16 * 4), SubImageRange.of(16, 16, 0, 0, 0, 4, 16 * 16 * 4));
    }

    @Test
    void oneFrameOfAStripReadsOnlyItsRows() {
        //A 16x16 sprite, frame 3 of a 16x1024 vertical strip: rows 48..63
        assertEquals(new SubImageRange(48L * 16 * 4, 16L * 16 * 4), SubImageRange.of(16, 16, 48, 0, 16, 4, 16L * 1024 * 4));
    }

    @Test
    void aColumnOfAWideSourceEndsAtItsLastRowsWidth() {
        //8x4 at (5, 2) of a 64-wide source: starts at row 2 column 5, three full rows plus the last row's 8 texels
        assertEquals(new SubImageRange((2L * 64 + 5) * 4, (3L * 64 + 8) * 4), SubImageRange.of(8, 4, 2, 5, 64, 4, 64L * 64 * 4));
    }

    @Test
    void aRangePastTheBufferOrBadUnpackStateGivesNull() {
        assertNull(SubImageRange.of(16, 16, 1, 0, 0, 4, 16 * 16 * 4));
        assertNull(SubImageRange.of(16, 16, 0, 0, 8, 4, 1 << 20));
        assertNull(SubImageRange.of(0, 16, 0, 0, 0, 4, 1 << 20));
    }
}
