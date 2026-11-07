package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.*;

/** The draws of Sable's sub-levels kept for the sun shadows (SubLevelShadowCasters). */
class SubLevelShadowCastersTest {
    private static final SubLevelShadowCasters.Drawable FAKE = cmd -> true;

    private static FloatBuffer matrix(Matrix4f m) {
        //Direct, as a Uniform's own buffer is: JOML reads a buffer's memory
        FloatBuffer b = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
        m.get(b);
        return b;
    }

    @BeforeEach
    void freshLevel() {
        SubLevelShadowCasters.beginLevel();
    }

    @Test
    void recordsOnlyInsideALayer() {
        assertFalse(SubLevelShadowCasters.recording());
        SubLevelShadowCasters.record(FAKE, matrix(new Matrix4f()), 1, 2, 3);
        assertEquals(0, SubLevelShadowCasters.count());

        SubLevelShadowCasters.beginLayer(0.5f);
        assertTrue(SubLevelShadowCasters.recording());
        SubLevelShadowCasters.record(FAKE, matrix(new Matrix4f()), 1, 2, 3);
        SubLevelShadowCasters.endLayer();

        assertFalse(SubLevelShadowCasters.recording());
        assertEquals(1, SubLevelShadowCasters.count());
        assertEquals(0.5f, SubLevelShadowCasters.get(0).cutout);
        assertEquals(2f, SubLevelShadowCasters.get(0).oy);
        assertSame(FAKE, SubLevelShadowCasters.get(0).drawable);
    }

    @Test
    void keepsTheModelViewItWasGiven() {
        Matrix4f m = new Matrix4f().rotateY(1.2f).translate(3, 4, 5);
        SubLevelShadowCasters.beginLayer(0);
        SubLevelShadowCasters.record(FAKE, matrix(m), 0, 0, 0);
        SubLevelShadowCasters.endLayer();
        assertTrue(SubLevelShadowCasters.get(0).modelView.equals(m, 1e-6f));
    }

    @Test
    void beginLevelForgetsTheLastFrame() {
        SubLevelShadowCasters.beginLayer(0);
        SubLevelShadowCasters.record(FAKE, matrix(new Matrix4f()), 0, 0, 0);
        SubLevelShadowCasters.beginLevel();
        assertEquals(0, SubLevelShadowCasters.count());
        assertFalse(SubLevelShadowCasters.recording(), "a layer left open by the last frame does not stay open");
    }

    /** Review focus 2: a draw whose buffers are gone since it was kept is left out, the others still go. */
    @Test
    void drawAllGoesOverEveryCasterAndCountsTheDrawnOnes() {
        SubLevelShadowCasters.beginLayer(0);
        SubLevelShadowCasters.record(FAKE, matrix(new Matrix4f()), 0, 0, 0);
        SubLevelShadowCasters.record(FAKE, matrix(new Matrix4f()), 1, 0, 0);
        SubLevelShadowCasters.endLayer();
        SubLevelShadowCasters.Caster first = SubLevelShadowCasters.get(0);
        int[] seen = {0};
        int drawn = SubLevelShadowCasters.drawAll(c -> {
            seen[0]++;
            return c != first;
        });
        assertEquals(2, seen[0]);
        assertEquals(1, drawn);
    }

    @Test
    void keepsTheFirstCastersPastTheLimit() {
        SubLevelShadowCasters.beginLayer(0.1f);
        FloatBuffer m = matrix(new Matrix4f());
        for (int i = 0; i < SubLevelShadowCasters.MAX_CASTERS + 10; i++) {
            SubLevelShadowCasters.record(FAKE, m, i, 0, 0);
        }
        SubLevelShadowCasters.endLayer();
        assertEquals(SubLevelShadowCasters.MAX_CASTERS, SubLevelShadowCasters.count());
        assertEquals(0f, SubLevelShadowCasters.get(0).ox);
    }
}
