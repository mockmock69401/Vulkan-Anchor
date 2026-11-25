package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-25 (session 16d; the user: the mirror in the ice wobbled while walking, gone with View Bobbing off): vanilla builds the level
 * projection with the view bobbing in it (P T Rz Rx, GameRenderer.bobView), so the eye is off the origin of the view space the reflection
 * pass rebuilds; ReflectionUniforms.eyeInView finds it and reflection.comp aims every mirror's ray from it (eyeRay).
 */
class ReflectionEyeTest {
    private static final String COMP = ShaderFiles.read("post/reflection.comp").replace("\r\n", "\n");

    private static Matrix4f perspective() {
        return new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1024f);
    }

    /** GameRenderer.bobView on a projection: translate (sin f pi bob 0.5, -|cos f pi bob|, 0), roll sin 3 bob degrees, pitch |cos| 5 bob. */
    private static Matrix4f bobbed(float walk, float bob) {
        float s = (float) Math.sin(walk * Math.PI), c = (float) Math.cos(walk * Math.PI);
        return perspective().translate(s * bob * 0.5f, -Math.abs(c * bob), 0)
                .rotateZ((float) Math.toRadians(s * bob * 3))
                .rotateX((float) Math.toRadians(Math.abs((float) Math.cos(walk * Math.PI - 0.2f) * bob) * 5));
    }

    @Test
    void aPlainPerspectiveHasItsEyeAtTheOrigin() {
        assertArrayEquals(new float[3], ReflectionUniforms.eyeInView(perspective()), 1e-5f);
    }

    @Test
    void theViewBobbingMovesTheEyeOffTheOriginByItsOwnTranslation() {
        float maxOff = 0;
        for (float walk = 0; walk < 2; walk += 0.05f) {
            float bob = 0.1f;
            float[] eye = ReflectionUniforms.eyeInView(bobbed(walk, bob));
            //The eye is where the bobbed matrix sends the view point to clip x = y = w = 0
            Vector3f e = new Vector3f(eye[0], eye[1], eye[2]);
            org.joml.Vector4f clip = bobbed(walk, bob).transform(new org.joml.Vector4f(e, 1));
            assertEquals(0, clip.x, 1e-4f);
            assertEquals(0, clip.y, 1e-4f);
            assertEquals(0, clip.w, 1e-4f);
            maxOff = Math.max(maxOff, e.length());
        }
        //Up to a tenth of a block (and a half of that sideways) while walking: about a degree a few blocks away
        assertTrue(maxOff > 0.08f && maxOff < 0.13f, "max offset " + maxOff);
    }

    @Test
    void theTaaJitterAndTheDepthRemapLeaveTheEyeWhereItIs() {
        Matrix4f m = bobbed(0.3f, 0.1f);
        float[] eye = ReflectionUniforms.eyeInView(m);
        //The jitter: clip x and y plus w times a sub-pixel offset (TaaJitter.apply)
        Matrix4f jittered = new Matrix4f().translation(0.0007f, -0.0004f, 0).mul(m);
        assertArrayEquals(eye, ReflectionUniforms.eyeInView(jittered), 1e-5f);
        //Reforged's 0..1 depth: z = 0.5 z + 0.5 w
        Matrix4f remap = new Matrix4f().scaling(1, 1, 0.5f).translate(0, 0, 1).mul(m);
        assertArrayEquals(eye, ReflectionUniforms.eyeInView(remap), 1e-5f);
    }

    @Test
    void everyMirrorAimsItsRayFromTheEye() {
        assertTrue(COMP.contains("layout(location = " + ReflectionUniforms.EYE + ") uniform vec4 eyeView;"));
        assertTrue(COMP.contains("vec3 eyeRay(vec3 world, mat3 toWorld) {\n    vec3 v = world - toWorld*eyeView.xyz;"));
        //Water / ice, the polished and metal blocks, PBR, the wet ground
        assertEquals(4, COMP.split(java.util.regex.Pattern.quote("vec3 d = eyeRay(world, toWorld);"), -1).length - 1);
        assertFalse(COMP.contains("vec3 d = world/max(dist, 0.000001);"));
    }
}
