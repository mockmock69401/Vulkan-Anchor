package net.vulkanmod.visuals.render;

/**
 * 2026-11-15 (shader roadmap session 25, the user: "눈과 모래 위를 걸으면 발자국이 남았다가 서서히 사라지게"): footprints in snow and sand.
 * Pure math (FootprintTest); Footprints places them, VkPostProcess uploads the map, footprint.glsl shades them in the composite.
 * <p>THE APPROACH (cheap): a world-anchored, wrapping map of MAP x MAP texels, PER_BLOCK per block (MAP / PER_BLOCK = 64 blocks around the
 * camera, addressed by world texel &amp; MASK, so it never shifts). Each texel is one R32UI word (pack): the time the print was made (TIME_STEP
 * seconds, wrapping at TIME_WRAP steps), its depth there 0..255 (the foot's shape), the low 4 bits of the surface's block y and which of
 * the four map wraps along x and z the texel stands for (the composite ignores a texel whose height or wrap is not the pixel's: a print
 * 64 blocks away or on another floor does not show), and whether it is sand. The composite reads the 2x2 texels around a top-facing pixel
 * within END blocks, fades each by its age (fade) and shades the hollow: darker at the bottom, its walls lit or shaded by the sun.
 * <p>The CPU keeps the last RING prints (Ring); a new one is drawn into the map (raster: an oval OUT the foot's length and its width,
 * along the walk) and only its few texels are uploaded; a print older than its fade is cleared from the map (texels it still owns), so a
 * texel's time never wraps around into a visible age.
 */
public final class FootprintMath {
    /** The setting's range (percent of the default depth). */
    public static final int STRENGTH_MAX = 200;
    public static final int MAP = 1024, MASK = MAP - 1, PER_BLOCK = 16, MAP_SHIFT = 10;
    /** Blocks the map covers per side; the wrap tag counts the map's wraps modulo 4. */
    public static final int BLOCKS = MAP / PER_BLOCK;
    public static final float TIME_STEP = 0.125f;
    public static final int TIME_WRAP = 4096;
    /**
     * Prints kept on the CPU (oldest replaced first); at a walk's 6 prints a second, about 170 s (2026-11-16: was 256, about 40 s, before
     * the duration setting could keep a print in snow up to 100 s).
     */
    public static final int RING = 1024;
    /**
     * 2026-11-16 (session 25 follow-up, the user: "발자국 깊이와 유지 시간도 추가해줘"): the Experimental page's depth (percent of the hollow's
     * default depth, 0..DEPTH_MAX) and duration (percent of SNOW_FADE / SAND_FADE, DURATION_MIN..DURATION_MAX: up to 100 s in snow, well within
     * the TIME_WRAP steps' 512 s). footParams.w carries both as depth + PACK x duration (whole percents, exact in a float).
     */
    public static final int DEPTH_MAX = 300, DURATION_MIN = 25, DURATION_MAX = 400, PACK = 1024;
    /** A boot's half length and half width (blocks), and how far either side of the walk's line the two feet land. */
    public static final float HALF_LENGTH = 0.17f, HALF_WIDTH = 0.105f, SIDE = 0.14f;
    /** A print every STRIDE blocks walked on the ground (each foot every second one). */
    public static final float STRIDE = 0.7f;
    /** Seconds a print takes to fade out: snow keeps them longer than dry, loose sand. Fully there for HOLD of it. */
    public static final float SNOW_FADE = 25f, SAND_FADE = 15f, HOLD = 0.4f;
    /** Composite: no prints beyond END blocks (fading over the last FADE_END). */
    public static final float END = 28f, FADE_END = 6f;
    static final int FLAG_SAND = 1 << 31;
    /** The composite's slots: footParams, footCamera. */
    public static final int SLOT = 73, CAMERA_SLOT = 74;
    /** The composite binding the map is read at (VkPostProcess.COPY_FOOTPRINTS). */
    public static final int BINDING = 27;
    /** footCamera's texel wraps here (a multiple of the map and of its four-wrap tag: 4 x MAP). */
    public static final int CAMERA_WRAP = 4 * MAP;

    private FootprintMath() {
    }

    /** One footprint: where (world x, z of its centre, the surface's block y), facing yaw (degrees, Minecraft's), when (seconds), on sand. */
    public record Print(double x, double z, int surfaceY, float yaw, double seconds, boolean sand) {
    }

    /** The texel word: time steps (wrapped), depth 0..255, the surface's block y, the texel's world texel x, z (the wrap tag), sand. */
    public static int pack(int timeStep, int depth, int surfaceY, int texelX, int texelZ, boolean sand) {
        int t = Math.floorMod(timeStep, TIME_WRAP);
        int d = Math.max(0, Math.min(255, depth));
        return t | (d << 12) | ((surfaceY & 15) << 20) | (tag(texelX, texelZ) << 24) | (sand ? FLAG_SAND : 0);
    }

    /** The wrap tag of a world texel: which of four map wraps along x (low 2 bits) and z (next 2). */
    public static int tag(int texelX, int texelZ) {
        return ((texelX >> MAP_SHIFT) & 3) | (((texelZ >> MAP_SHIFT) & 3) << 2);
    }

    public static int timeOf(int word) {
        return word & 0xFFF;
    }

    public static int depthOf(int word) {
        return (word >>> 12) & 0xFF;
    }

    /** The time step of a moment (seconds), wrapped. */
    public static int timeStep(double seconds) {
        return Double.isFinite(seconds) ? (int) Math.floorMod((long) Math.floor(seconds / TIME_STEP), (long) TIME_WRAP) : 0;
    }

    /** A print's age in seconds from its stored step and now's step (both wrapped). */
    public static float age(int printStep, int nowStep) {
        return Math.floorMod(nowStep - printStep, TIME_WRAP) * TIME_STEP;
    }

    /** How much of a print shows at an age (seconds) with that fade time: 1 for HOLD of it, then down to 0 smoothly. */
    public static float fade(float age, float fadeSeconds) {
        if (!(age >= 0) || !(fadeSeconds > 0)) return 0;
        float a = age / fadeSeconds;
        if (a >= 1) return 0;
        if (a <= HOLD) return 1;
        float t = (a - HOLD) / (1 - HOLD);
        return 1 - t * t * (3 - 2 * t);
    }

    /**
     * The foot's depth 0..1 at (u, v) blocks from its centre along (u) and across (v) the walk: a rounded oval, a little narrower at the
     * arch, soft at its edge.
     */
    public static float shape(float u, float v) {
        float lu = u / HALF_LENGTH;
        float arch = 1 - 0.18f * (float) Math.exp(-lu * lu * 8);
        float lv = v / (HALF_WIDTH * arch);
        float r = (float) Math.sqrt(lu * lu + lv * lv);
        if (r >= 1) return 0;
        float t = Math.max(0, (r - 0.55f) / 0.45f);
        return 1 - t * t * (3 - 2 * t);
    }

    /**
     * Draws a print into the map (texels int[MAP * MAP], row z &amp; MASK): each texel the oval covers takes the deeper of what it holds (a
     * print made at another time whose texel is deeper keeps it) and, where this print is deeper, its word. Returns the texel box drawn
     * {x0, z0, x1, z1} (world texels, inclusive), or null when nothing was drawn.
     */
    public static int[] raster(int[] map, Print p) {
        double yaw = Math.toRadians(p.yaw());
        //Minecraft's yaw: 0 faces +z, 90 faces -x
        float fx = (float) -Math.sin(yaw), fz = (float) Math.cos(yaw);
        float reach = HALF_LENGTH + 1f / PER_BLOCK;
        int x0 = (int) Math.floor((p.x() - reach) * PER_BLOCK), x1 = (int) Math.floor((p.x() + reach) * PER_BLOCK);
        int z0 = (int) Math.floor((p.z() - reach) * PER_BLOCK), z1 = (int) Math.floor((p.z() + reach) * PER_BLOCK);
        int step = timeStep(p.seconds());
        boolean any = false;
        for (int tz = z0; tz <= z1; tz++) {
            for (int tx = x0; tx <= x1; tx++) {
                double cx = (tx + 0.5) / PER_BLOCK - p.x(), cz = (tz + 0.5) / PER_BLOCK - p.z();
                float u = (float) (cx * fx + cz * fz), v = (float) (cx * fz - cz * fx);
                int depth = Math.round(shape(u, v) * 255);
                if (depth <= 0) continue;
                int i = index(tx, tz);
                int old = map[i];
                if (old != 0 && depthOf(old) >= depth && sameColumn(old, p.surfaceY(), tx, tz)) continue;
                map[i] = pack(step, depth, p.surfaceY(), tx, tz, p.sand());
                any = true;
            }
        }
        return any ? new int[]{x0, z0, x1, z1} : null;
    }

    /** Clears the texels a print still owns (its time step there; a newer print drawn over keeps them). Returns the box as raster's. */
    public static int[] clear(int[] map, Print p) {
        float reach = HALF_LENGTH + 1f / PER_BLOCK;
        int x0 = (int) Math.floor((p.x() - reach) * PER_BLOCK), x1 = (int) Math.floor((p.x() + reach) * PER_BLOCK);
        int z0 = (int) Math.floor((p.z() - reach) * PER_BLOCK), z1 = (int) Math.floor((p.z() + reach) * PER_BLOCK);
        int step = timeStep(p.seconds());
        boolean any = false;
        for (int tz = z0; tz <= z1; tz++) {
            for (int tx = x0; tx <= x1; tx++) {
                int i = index(tx, tz);
                int w = map[i];
                if (w != 0 && timeOf(w) == step && sameColumn(w, p.surfaceY(), tx, tz)) {
                    map[i] = 0;
                    any = true;
                }
            }
        }
        return any ? new int[]{x0, z0, x1, z1} : null;
    }

    private static boolean sameColumn(int word, int surfaceY, int tx, int tz) {
        return ((word >>> 20) & 15) == (surfaceY & 15) && ((word >>> 24) & 15) == tag(tx, tz);
    }

    /** The map index of a world texel. */
    public static int index(int texelX, int texelZ) {
        return (texelX & MASK) + MAP * (texelZ & MASK);
    }

    /** The left foot on even steps, the right on odd ones: the print's centre offset (x, z) SIDE across the walk facing yaw. */
    public static float[] side(float yaw, boolean left) {
        double r = Math.toRadians(yaw);
        //across: facing (−sin, cos) turned right is (−cos, −sin)
        float s = left ? -SIDE : SIDE;
        return new float[]{(float) (-Math.cos(r) * s), (float) (-Math.sin(r) * s)};
    }

    /** The composite's footParams for a setting (percent), now (seconds) and the camera's y, at the default depth and duration. */
    public static float[] params(int setting, double seconds, double cameraY) {
        return params(setting, seconds, cameraY, 100, 100);
    }

    /**
     * The composite's footParams: x the strength (the setting, 0: off), y now's time step, z the camera's y, w the depth and the duration
     * (percents, packed: depth + PACK x duration; footprint.glsl unpacks them).
     */
    public static float[] params(int setting, double seconds, double cameraY, int depth, int duration) {
        float s = Math.max(0, Math.min(STRENGTH_MAX, setting)) / 100f;
        int d = Math.max(0, Math.min(DEPTH_MAX, depth)), t = Math.max(DURATION_MIN, Math.min(DURATION_MAX, duration));
        return new float[]{s, timeStep(seconds), (float) cameraY, d + (float) PACK * t};
    }

    /** A print's fade time (seconds): SAND_FADE or SNOW_FADE times the duration (percent, kept in its range). */
    public static float fadeSeconds(boolean sand, int duration) {
        return (sand ? SAND_FADE : SNOW_FADE) * Math.max(DURATION_MIN, Math.min(DURATION_MAX, duration)) / 100f;
    }

    /**
     * The composite's footCamera: xy the camera's world texel (x, z: floor(position x PER_BLOCK)) modulo CAMERA_WRAP (exact in a float; the map
     * and its wrap tag repeat within it), zw the camera within that texel (0..1).
     */
    public static float[] camera(double x, double z) {
        double tx = Math.floor(x * PER_BLOCK), tz = Math.floor(z * PER_BLOCK);
        return new float[]{(float) Math.floorMod((long) tx, (long) CAMERA_WRAP), (float) Math.floorMod((long) tz, (long) CAMERA_WRAP),
                (float) (x * PER_BLOCK - tx), (float) (z * PER_BLOCK - tz)};
    }

    /**
     * A box of world texels {x0, z0, x1, z1} (inclusive) as map rectangles {x, z, width, height}, split where the map wraps (at most four;
     * a box wider than the map is cut to it).
     */
    public static java.util.List<int[]> split(int[] box) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        int w = Math.min(MAP, box[2] - box[0] + 1), h = Math.min(MAP, box[3] - box[1] + 1);
        if (w <= 0 || h <= 0) return out;
        int mx = box[0] & MASK, mz = box[1] & MASK;
        int w0 = Math.min(w, MAP - mx), h0 = Math.min(h, MAP - mz);
        out.add(new int[]{mx, mz, w0, h0});
        if (w0 < w) out.add(new int[]{0, mz, w - w0, h0});
        if (h0 < h) out.add(new int[]{mx, 0, w0, h - h0});
        if (w0 < w && h0 < h) out.add(new int[]{0, 0, w - w0, h - h0});
        return out;
    }

    /** A fixed-size ring of prints, oldest replaced first. */
    public static final class Ring {
        private final Print[] prints;
        private int head, size;

        public Ring(int capacity) {
            this.prints = new Print[Math.max(1, capacity)];
        }

        /** Adds a print; returns the one it replaced (the oldest) or null. */
        public Print add(Print p) {
            Print old = this.size == this.prints.length ? this.prints[this.head] : null;
            this.prints[this.head] = p;
            this.head = (this.head + 1) % this.prints.length;
            if (this.size < this.prints.length) this.size++;
            return old;
        }

        /** Removes and returns the oldest print if its age at seconds is at least its fade time, else null. */
        public Print expire(double seconds) {
            return expire(seconds, 100);
        }

        /**
         * As expire(seconds) with the duration setting (percent; fadeSeconds). The oldest print goes first: a sand print behind an older snow
         * one waits for it (cleared a little late, already faded out in the composite).
         */
        public Print expire(double seconds, int duration) {
            if (this.size == 0) return null;
            int tail = Math.floorMod(this.head - this.size, this.prints.length);
            Print p = this.prints[tail];
            if (seconds - p.seconds() < fadeSeconds(p.sand(), duration) && seconds >= p.seconds()) return null;
            this.prints[tail] = null;
            this.size--;
            return p;
        }

        public int size() {
            return this.size;
        }

        /** Forgets every print (a new level). */
        public void clear() {
            java.util.Arrays.fill(this.prints, null);
            this.head = 0;
            this.size = 0;
        }
    }
}
