package net.vulkanmod.visuals.render;

/**
 * P7l design 3.1: the light volume's layout. A section sits in the slot given by its section position modulo the slot counts
 * (a ring around the camera), 4096 bytes in DataLayer order (y*256 + z*16 + x); the ready table holds per slot the key of
 * the section uploaded there, so a slot still holding another section reads as unknown.
 */
public final class LightVolumeMath {
    public static final int SECTION_BYTES = 4096;
    /** Sections read and uploaded per frame, nearest first. */
    public static final int SECTION_BUDGET = 64;
    /** The key's y field has 7 bits. */
    public static final int MAX_SECTIONS_Y = 128;
    /** Frames between full scans for slots missing their section (a chunk that arrived late). */
    public static final int RESCAN_FRAMES = 30;

    public record Layout(int slotsX, int slotsY, int slotsZ, int minSectionY) {
        public int slots() {
            return this.slotsX * this.slotsY * this.slotsZ;
        }

        public long bytes() {
            return (long) this.slots() * SECTION_BYTES;
        }

        public int half() {
            return this.slotsX / 2;
        }

        public int slot(int sx, int sy, int sz) {
            return Math.floorMod(sx, this.slotsX) + this.slotsX * (Math.floorMod(sz, this.slotsZ) + this.slotsZ * (sy - this.minSectionY));
        }

        public boolean inRange(int sx, int sy, int sz, int cameraSx, int cameraSz) {
            return Math.abs(sx - cameraSx) <= this.half() && Math.abs(sz - cameraSz) <= this.half()
                    && sy >= this.minSectionY && sy < this.minSectionY + this.slotsY;
        }

        /** x and z: their 12 low bits, y: 7 bits above the minimum, bit 31 set (0 is an empty slot). lightvolume.glsl mirrors it. */
        public int key(int sx, int sy, int sz) {
            return (sx & 0xFFF) | (sz & 0xFFF) << 12 | ((sy - this.minSectionY) & 0x7F) << 24 | 1 << 31;
        }

        /** The section within range of the camera's section that slot holds: x, y, z. */
        public int[] sectionAt(int slot, int cameraSx, int cameraSz) {
            int ix = slot % this.slotsX, iz = (slot / this.slotsX) % this.slotsZ, iy = slot / (this.slotsX * this.slotsZ);
            int x0 = cameraSx - this.half(), z0 = cameraSz - this.half();
            return new int[]{x0 + Math.floorMod(ix - x0, this.slotsX), this.minSectionY + iy, z0 + Math.floorMod(iz - z0, this.slotsZ)};
        }
    }

    private LightVolumeMath() {
    }

    /** Vanilla's render distance (chunks) around the camera's section, over the dimension's sections (at most MAX_SECTIONS_Y). */
    public static Layout layout(int renderDistance, int minSectionY, int sectionsY) {
        int n = 2 * Math.max(renderDistance, 2) + 1;
        return new Layout(n, Math.min(sectionsY, MAX_SECTIONS_Y), n, minSectionY);
    }

    /** The data buffer is bound whole as one storage buffer, so it cannot exceed the device's maxStorageBufferRange. */
    public static boolean fitsStorageRange(Layout layout, long maxStorageBufferRange) {
        return layout.bytes() <= maxStorageBufferRange;
    }
}
