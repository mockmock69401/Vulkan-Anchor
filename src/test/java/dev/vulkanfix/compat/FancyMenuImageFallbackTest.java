package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class FancyMenuImageFallbackTest {
    /** Stands in for FancyMenu's private TextureRegion record (same accessor names). */
    private record TextureRegion(float minU, float minV, float maxU, float maxV) {
    }

    @Test
    void readsTheTextureRegionByItsAccessors() {
        assertArrayEquals(new float[]{0.25f, 0.5f, 0.75f, 1f}, FancyMenuImageFallback.uvs(new TextureRegion(0.25f, 0.5f, 0.75f, 1f)));
    }

    @Test
    void theWholeTextureWithoutARegion() {
        assertArrayEquals(new float[]{0, 0, 1, 1}, FancyMenuImageFallback.uvs(null));
        assertArrayEquals(new float[]{0, 0, 1, 1}, FancyMenuImageFallback.uvs("not a region"));
    }

    @Test
    void unpacksArgb() {
        assertArrayEquals(new float[]{1, 0, 0, 1}, FancyMenuImageFallback.rgba(0xFFFF0000));
        assertArrayEquals(new float[]{1, 1, 1, 0.5f}, FancyMenuImageFallback.rgba(0x80FFFFFF), 0.003f);
    }
}
