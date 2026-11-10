package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/** Session 7 (2026-11-10): a rebuilt renderer takes the last cloud noise map built for the same clouds (CloudMapCache). */
class CloudMapCacheTest {
    @Test
    void theSameCloudsAreBuiltOnceAndEveryCopyEqualsAFreshBuild() {
        CloudMapCache.clear();
        byte[] mask = new byte[CloudMath.MAP * CloudMath.MAP * 4];
        for (int i = 0; i < mask.length; i += 4 * 7) mask[i] = (byte) 255;
        byte[] first = CloudMapCache.withNoise(mask, 1, 1);
        byte[] again = CloudMapCache.withNoise(mask.clone(), 1, 1);
        assertEquals(1, CloudMapCache.builds);
        assertNotSame(first, again);
        assertArrayEquals(CloudMath.withNoise(mask, 1, 1), again);
        //Another size, count or mask builds again
        assertArrayEquals(CloudMath.withNoise(mask, 1.5f, 1), CloudMapCache.withNoise(mask, 1.5f, 1));
        assertEquals(2, CloudMapCache.builds);
        CloudMapCache.withNoise(mask, 1.5f, 0.5f);
        assertEquals(3, CloudMapCache.builds);
        byte[] other = mask.clone();
        other[4] = (byte) 255;
        CloudMapCache.withNoise(other, 1.5f, 0.5f);
        assertEquals(4, CloudMapCache.builds);
        //A copy changed by its user leaves the cache's map alone
        byte[] copy = CloudMapCache.withNoise(other, 1.5f, 0.5f);
        copy[1] ^= 1;
        assertArrayEquals(CloudMath.withNoise(other, 1.5f, 0.5f), CloudMapCache.withNoise(other, 1.5f, 0.5f));
        assertEquals(4, CloudMapCache.builds);
        CloudMapCache.clear();
    }
}
