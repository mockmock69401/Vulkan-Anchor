package net.vulkanmod.render.text;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-30: a font page's level-0 shadow, the mip uploads it produces, and the option's sampler switch. */
class FontPagesTest {
    private static ByteBuffer direct(byte[] b) {
        ByteBuffer buf = ByteBuffer.allocateDirect(b.length);
        buf.put(b).flip();
        return buf;
    }

    @Test
    void uploadsThroughTheBoundPageUpdateMips() {
        FontPageShadow page = new FontPageShadow(256, 256, 4);
        byte[] white = new byte[2 * 2 * 4];
        java.util.Arrays.fill(white, (byte) 255);
        List<FontPageShadow.Upload> ups = page.write(8, 8, 2, 2, 0, 0, 0, direct(white));
        assertEquals(3, ups.size());
        assertEquals(1, ups.get(0).level());
        assertArrayEquals(new int[]{4, 4, 1, 1}, ups.get(0).rect());
        assertArrayEquals(new int[]{2, 2, 1, 1}, ups.get(1).rect());
        assertArrayEquals(new int[]{1, 1, 1, 1}, ups.get(2).rect());
        assertArrayEquals(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255}, ups.get(0).pixels());
    }

    @Test
    void unpackParametersPickTheRightSourceBytes() {
        FontPageShadow page = new FontPageShadow(256, 256, 1);
        byte[] src = new byte[10 * 5];
        for (int i = 0; i < src.length; i++) src[i] = (byte) i;
        page.write(20, 30, 2, 2, 2, 3, 10, direct(src));
        assertEquals(2 * 10 + 3, page.texel(0, 20, 30));
        assertEquals(2 * 10 + 4, page.texel(0, 21, 30));
        assertEquals(3 * 10 + 3, page.texel(0, 20, 31));
        assertEquals(3 * 10 + 4, page.texel(0, 21, 31));
    }

    /** Final review: a transparent texel takes an opaque neighbour's colour (else white), so bilinear edges never darken. */
    @Test
    void transparentTexelsTakeANeighboursColour() {
        FontPageShadow page = new FontPageShadow(256, 256, 4);
        byte[] src = {(byte) 255, 0, 0, (byte) 255, 0, 0, 0, 0, 0, 0, 0, 0};
        List<FontPageShadow.Upload> ups = page.write(10, 10, 3, 1, 0, 0, 0, direct(src));
        assertEquals(0, ups.get(0).level(), "level 0 goes back up with the new colours");
        assertArrayEquals(new int[]{10, 10, 3, 1}, ups.get(0).rect());
        assertArrayEquals(new byte[]{(byte) 255, 0, 0, (byte) 255, (byte) 255, 0, 0, 0, (byte) 255, (byte) 255, (byte) 255, 0}, ups.get(0).pixels());
    }

    /** Final review: an RGBA page whose upload has no transparent texels needs no level-0 re-upload; R8 pages never do. */
    @Test
    void levelZeroIsResentOnlyWhenChanged() {
        byte[] opaque = new byte[2 * 2 * 4];
        java.util.Arrays.fill(opaque, (byte) 255);
        assertEquals(1, new FontPageShadow(256, 256, 4).write(0, 0, 2, 2, 0, 0, 0, direct(opaque)).get(0).level());
        assertEquals(1, new FontPageShadow(256, 256, 1).write(0, 0, 2, 2, 0, 0, 0, direct(new byte[4])).get(0).level());
    }

    /** Final review: the shadow holds 64x64 tiles where uploads landed, not the whole page (2048x2048 pages were 21 MiB each). */
    @Test
    void shadowMemoryFollowsTheUsedArea() {
        FontPageShadow page = new FontPageShadow(2048, 2048, 4);
        assertEquals(0, page.allocatedBytes());
        byte[] white = new byte[2 * 2 * 4];
        java.util.Arrays.fill(white, (byte) 255);
        page.write(1000, 1000, 2, 2, 0, 0, 0, direct(white));
        assertTrue(page.allocatedBytes() <= 4 * 64 * 64 * 4, "one tile per level: " + page.allocatedBytes());
        assertEquals(255, page.texel(0, 1001, 1001));
        assertEquals(255, page.texel(1, 500, 500));
    }

    @Test
    void r8PagesAverageWithoutPremultiply() {
        FontPageShadow page = new FontPageShadow(256, 256, 1);
        List<FontPageShadow.Upload> ups = page.write(0, 0, 2, 2, 0, 0, 0, direct(new byte[]{0, (byte) 255, (byte) 255, (byte) 255}));
        assertEquals(191, ups.get(0).pixels()[0] & 255);
    }

    /** Vanilla's text render types call setFilter(false, false) on the font texture at every draw: pages ignore it. */
    @Test
    void registeredPagesAreManaged() {
        FontPages.applyOption(true); //the option state set, so Initializer (GLFW) is not loaded in tests
        Object page = new Object(), other = new Object();
        FontPages.register(page, on -> { });
        assertTrue(FontPages.manages(page));
        assertFalse(FontPages.manages(other));
        assertFalse(FontPages.manages(null));
    }

    @Test
    void toggleSwapsSamplersOfLiveAndNewPages() {
        List<String> log = new ArrayList<>();
        Object a = new Object(), b = new Object(), c = new Object();
        FontPages.applyOption(true);
        FontPages.register(a, on -> log.add("a" + on));
        FontPages.register(b, on -> log.add("b" + on));
        log.clear();
        FontPages.applyOption(false);
        assertTrue(log.containsAll(List.of("afalse", "bfalse")), log.toString());
        FontPages.register(c, on -> log.add("c" + on));
        assertTrue(log.contains("cfalse"), log.toString());
        log.clear();
        FontPages.applyOption(true);
        assertTrue(log.containsAll(List.of("atrue", "btrue", "ctrue")), log.toString());
    }
}
