package net.vulkanmod.render.texture;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reforged 0.3.5's interpolated-animation path, backported to 1.20.1: both the wide-sprite and the scalar path must give what
 * vanilla's InterpolationData gives per pixel (the current frame's alpha, the colour channels mixed by delta).
 */
class SpriteInterpolationTest {
    /** Reads pixels and sizes through the images' own accessors: the mixin accessors are not applied in tests. */
    private static final SpriteInterpolation.Memory MEMORY = new SpriteInterpolation.Memory() {
        @Override
        public long pixels(NativeImage image) {
            return pointer(image);
        }

        @Override
        public long size(NativeImage image) {
            return 4L * image.getWidth() * image.getHeight();
        }
    };

    private static long pointer(NativeImage image) {
        try {
            var field = NativeImage.class.getDeclaredField("pixels");
            field.setAccessible(true);
            return field.getLong(image);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int reference(int first, int next, double delta) {
        int r = (int) (delta * (first >> 16 & 255) + (1 - delta) * (next >> 16 & 255));
        int g = (int) (delta * (first >> 8 & 255) + (1 - delta) * (next >> 8 & 255));
        int b = (int) (delta * (first & 255) + (1 - delta) * (next & 255));
        return first & 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static void check(int size, int mips, double delta) {
        Random random = new Random(size * 31L + mips);
        //A two-frame strip, frame 1 below frame 0, with its mip chain; the output holds one frame per mip
        NativeImage[] source = new NativeImage[mips], output = new NativeImage[mips];
        try {
            for (int mip = 0; mip < mips; mip++) {
                int s = size >> mip;
                source[mip] = new NativeImage(NativeImage.Format.RGBA, s, 2 * s, false);
                output[mip] = new NativeImage(NativeImage.Format.RGBA, s, s, false);
                for (int y = 0; y < 2 * s; y++)
                    for (int x = 0; x < s; x++) source[mip].setPixelRGBA(x, y, random.nextInt());
            }
            assertTrue(SpriteInterpolation.interpolate(source, output, size, size, 0, 1, 0, 0, delta, MEMORY));
            for (int mip = 0; mip < mips; mip++) {
                int s = size >> mip;
                for (int y = 0; y < s; y++)
                    for (int x = 0; x < s; x++) {
                        int expected = reference(source[mip].getPixelRGBA(x, y + s), source[mip].getPixelRGBA(x, y), delta);
                        assertEquals(expected, output[mip].getPixelRGBA(x, y), "mip " + mip + " at " + x + "," + y);
                    }
            }
        } finally {
            for (NativeImage image : source) if (image != null) image.close();
            for (NativeImage image : output) if (image != null) image.close();
        }
    }

    @Test
    void wideSpritesMatchVanilla() {
        check(64, 4, 0.37);
    }

    @Test
    void narrowSpritesMatchVanilla() {
        check(16, 3, 0.81);
    }
}
