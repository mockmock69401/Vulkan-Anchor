package net.vulkanmod.visuals.render;

import java.util.List;

/**
 * 2026-11-16 (shader roadmap session 26; the user: "세번째 사진 처럼 이동할 때 물결치는 효과도 구현해줘", Experimental > Interaction): the wakes of
 * whatever moves through the water's surface (wake.glsl, read by the reflection pass). A trail per entity at the surface (Wakes) sends out a
 * ring source every STEP seconds, newest first, each living LIFE seconds; this class holds the trail, the sources' ring speed, amplitude and
 * phase, packs the trails for the pass and mirrors wake.glsl (WakeTest).
 */
public final class WakeMath {
    /** The reflection pass's storage binding and loose uniform slot (wake.glsl's WakeData and wakeParams). */
    public static final int BINDING = 14, SLOT = 35;
    public static final int MAX_TRAILS = 16, MAX_SOURCES = 48;
    /**
     * A trail goes to the pass in chunks of CHUNK sources (each with the next chunk's first source too), at most MAX_CHUNKS: a pixel outside a
     * chunk's circle skips it (one long trail's circle covered most of the water in view: 0.4 ms at 1440p before).
     */
    public static final int CHUNK = 8, MAX_CHUNKS = 96;
    /** Seconds between a trail's sources, and how long each lives. */
    public static final float STEP = 0.05f, LIFE = 2.0f;
    /** wake.glsl's consts. */
    public static final float WAVELENGTH = 0.4f, SLOPE = 0.5f, SPREAD = 1.0f, Y_RANGE = 0.6f, SHINE = 0.25f, FOAM = 0.45f, FOAM_AGE = 0.45f,
            FOAM_WHITE = 0.85f, FADE_NONE = 0.5f, FADE_FULL = 2.0f;
    public static final float PACKET_WAVE = 0.3f, PACKET_WIDTH = 0.35f, PACKET_HEIGHT = 0.04f, PACKET_GAIN = 8.0f;
    /**
     * A source's rings run at KELVIN times the entity's speed across the water, between RING_MIN and RING_MAX blocks a second: a source
     * faster than its rings leaves a V of half angle asin(ring / speed), so the share keeps it near a Kelvin wake's 19.5 degrees.
     */
    public static final float KELVIN = 0.34f, RING_MIN = 0.9f, RING_MAX = 2.5f;
    /** The rings' amplitude (IDLE: around a body bobbing in place) and the wake's (0 at rest, 1 from FULL_SPEED blocks a second). */
    public static final float IDLE = 0.5f, FULL_SPEED = 4.0f;
    /** An entity this wide (blocks) makes waves of amplitude 1; the size factor runs between SIZE_MIN and SIZE_MAX. */
    public static final float SIZE_WIDTH = 0.6f, SIZE_MIN = 0.6f, SIZE_MAX = 1.8f;
    /** The vec4s the pass's buffer holds: two a trail's header, two a source. */
    public static final int VEC4S = 2 * MAX_CHUNKS + 2 * MAX_TRAILS * MAX_SOURCES;
    public static final int BYTES = VEC4S * 16;

    private WakeMath() {
    }

    /** The rings' speed (blocks a second) for an entity moving at speed across the water. */
    public static float ringSpeed(float speed) {
        return clamp(KELVIN * speed, RING_MIN, RING_MAX);
    }

    /** The size factor of an entity this wide. */
    static float size(float width) {
        return clamp((float) Math.sqrt(Math.max(width, 0) / SIZE_WIDTH), SIZE_MIN, SIZE_MAX);
    }

    /** The wake's amplitude for an entity moving at speed (blocks a second, its vertical motion included) of this width. */
    public static float amplitude(float speed, float width) {
        return clamp(speed / FULL_SPEED, 0, 1) * size(width);
    }

    /** The rings' amplitude for an entity of this width. */
    public static float ringAmplitude(float width) {
        return IDLE * size(width);
    }

    /**
     * One source: where (world x, z), when it was sent (seconds), its ring speed, the wake's amplitude, the rings' phase (cycles), whether it
     * is joined to the one before, the rings' amplitude.
     */
    public record Source(double x, double z, double time, float ring, float amplitude, float phase, boolean joined, float ringAmplitude) {
    }

    /**
     * One entity's trail: its sources newest first, at most MAX_SOURCES; the wave's phase runs at ring / WAVELENGTH cycles a second, so a
     * source's crests lie a wavelength apart whatever its ring speed.
     */
    public static final class Trail {
        private final Source[] sources = new Source[MAX_SOURCES];
        private int head, size;
        private double phase;
        /** The next source's time, the last frame's position and time, the smoothed speeds, the surface's y. */
        double nextEmit = Double.NaN, lastX, lastY, lastZ, lastTime = Double.NaN;
        float speed, across;
        double surface;
        boolean touching;

        public int size() {
            return this.size;
        }

        /** The i-th newest source. */
        public Source get(int i) {
            return this.sources[Math.floorMod(this.head - 1 - i, MAX_SOURCES)];
        }

        /** Sends a source at (x, z) at time; joined: the entity has been at the surface since the last one. */
        public void emit(double x, double z, double time, float speedAcross, float speed, float width, boolean joined) {
            float ring = ringSpeed(speedAcross);
            if (this.size > 0) this.phase += (time - this.get(0).time()) * ring / WAVELENGTH;
            this.phase -= Math.floor(this.phase);
            this.sources[this.head] = new Source(x, z, time, ring, amplitude(speed, width), (float) this.phase, joined && this.size > 0,
                    ringAmplitude(width));
            this.head = (this.head + 1) % MAX_SOURCES;
            this.size = Math.min(this.size + 1, MAX_SOURCES);
        }

        /** Drops the sources older than LIFE seconds at now. */
        public void expire(double now) {
            while (this.size > 0 && now - this.get(this.size - 1).time() >= LIFE) this.size--;
        }

        /**
         * Moves the trail to (x, y, z) at time: sources every STEP seconds along the way from the last position while touching the surface
         * (none otherwise); a jump of more than 8 blocks or a pause of over a second starts the trail's path over. width: the entity's.
         */
        public void advance(double x, double y, double z, double time, boolean touching, double surface, float width) {
            if (Double.isNaN(this.lastTime) || time - this.lastTime > 1 || sq(x - this.lastX) + sq(z - this.lastZ) > 64) {
                this.lastX = x;
                this.lastY = y;
                this.lastZ = z;
                this.lastTime = time;
                this.nextEmit = time;
                this.speed = 0;
                this.across = 0;
                this.touching = false;
            }
            double dt = time - this.lastTime;
            if (dt > 0) {
                float k = (float) Math.min(1, dt / 0.15);
                double h = Math.sqrt(sq(x - this.lastX) + sq(z - this.lastZ)) / dt;
                double all = Math.sqrt(h * h + sq(y - this.lastY) / (dt * dt));
                this.across += (float) ((h - this.across) * k);
                this.speed += (float) ((all - this.speed) * k);
            }
            boolean wasTouching = this.touching;
            if (touching) {
                //A first touch (or a long frame) sends its first source now, not a burst for the time before
                if (!wasTouching || this.nextEmit < time - 0.5) this.nextEmit = time;
                while (this.nextEmit <= time) {
                    double f = dt > 0 ? clamp((float) ((this.nextEmit - this.lastTime) / dt), 0, 1) : 1;
                    this.emit(this.lastX + (x - this.lastX) * f, this.lastZ + (z - this.lastZ) * f, this.nextEmit, this.across, this.speed, width,
                            wasTouching);
                    wasTouching = true;
                    this.nextEmit += STEP;
                }
                this.surface = surface;
            }
            this.touching = touching;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.lastTime = time;
            this.expire(time);
        }
    }

    /**
     * Packs up to MAX_TRAILS trails into out (VEC4S x 4 floats, camera relative: the camera at cx, cy, cz) at now, each in chunks (at most
     * MAX_CHUNKS in all); returns {trails, chunks} that went in. The trails come in the order given (Wakes puts the nearest first); empty ones
     * are left out.
     */
    public static int[] pack(List<Trail> trails, double cx, double cy, double cz, double now, float[] out) {
        int n = 0, chunks = 0;
        for (Trail t : trails) {
            if (n >= MAX_TRAILS || chunks >= MAX_CHUNKS) break;
            if (t.size() == 0) continue;
            int first = 2 * MAX_CHUNKS + n * 2 * MAX_SOURCES;
            for (int i = 0; i < t.size(); i++) {
                Source s = t.get(i);
                int o = (first + 2 * i) * 4;
                out[o] = (float) (s.x() - cx);
                out[o + 1] = (float) (s.z() - cz);
                out[o + 2] = (float) (now - s.time());
                out[o + 3] = s.ring();
                out[o + 4] = s.amplitude();
                out[o + 5] = s.phase();
                out[o + 6] = s.joined() ? 1 : 0;
                out[o + 7] = s.ringAmplitude();
            }
            //Chunks of CHUNK pairs: sources start .. start + CHUNK (one source shared with the next chunk); a lone source is a chunk of one
            for (int start = 0; start < Math.max(t.size() - 1, 1) && chunks < MAX_CHUNKS; start += CHUNK) {
                int end = Math.min(start + CHUNK, t.size() - 1);
                double minX = Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
                float reach = 0;
                for (int i = start; i <= end; i++) {
                    Source s = t.get(i);
                    minX = Math.min(minX, s.x());
                    maxX = Math.max(maxX, s.x());
                    minZ = Math.min(minZ, s.z());
                    maxZ = Math.max(maxZ, s.z());
                    reach = Math.max(reach, s.ring() * (float) (now - s.time()));
                }
                int h = 2 * chunks * 4;
                out[h] = (float) ((minX + maxX) * 0.5 - cx);
                out[h + 1] = (float) ((minZ + maxZ) * 0.5 - cz);
                out[h + 2] = (float) (Math.sqrt(sq(maxX - minX) + sq(maxZ - minZ)) * 0.5) + reach + Math.max(WAVELENGTH, 3 * PACKET_WIDTH);
                out[h + 3] = (float) (t.surface - cy);
                out[h + 4] = first + 2 * start;
                out[h + 5] = end - start + 1;
                out[h + 6] = 0;
                out[h + 7] = 0;
                chunks++;
            }
            n++;
        }
        return new int[]{n, chunks};
    }

    /** The floats of out a pack of this many trails filled (the headers, then their sources). */
    public static int floats(int trails) {
        return (2 * MAX_CHUNKS + trails * 2 * MAX_SOURCES) * 4;
    }

    /** The pass's wakeParams: the setting's strength (ratio, 0 = off), the chunk count, the sources' lifetime. */
    public static float[] params(float strength, int chunks) {
        return chunks > 0 && strength > 0 ? new float[]{strength, chunks, LIFE, 0} : new float[4];
    }

    /** wake.glsl's wakePacket: {H, h, h'} at e. */
    static float[] packet(float e) {
        float k = 6.2831853f / PACKET_WAVE, w2 = PACKET_WIDTH * PACKET_WIDTH;
        float g = (float) Math.exp(-e * e / w2), s = (float) Math.sin(k * e), c = (float) Math.cos(k * e), b = 2 * e / w2;
        return new float[]{g * s / k, g * (c - b * s / k), g * (-2 * b * c - k * s + (b * b - 2 / w2) * s / k)};
    }

    /** wake.glsl's wakeAt: {slope x, slope z, shine, foam} at the camera relative (wx, wy, wz) for packed data and params. */
    public static float[] wakeAt(float[] data, float[] params, float wx, float wy, float wz, float footprint) {
        float pixels = footprint > 0.000001f ? Math.min(WAVELENGTH, PACKET_WAVE) * 0.5f / footprint : 1000000f;
        float fade = smoothstep(FADE_NONE, FADE_FULL, pixels);
        float[] r = new float[4];
        if (!(params[0] > 0) || fade <= 0) return r;
        float life = Math.max(params[2], 0.001f);
        int trails = Math.min((int) (params[1] + 0.5f), MAX_CHUNKS);
        float reach = 3 * PACKET_WIDTH;
        float sx = 0, sz = 0, wakeX = 0, wakeZ = 0, height = 0, shine = 0, foam = 0;
        for (int t = 0; t < trails; t++) {
            int h = 2 * t * 4;
            float dx = wx - data[h], dz = wz - data[h + 1];
            if (dx * dx + dz * dz > data[h + 2] * data[h + 2] || Math.abs(wy - data[h + 3]) > Y_RANGE) continue;
            int first = (int) (data[h + 4] + 0.5f);
            int count = Math.min((int) (data[h + 5] + 0.5f), CHUNK + 1);
            float nx = 0, nz = 0, nAge = 0, nRing = 0, nAmp = 0, nPhase = 0, nE = 0, nRingAmp = 0, nDx = 0, nDz = 0, nH = 0, nHeight = 0;
            boolean nJoined = false;
            for (int i = 0; i < count; i++) {
                int o = (first + 2 * i) * 4;
                float x = data[o], z = data[o + 1], age = data[o + 2], ring = data[o + 3];
                float amp = data[o + 4], phase = data[o + 5], ringAmp = data[o + 7];
                boolean joined = data[o + 6] > 0.5f;
                float ddx = wx - x, ddz = wz - z;
                float dist = (float) Math.sqrt(ddx * ddx + ddz * ddz);
                float e = dist - ring * age;
                float dirX = dist > 0.0001f ? ddx / dist : 0, dirZ = dist > 0.0001f ? ddz / dist : 0;
                float[] pk = Math.abs(e) < reach ? packet(e) : new float[3];
                if (i > 0 && nJoined) {
                    float mid = 0.5f * (nAge + age);
                    float live = 1 - clamp(mid / life, 0, 1);
                    if (Math.min(nE, e) < reach && Math.max(nE, e) > -reach) {
                        float a = 0.5f * (nAmp + amp) * live / (float) Math.sqrt(1 + Math.max(0.5f * (nRing * nAge + ring * age), 0) / SPREAD);
                        float span = age - nAge, de = e - nE, part, gx, gz;
                        if (Math.abs(de) > 0.02f * PACKET_WIDTH) {
                            part = span * (pk[0] - nH) / de;
                            gx = span * ((pk[1] * dirX - nHeight * nDx) / de - (pk[0] - nH) * (dirX - nDx) / (de * de));
                            gz = span * ((pk[1] * dirZ - nHeight * nDz) / de - (pk[0] - nH) * (dirZ - nDz) / (de * de));
                        } else {
                            float[] p0 = packet(0.5f * (nE + e));
                            part = span * p0[1];
                            gx = span * p0[2] * 0.5f * (dirX + nDx);
                            gz = span * p0[2] * 0.5f * (dirZ + nDz);
                        }
                        wakeX += a * gx;
                        wakeZ += a * gz;
                        height += a * part;
                        foam += a * Math.max(part, 0) * (float) Math.exp(-mid / FOAM_AGE);
                    }
                    if ((nE > 0) != (e > 0)) {
                        float k = nE / (nE - e);
                        float cxx = nx + (x - nx) * k, czz = nz + (z - nz) * k;
                        float qx = wx - cxx, qz = wz - czz;
                        float rr = (float) Math.sqrt(qx * qx + qz * qz);
                        float a0 = nAge + (age - nAge) * k;
                        float dp = phase - nPhase;
                        float ph = nPhase + k * (dp - (float) Math.floor(dp + 0.5f));
                        float rl = 1 - clamp(a0 / life, 0, 1);
                        float a = (nRingAmp + (ringAmp - nRingAmp) * k) * rl / (float) Math.sqrt(1 + rr / SPREAD);
                        if (rr > 0.0001f) {
                            float c = (float) Math.cos(6.2831853 * ph);
                            sx -= qx / rr * a * c;
                            sz -= qz / rr * a * c;
                        }
                        shine += a * Math.max((float) Math.sin(6.2831853 * ph), 0);
                    }
                }
                nx = x;
                nz = z;
                nAge = age;
                nRing = ring;
                nAmp = amp;
                nPhase = phase;
                nE = e;
                nRingAmp = ringAmp;
                nJoined = joined;
                nDx = dirX;
                nDz = dirZ;
                nH = pk[0];
                nHeight = pk[1];
            }
        }
        float strength = params[0] * fade;
        r[0] = (sx * SLOPE + wakeX * PACKET_GAIN * PACKET_HEIGHT) * strength;
        r[1] = (sz * SLOPE + wakeZ * PACKET_GAIN * PACKET_HEIGHT) * strength;
        r[2] = Math.min(shine + Math.max(height * PACKET_GAIN, 0), 1.5f) * SHINE * strength;
        r[3] = clamp(foam * PACKET_GAIN * FOAM * strength, 0, 1);
        return r;
    }

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static float smoothstep(float a, float b, float x) {
        float t = clamp((x - a) / (b - a), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static double sq(double v) {
        return v * v;
    }
}
