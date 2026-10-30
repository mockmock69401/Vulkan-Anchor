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
