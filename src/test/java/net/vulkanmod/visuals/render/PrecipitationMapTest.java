package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-09 (session 6): the precipitation map around the camera (PrecipitationMap). */
class PrecipitationMapTest {
    /** A level of ground at y 64 with a roof at y 80 over x 10..12, unloaded chunks at x >= 4000, counting climate lookups. */
    private static final class Fake implements PrecipitationMap.Columns {
        final Set<Long> looked = new HashSet<>();
        int lookups;
        int roofTop = 81;

        @Override
        public int height(int x, int z) {
            if (x >= 4000) return PrecipitationMap.NO_HEIGHT;
            return x >= 10 && x <= 12 ? this.roofTop : 64;
        }

        @Override
        public PrecipitationMap.Climate climate(int x, int y, int z) {
            this.lookups++;
            this.looked.add(((long) x << 32) ^ (z & 0xFFFFFFFFL));
            return z < 0 ? PrecipitationMap.Climate.NONE : new PrecipitationMap.Climate(true, x < 0, 200);
        }
    }

    private static int at(PrecipitationMap map, int x, int z) {
        return map.texels()[PrecipitationMap.index(x, z)];
    }

    @Test
    void theMapFillsNearestFirstWithinItsBudgetAndHoldsVanillasColumns() {
        PrecipitationMap map = new PrecipitationMap();
        Fake level = new Fake();
        assertTrue(map.update(level, 0, 70, 5, 100));
        assertEquals(100, level.lookups);
        //The nearest columns are known, the far ones not yet (0: nothing drawn there)
        assertNotEquals(0, at(map, 0, 5));
        assertNotEquals(0, at(map, 1, 6));
        assertEquals(0, at(map, 60, 60));
        //Fill the rest
        for (int i = 0; i < 40; i++) map.update(level, 0, 70, 5, PrecipitationMap.BUDGET);
        assertEquals(64, WeatherMath.height(at(map, 3, 5)));
        assertEquals(81, WeatherMath.height(at(map, 11, 5)), "the roof");
        assertEquals(WeatherMath.SNOW, WeatherMath.kind(at(map, -3, 5), 70));
        assertEquals(WeatherMath.RAIN, WeatherMath.kind(at(map, 3, 5), 70));
        assertEquals(WeatherMath.NONE, WeatherMath.kind(at(map, 3, -5), 70), "no precipitation in that biome");
        assertTrue(map.hasRain() && map.hasSnow());
        //Nothing changed: no upload
        assertFalse(map.update(level, 0, 70, 5, 0));
        //A block placed on a column changes its height at once (heights are read every frame)
        level.roofTop = 90;
        assertTrue(map.update(level, 0, 70, 5, 0));
        assertEquals(90, WeatherMath.height(at(map, 11, 5)));
    }

    @Test
    void theMapScrollsWithTheCameraKeepingWhatItKnows() {
        PrecipitationMap map = new PrecipitationMap();
        Fake level = new Fake();
        for (int i = 0; i < 40; i++) map.update(level, 0, 70, 0, PrecipitationMap.BUDGET);
        level.lookups = 0;
        //One block east: one new column of the map (128) is looked up first
        map.update(level, 1, 70, 0, PrecipitationMap.BUDGET);
        assertTrue(level.looked.contains(((long) 64 << 32) ^ 0L), "the new column");
        assertNotEquals(0, at(map, 64, 0));
        assertNotEquals(0, at(map, -63, 0));
        //A texel shared by two columns 128 apart holds the one inside the window
        assertEquals(64, WeatherMath.height(at(map, 64, 3)));
        //An unloaded chunk is 0 (no rain drawn where the level is not known)
        for (int i = 0; i < 40; i++) map.update(level, 3990, 70, 0, PrecipitationMap.BUDGET);
        assertEquals(0, at(map, 4010, 0));
        assertNotEquals(0, at(map, 3990, 0));
    }

    @Test
    void aFailingLookupMeansNoPrecipitation() {
        PrecipitationMap map = new PrecipitationMap();
        PrecipitationMap.Columns broken = new PrecipitationMap.Columns() {
            @Override
            public int height(int x, int z) {
                return 64;
            }

            @Override
            public PrecipitationMap.Climate climate(int x, int y, int z) {
                throw new IllegalStateException("modded biome");
            }
        };
        map.update(broken, 0, 70, 0, 10);
        assertEquals(WeatherMath.NONE, WeatherMath.kind(at(map, 0, 0), 70));
    }

    /** Session 7: updates at most 90 a second; the first one, and a clock that went back, are always due. */
    @Test
    void updatesAreDueAtMostSixtyTimesASecond() {
        long t = 5_000_000_000L;
        assertTrue(PrecipitationMap.due(Long.MIN_VALUE, t));
        assertFalse(PrecipitationMap.due(t, t));
        assertFalse(PrecipitationMap.due(t, t + PrecipitationMap.UPDATE_NANOS - 1));
        assertTrue(PrecipitationMap.due(t, t + PrecipitationMap.UPDATE_NANOS));
        assertTrue(PrecipitationMap.due(t, t - 1));
        //60 fps updates every frame, even a frame that came early (15 ms); 144 fps every other frame
        assertTrue(PrecipitationMap.due(t, t + 15_000_000L));
        assertFalse(PrecipitationMap.due(t, t + 1_000_000_000L / 144));
        assertTrue(PrecipitationMap.due(t, t + 2 * (1_000_000_000L / 144)));
    }
}
