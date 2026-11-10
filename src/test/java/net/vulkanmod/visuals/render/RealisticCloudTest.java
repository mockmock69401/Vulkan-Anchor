package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 3 (2026-11-09): the realistic clouds (clouds.glsl's realistic part, CloudMath's mirror, CloudUniforms, the settings). */
class RealisticCloudTest {
    private static final byte[] MAP = CloudMath.withNoise(new byte[CloudMath.MAP * CloudMath.MAP * 4]);
    private static final float[] UP = {0, 1, 0}, DOWN = {0, -1, 0};

    private static CloudMath.Realistic layer(float cover) {
        return new CloudMath.Realistic(200, CloudMath.REAL_THICKNESS, 8192, 1000, 1500, cover);
    }

    /** Every REAL_ constant of clouds.glsl is CloudMath's field of the same name and value, and every such field is in the shader. */
    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        String text = ShaderFiles.read("post/clouds.glsl");
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(REAL_\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(text);
        int found = 0;
        while (m.find()) {
            Field f = CloudMath.class.getField(m.group(2));
            double want = Double.parseDouble(m.group(3));
            assertEquals(m.group(1).equals("int") ? int.class : float.class, f.getType(), m.group(2));
            assertEquals(want, ((Number) f.get(null)).doubleValue(), 1e-6 * Math.max(1, Math.abs(want)), m.group(2));
            found++;
        }
        assertTrue(found >= 30, "constants found: " + found);
        for (Field f : CloudMath.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !f.getName().startsWith("REAL_") || f.getName().equals("REAL_LIFT") || f.getName().equals("REAL_THICKNESS")) continue;
            assertTrue(Pattern.compile("const\\s+(float|int)\\s+" + f.getName() + "\\b").matcher(text).find(), f.getName() + " missing in clouds.glsl");
        }
        //The three noise periods divide vanilla's 3072-block period (the offset wraps there)
        assertEquals(0, CloudMath.PERIOD % CloudMath.REAL_NOISE_CELL, 1e-9);
        assertEquals(0, CloudMath.PERIOD % CloudMath.REAL_NOISE_CELL2, 1e-9);
        assertEquals(0, CloudMath.PERIOD % CloudMath.REAL_NOISE_CELL3, 1e-9);
        //The style's dispatch and the reflection's cheap march
        assertTrue(text.contains("vec4 farCloudMarch(vec3 origin, vec3 dir, float maxDist, vec3 lightDir, float jitter)"));
        assertTrue(text.contains("int samples = q >= 3 ? REAL_SAMPLES_HIGH : (q == 2 ? REAL_SAMPLES_MEDIUM : REAL_SAMPLES_LOW);"));
        assertTrue(text.contains("samples, lights, q >= 2 ? 3 : 1);"));
        assertArrayEquals(new int[]{CloudMath.REAL_SAMPLES_LOW, CloudMath.REAL_LIGHT_LOW, 1}, CloudMath.quality(1));
        assertArrayEquals(new int[]{CloudMath.REAL_SAMPLES_MEDIUM, CloudMath.REAL_LIGHT_MEDIUM, 3}, CloudMath.quality(2));
        assertArrayEquals(new int[]{CloudMath.REAL_SAMPLES_HIGH, CloudMath.REAL_LIGHT_HIGH, 3}, CloudMath.quality(3));
        String reflection = ShaderFiles.read("post/reflection.comp");
        assertTrue(reflection.contains("realMarch(origin, dir, HAZE_MAX_DISTANCE, cloudLightDir.xyz, jitter, REAL_REFLECT_SAMPLES, 0, 1)"));
        assertTrue(ShaderFiles.read("post/scene_copy.comp").contains("farCloudMarch(dir*near, dir, maxDist - near, cloudLightDir.xyz, interleavedGradientNoise(vec2(p)))"));
    }

    /** The noise map's channels are equalised (an even 0..1, so the coverage is the share it leaves) and the mask channel is kept. */
    @Test
    void theNoiseMapIsEqualisedAndKeepsTheMask() {
        //(every cloud kept: the default count removes some from the weather channel, theCountRemovesWholeCloudsAndKeepsTheRest)
        float[][] n = CloudMath.noiseMap(1, 2);
        for (float[] c : n) {
            int below = 0;
            float min = 1, max = 0;
            for (float v : c) {
                if (v < 0.3f) below++;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            assertEquals(0f, min, 1e-6f);
            assertEquals(1f, max, 1e-6f);
            assertEquals(0.3, below / (double) c.length, 0.01);
        }
        byte[] mask = new byte[CloudMath.MAP * CloudMath.MAP * 4];
        mask[0] = (byte) 255;
        byte[] with = CloudMath.withNoise(mask);
        assertEquals((byte) 255, with[0]);
        assertEquals((byte) 0, with[4], "r is the mask only");
        assertEquals(Math.round(CloudMath.noiseMap()[0][1] * 255), with[5] & 0xFF);
        assertEquals((byte) 0, mask[1], "a copy");
    }

    /** The noises and the realistic clouds repeat every 3072 blocks (vanilla's period: the offset wraps without a jump). */
    @Test
    void theRealisticCloudsRepeatWithTheOffset() {
        for (int c = 1; c < 4; c++) {
            assertEquals(CloudMath.noise(MAP, 17.3f, 905.1f, c), CloudMath.noise(MAP, 17.3f + 3072, 905.1f - 3072, c), 1e-5f);
        }
        //(the 6-block octave's lattice fraction loses some float precision 512 cells out)
        assertEquals(CloudMath.erosion(33.3f, 20.5f, 1201.7f, 3), CloudMath.erosion(33.3f + 3072, 20.5f, 1201.7f + 3072, 3), 1e-4f);
        CloudMath.Realistic a = layer(0.6f), b = new CloudMath.Realistic(200, CloudMath.REAL_THICKNESS, 8192, 1000 + 3072, 1500 - 3072, 0.6f);
        float[] dir = {0.6f, 0.5f, 0.62f};
        float l = (float) Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        for (int i = 0; i < 3; i++) dir[i] /= l;
        assertArrayEquals(CloudMath.realMarch(MAP, a, new float[3], dir, 1e6f, UP, 0.5f, 32, 3, 2), CloudMath.realMarch(MAP, b, new float[3], dir, 1e6f, UP, 0.5f, 32, 3, 2), 1e-3f);
    }

    /** Nothing outside the layer or at coverage 0; more coverage, more cloud; a tower is wider low than high (domes). */
    @Test
    void theDensityFollowsTheCoverageAndTheProfile() {
        assertEquals(0f, CloudMath.realDensity(MAP, layer(1), 10, 199, 10, 2));
        assertEquals(0f, CloudMath.realDensity(MAP, layer(1), 10, 200 + CloudMath.REAL_THICKNESS + 1, 10, 2));
        float last = -1;
        for (float cover : new float[]{0, 0.25f, 0.5f, 0.75f, 1}) {
            float share = share(layer(cover), 200 + 0.3f * CloudMath.REAL_THICKNESS);
            if (cover == 0) assertEquals(0f, share);
            assertTrue(share >= last, cover + ": " + share + " < " + last);
            last = share;
        }
        //(3b: the bases now lie from about 0.15 of the layer up, so the low probe sits at 0.3)
        assertTrue(share(layer(0.5f), 200 + 0.3f * CloudMath.REAL_THICKNESS) > share(layer(0.5f), 200 + 0.7f * CloudMath.REAL_THICKNESS) * 2,
                "the towers narrow toward their tops");
        assertTrue(share(layer(0.5f), 200 + 0.3f * CloudMath.REAL_THICKNESS) > 0.1f, "default coverage leaves visible cumulus");
    }

    /**
     * Session 3b (2026-11-09): the cumulus are rounded, not cut at one height. Per cloudy column (a 13 x 29 block grid over the period)
     * the lowest and highest block with density over 0.05: before 3b 94% of the bases lay within 3 blocks of the lowest (a knife-cut
     * base, the user's "sliced" clouds) and the tops spanned 15 to 56 blocks. Now the base rises toward a clump's thin edges and its
     * height varies from clump to clump, and the tops are taller and more varied.
     */
    @Test
    void theBasesAreRoundedAndVaryPerClump() {
        CloudMath.Realistic r = layer(CloudMath.REAL_COVER);
        java.util.List<Float> bases = new java.util.ArrayList<>(), tops = new java.util.ArrayList<>();
        double thinBase = 0, thickBase = 0;
        int thin = 0, thick = 0;
        for (int x = 0; x < 3072; x += 13) {
            for (int z = 0; z < 3072; z += 29) {
                float lo = -1, hi = -1;
                for (float y = 200; y < 200 + CloudMath.REAL_THICKNESS; y += 1) {
                    if (CloudMath.realDensity(MAP, r, x, y, z, 3) > 0.05f) {
                        if (lo < 0) lo = y - 200;
                        hi = y - 200;
                    }
                }
                if (lo < 0) continue;
                bases.add(lo);
                tops.add(hi);
                if (hi - lo < 20) {
                    thinBase += lo;
                    thin++;
                } else if (hi - lo > 50) {
                    thickBase += lo;
                    thick++;
                }
            }
        }
        java.util.Collections.sort(bases);
        java.util.Collections.sort(tops);
        int n = bases.size();
        assertTrue(n > 1200, "cloudy columns " + n);
        float lowest = bases.get(0);
        long cut = bases.stream().filter(b -> b <= lowest + 3).count();
        assertTrue(cut < 0.05 * n, "bases within 3 blocks of the lowest: " + cut + " of " + n);
        assertTrue(bases.get(n * 9 / 10) - bases.get(n / 10) >= 8, "base spread " + bases.get(n / 10) + ".." + bases.get(n * 9 / 10));
        assertTrue(tops.get(n * 9 / 10) - tops.get(n / 10) >= 35, "top spread " + tops.get(n / 10) + ".." + tops.get(n * 9 / 10));
        assertTrue(tops.get(n / 2) > 0.35f * CloudMath.REAL_THICKNESS, "median top " + tops.get(n / 2));
        assertTrue(thin > 100 && thick > 100, thin + " thin, " + thick + " thick columns");
        assertTrue(thinBase / thin > thickBase / thick + 3, "thin columns (a clump's edges) sit higher: " + thinBase / thin + " vs " + thickBase / thick);
    }

    /**
     * The erosion's octaves: the lumps (1 - |2n - 1|) are low inside rounded lumps and 1 along the creases between them (the cauliflower
     * edge); the light march's flat REAL_NOISE_MEAN is the medium and high qualities' mean erosion, between the low quality's and theirs.
     */
    @Test
    void theErosionsMeanIsTheLightMarchs() {
        java.util.Random rnd = new java.util.Random(5);
        double[] mean = new double[4];
        int n = 40000;
        for (int i = 0; i < n; i++) {
            float x = rnd.nextFloat() * 3072, y = rnd.nextFloat() * CloudMath.REAL_THICKNESS, z = rnd.nextFloat() * 3072;
            for (int o = 1; o <= 3; o++) {
                float e = CloudMath.erosion(x, y, z, o);
                assertTrue(e >= 0 && e <= 1, "" + e);
                mean[o] += e / n;
            }
        }
        assertEquals(mean[3], CloudMath.REAL_NOISE_MEAN, 0.03, "3 octaves " + mean[3]);
        assertTrue(mean[1] < CloudMath.REAL_NOISE_MEAN, "1 octave " + mean[1]);
        assertEquals(CloudMath.REAL_NOISE_MEAN, CloudMath.erosion(1, 2, 3, 0));
        assertEquals(1f, CloudMath.lumps(0.5f, 0, 0, 64) + Math.abs(2 * CloudMath.valueNoise(0.5f, 0, 0, 64) - 1), 1e-6f);
    }

    /** Texels of the weather channel a cumulus grows on at the default coverage (g above 1 - REAL_COVER). */
    private static boolean[] cloudy(float[] weather) {
        boolean[] c = new boolean[weather.length];
        for (int i = 0; i < weather.length; i++) c[i] = weather[i] > 1 - CloudMath.REAL_COVER;
        return c;
    }

    private static int count(boolean[] c) {
        int n = 0;
        for (boolean b : c) if (b) n++;
        return n;
    }

    /**
     * After 3b (2026-11-09): the count removes whole clouds and leaves the others as they were. Count 100% keeps COUNT_KEEP of the cloudy
     * texels (fewer clouds than 3b by default), 200% keeps them all (3b's map exactly), 0 none; the texels still cloudy at 100% almost all
     * keep 3b's value exactly, and fewer, not smaller, clouds means the separate patches drop by about as much. A dropped cloud is scaled by
     * COUNT_DROP, so only a coverage above 1 - COUNT_DROP (heavy rain, a high setting) brings part of it back.
     */
    @Test
    void theCountRemovesWholeCloudsAndKeepsTheRest() {
        float[] all = CloudMath.noiseMap(1, 2)[0], half = CloudMath.noiseMap(1, 1)[0], none = CloudMath.noiseMap(1, 0)[0];
        assertArrayEquals(CloudMath.noiseMap(1, 5)[0], all, "count 200% and above keep every clump");
        boolean[] ca = cloudy(all), ch = cloudy(half);
        assertEquals(0, count(cloudy(none)));
        double kept = count(ch) / (double) count(ca);
        assertEquals(CloudMath.COUNT_KEEP, kept, 0.12, "share of the cloudy texels kept at count 100%");
        int same = 0;
        for (int i = 0; i < ch.length; i++) {
            assertTrue(!ch[i] || ca[i], "the count only removes");
            if (ch[i] && Math.abs(half[i] - all[i]) < 1e-6f) same++;
        }
        assertEquals(count(ch), same, "kept clouds unchanged");
        int pa = patches(ca), ph = patches(ch);
        assertEquals(kept, ph / (double) pa, 0.2, "patches " + ph + " of " + pa);
        assertEquals(0.5f, CloudMath.countKept(1), 1e-6f);
        assertEquals(1f, CloudMath.countKept(CloudMath.COUNT_MAX / 100f), 1e-6f);
    }

    /** Separate cloudy patches on the map (4-connected, wrapping). */
    private static int patches(boolean[] c) {
        int n = CloudMath.MAP, found = 0;
        boolean[] seen = new boolean[c.length];
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        for (int i = 0; i < c.length; i++) {
            if (!c[i] || seen[i]) continue;
            found++;
            seen[i] = true;
            q.add(i);
            while (!q.isEmpty()) {
                int j = q.poll(), x = j % n, z = j / n;
                for (int k : new int[]{z * n + (x + 1) % n, z * n + (x + n - 1) % n, ((z + 1) % n) * n + x, ((z + n - 1) % n) * n + x}) {
                    if (c[k] && !seen[k]) {
                        seen[k] = true;
                        q.add(k);
                    }
                }
            }
        }
        return found;
    }

    /**
     * After 3b: the size scales each cloud and the gaps between them over the same share of the sky: at 200% there are about a quarter as
     * many cloudy patches (twice as wide each way), at 50% about four times as many; the share covered stays; the layer thickens with the
     * size ((1 + size) / 2), so bigger clouds are also taller.
     */
    @Test
    void theSizeScalesEachCloud() {
        boolean[] small = cloudy(CloudMath.noiseMap(0.5f, 2)[0]), normal = cloudy(CloudMath.noiseMap(1, 2)[0]), big = cloudy(CloudMath.noiseMap(2, 2)[0]);
        int ps = patches(small), pn = patches(normal), pb = patches(big);
        assertTrue(pb < 0.5 * pn && ps > 2 * pn, "patches at 50 / 100 / 200%: " + ps + " / " + pn + " / " + pb);
        for (boolean[] c : new boolean[][]{small, big}) assertEquals(count(normal), count(c), 0.02 * c.length);
        assertEquals(CloudMath.REAL_THICKNESS, CloudMath.realThickness(1), 1e-6f);
        assertEquals(1.5f * CloudMath.REAL_THICKNESS, CloudMath.realThickness(2), 1e-4f);
        assertEquals(0.75f * CloudMath.REAL_THICKNESS, CloudMath.realThickness(0.5f), 1e-4f);
        //The octaves stay whole cells over the map (seamless at the offset's wrap) and at least two texels a cell
        for (float size : new float[]{0.5f, 0.77f, 1, 1.3f, 2}) {
            for (int base : new int[]{16, 32, 64, 128}) {
                int cells = CloudMath.cells(base, size);
                assertTrue(cells >= 1 && cells <= CloudMath.MAP / 2, base + " at " + size + ": " + cells);
            }
        }
    }

    private static float share(CloudMath.Realistic r, float y) {
        int in = 0, n = 0;
        for (int x = 0; x < 3072; x += 23) {
            for (int z = 0; z < 3072; z += 23) {
                if (CloudMath.realDensity(MAP, r, x, y, z, 1) > 0) in++;
                n++;
            }
        }
        return in / (float) n;
    }

    /**
     * Self-shadowing: with the sun overhead the light march finds more cloud above a tower's base than above its top; the light per unit
     * of coverage stays bounded by twice the strongest phase (the octaves add at most 1 + 0.5 + 0.25) or the cirrus' forward lobe.
     */
    @Test
    void theBasesLieInTheTowersShadow() {
        CloudMath.Realistic r = layer(0.9f);
        double low = 0, high = 0;
        int n = 0;
        for (int x = 0; x < 3072; x += 41) {
            for (int z = 0; z < 3072; z += 43) {
                float yl = 200 + 0.25f * CloudMath.REAL_THICKNESS, yh = 200 + 0.5f * CloudMath.REAL_THICKNESS;
                if (CloudMath.realDensity(MAP, r, x, yl, z, 1) > 0 && CloudMath.realDensity(MAP, r, x, yh, z, 1) > 0) {
                    low += CloudMath.lightDepth(MAP, r, x, yl, z, UP, CloudMath.REAL_LIGHT_HIGH);
                    high += CloudMath.lightDepth(MAP, r, x, yh, z, UP, CloudMath.REAL_LIGHT_HIGH);
                    n++;
                }
            }
        }
        assertTrue(n > 50, "" + n);
        assertTrue(low > 1.5 * high, "base " + low / n + " top " + high / n);
        //The cirrus scatters further forward (ice) and takes more sky light than the cumulus
        float maxPhase = Math.max(CloudMath.mix(CloudMath.hg(1, CloudMath.REAL_FORWARD_G), CloudMath.hg(1, CloudMath.REAL_BACK_G), CloudMath.REAL_BACK) * 2,
                CloudMath.mix(CloudMath.hg(1, CloudMath.REAL_CIRRUS_G), 1, 0.5f));
        for (int x = 0; x < 600; x += 37) {
            for (float[] d : new float[][]{UP, DOWN, {0.8f, 0.6f, 0}}) {
                float[] m = CloudMath.realMarch(MAP, r, new float[]{x, d[1] < 0 ? 400 : 0, 50}, d, 1e6f, UP, 0.5f, 56, 4, 2);
                assertTrue(m[0] <= m[2] * maxPhase + 1e-4f && m[1] <= m[2] * CloudMath.REAL_CIRRUS_AMBIENT + 1e-4f, java.util.Arrays.toString(m));
            }
        }
    }

    /**
     * The reflection's cheap march (REAL_REFLECT_SAMPLES, one erosion octave, the flat depth instead of a light march) gives the clouds
     * about the coverage, sunlight and sky light the composite's medium march gives them along rising rays (2026-11-09: with the flat
     * depth at 6 the reflected clouds were a third as bright and vanished into the reflected sky).
     */
    @Test
    void theReflectionsCloudsMatchTheSkys() {
        CloudMath.Realistic r = new CloudMath.Realistic(192.33f + CloudMath.REAL_LIFT - 65, CloudMath.REAL_THICKNESS, 8192, 1000, 1500, CloudMath.REAL_COVER);
        double cc = 0, cf = 0, sc = 0, sf = 0, ac = 0, af = 0;
        int[] medium = CloudMath.quality(2);
        for (int i = 0; i < 400; i++) {
            double az = i * 0.0157, el = Math.toRadians(12 + (i % 7) * 3);
            float[] d = {(float) (Math.cos(el) * Math.sin(az)), (float) Math.sin(el), (float) (Math.cos(el) * Math.cos(az))};
            float[] cheap = CloudMath.realMarch(MAP, r, new float[]{0, -2, 30}, d, 1e6f, UP, 0.5f, CloudMath.REAL_REFLECT_SAMPLES, 0, 1);
            float[] full = CloudMath.realMarch(MAP, r, new float[]{0, -2, 30}, d, 1e6f, UP, 0.5f, medium[0], medium[1], medium[2]);
            cc += cheap[2];
            cf += full[2];
            sc += cheap[0];
            sf += full[0];
            ac += cheap[1];
            af += full[1];
        }
        assertEquals(1, cc / cf, 0.15, "coverage");
        assertEquals(1, (sc / cc) / (sf / cf), 0.2, "sunlight per coverage");
        assertEquals(1, (ac / cc) / (af / cf), 0.2, "sky light per coverage");
    }

    /** The phase is 1 for an even scatter and peaks toward the light (the silver lining); the cirrus is drawn only above the camera. */
    @Test
    void thePhaseAndTheCirrus() {
        assertEquals(1f, CloudMath.hg(0.3f, 0), 1e-6f);
        assertTrue(CloudMath.hg(1, CloudMath.REAL_FORWARD_G) > 5 * CloudMath.hg(0, CloudMath.REAL_FORWARD_G));
        CloudMath.Realistic none = layer(0.5f);
        //Looking up from far above the cumulus' top but below the cirrus at a shallow angle: the cirrus only
        float[] dir = {0.97f, 0.243f, 0};
        float total = 0;
        for (int z = 0; z < 3000; z += 101) {
            float[] m = CloudMath.realMarch(MAP, none, new float[]{0, CloudMath.REAL_THICKNESS + 250, z}, dir, 1e6f, UP, 0.5f, 32, 3, 2);
            total += m[2];
            assertTrue(m[2] <= CloudMath.REAL_CIRRUS_OPACITY + 1e-5f, "" + m[2]);
        }
        assertTrue(total > 0, "some cirrus");
        assertArrayEquals(new float[4], CloudMath.realMarch(MAP, none, new float[]{0, 200 + CloudMath.REAL_CIRRUS_HEIGHT + 10, 0}, dir, 1e6f, UP, 0.5f, 32, 3, 2),
                "looking up from above the cirrus: nothing");
    }

    private static PostProcessSettings settings(Consumer<VisualsConfig> edit) {
        VisualsConfig c = new VisualsConfig();
        edit.accept(c);
        return PostProcessSettings.from(c);
    }

    private static AtmosphereFrame frame(float rain) {
        return frame(rain, 1);
    }

    private static AtmosphereFrame frame(float rain, long serial) {
        return new AtmosphereFrame(serial, new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f(), 100, 63, 0.7f, 0.6f, 0.5f, false, false, 0, rain, 0, null,
                new float[]{121 / 255f, 167 / 255f, 1}, 256, null, new CloudFrame(192, 100, 200, new float[]{1, 1, 1}));
    }

    private static CloudUniforms.Values clouds(PostProcessSettings s, AtmosphereFrame f, long serial) {
        return CloudUniforms.compute(s, f, AtmosphereUniforms.compute(s, f, serial, true, false), serial, true, true, 16);
    }

    /**
     * The block: the vanilla shape keeps vanilla's layer and cloudOffset.zw 0 (as before); the realistic style lifts the base by
     * REAL_LIFT, is REAL_THICKNESS thick, has the quality plus the jitter phase in z (the phase 0 without TAA) and the coverage in w,
     * raised by rain; its light is the sky's colour straight up.
     */
    @Test
    void theBlockCarriesTheStyle() {
        float[] vanilla = clouds(settings(c -> {
        }), frame(0), 1).slots();
        assertEquals(192 + CloudMath.CLOUD_LIFT - 100, vanilla[0], 1e-4f);
        assertEquals(CloudMath.CLOUD_THICKNESS, vanilla[1]);
        assertEquals(0f, vanilla[6]);
        assertEquals(0f, vanilla[7]);
        for (int q = 0; q <= CloudMath.QUALITY_MAX; q++) {
            int quality = q;
            float[] v = clouds(settings(c -> {
                c.cloudStyle = CloudMath.STYLE_REALISTIC;
                c.cloudQuality = quality;
            }), frame(0, 7), 7).slots();
            assertEquals(192 + CloudMath.CLOUD_LIFT + CloudMath.REAL_LIFT - 100, v[0], 1e-4f);
            assertEquals(CloudMath.REAL_THICKNESS, v[1]);
            assertEquals(q + 1, (int) Math.floor(v[6]), "the quality is the whole part");
            assertEquals((7 * (double) CloudUniforms.REAL_PHASE_STEP) % 1, v[6] - Math.floor(v[6]), 1e-4);
            assertEquals(CloudMath.REAL_COVER, v[7], 1e-6f);
        }
        float[] noTaa = clouds(settings(c -> {
            c.cloudStyle = CloudMath.STYLE_REALISTIC;
            c.taa = false;
        }), frame(0, 7), 7).slots();
        assertEquals(2f, noTaa[6], "medium, no jitter phase without TAA");
        //After 3b: the cloud size thickens the layer
        float[] big = clouds(settings(c -> {
            c.cloudStyle = CloudMath.STYLE_REALISTIC;
            c.cloudSize = 200;
        }), frame(0), 1).slots();
        assertEquals(CloudMath.realThickness(2), big[1], 1e-4f);
        float[] rain = clouds(settings(c -> c.cloudStyle = CloudMath.STYLE_REALISTIC), frame(1), 1).slots();
        assertEquals(CloudMath.REAL_COVER * (1 + CloudUniforms.REAL_RAIN), rain[7], 1e-5f);
        float[] clear = clouds(settings(c -> {
            c.cloudStyle = CloudMath.STYLE_REALISTIC;
            c.cloudCoverage = 0;
        }), frame(0), 1).slots();
        assertEquals(0f, clear[7]);
        assertEquals(CloudMath.REAL_COVER * 2, CloudUniforms.realCover(2, 0, 0), 1e-6f);
        assertTrue(CloudUniforms.realCover(1, 1, 1) > CloudUniforms.realCover(1, 1, 0));
        //Far clouds off: the realistic style draws nothing and leaves vanilla's layer for vanilla's own clouds
        CloudUniforms.Values off = clouds(settings(c -> {
            c.cloudStyle = CloudMath.STYLE_REALISTIC;
            c.farClouds = false;
        }), frame(0), 1);
        assertFalse(off.on());
        assertEquals(CloudMath.CLOUD_THICKNESS, off.slots()[1]);
        assertEquals(0f, off.slots()[6]);
        //The map not uploaded yet: vanilla's clouds are up, so the block keeps vanilla's layer
        PostProcessSettings real = settings(c -> c.cloudStyle = CloudMath.STYLE_REALISTIC);
        AtmosphereFrame f0 = frame(0);
        CloudUniforms.Values noMap = CloudUniforms.compute(real, f0, AtmosphereUniforms.compute(real, f0, 1, true, false), 1, true, false, 16);
        assertFalse(noMap.on());
        assertEquals(192 + CloudMath.CLOUD_LIFT - 100, noMap.slots()[0], 1e-4f);
        assertEquals(CloudMath.CLOUD_THICKNESS, noMap.slots()[1]);
        //The sky light is the sky's colour straight up
        AtmosphereFrame f = frame(0);
        PostProcessSettings s = settings(c -> c.cloudStyle = CloudMath.STYLE_REALISTIC);
        AtmosphereUniforms.Values a = AtmosphereUniforms.compute(s, f, 1, true, false);
        float[] zenith = AtmosphereMath.skyColour(a.atmosphere(), UP);
        float[] amb = CloudUniforms.compute(s, f, a, 1, true, true, 16).light().ambient();
        for (int i = 0; i < 3; i++) assertEquals(CloudUniforms.REAL_AMBIENT * zenith[i], amb[i], 1e-6f);
    }

    /** A vanilla-shape march (three values) composites as before: its coverage is also its sky-lit weight. */
    @Test
    void aThreeValueMarchIsTheVanillaShape() {
        assertArrayEquals(new float[]{0.4f, 0.7f, 0.7f, 300}, CloudMath.four(new float[]{0.4f, 0.7f, 300}));
        float[] four = {0.4f, 0.2f, 0.7f, 300};
        assertTrue(CloudMath.four(four) == four);
        AtmosphereMath.CloudLight c = new AtmosphereMath.CloudLight(new float[]{1, 2, 3, 0}, new float[]{10, 20, 30, 0}, new float[]{0, 1, 0, 0});
        assertArrayEquals(new float[]{7.4f, 14.8f, 22.2f, 0.7f}, AtmosphereMath.cloudColour(c, new float[]{0.4f, 0.7f, 300}), 1e-5f);
        assertArrayEquals(new float[]{2.4f, 4.8f, 7.2f, 0.7f}, AtmosphereMath.cloudColour(c, four), 1e-5f);
    }

    /** The settings: a style choice under the far clouds' switch, coverage and quality under the style (greyed out with the vanilla shape). */
    @Test
    void theSettingsSitUnderTheFarClouds() {
        VisualsOptionLayout.Item style = item("cloud_style"), coverage = item("cloud_coverage"), quality = item("cloud_quality");
        assertEquals(VisualsOptionLayout.Kind.CHOICE, style.kind());
        assertEquals("far_clouds", style.requires());
        assertEquals("cloud_style", coverage.requires());
        assertEquals("cloud_style", quality.requires());
        assertEquals(CloudMath.STYLE_VANILLA, VisualsOptionLayout.defaultValue(style), "the vanilla shape stays the default");
        assertEquals(100, VisualsOptionLayout.defaultValue(coverage));
        assertEquals(1, VisualsOptionLayout.defaultValue(quality), "medium");
        assertEquals(PostProcessSettings.CLOUD_COVERAGE_MAX, coverage.max());
        VisualsConfig c = new VisualsConfig();
        VisualsOptionLayout.set(c, quality, 9);
        assertEquals(CloudMath.QUALITY_MAX, c.cloudQuality);
        VisualsOptionLayout.set(c, coverage, 150);
        assertEquals(1.5f, PostProcessSettings.from(c).cloudCoverage(), 1e-6f);
        //After 3b: size and count, also under the style, 100 each by default
        VisualsOptionLayout.Item size = item("cloud_size"), count = item("cloud_count");
        assertEquals("cloud_style", size.requires());
        assertEquals("cloud_style", count.requires());
        assertEquals(100, VisualsOptionLayout.defaultValue(size));
        assertEquals(100, VisualsOptionLayout.defaultValue(count));
        assertEquals(CloudMath.SIZE_MIN, size.min());
        assertEquals(CloudMath.SIZE_MAX, size.max());
        assertEquals(CloudMath.COUNT_MAX, count.max());
        VisualsOptionLayout.set(c, size, 10);
        VisualsOptionLayout.set(c, count, 999);
        assertEquals(CloudMath.SIZE_MIN / 100f, PostProcessSettings.from(c).cloudSize(), 1e-6f);
        assertEquals(CloudMath.COUNT_MAX / 100f, PostProcessSettings.from(c).cloudCount(), 1e-6f);
    }

    private static VisualsOptionLayout.Item item(String key) {
        return VisualsOptionLayout.PAGES.stream().flatMap(p -> p.blocks().stream()).flatMap(b -> b.items().stream()).filter(i -> i.key().equals(key))
                .findFirst().orElseThrow();
    }

    /** The cumulus density before the erosion (realDensity's d), 0 where it returns early. */
    private static float uneroded(CloudMath.Realistic r, float x, float y, float z) {
        float h = (y - r.base()) / r.thickness(), f = Math.min(r.cover(), 1);
        if (!(h > 0 && h < 1) || !(f > 0)) return 0;
        float mx = r.offsetX() + x, mz = r.offsetZ() + z;
        float s = (CloudMath.noise(MAP, mx, mz, 1) - 1 + f) / f;
        if (s <= 0) return 0;
        float tower = CloudMath.noise(MAP, mx, mz, 3), lift = CloudMath.REAL_BASE_LIFT * (1 - tower);
        float hp = (h - lift) / ((1 - lift) * CloudMath.mix(CloudMath.REAL_TOP_MIN, 1, s) * CloudMath.mix(1 - CloudMath.REAL_TOWER, 1, tower));
        if (!(hp > 0 && hp < 1)) return 0;
        return s * smooth(0, CloudMath.REAL_BOTTOM, hp) * (1 - smooth(CloudMath.REAL_ROUND, 1, hp));
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    /**
     * Session 7 (2026-11-10): realErosionFor skips the finer octaves where the coarser ones already prove the density 0. Over a grid through
     * the layer at every quality the density is the full sum's exactly, and the shortcut fires on a good share of the cloudy samples
     * (the fringes it saves the noise of).
     */
    @Test
    void theErosionShortcutGivesTheFullSumsDensity() {
        CloudMath.Realistic r = layer(CloudMath.REAL_COVER * 2);
        for (int octaves = 0; octaves <= 3; octaves++) {
            int cloudy = 0, skipped = 0;
            for (int x = 0; x < 3072; x += 37) {
                for (int z = 0; z < 3072; z += 41) {
                    for (float y = 201; y < 200 + CloudMath.REAL_THICKNESS; y += 7.3f) {
                        float d = uneroded(r, x, y, z);
                        if (d <= 0) {
                            assertEquals(0f, CloudMath.realDensity(MAP, r, x, y, z, octaves));
                            continue;
                        }
                        //session 3b's sum of every octave
                        float e = CloudMath.REAL_ERODE * CloudMath.erosion(r.offsetX() + x, y - r.base(), r.offsetZ() + z, octaves);
                        float full = Math.max((d - e) / (1 - e), 0);
                        assertEquals(full, CloudMath.realDensity(MAP, r, x, y, z, octaves), 0f, "at " + x + " " + y + " " + z + " octaves " + octaves);
                        cloudy++;
                        if (CloudMath.erosionFor(r.offsetX() + x, y - r.base(), r.offsetZ() + z, octaves, d) < 0) skipped++;
                    }
                }
            }
            System.out.println("erosion shortcut, octaves " + octaves + ": " + skipped + " of " + cloudy + " samples");
            if (octaves >= 2) assertTrue(skipped > cloudy / 20, "octaves " + octaves + ": skipped " + skipped + " of " + cloudy);
            if (octaves < 2) assertEquals(0, skipped);
        }
    }
}
