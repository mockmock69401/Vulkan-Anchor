package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.framegen.PauseRules;
import net.vulkanmod.visuals.fsr.FsrCapture.CameraState;
import net.vulkanmod.visuals.render.ReforgedProjection;
import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta.Kind;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FSR design 4.5 steps 1 and 5: FSR pauses on Optical Flow's rules, and a camera cut resets it (ruling R3). */
class FsrCaptureTest {
    private static final CameraState HERE = new CameraState(100, 64, -200, "minecraft:overworld", "FIRST_PERSON");

    @Test
    void pauseReasonsMatchOpticalFlow() {
        //every reason PauseRules.reason can return: all its eleven inputs, each on and off
        Set<String> reasons = new HashSet<>();
        for (int bits = 0; bits < 1 << 11; bits++) {
            reasons.add(PauseRules.reason(on(bits, 0), on(bits, 1), on(bits, 2), on(bits, 3), on(bits, 4), on(bits, 5), on(bits, 6),
                    on(bits, 7), on(bits, 8), on(bits, 9), on(bits, 10)));
        }
        assertEquals(12, reasons.size(), "eleven reasons and null: " + reasons);
        for (String reason : reasons) assertEquals(reason == null, FsrCapture.generates(reason), String.valueOf(reason));
        //a frame whose world was not captured has nothing to generate from
        assertFalse(FsrCapture.generates(FsrCapture.NOT_CAPTURED));

        //a teleport: more than 16 blocks between captured frames
        assertTrue(FsrCapture.resetOn(HERE, moved(16.01, 0, 0)));
        assertTrue(FsrCapture.resetOn(HERE, moved(10, 10, 10)));//17.3 blocks
        //a dimension change, at the same coordinates
        assertTrue(FsrCapture.resetOn(HERE, new CameraState(HERE.x(), HERE.y(), HERE.z(), "minecraft:the_nether", HERE.cameraType())));
        //a camera-type change (F5)
        assertTrue(FsrCapture.resetOn(HERE, new CameraState(HERE.x(), HERE.y(), HERE.z(), HERE.dimension(), "THIRD_PERSON_BACK")));
        //no captured frame before: nothing to continue from
        assertTrue(FsrCapture.resetOn(null, HERE));

        //walking, sprinting, falling, flying fast: no cut
        assertFalse(FsrCapture.resetOn(HERE, HERE));
        assertFalse(FsrCapture.resetOn(HERE, moved(0.2, -0.1, 0.3)));
        assertFalse(FsrCapture.resetOn(HERE, moved(16, 0, 0)));//exactly 16: not more than
        assertFalse(FsrCapture.resetOn(HERE, moved(8, -8, 8)));//13.9 blocks
    }

    @Test
    void nearPlaneOfTheZeroOneProjection() {
        //vanilla's GL projection remapped to 0..1 depth (ReforgedProjection), as FrameGen.onCamera keeps it
        Matrix4f gl = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 1024f);
        assertEquals(0.05f, FsrCapture.nearPlane(ReforgedProjection.vulkanDepth(gl)), 1e-6f);
        gl = new Matrix4f().perspective((float) Math.toRadians(30), 1f, 0.5f, 64f);
        assertEquals(0.5f, FsrCapture.nearPlane(ReforgedProjection.vulkanDepth(gl)), 1e-5f);
        assertTrue(FsrCapture.usableNear(FsrCapture.nearPlane(ReforgedProjection.vulkanDepth(gl))));
        //2026-10-28 (Deceased: "near plane NaN" on the frame joining the world turned FSR off for the session): a projection that is
        // no perspective yet gives no near plane, and that frame is skipped instead
        assertFalse(FsrCapture.usableNear(FsrCapture.nearPlane(new Matrix4f().zero())));
        for (float bad : new float[]{Float.NaN, 0, -0.05f, Float.POSITIVE_INFINITY}) assertFalse(FsrCapture.usableNear(bad), "" + bad);
    }

    @Test
    void hudLessCopyBindingsMatchThePass() {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(FsrCapture.PROGRAM);
        VulkanShaderMeta m = GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).meta();
        Map<String, Kind> have = new TreeMap<>();
        for (VulkanShaderMeta.Binding b : m.bindings()) have.put(b.set() + ":" + b.binding(), b.kind());
        Map<String, Kind> want = new TreeMap<>();
        want.put(GlslForVulkan.SET_IMAGE + ":" + FsrCapture.HUD_LESS_OUT, Kind.IMAGE);
        want.put(GlslForVulkan.SET_SAMPLER + ":" + FsrCapture.MAIN_COLOUR, Kind.SAMPLER);
        assertEquals(want, have);
        //dispatched without push constants
        assertEquals(0, m.pushConstantBytes());
    }

    @Test
    void imagesFsrReadsCanBeCopiedFrom() {
        //FSR's generation copies the HUD-less colour in its own command list (vkCmdCopyImage needs TRANSFER_SRC); its inputs too
        int transferSrc = org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        assertEquals(transferSrc, FsrCapture.HUD_LESS_USAGE & transferSrc);
        assertEquals(transferSrc, FsrInputs.USAGE & transferSrc);
        //the capture and the inputs pass still write and FSR still samples them
        int storageSampled = org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
        assertEquals(storageSampled, FsrCapture.HUD_LESS_USAGE & storageSampled);
        assertEquals(storageSampled, FsrInputs.USAGE & storageSampled);
    }

    private static boolean on(int bits, int bit) {
        return (bits & 1 << bit) != 0;
    }

    private static CameraState moved(double dx, double dy, double dz) {
        return new CameraState(HERE.x() + dx, HERE.y() + dy, HERE.z() + dz, HERE.dimension(), HERE.cameraType());
    }
}
