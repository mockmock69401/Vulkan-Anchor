package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** From a sub-level's model-view to the shadow map (SubLevelShadowMath). */
class SubLevelShadowMathTest {
    @Test
    void toCameraWorldLeavesTheStructuresOwnTransform() {
        Matrix4f view = new Matrix4f().rotateX(0.4f).rotateY(1.1f);
        Matrix4f structure = new Matrix4f().rotateY((float) Math.PI / 2);
        Matrix4f out = SubLevelShadowMath.toCameraWorld(new Matrix4f(view).invert(), new Matrix4f(view).mul(structure), new Matrix4f());
        assertTrue(out.equals(structure, 1e-5f), out.toString());
    }

    @Test
    void reachesFollowsTheMapsBox() {
        Vector3f toSun = new Vector3f(0.3f, 0.9f, 0.1f).normalize();
        Matrix4f vp = ShadowMath.cascade(toSun, 0, 0, 0, 32, 2048, 2048).viewProjection();
        Matrix4f id = new Matrix4f();
        assertTrue(SubLevelShadowMath.reaches(vp, id, -8, -8, -8), "a section on the camera");
        Vector3f high = new Vector3f(toSun).mul(300).sub(8, 8, 8);
        assertTrue(SubLevelShadowMath.reaches(vp, id, high.x, high.y, high.z), "a section high toward the sun casts down");
        Vector3f tooHigh = new Vector3f(toSun).mul(600).sub(8, 8, 8);
        assertFalse(SubLevelShadowMath.reaches(vp, id, tooHigh.x, tooHigh.y, tooHigh.z), "past the map's depth");
        Vector3f aside = new Vector3f(toSun).cross(0, 1, 0).normalize().mul(200).sub(8, 8, 8);
        assertFalse(SubLevelShadowMath.reaches(vp, id, aside.x, aside.y, aside.z), "beside the map");
    }

    @Test
    void reachesTakesTheStructuresTransformIntoAccount() {
        Vector3f toSun = new Vector3f(0.3f, 0.9f, 0.1f).normalize();
        Matrix4f vp = ShadowMath.cascade(toSun, 0, 0, 0, 32, 2048, 2048).viewProjection();
        Vector3f aside = new Vector3f(toSun).cross(0, 1, 0).normalize().mul(200);
        Matrix4f movedAway = new Matrix4f().translate(aside);
        assertFalse(SubLevelShadowMath.reaches(vp, movedAway, -8, -8, -8));
    }
}
