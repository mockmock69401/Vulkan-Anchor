package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** W2 test 5: every constant W2's shaders declare is a PostMath constant of the same name and value (the tests check PostMath). */
class PostShaderConstantsTest {
    private static final String[] FILES = {"post/postmath.glsl", "post/postfinal.glsl", "post/post_blit.frag"};
    private static final Pattern CONST = Pattern.compile("const\\s+(float|int|vec3)\\s+(\\w+)\\s*=\\s*([^;]+);");
    private static final Pattern VEC3 = Pattern.compile("vec3\\(\\s*([^,]+),\\s*([^,]+),\\s*([^)]+)\\)");

    private static Map<String, float[]> shaderConstants() {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String file : FILES) {
            Matcher m = CONST.matcher(ShaderFiles.read(file));
            while (m.find()) {
                String type = m.group(1), name = m.group(2), value = m.group(3).trim();
                float[] v;
                if (type.equals("vec3")) {
                    Matcher vm = VEC3.matcher(value);
                    assertTrue(vm.matches(), file + ": " + name + " is not a vec3 literal: " + value);
                    v = new float[]{Float.parseFloat(vm.group(1).trim()), Float.parseFloat(vm.group(2).trim()), Float.parseFloat(vm.group(3).trim())};
                } else {
                    v = new float[]{Float.parseFloat(value)};
                }
                float[] old = out.put(name, v);
                if (old != null) assertArrayEquals(old, v, 0f, name + " declared twice with different values");
            }
        }
        return out;
    }

    @Test
    void everyShaderConstantMatchesPostMath() throws Exception {
        Map<String, float[]> constants = shaderConstants();
        assertTrue(constants.containsKey("LUMA"), "boundary 3.7: postmath.glsl exports LUMA");
        assertTrue(constants.size() >= 10, "found " + constants.keySet());
        for (Map.Entry<String, float[]> e : constants.entrySet()) {
            Field f;
            try {
                f = PostMath.class.getField(e.getKey());
            } catch (NoSuchFieldException missing) {
                fail("PostMath has no constant " + e.getKey() + " (declared in a W2 shader)");
                return;
            }
            assertTrue(Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()), e.getKey());
            Object java = f.get(null);
            float[] expected = e.getValue();
            if (java instanceof float[] array) {
                assertArrayEquals(expected, array, 0f, e.getKey());
            } else if (java instanceof Float value) {
                assertEquals(expected.length, 1, e.getKey());
                assertEquals(expected[0], value, 0f, e.getKey());
            } else if (java instanceof Integer value) {
                assertEquals(expected.length, 1, e.getKey());
                assertEquals(expected[0], value.floatValue(), 0f, e.getKey());
            } else {
                fail(e.getKey() + ": unexpected Java type " + f.getType());
            }
        }
    }

    @Test
    void lumaIsTheRec709LuminanceRow() {
        assertArrayEquals(new float[]{0.2126f, 0.7152f, 0.0722f}, PostMath.LUMA, 0f);
    }

    /** The final pass never reads the depth (W2 brief, "Do not"). */
    @Test
    void theFinalPassDeclaresOnlyItsThreeSamplers() {
        String text = ShaderFiles.read("post/postfinal.glsl");
        assertTrue(text.contains("binding = 0) uniform sampler2D"));
        assertTrue(text.contains("binding = 1) uniform sampler2D"));
        assertTrue(text.contains("binding = 2) uniform sampler2D"));
        assertTrue(!text.contains("binding = 3") && !text.toLowerCase().contains("depth"), "no depth in the final pass");
    }
}
