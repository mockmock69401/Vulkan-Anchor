package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** P7l design 3.3-3.6: the copy's and the reflection's light volume block. */
class LightVolumeUniformsTest {
    private static final LightVolumeMath.Layout L = LightVolumeMath.layout(12, -4, 24);

    @Test
    void offWithoutTheSettingOrAVolume() {
        VisualsConfig c = new VisualsConfig();
        assertFalse(LightVolumeUniforms.compute(PostProcessSettings.from(c), null, 0, 0, 0, null, 1, true, true).on());
        c.perPixelLight = false;
        assertFalse(LightVolumeUniforms.compute(PostProcessSettings.from(c), L, 0, 0, 0, null, 1, true, true).on());
    }

    /** The camera's block exactly (also below 0), the fraction inside it. */
    @Test
    void theCameraSplitsIntoItsBlockAndTheFraction() {
        LightVolumeUniforms.Values v = LightVolumeUniforms.compute(PostProcessSettings.from(new VisualsConfig()), L, -5.25, 70.5, 1000000.75, null, 1, true, true);
        assertTrue(v.on());
        assertArrayEquals(new float[]{-6, 70, 1000000}, v.camera(), 0f);
        assertArrayEquals(new float[]{0.75f, 0.5f, 0.75f}, v.fraction(), 1e-6f);
        assertEquals(L, v.layout());
    }

    /** No lift without this frame's frame (no day factor), without the sun's visibility, or without sky light. */
    @Test
    void theLiftNeedsTheSunAndTheSky() {
        PostProcessSettings s = PostProcessSettings.from(new VisualsConfig());
        assertEquals(0f, LightVolumeUniforms.compute(s, L, 0, 0, 0, null, 1, true, true).lift());
        LightVolumeUniforms.Values nether = LightVolumeUniforms.compute(s, L, 0, 0, 0, null, 1, false, true);
        assertFalse(nether.sky() || nether.sun());
        assertFalse(LightVolumeUniforms.compute(s, L, 0, 0, 0, null, 1, true, false).sun());
    }

    @Test
    void theDebugViewPassesThrough() {
        VisualsConfig c = new VisualsConfig();
        c.lightDebug = 2;
        assertEquals(2, LightVolumeUniforms.compute(PostProcessSettings.from(c), L, 0, 0, 0, null, 1, true, true).debug());
        assertEquals(0, LightVolumeUniforms.off().debug());
        c.lightDebug = PostProcessSettings.REFLECTIVE_DEBUG;
        assertEquals(0, LightVolumeUniforms.compute(PostProcessSettings.from(c), L, 0, 0, 0, null, 1, true, true).debug(),
                "the reflective view is the reflection pass's: the copy draws the scene as usual");
    }
}
