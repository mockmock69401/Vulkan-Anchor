package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.render.TaaMath;
import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta.Kind;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FSR design 4.5 step 2: the depth FSR reads (near / view distance) and the camera motion vectors, FsrInputMath mirroring fsr_inputs.comp. */
class FsrInputMathTest {
    private static final int W = 1920, H = 1080;
    private static final float NEAR = 0.05f;
    private static final Matrix4f PROJ = new Matrix4f().perspective((float) Math.toRadians(70), (float) W / H, NEAR, 512f, true);
    private static final Matrix4f INV_PROJ = new Matrix4f(PROJ).invert();
    /** Pixels across the screen, as FSR UV (top-left origin, v down). */
    private static final float[][] PIXELS = {{0.5f, 0.5f}, {0.1f, 0.2f}, {0.9f, 0.85f}, {0.3f, 0.6f}};

    /** prevFromView for a camera that was at prev (model view prevMv) and is now at now with model view nowMv. */
    private static Matrix4f prevFromView(Matrix4f prevMv, double[] prev, Matrix4f nowMv, double[] now) {
        TaaMath.Camera p = new TaaMath.Camera(PROJ, prevMv, prev[0], prev[1], prev[2]);
        return TaaMath.targetFromView(p, new Matrix4f(nowMv).invert(), now[0], now[1], now[2]);
    }

    @Test
    void depthIsNearOverDistance() {
        assertEquals(0.25f, FsrInputMath.depth(0.05f * 4, 0.05f), 0f);
        assertEquals(5e-8f, FsrInputMath.depth(1e6f, 0.05f), 5e-8f * 1e-5f);
        assertEquals(0f, FsrInputMath.depth(FsrInputMath.SKY, 0.05f), 0f, "sky / no geometry");
        assertEquals(1f, FsrInputMath.depth(0.01f, 0.05f), 0f, "nearer than the near plane is the near plane");
        //inverted: nearer is larger
        assertTrue(FsrInputMath.depth(10f, NEAR) > FsrInputMath.depth(100f, NEAR));
    }

    @Test
    void stillCameraHasNoMotion() {
        double[] at = {12.5, 70, -3};
        for (Matrix4f mv : new Matrix4f[]{new Matrix4f(), new Matrix4f().rotateX(0.3f).rotateY(-1.1f)}) {
            Matrix4f pfv = prevFromView(mv, at, mv, at);
            for (float[] px : PIXELS) {
                for (float d : new float[]{0.3f, 10f, 100f, 5000f, FsrInputMath.SKY}) {
                    float[] m = FsrInputMath.motion(pfv, px[0], px[1], d, INV_PROJ);
                    assertEquals(0f, m[0], 1e-6f, Arrays.toString(px) + " at " + d);
                    assertEquals(0f, m[1], 1e-6f, Arrays.toString(px) + " at " + d);
                }
            }
        }
    }

    @Test
    void sidestepMovesFarLessThanNear() {
        //the camera moved +1 block on x since the previous frame
        Matrix4f pfv = prevFromView(new Matrix4f(), new double[]{0, 0, 0}, new Matrix4f(), new double[]{1, 0, 0});
        for (float[] px : PIXELS) {
            float[] near = FsrInputMath.motion(pfv, px[0], px[1], 10f, INV_PROJ);
            float[] far = FsrInputMath.motion(pfv, px[0], px[1], 100f, INV_PROJ);
            assertEquals(10f, near[0] / far[0], 1e-3f, Arrays.toString(px));
            assertEquals(0f, near[1], 1e-6f);
            assertEquals(0f, far[1], 1e-6f);
            //the picture slid against the step (current u = previous u - near[0]): the vector, previous minus current, points along it
            assertTrue(near[0] > 0f && far[0] > 0f, Arrays.toString(near));
            //a step adds no motion to the sky (a point at infinity)
            float[] sky = FsrInputMath.motion(pfv, px[0], px[1], FsrInputMath.SKY, INV_PROJ);
            assertEquals(0f, sky[0], 1e-6f);
            assertEquals(0f, sky[1], 1e-6f);
        }
    }

    @Test
    void skyMovesWithTheTurn() {
        //turned right by 0.1 rad since the previous frame: what is now at the centre was right of it before
        Matrix4f pfv = prevFromView(new Matrix4f(), new double[]{0, 0, 0}, new Matrix4f().rotateY(0.1f), new double[]{0, 0, 0});
        float[] sky = FsrInputMath.motion(pfv, 0.5f, 0.5f, FsrInputMath.SKY, INV_PROJ);
        float[] far = FsrInputMath.motion(pfv, 0.5f, 0.5f, 5000f, INV_PROJ);
        assertTrue(Math.abs(sky[0]) > 0.01f, Arrays.toString(sky));
        assertEquals(sky[0], far[0], 1e-5f, "a turn moves every distance alike");
        assertEquals(0f, sky[1], 1e-6f);
    }

    @Test
    void behindThePreviousCameraHasNoMotion() {
        //turned half way round: the point in front now was behind the previous camera
        Matrix4f pfv = prevFromView(new Matrix4f(), new double[]{0, 0, 0}, new Matrix4f().rotateY((float) Math.PI), new double[]{0, 0, 0});
        float[] m = FsrInputMath.motion(pfv, 0.5f, 0.5f, 10f, INV_PROJ);
        assertEquals(0f, m[0], 0f);
        assertEquals(0f, m[1], 0f);
    }

    /** The vector times the render size is TaaMath.reproject's move in pixels, previous minus current. */
    @Test
    void agreesWithTheTaaReprojection() {
        Matrix4f pfv = prevFromView(new Matrix4f().rotateY(0.2f), new double[]{0, 64, 0}, new Matrix4f().rotateY(0.25f).rotateX(0.05f),
                new double[]{0.4, 64.2, -0.3});
        for (float[] px : PIXELS) {
            for (float d : new float[]{2f, 30f, 300f}) {
                Vector4f view = new Vector4f(FsrInputMath.viewPoint(INV_PROJ, px[0], px[1], d));
                Vector4f clip = PROJ.transform(new Vector4f(view.x, view.y, view.z, 1f));
                float x = px[0] * W, y = px[1] * H;
                float[] r = TaaMath.reproject(INV_PROJ, pfv, x, y, clip.z / clip.w, W, H, false);
                assertNotNull(r);
                float[] m = FsrInputMath.motion(pfv, px[0], px[1], d, INV_PROJ);
                assertEquals((r[0] - x) / W, m[0], 2e-5f, Arrays.toString(px) + " at " + d);
                assertEquals((r[1] - y) / H, m[1], 2e-5f, Arrays.toString(px) + " at " + d);
            }
        }
    }

    @Test
    void viewPointIsOnThePixelsRayAtTheDistance() {
        Vector4f p = new Vector4f(FsrInputMath.viewPoint(INV_PROJ, 0.25f, 0.75f, 42f));
        assertEquals(1f, p.w, 0f);
        assertEquals(-42f, p.z, 1e-3f);
        Vector4f clip = PROJ.transform(new Vector4f(p.x, p.y, p.z, 1f));
        assertEquals(0.25f, clip.x / clip.w * 0.5f + 0.5f, 1e-5f);
        assertEquals(0.75f, 0.5f - clip.y / clip.w * 0.5f, 1e-5f, "v is down");
        assertEquals(0f, FsrInputMath.viewPoint(INV_PROJ, 0.25f, 0.75f, FsrInputMath.SKY).w, 0f, "the sky is a direction");
    }

    @Test
    void pixelUvIsTheCentre() {
        float[] uv = FsrInputMath.pixelUv(0, 9, 4, 10);
        assertEquals(0.125f, uv[0], 0f);
        assertEquals(0.95f, uv[1], 1e-7f);
    }

    // ---- the shader ----

    private static final String SHADER = "fsr/fsr_inputs.comp";

    @Test
    void theShaderPairsEveryBlockWithItsMirror() {
        String s = ShaderFiles.read(SHADER);
        for (String mirror : new String[]{"FsrInputMath.depth", "FsrInputMath.motionOf", "FsrInputMath.viewPoint", "FsrInputMath.pixelUv"}) {
            assertTrue(s.contains("//" + mirror), "fsr_inputs.comp has no block marked //" + mirror);
        }
        assertTrue(s.contains("layout(local_size_x = 8, local_size_y = 8) in;"), "8x8 groups");
        //the sky direction is viewpos.glsl's viewRay; the mirror uses the same two depths
        String viewpos = ShaderFiles.read("post/viewpos.glsl");
        assertTrue(viewpos.contains("const float VIEW_RAY_NEAR = " + FsrInputMath.VIEW_RAY_NEAR + ";"), "VIEW_RAY_NEAR");
        assertTrue(viewpos.contains("const float VIEW_RAY_FAR = " + FsrInputMath.VIEW_RAY_FAR + ";"), "VIEW_RAY_FAR");
    }

    private static VulkanShaderMeta meta() {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(FsrInputs.PROGRAM);
        return GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).meta();
    }

    @Test
    void bindingsAndUniformsMatchThePass() {
        VulkanShaderMeta m = meta();
        Map<String, Kind> have = new TreeMap<>();
        for (VulkanShaderMeta.Binding b : m.bindings()) have.put(b.set() + ":" + b.binding(), b.kind());
        Map<String, Kind> want = new TreeMap<>();
        want.put(GlslForVulkan.SET_IMAGE + ":" + FsrInputs.DEPTH_OUT, Kind.IMAGE);
        want.put(GlslForVulkan.SET_IMAGE + ":" + FsrInputs.MOTION_OUT, Kind.IMAGE);
        want.put(GlslForVulkan.SET_SAMPLER + ":" + FsrInputs.MAIN_DEPTH, Kind.SAMPLER);
        want.put(GlslForVulkan.SET_SAMPLER + ":" + FsrInputs.LOD_DEPTH, Kind.SAMPLER);
        if (m.pushInUniformBuffer()) want.put(GlslForVulkan.SET_UNIFORM + ":" + FsrInputs.LOOSE_UNIFORMS, Kind.UNIFORM_BUFFER);
        assertEquals(want, have);

        Map<Integer, String> uniforms = new LinkedHashMap<>();
        for (VulkanShaderMeta.PushMember p : m.push()) uniforms.put(p.location(), p.type());
        Map<Integer, String> expected = new LinkedHashMap<>();
        expected.put(FsrInputs.INV_VANILLA_PROJ, "mat4");
        expected.put(FsrInputs.INV_LOD_PROJ, "mat4");
        expected.put(FsrInputs.PREV_FROM_VIEW, "mat4");
        expected.put(FsrInputs.DEPTH_PARAMS, "vec4");
        expected.put(FsrInputs.FSR_PARAMS, "vec4");
        assertEquals(new TreeMap<>(expected), new TreeMap<>(uniforms));
    }
}
