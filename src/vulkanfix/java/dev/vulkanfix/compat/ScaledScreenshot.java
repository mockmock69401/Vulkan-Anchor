package dev.vulkanfix.compat;

/**
 * Size of a screenshot of the main target. Under render scale the main target's colour attachment is the scaled
 * framebuffer, smaller than the target size Reforged allocates the NativeImage with; the copy is tightly packed rows of
 * the image's own width, so the NativeImage must have exactly that size.
 */
public final class ScaledScreenshot {
    private ScaledScreenshot() {
    }

    /** {width, height} to grab at, or null when Reforged's target-sized grab already matches the image. */
    public static int[] size(int targetWidth, int targetHeight, int imageWidth, int imageHeight) {
        if (imageWidth <= 0 || imageHeight <= 0 || (imageWidth == targetWidth && imageHeight == targetHeight)) {
            return null;
        }
        return new int[]{imageWidth, imageHeight};
    }
}
