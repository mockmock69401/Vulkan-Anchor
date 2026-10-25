package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10-21 (CTE2: the sky's brightness pulsed with every step): vanilla's view bobbing is a translation inside the projection, so a
 * view ray must be the difference of two unprojected points, never the direction of one point near the eye. Also the underwater fog.
 */
class ViewRayBobTest {
    private static String shader(String name) throws IOException {
        try (InputStream in = ViewRayBobTest.class.getResourceAsStream("/assets/vulkanmod/visuals/shaders/post/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A Vulkan-depth perspective (near 0.05, infinite far) with a bob translation baked in, as GameRenderer builds it. */
    private static Matrix4f bobbed(float bx, float by) {
        Matrix4f p = new Matrix4f().setPerspective((float) Math.toRadians(70), 16f / 9f, 0.05f, Float.POSITIVE_INFINITY, true);
        return p.mul(new Matrix4f().translation(bx, by, 0));
    }

    @Test
    void theAoRayIgnoresTheBobTranslation() {
        Vector3f still = AmbientOcclusion.ray(bobbed(0, 0).invert(), 300, 100, 1920, 1080);
        Vector3f bob = AmbientOcclusion.ray(bobbed(0.05f, -0.1f).invert(), 300, 100, 1920, 1080);
        assertEquals(1, still.dot(bob), 1e-5, "the ray keeps its direction under the bob");
        //One point at depth 0.5 (0.1 blocks out) would swing by tens of degrees
        Matrix4f inv = bobbed(0.05f, -0.1f).invert();
        org.joml.Vector4f p = inv.transform(new org.joml.Vector4f(-0.6875f, 0.8148f, 0.5f, 1));
        Vector3f one = new Vector3f(p.x / p.w, p.y / p.w, p.z / p.w).normalize();
        assertTrue(still.dot(one) < 0.95, "the old single-point ray: " + still.dot(one));
    }

    @Test
    void skyVolumetricAndTaaUseTheTwoPointRay() throws IOException {
        String viewpos = shader("viewpos.glsl");
        assertTrue(viewpos.contains("vec3 viewRay(mat4 invProj, vec2 uvGl)"));
        assertTrue(viewpos.contains("viewPosition(invProj, uvGl, VIEW_RAY_FAR) - viewPosition(invProj, uvGl, VIEW_RAY_NEAR)"));
        assertTrue(shader("scene_copy.comp").contains("viewRay(invVanillaProj, pixelUvGl(p, size))"));
        assertTrue(shader("volumetric.comp").contains("dir = viewRay(invVanillaProj, pixelUvGl(p, full));"));
        assertTrue(shader("post_taa.comp").contains("view = viewRay(invVanillaProj, pixelUvGl(closestAt, size));"));
        assertTrue(shader("ao.glsl").contains("return normalize(f.xyz/f.w - n.xyz/n.w);"));
    }

    @Test
    void underwaterFogReachesFurtherOnlyInWaterWithThePostProcessing() {
        assertEquals(96 * UnderwaterFog.END_SCALE, UnderwaterFog.end(96, true, true), 1e-6);
        assertEquals(96, UnderwaterFog.end(96, false, true));
        assertEquals(96, UnderwaterFog.end(96, true, false));
        assertEquals(Float.POSITIVE_INFINITY, UnderwaterFog.end(Float.POSITIVE_INFINITY, true, true));
    }
}
