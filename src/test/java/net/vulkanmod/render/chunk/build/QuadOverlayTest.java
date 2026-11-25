package net.vulkanmod.render.chunk.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Continuity's emissive overlay (Conquest's magma) lies on the block's own quad: it is found, the block's other faces are not. */
class QuadOverlayTest {
    //the top face of a block at (7, 4, 0) as the log of the magma block showed it, and its north face
    private static final float[] TOP = {7, 4, 0, 7, 4, 1, 8, 4, 1, 8, 4, 0};
    private static final float[] NORTH = {7, 3, 0, 7, 4, 0, 8, 4, 0, 8, 3, 0};

    @Test
    void anOverlayOnTheSameCornersIsFound() {
        float[] seen = TOP.clone();
        assertTrue(QuadOverlay.coversEarlier(seen, 1, TOP, 0));
    }

    @Test
    void theCornersMayComeInAnotherOrder() {
        float[] rotated = {8, 4, 1, 8, 4, 0, 7, 4, 0, 7, 4, 1};
        assertTrue(QuadOverlay.coversEarlier(TOP.clone(), 1, rotated, 0));
    }

    @Test
    void anotherFaceOfTheBlockIsNoOverlay() {
        assertFalse(QuadOverlay.coversEarlier(TOP.clone(), 1, NORTH, 0));
    }

    @Test
    void aQuadSharingOnlyAnEdgeIsNoOverlay() {
        float[] next = {8, 4, 0, 8, 4, 1, 9, 4, 1, 9, 4, 0};
        assertFalse(QuadOverlay.coversEarlier(TOP.clone(), 1, next, 0));
    }

    @Test
    void theFirstQuadOfABlockIsNoOverlay() {
        assertFalse(QuadOverlay.coversEarlier(new float[12 * QuadOverlay.MAX_QUADS], 0, TOP, 0));
    }
}
