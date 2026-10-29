package net.vulkanmod.render.model.quad;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Vertex arrays are shared only while this thread bakes (dh 83d418f); 2026-10-30 audit: a failed bake does not leave it marked. */
class BakedQuadDeduplicatorTest {

    @Test
    void sharedOnlyWhileBaking() {
        int[] a = {1, 2, 3, 41}, b = {1, 2, 3, 41};
        assertSame(a, BakedQuadDeduplicator.deduplicateVertices(a));
        BakedQuadDeduplicator.enterBake();
        int[] shared = BakedQuadDeduplicator.deduplicateVertices(a);
        assertSame(shared, BakedQuadDeduplicator.deduplicateVertices(b));
        BakedQuadDeduplicator.exitBake();
        int[] c = {1, 2, 3, 41};
        assertSame(c, BakedQuadDeduplicator.deduplicateVertices(c));
    }

    @Test
    void theBakeryResetsABakeThatThrew() {
        BakedQuadDeduplicator.enterBake();
        BakedQuadDeduplicator.enterBake();
        BakedQuadDeduplicator.exitBake();
        //the inner bake threw: its exit never ran
        BakedQuadDeduplicator.resetBake();
        int[] a = {7, 8, 9, 42}, b = {7, 8, 9, 42};
        assertSame(a, BakedQuadDeduplicator.deduplicateVertices(a));
        assertNotSame(a, BakedQuadDeduplicator.deduplicateVertices(b));
    }
}
