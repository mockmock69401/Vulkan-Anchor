package net.vulkanmod.visuals.config;

import net.vulkanmod.visuals.render.SceneCompositeMath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 14 follow-up: Half-resolution Weather & Sky (the shader weather, the far clouds and the aurora at half resolution). */
class HalfResEffectsTest {
    @Test
    void offByDefaultOnBelowHigh() {
        assertFalse(new VisualsConfig().halfResEffects);
        assertEquals(VisualsPresets.HIGH, VisualsPresets.detect(new VisualsConfig()), "a fresh file is still High");
        for (int level = VisualsPresets.POTATO; level < VisualsPresets.LEVELS; level++) {
            VisualsConfig c = new VisualsConfig();
            VisualsPresets.apply(c, level);
            assertEquals(level <= VisualsPresets.MEDIUM, c.halfResEffects, "level " + level);
            assertEquals(level, VisualsPresets.detect(c));
        }
        assertEquals(VisualsOptionLayout.Kind.SWITCH, VisualsOptionLayout.item("half_res_effects").kind());
    }

    @Test
    void aLevelSavedBeforeItKeepsItsLevel(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("v.json");
        for (int level = VisualsPresets.POTATO; level < VisualsPresets.LEVELS; level++) {
            VisualsConfig c = new VisualsConfig();
            VisualsPresets.apply(c, level);
            c.halfResEffects = false;//the field did not exist
            c.revision = 16;
            c.write(f);
            String text = Files.readString(f).replace("\"halfResEffects\": false,", "").replace(",\n  \"halfResEffects\": false", "");
            Files.writeString(f, text);
            VisualsConfig back = VisualsConfig.read(f);
            assertEquals(level, back.preset, "level " + level + " stays");
            assertEquals(level <= VisualsPresets.MEDIUM, back.halfResEffects, "level " + level);
            assertEquals(VisualsConfig.currentRevision(), back.revision);
        }
        //Custom keeps it off (as before)
        VisualsConfig custom = new VisualsConfig();
        custom.exposure = 130;
        custom.preset = VisualsPresets.CUSTOM;
        custom.revision = 16;
        custom.write(f);
        assertFalse(VisualsConfig.read(f).halfResEffects);
    }

    @Test
    void theUpsampleWeighsTexelsByTheirDistance() {
        assertTrue(VisualsConfig.currentRevision() >= VisualsConfig.HALF_RES_REVISION);
        assertEquals(0.25f, SceneCompositeMath.halfSkyWeight(0.25f, 100, 100), 1e-6f, "same distance: bilinear");
        assertTrue(SceneCompositeMath.halfSkyWeight(0.25f, 1e6f, 40) < 0.25f / 1000, "the sky behind a hill's edge barely counts on the hill");
        assertEquals(0.25f, SceneCompositeMath.halfSkyWeight(0.25f, 1e6f, 1e6f), 1e-6f, "the sky: both held as HALF_SKY_FAR");
        assertEquals(12, SceneCompositeMath.HALF_SKY_AURORA_STEPS);
    }

    /**
     * 2026-11-25: the half-size clouds accumulate over frames: cloudPrevClip takes a direction back to where the last frame showed it,
     * the two images swap each frame the pass runs, the history keeps its contents and counts only after a frame the pass ran.
     */
    @Test
    void theHalfSizeCloudsKeepTheLastFrames() throws Exception {
        org.joml.Matrix4f projection = new org.joml.Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 1000);
        org.joml.Matrix4f invProjection = new org.joml.Matrix4f(projection).invert();
        org.joml.Matrix4f invView = new org.joml.Matrix4f().rotationYXZ(0.5f, -0.3f, 0);
        //a pixel's view ray as viewRay takes it (two points on it), into the world
        org.joml.Vector3f near = invProjection.transformProject(new org.joml.Vector3f(-0.4f, 0.4f, 0.5f));
        org.joml.Vector3f far = invProjection.transformProject(new org.joml.Vector3f(-0.4f, 0.4f, 0.9f));
        org.joml.Vector3f dir = invView.transformDirection(far.sub(near).normalize());
        org.joml.Vector4f clip = SceneCompositeMath.prevClip(invProjection, invView).transform(new org.joml.Vector4f(dir, 0));
        assertTrue(clip.w > 0);
        assertEquals(-0.4f, clip.x / clip.w, 1e-4f, "a still camera: the same place");
        assertEquals(0.4f, clip.y / clip.w, 1e-4f);
        //the camera turned since: the direction shows elsewhere in the last frame
        org.joml.Vector4f turned = SceneCompositeMath.prevClip(invProjection, new org.joml.Matrix4f().rotationYXZ(0.6f, -0.3f, 0)).transform(new org.joml.Vector4f(dir, 0));
        assertTrue(Math.abs(turned.x / turned.w + 0.4f) > 0.05f);
        assertTrue(SceneCompositeMath.HALF_SKY_KEEP > 0.5f && SceneCompositeMath.HALF_SKY_KEEP < 1);
        String post = Files.readString(Path.of("src/main/java/net/vulkanmod/visuals/render/VkPostProcess.java")).replace("\r\n", "\n");
        assertTrue(post.contains("boolean history = this.skyHalfOn && this.skyHalfWritten && this.lastInvProjection != null;"));
        assertTrue(post.contains("            this.skyHalfCloud = this.skyHalfHistory;\n            this.skyHalfHistory = last;"));
        assertTrue(post.contains("this.skyHalfHistory.transition(cmd, VK_IMAGE_LAYOUT_GENERAL,"), "not from UNDEFINED: its contents kept");
        String copy = Files.readString(Path.of("src/main/resources/assets/vulkanmod/visuals/shaders/post/scene_copy.comp"));
        assertTrue(copy.contains("layout(binding = 29, rgba16f) uniform readonly restrict image2D skyHalfHistory;"));
        assertTrue(copy.contains("layout(location = 84) uniform mat4 cloudPrevClip;"));
        assertTrue(copy.contains("m = mix(m, last, HALF_SKY_KEEP);"));
    }

    /** 2026-11-25: the sky pixel takes the far clouds first and marches the aurora only where they leave it showing. */
    @Test
    void theAuroraIsSkippedBehindCloudThatHidesIt() throws Exception {
        String copy = Files.readString(Path.of("src/main/resources/assets/vulkanmod/visuals/shaders/post/scene_copy.comp")).replace("\r\n", "\n");
        assertTrue(copy.contains("const float AURORA_CLOUD_HIDDEN = 0.995;"));
        assertTrue(copy.contains("        vec4 cloud = farClouds(a, dir, HAZE_MAX_DISTANCE, p);\n        if (cloud.a < AURORA_CLOUD_HIDDEN) {\n"
                + "            sky += auroraScreen(dir, p);\n        }\n        return vec4((sky*(1.0 - cloud.a) + cloud.rgb)*underwaterExposure(), 0.0);"));
        assertTrue(copy.contains("if (!scene && auroraParams.x > 0.0 && halfParams.y > 0.5 && m.z < AURORA_CLOUD_HIDDEN) {"), "the half-size pass too");
    }

    @Test
    void theRendererRunsTheHalfSizePassesOnlyWithTheSetting() throws Exception {
        String post = Files.readString(Path.of("src/main/java/net/vulkanmod/visuals/render/VkPostProcess.java"));
        assertTrue(post.contains("this.halfOn = VisualsConfig.get().halfResEffects;"));
        //2026-11-25: the far clouds always from the half-size pass, the aurora by halfAurora; the pass only while either is drawn
        assertTrue(post.contains("this.skyHalfOn = this.copySkyHalf != null && (clouds.on() || halfAurora);"));
        assertTrue(post.contains("this.copyUniforms.putVec4(COPY_HALF_SLOT, this.skyHalfOn ? 1 : 0, this.skyHalfOn && halfAurora ? 1 : 0, history ? 1 : 0, 0);"));
        assertTrue(post.contains("return VisualsConfig.get().halfResEffects && this.weatherUp != null;"));
        String copy = Files.readString(Path.of("src/main/resources/assets/vulkanmod/visuals/shaders/post/scene_copy.comp"));
        assertTrue(copy.contains("layout(location = 61) uniform vec4 halfParams;"));
        String renderer = Files.readString(Path.of("src/main/java/net/vulkanmod/visuals/render/VisualsRenderer.java"));
        assertTrue(renderer.contains("this.post.halfWeather() ? (height + 1) / 2 : height"), "the streaks' width for the half-size pixel");
        for (String lang : new String[]{"en_us", "ko_kr"}) {
            String json = Files.readString(Path.of("src/main/resources/assets/vulkanmod/lang/" + lang + ".json"));
            assertTrue(json.contains("\"vulkanmod.visuals.half_res_effects\"") && json.contains("\"vulkanmod.visuals.half_res_effects.tooltip\""), lang);
        }
    }
}
