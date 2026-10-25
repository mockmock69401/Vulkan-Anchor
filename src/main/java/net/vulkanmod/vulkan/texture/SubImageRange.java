package net.vulkanmod.vulkan.texture;

/**
 * The bytes of a source buffer a sub-image upload reads, as GL's unpack state describes them: the first texel at
 * (skipRows * rowLength + skipPixels) and every row up to the last one's width. Stutter (2026-10-21): an upload copies only these
 * into the staging buffer, not the whole source (an animated sprite's frame strip with every frame), which was ~25 MB a tick in CTE2.
 */
public record SubImageRange(long start, long size) {

    /** rowLength 0 means rows of width texels; null when the range does not fit in limit bytes (the caller copies the whole buffer). */
    public static SubImageRange of(int width, int height, int skipRows, int skipPixels, int rowLength, int formatSize, long limit) {
        if (width <= 0 || height <= 0 || skipRows < 0 || skipPixels < 0 || rowLength < 0 || formatSize <= 0) return null;
        long row = rowLength > 0 ? rowLength : width;
        if (row < width) return null;
        long start = ((long) skipRows * row + skipPixels) * formatSize;
        long size = ((long) (height - 1) * row + width) * formatSize;
        if (start + size > limit) return null;
        return new SubImageRange(start, size);
    }
}
