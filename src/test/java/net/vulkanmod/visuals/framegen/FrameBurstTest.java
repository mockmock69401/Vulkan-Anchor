package net.vulkanmod.visuals.framegen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The burst's pixel handling (Task 10): the swapchain's BGRA bytes become RGBA for the PNG writer. */
class FrameBurstTest {
    @Test
    void bgraBecomesRgbaAndAlphaStays() {
        ByteBuffer src = ByteBuffer.wrap(new byte[]{1, 2, 3, 4, 10, 20, 30, 40});
        ByteBuffer dst = ByteBuffer.allocate(8);
        FrameBurst.toRgba(src, dst, 2, true);
        assertEquals(3, dst.get(0));
        assertEquals(2, dst.get(1));
        assertEquals(1, dst.get(2));
        assertEquals(4, dst.get(3));
        assertEquals(30, dst.get(4));
        assertEquals(20, dst.get(5));
        assertEquals(10, dst.get(6));
        assertEquals(40, dst.get(7));
    }

    @Test
    void rgbaIsCopiedAsIs() {
        ByteBuffer src = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
        ByteBuffer dst = ByteBuffer.allocate(4);
        FrameBurst.toRgba(src, dst, 1, false);
        assertEquals(1, dst.get(0));
        assertEquals(3, dst.get(2));
    }

    @Test
    void thePngWriterMakesAFileAnImageReaderReads(@TempDir Path dir) throws Exception {
        ByteBuffer rgba = ByteBuffer.allocate(3 * 2 * 4);
        for (int i = 0; i < 6; i++) rgba.put(i * 4, (byte) (i * 40)).put(i * 4 + 1, (byte) 7).put(i * 4 + 2, (byte) 200).put(i * 4 + 3, (byte) 255);
        Path out = dir.resolve("a.png");
        FrameBurst.writePng(out, rgba, 3, 2);
        BufferedImage img = javax.imageio.ImageIO.read(Files.newInputStream(out));
        assertEquals(3, img.getWidth());
        assertEquals(2, img.getHeight());
        assertEquals(0xFF000000 | (80 << 16) | (7 << 8) | 200, img.getRGB(2, 0));
        assertEquals(0xFF000000 | (200 << 16) | (7 << 8) | 200, img.getRGB(2, 1));
    }
}
