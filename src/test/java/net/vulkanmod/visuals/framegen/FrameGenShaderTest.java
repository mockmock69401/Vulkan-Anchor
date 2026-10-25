package net.vulkanmod.visuals.framegen;

import net.vulkanmod.visuals.lod.LodClamp;
import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta;
import net.vulkanmod.visuals.vk.shader.VulkanShaderMeta.Kind;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joml.Matrix4f;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Frame generation shaders: their constants are WarpMath's, their bindings are FrameGenBindings', the parameter block is 640 bytes. */
class FrameGenShaderTest {
    private static final String[] FILES = {"fg/params.glsl", "fg/fg_capture.comp", "fg/fg_splat.comp", "fg/fg_resolve.comp",
            "fg/fg_fill.comp", "fg/fg_compose.frag"};
    private static final Pattern CONST = Pattern.compile("const\\s+(float|int|uint)\\s+(\\w+)\\s*=\\s*([^;]+);");

    private static Map<String, String> shaderConstants() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String file : FILES) {
            Matcher m = CONST.matcher(ShaderFiles.read(file));
            while (m.find()) {
                String old = out.put(m.group(2), m.group(3).trim());
                if (old != null) assertEquals(old, m.group(3).trim(), m.group(2) + " declared twice with different values");
            }
        }
        return out;
    }

    private static float parse(String v) {
        return v.equals("0xFFFFFFFFu") ? Float.NaN : Float.parseFloat(v);
    }

    @Test
    void everyWarpConstantMatchesWarpMath() throws Exception {
        Map<String, String> constants = shaderConstants();
        Set<String> names = new HashSet<>(List.of("SPLAT_FOOTPRINT", "FILL_DIRECTIONS", "FILL_RADIUS", "DISOCCLUSION_DEPTH", "SKY_DEPTH",
                "HOLE_MARK", "EDGE_MARK", "VALID_MARK"));
        for (String name : names) {
            assertTrue(constants.containsKey(name), "no shader declares " + name);
            Object java = WarpMath.class.getField(name).get(null);
            assertEquals(((Number) java).floatValue(), parse(constants.get(name)), 0f, name);
        }
        //Every other constant the shaders share with Java by name
        assertEquals("0xFFFFFFFFu", constants.get("EMPTY"), "EMPTY is all bits set, as WarpMath.EMPTY");
        assertEquals(-1, WarpMath.EMPTY);
        assertEquals(LodClamp.CLAMP_THRESHOLD, parse(constants.get("LOD_CLAMP_THRESHOLD")), 0f, "LOD_CLAMP_THRESHOLD");
    }

    private static VulkanShaderMeta meta(String program, int stage) {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(program);
        return GlslForVulkan.toVulkan(p.source(p.stages().get(stage), ShaderSource.classpath()), p.defines()).meta();
    }

    private static void expect(String program, int stage, Map<Integer, Kind> expected) {
        VulkanShaderMeta m = meta(program, stage);
        Map<Integer, Kind> have = new LinkedHashMap<>();
        for (VulkanShaderMeta.Binding b : m.bindings()) {
            assertEquals(null, have.put(b.binding() + (b.set() << 16), b.kind()), program + ": two resources at one set and binding");
        }
        Map<Integer, Kind> want = new LinkedHashMap<>();
        expected.forEach((binding, kind) -> want.put(binding + (setOf(kind) << 16), kind));
        assertEquals(want, have, program);
        assertTrue(m.push().isEmpty(), program + " has no loose uniforms: the parameters are the FgParams block");
    }

    private static int setOf(Kind kind) {
        return switch (kind) {
            case STORAGE_BUFFER -> GlslForVulkan.SET_STORAGE;
            case UNIFORM_BUFFER -> GlslForVulkan.SET_UNIFORM;
            case SAMPLER -> GlslForVulkan.SET_SAMPLER;
            case IMAGE -> GlslForVulkan.SET_IMAGE;
        };
    }

    @Test
    void bindingsMatchTheShaders() {
        Map<Integer, Kind> m = new LinkedHashMap<>();
        m.put(FrameGenBindings.CAPTURE_OUT, Kind.IMAGE);
        m.put(FrameGenBindings.CAPTURE_COLOUR, Kind.SAMPLER);
        m.put(FrameGenBindings.CAPTURE_MAIN_DEPTH, Kind.SAMPLER);
        m.put(FrameGenBindings.CAPTURE_LOD_DEPTH, Kind.SAMPLER);
        m.put(FrameGenBindings.PARAMS, Kind.UNIFORM_BUFFER);
        expect("fgCapture", 0, m);

        m = new LinkedHashMap<>();
        m.put(FrameGenBindings.SPLAT_OUT, Kind.IMAGE);
        m.put(FrameGenBindings.SPLAT_WORLD, Kind.SAMPLER);
        m.put(FrameGenBindings.PARAMS, Kind.UNIFORM_BUFFER);
        expect("fgSplat", 0, m);

        m = new LinkedHashMap<>();
        m.put(FrameGenBindings.RESOLVE_OUT, Kind.IMAGE);
        m.put(FrameGenBindings.RESOLVE_SPLAT, Kind.IMAGE);
        m.put(FrameGenBindings.RESOLVE_WORLD_LINEAR, Kind.SAMPLER);
        m.put(FrameGenBindings.RESOLVE_WORLD_NEAREST, Kind.SAMPLER);
        m.put(FrameGenBindings.PARAMS, Kind.UNIFORM_BUFFER);
        expect("fgResolve", 0, m);

        m = new LinkedHashMap<>();
        m.put(FrameGenBindings.FILL_OUT, Kind.IMAGE);
        m.put(FrameGenBindings.FILL_RESOLVED, Kind.IMAGE);
        m.put(FrameGenBindings.FILL_SPLAT, Kind.IMAGE);
        m.put(FrameGenBindings.PARAMS, Kind.UNIFORM_BUFFER);
        expect("fgFill", 0, m);

        m = new LinkedHashMap<>();
        m.put(FrameGenBindings.COMPOSE_WORLD, Kind.SAMPLER);
        m.put(FrameGenBindings.COMPOSE_UI, Kind.SAMPLER);
        m.put(FrameGenBindings.COMPOSE_VIGNETTE, Kind.SAMPLER);
        m.put(FrameGenBindings.COMPOSE_ICONS, Kind.SAMPLER);
        m.put(FrameGenBindings.PARAMS, Kind.UNIFORM_BUFFER);
        expect("fgCompose", 1, m);
    }

    @Test
    void theParamsBlockIs640Bytes() {
        assertEquals(640, FrameGenParams.SIZE);
        ByteBuffer b = ByteBuffer.allocateDirect(1024).order(ByteOrder.nativeOrder());
        b.position(64);
        Matrix4f marker = new Matrix4f().identity().m30(7f);
        float[] quads = {1, 2, 3, 4, 0.25f, 0.5f, 0.125f, 0.0625f};
        FrameGenParams.write(b, marker, marker, marker, marker, marker, 1920, 1080, 2560, 1440, true, false, 3, true, true,
                new float[]{0.5f, 0.25f, 0.125f}, true, quads, 1);
        assertEquals(64 + 640, b.position(), "write() fills exactly 640 bytes");
        assertEquals(7f, b.getFloat(64 + 12 * 4), 0f, "invRealProj column 3 x");
        assertEquals(7f, b.getFloat(64 + 4 * 64 + 12 * 4), 0f, "invGenProj column 3 x");
        assertEquals(1920f, b.getFloat(64 + 320), 0f, "size.x");
        assertEquals(1440f, b.getFloat(64 + 320 + 12), 0f, "size.w");
        assertEquals(1f, b.getFloat(64 + 336), 0f, "flags.x lodValid");
        assertEquals(0f, b.getFloat(64 + 340), 0f, "flags.y depthValid");
        assertEquals(3f, b.getFloat(64 + 344), 0f, "flags.z fgView");
        assertEquals(1f, b.getFloat(64 + 348), 0f, "flags.w uiValid");
        assertEquals(0.5f, b.getFloat(64 + 352), 0f, "vignette.r");
        assertEquals(1f, b.getFloat(64 + 364), 0f, "vignette.a");
        assertEquals(1f, b.getFloat(64 + 368), 0f, "crosshair.x");
        assertEquals(1f, b.getFloat(64 + 372), 0f, "crosshair.y warped");
        assertEquals(1f, b.getFloat(64 + 384), 0f, "quads[0].x");
        assertEquals(0.25f, b.getFloat(64 + 400), 0f, "quads[1].x");
        assertEquals(0f, b.getFloat(64 + 416), 0f, "unused quads are zero");
        assertEquals(0f, b.getFloat(64 + 636), 0f, "the last quad entry is zero");
    }

    @Test
    void thePacerKeepsTheCapturedFieldsAndWritesItsOwn() {
        //the render thread's block for the slot: LOD projection, flags, vignette and crosshair are its
        ByteBuffer captured = ByteBuffer.allocateDirect(640).order(ByteOrder.nativeOrder());
        Matrix4f lod = new Matrix4f().m30(5f);
        Matrix4f old = new Matrix4f().m30(9f);
        float[] quads = {1, 2, 3, 4, 0.25f, 0.5f, 0.125f, 0.0625f};
        FrameGenParams.write(captured, old, lod, old, old, old, 1, 2, 3, 4, true, true, 2, true, false,
                new float[]{0.5f, 0.25f, 0.125f}, true, quads, 1);

        ByteBuffer ring = ByteBuffer.allocateDirect(640).order(ByteOrder.nativeOrder());
        Matrix4f mine = new Matrix4f().m30(7f);
        FrameGenParams.writePacer(ring, captured, mine, mine, mine, mine, 1920, 1080, 2560, 1440, true);
        assertEquals(640, ring.position());
        assertEquals(7f, ring.getFloat(12 * 4), 0f, "invRealProj is the pacer's");
        assertEquals(5f, ring.getFloat(64 + 12 * 4), 0f, "invLodProj is the capture's");
        assertEquals(7f, ring.getFloat(128 + 12 * 4), 0f, "genFromReal");
        assertEquals(7f, ring.getFloat(192 + 12 * 4), 0f, "realFromGen");
        assertEquals(7f, ring.getFloat(256 + 12 * 4), 0f, "invGenProj");
        assertEquals(1920f, ring.getFloat(320), 0f, "size is the pacer's");
        assertEquals(1440f, ring.getFloat(332), 0f);
        assertEquals(2f, ring.getFloat(344), 0f, "flags are the capture's (fgView)");
        assertEquals(1f, ring.getFloat(348), 0f, "uiValid");
        assertEquals(0.5f, ring.getFloat(352), 0f, "vignette");
        assertEquals(1f, ring.getFloat(368), 0f, "crosshair count");
        assertEquals(1f, ring.getFloat(372), 0f, "warped is the pacer's");
        assertEquals(0.25f, ring.getFloat(400), 0f, "quads are the capture's");
    }
}
