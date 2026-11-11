package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
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

/** Session 11 (2026-11-10): the anime clouds (clouds.glsl's anime part, CloudMath's mirror, CloudUniforms). */
class AnimeCloudTest {
    private static final byte[] MAP = CloudMath.withNoise(new byte[CloudMath.MAP * CloudMath.MAP * 4]);
    private static final float[] UP = {0, 1, 0};

    private static CloudMath.Realistic layer(float cover) {
        return new CloudMath.Realistic(200, CloudMath.ANIME_THICKNESS, 8192, 1000, 1500, cover);
    }

    /** Every ANIME_ constant of clouds.glsl is CloudMath's field of the same name and value, and every such field is in the shader. */
    @Test
    void everyShaderConstantEqualsTheMirrorsField() throws Exception {
        String text = ShaderFiles.read("post/clouds.glsl");
        Matcher m = Pattern.compile("(?m)^\\s*const\\s+(float|int)\\s+(ANIME_\\w+)\\s*=\\s*([-+0-9.eE]+)\\s*;").matcher(text);
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
            if (!Modifier.isStatic(f.getModifiers()) || !f.getName().startsWith("ANIME_") || f.getName().equals("ANIME_LIFT") || f.getName().equals("ANIME_THICKNESS")) continue;
            assertTrue(Pattern.compile("const\\s+(float|int)\\s+" + f.getName() + "\\b").matcher(text).find(), f.getName() + " missing in clouds.glsl");
        }
        //The puffs' periods divide vanilla's 3072-block period (the offset wraps there)
        assertEquals(0, CloudMath.PERIOD % CloudMath.ANIME_PUFF_CELL, 1e-9);
        assertEquals(0, CloudMath.PERIOD % CloudMath.ANIME_PUFF_CELL2, 1e-9);
        //The style's dispatch: the quality after ANIME_Z; the realistic style below it; the reflection's short march
        assertTrue(text.contains("return cloudOffset.z >= 1.0 && cloudOffset.z < float(ANIME_Z);"));
        assertTrue(text.contains("int samples = a >= 2 ? ANIME_SAMPLES_HIGH : (a == 1 ? ANIME_SAMPLES_MEDIUM : ANIME_SAMPLES_LOW);"));
        assertEquals(CloudMath.ANIME_SAMPLES_LOW, CloudMath.animeSamples(0));
        assertEquals(CloudMath.ANIME_SAMPLES_MEDIUM, CloudMath.animeSamples(1));
        assertEquals(CloudMath.ANIME_SAMPLES_HIGH, CloudMath.animeSamples(2));
        String reflection = ShaderFiles.read("post/reflection.comp");
        assertTrue(reflection.contains("animeMarch(origin, dir, HAZE_MAX_DISTANCE, cloudLightDir.xyz, jitter, REAL_REFLECT_SAMPLES)"));
        //Left out of the lean reflection build with the realistic march (VkPostProcess picks the lean one when Values.realistic() is false)
        int lean = reflection.indexOf("#ifndef REFLECT_LEAN"), anime = reflection.indexOf("if (cloudAnime())"), end = reflection.indexOf("#endif", lean);
        assertTrue(lean >= 0 && lean < anime && anime < end, "the anime branch sits in the non-lean part");
    }

    /**
     * The block: the anime style lifts the base by ANIME_LIFT, is animeThickness thick, has ANIME_Z plus the quality and the jitter phase
     * in z and the realistic coverage in w; its light is the sky's sunlight (boosted when low) and the sky's colour above. The default
     * (the vanilla shape) is untouched.
     */
    @Test
    void theBlockCarriesTheStyle() {
        float[] vanilla = clouds(settings(c -> {
        }), frame(0, 1), 1).slots();
        assertEquals(0f, vanilla[6], "the default style is still the vanilla shape");
        for (int q = 0; q <= CloudMath.QUALITY_MAX; q++) {
            int quality = q;
            CloudUniforms.Values v = clouds(settings(c -> {
                c.cloudStyle = CloudMath.STYLE_ANIME;
                c.cloudQuality = quality;
            }), frame(0, 7), 7);
            float[] s = v.slots();
            assertTrue(v.on());
            assertEquals(192 + CloudMath.CLOUD_LIFT + CloudMath.ANIME_LIFT - 100, s[0], 1e-4f);
            assertEquals(CloudMath.ANIME_THICKNESS, s[1], 1e-6f);
            assertEquals(CloudMath.ANIME_Z + q, (int) Math.floor(s[6]), "the quality after ANIME_Z");
            assertEquals((7 * (double) CloudUniforms.REAL_PHASE_STEP) % 1, s[6] - Math.floor(s[6]), 1e-4);
            assertEquals(CloudMath.REAL_COVER, s[7], 1e-6f);
            assertTrue(v.realistic(), "a marched style: the full reflection build");
        }
        assertEquals(CloudMath.animeThickness(2), clouds(settings(c -> {
            c.cloudStyle = CloudMath.STYLE_ANIME;
            c.cloudSize = 200;
        }), frame(0, 1), 1).slots()[1], 1e-4f);
        assertEquals(CloudMath.REAL_COVER * (1 + CloudUniforms.REAL_RAIN), clouds(settings(c -> c.cloudStyle = CloudMath.STYLE_ANIME), frame(1, 1), 1).slots()[7], 1e-5f);
        CloudUniforms.Values off = clouds(settings(c -> {
            c.cloudStyle = CloudMath.STYLE_ANIME;
            c.farClouds = false;
        }), frame(0, 1), 1);
        assertFalse(off.on());
        assertEquals(CloudMath.CLOUD_THICKNESS, off.slots()[1], "far clouds off: vanilla's layer for vanilla's own clouds");
        //The light
        PostProcessSettings s = settings(c -> c.cloudStyle = CloudMath.STYLE_ANIME);
        AtmosphereFrame f = frame(0, 1);
        AtmosphereUniforms.Values a = AtmosphereUniforms.compute(s, f, 1, true, false);
        float[] zenith = AtmosphereMath.skyColour(a.atmosphere(), UP), sun = a.atmosphere().sunLight();
        AtmosphereMath.CloudLight light = CloudUniforms.compute(s, f, a, 1, true, true, 16).light();
        float boost = CloudUniforms.animeBoost(sun, 0);
        for (int i = 0; i < 3; i++) {
            assertEquals(CloudUniforms.ANIME_AMBIENT * zenith[i], light.ambient()[i], 1e-6f);
            assertEquals(CloudUniforms.ANIME_LIGHT * boost * sun[i], light.lit()[i], 1e-5f);
        }
    }

    /** A low sun's light is raised toward ANIME_BOOST_LUMA keeping its hue, at most ANIME_BOOST_MAX times; a bright one is left alone. */
    @Test
    void aLowSunStaysBright() {
        assertEquals(1f, CloudUniforms.animeBoost(new float[]{1, 1, 1}));
        assertEquals(1f, CloudUniforms.animeBoost(new float[]{0, 0, 0}));
        float[] low = {0.5f, 0.3f, 0.15f};
        float b = CloudUniforms.animeBoost(low);
        float luma = 0.2126f * low[0] + 0.7152f * low[1] + 0.0722f * low[2];
        assertEquals(CloudUniforms.ANIME_BOOST_LUMA, luma * b, 1e-5f);
        assertEquals(CloudUniforms.ANIME_BOOST_MAX, CloudUniforms.animeBoost(new float[]{0.01f, 0.01f, 0.01f}), 1e-6f, "the moon stays dark");
        //Rain takes the boost away: the light it dims stays dim
        assertEquals(b, CloudUniforms.animeBoost(low, 0), 1e-6f);
        assertEquals(1f, CloudUniforms.animeBoost(low, 1), 1e-6f);
        assertTrue(CloudUniforms.animeBoost(low, 0.5f) > 1 && CloudUniforms.animeBoost(low, 0.5f) < b);
    }

    /**
     * The pillows: wherever a cloud stands its bottom is at most ANIME_RIM of the layer and its top at least that (they meet at the
     * outline), most of the base lies flat on the layer's floor, the tops vary, and the shape repeats every PERIOD blocks.
     */
    @Test
    void thePillowsHaveFlatBasesAndRoundedTops() {
        CloudMath.Realistic r = layer(CloudMath.REAL_COVER);
        float rim = CloudMath.ANIME_RIM * r.thickness();
        int cloudy = 0, flat = 0;
        float lo = Float.MAX_VALUE, hi = 0;
        for (int x = 0; x < 3072; x += 7) {
            for (int z = 0; z < 3072; z += 11) {
                float[] bt = CloudMath.animeShape(MAP, r, x, z);
                if (bt[1] <= bt[0]) continue;
                cloudy++;
                assertTrue(bt[0] >= 0 && bt[0] <= rim + 1e-4f, "bottom " + bt[0]);
                assertTrue(bt[1] >= rim - 1e-4f && bt[1] <= r.thickness() + 1e-4f, "top " + bt[1]);
                if (bt[0] < 0.5f) flat++;
                lo = Math.min(lo, bt[1]);
                hi = Math.max(hi, bt[1]);
                assertArrayEquals(bt, CloudMath.animeShape(MAP, r, x + (float) CloudMath.PERIOD, z - 2 * (float) CloudMath.PERIOD), 0.1f);
            }
        }
        double share = cloudy / (3072.0 / 7 * 3072 / 11);
        assertTrue(share > 0.08 && share < 0.4, "sky share " + share);
        assertTrue(flat > 0.5 * cloudy, "flat bases " + flat + " of " + cloudy);
        assertTrue(hi - lo > 0.4f * r.thickness(), "the tops vary " + lo + ".." + hi);
        //No coverage, no cloud; more coverage, more cloud
        assertEquals(0, count(layer(0)));
        assertTrue(count(layer(0.6f)) > count(r));
    }

    /** The height bound skips the puffs only where the full shape also says outside: inside or not, the same answer everywhere. */
    @Test
    void theHeightBoundNeverChangesTheAnswer() {
        CloudMath.Realistic r = layer(CloudMath.REAL_COVER * 1.5f);
        java.util.Random rnd = new java.util.Random(11);
        int inside = 0;
        for (int i = 0; i < 200000; i++) {
            float x = rnd.nextFloat() * 3072, z = rnd.nextFloat() * 3072, h = rnd.nextFloat() * r.thickness();
            float[] full = CloudMath.animeShape(MAP, r, x, z), bounded = CloudMath.animeShape(MAP, r, x, z, h);
            boolean a = h > full[0] && h < full[1], b = h > bounded[0] && h < bounded[1];
            assertEquals(a, b, x + " " + z + " " + h);
            if (a) inside++;
        }
        assertTrue(inside > 1000, "inside " + inside);
    }

    private static int count(CloudMath.Realistic r) {
        int n = 0;
        for (int x = 0; x < 3072; x += 13) {
            for (int z = 0; z < 3072; z += 17) {
                float[] bt = CloudMath.animeShape(MAP, r, x, z);
                if (bt[1] > bt[0]) n++;
            }
        }
        return n;
    }

    /** The cloud count and size reach the anime clouds through the noise map: fewer clouds at count 50, bigger patches at size 200. */
    @Test
    void theCountAndSizeShapeTheAnimeClouds() {
        CloudMath.Realistic r = layer(CloudMath.REAL_COVER);
        byte[] fewer = CloudMath.withNoise(new byte[CloudMath.MAP * CloudMath.MAP * 4], 1, 0.5f);
        int def = 0, few = 0;
        for (int x = 0; x < 3072; x += 13) {
            for (int z = 0; z < 3072; z += 17) {
                float[] a = CloudMath.animeShape(MAP, r, x, z), b = CloudMath.animeShape(fewer, r, x, z);
                if (a[1] > a[0]) def++;
                if (b[1] > b[0]) few++;
            }
        }
        assertTrue(few < 0.8 * def, few + " vs " + def);
    }

    /**
     * The light is soft (no bands): it rises smoothly from ANIME_SHADOW facing away to 1 facing the light, creases are darker, the sky
     * light dims toward the base, and the rim brightens the silhouette.
     */
    @Test
    void theLightIsSoft() {
        float[] sun = {0.6f, 0.8f, 0}, view = {0, 1, 0};
        float away = CloudMath.animeLight(new float[]{0, -1, 0}, 1, 1, view, sun)[0];
        float toward = CloudMath.animeLight(sun, 1, 1, sun, sun)[0];//seen face on: no rim
        assertEquals(CloudMath.ANIME_SHADOW, away, 1e-4f);
        assertEquals(1f, toward, 1e-4f);
        //Smooth: turning the normal in small steps never jumps by more than a few percent
        float prev = Float.NaN;
        for (int i = 0; i <= 180; i++) {
            double a = Math.toRadians(i);
            float[] n = {(float) Math.cos(a) * 0.6f - (float) Math.sin(a) * 0.8f, (float) Math.cos(a) * 0.8f + (float) Math.sin(a) * 0.6f, 0};
            float v = CloudMath.animeLight(n, 1, 1, view, sun)[0];
            if (!Float.isNaN(prev)) assertTrue(Math.abs(v - prev) < 0.04f, "step at " + i + ": " + prev + " -> " + v);
            prev = v;
        }
        assertEquals(1 - CloudMath.ANIME_CREASE_SHADE, CloudMath.animeLight(sun, 0, 1, sun, sun)[0], 1e-4f, "a crease is shaded");
        assertEquals(CloudMath.ANIME_AMBIENT_LOW, CloudMath.animeLight(sun, 1, 0, view, sun)[1], 1e-4f, "the base gets less sky light");
        assertEquals(1f, CloudMath.animeLight(sun, 1, 1, view, sun)[1], 1e-4f);
        float[] side = {0, 0, 1};
        float rim = CloudMath.animeLight(side, 1, 1, new float[]{1, 0, 0}, sun)[0] - CloudMath.animeLight(side, 1, 1, new float[]{0, 0, -1}, sun)[0];
        assertTrue(rim > 0.1f && rim <= CloudMath.ANIME_RIM_GAIN + 1e-4f, "rim " + rim);
        assertEquals(CloudMath.ANIME_FLAT, CloudMath.animeLight(new float[]{0, -1, 0}, 1, 1, view, new float[]{1, 0, 0})[0], 1e-4f, "the light at the horizon");
    }

    /**
     * The march: the clouds are opaque inside and soft at their edges (some rays partly covered), its distance lies near the first surface
     * a fine walk finds, and nothing is drawn past ANIME_FADE_FAR: the layer fades out before it ends instead of stopping at a line.
     */
    @Test
    void theMarchIsSoftAndFadesOut() {
        CloudMath.Realistic r = new CloudMath.Realistic(120, CloudMath.ANIME_THICKNESS, 8192, 1000, 1500, CloudMath.REAL_COVER);
        int hits = 0, solid = 0, soft = 0, near = 0;
        for (int i = 0; i < 400; i++) {
            double az = i * 0.0317, el = Math.toRadians(20 + (i % 9) * 6);
            float[] d = {(float) (Math.cos(el) * Math.sin(az)), (float) Math.sin(el), (float) (Math.cos(el) * Math.cos(az))};
            float[] m = CloudMath.animeMarch(MAP, r, new float[3], d, 1e6f, UP, 0.5f, CloudMath.ANIME_SAMPLES_HIGH);
            if (!(m[2] > 0.01f)) continue;
            hits++;
            if (m[2] > 0.95f) solid++;
            else soft++;
            assertTrue(m[1] <= m[2] + 1e-5f, "sky-lit weight within the coverage");
            float fine = -1;
            for (float t = 120 / d[1]; t < (120 + CloudMath.ANIME_THICKNESS) / d[1]; t += 0.25f) {
                if (CloudMath.animeInside(MAP, r, d[0] * t, d[1] * t, d[2] * t)) {
                    fine = t;
                    break;
                }
            }
            float at = m[3] / CloudMath.ANIME_HAZE;//the distance is the haze's share of it
            if (fine >= 0 && at >= fine - 1 && at < fine + 3 * CloudMath.ANIME_SOFT + 6) near++;
        }
        assertTrue(hits > 40, "hits " + hits);
        assertTrue(solid > 0.5 * hits, "mostly opaque: " + solid + " of " + hits);
        assertTrue(soft > 0.03 * hits, "soft edges: " + soft + " of " + hits);
        assertTrue(near > 0.85 * hits, near + " of " + hits + " near the first surface");
        assertArrayEquals(new float[4], CloudMath.animeMarch(MAP, layer(0), new float[3], UP, 1e6f, UP, 0.5f, 40), 1e-6f);
        //The fade: full to ANIME_FADE_NEAR, none from ANIME_FADE_FAR; a grazing ray toward the horizon covers nothing beyond it
        assertEquals(1f, CloudMath.animeFade(CloudMath.ANIME_FADE_NEAR), 1e-6f);
        assertEquals(0f, CloudMath.animeFade(CloudMath.ANIME_FADE_FAR), 1e-6f);
        CloudMath.Realistic full = new CloudMath.Realistic(60, CloudMath.ANIME_THICKNESS, 8192, 1000, 1500, 1f);
        for (int i = 0; i < 50; i++) {
            double az = i * 0.13, el = Math.toRadians(0.3 + i * 0.02);
            float[] d = {(float) (Math.cos(el) * Math.sin(az)), (float) Math.sin(el), (float) (Math.cos(el) * Math.cos(az))};
            float[] m = CloudMath.animeMarch(MAP, full, new float[3], d, 1e6f, UP, 0.5f, CloudMath.ANIME_SAMPLES_HIGH);
            assertTrue(m[3] / CloudMath.ANIME_HAZE * Math.cos(el) <= CloudMath.ANIME_FADE_FAR + 1, "drawn at " + m[3]);
        }
    }

    private static PostProcessSettings settings(Consumer<VisualsConfig> edit) {
        VisualsConfig c = new VisualsConfig();
        edit.accept(c);
        return PostProcessSettings.from(c);
    }

    private static AtmosphereFrame frame(float rain, long serial) {
        return new AtmosphereFrame(serial, new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f(), 100, 63, 0.7f, 0.6f, 0.5f, false, false, 0, rain, 0, null,
                new float[]{121 / 255f, 167 / 255f, 1}, 256, null, new CloudFrame(192, 100, 200, new float[]{1, 1, 1}));
    }

    private static CloudUniforms.Values clouds(PostProcessSettings s, AtmosphereFrame f, long serial) {
        return CloudUniforms.compute(s, f, AtmosphereUniforms.compute(s, f, serial, true, false), serial, true, true, 16);
    }
}
