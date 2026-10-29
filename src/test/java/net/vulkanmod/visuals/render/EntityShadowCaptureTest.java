package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;
import net.vulkanmod.visuals.vk.shader.ShaderFiles;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-29: the entities' triangles for the entity shadow map (EntityShadowCapture, VkEntityShadows, lighting.comp). */
class EntityShadowCaptureTest {
    private static final int STRIDE = 36;//the entity vertex format: position first

    private static ByteBuffer vertices(float[][] positions) {
        ByteBuffer b = MemoryUtil.memAlloc(positions.length * STRIDE);
        for (int i = 0; i < positions.length; i++) {
            b.putFloat(i * STRIDE, positions[i][0]).putFloat(i * STRIDE + 4, positions[i][1]).putFloat(i * STRIDE + 8, positions[i][2]);
        }
        return b;
    }

    @Test
    void quadsBecomeTwoTrianglesInWorldSpace() {
        ByteBuffer in = vertices(new float[][]{{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}});
        FloatBuffer out = MemoryUtil.memAllocFloat(64);
        try {
            Matrix4f toWorld = new Matrix4f().translation(2, 3, 4);
            int n = EntityShadowCapture.append(out, 0, MemoryUtil.memAddress(in), STRIDE, 4, true, toWorld);
            assertEquals(6, n);
            float[][] want = {{2, 3, 4}, {3, 3, 4}, {3, 4, 4}, {3, 4, 4}, {2, 4, 4}, {2, 3, 4}};
            for (int i = 0; i < 6; i++) {
                for (int k = 0; k < 3; k++) assertEquals(want[i][k], out.get(i * 3 + k), 1e-6f, "corner " + i);
            }
            //Triangles stay triangles, after what is there
            assertEquals(9, EntityShadowCapture.append(out, n, MemoryUtil.memAddress(in), STRIDE, 3, false, new Matrix4f()));
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(out);
        }
    }

    @Test
    void farPrimitivesAndTheCapAreLeftOut() {
        float far = EntityShadowCapture.REACH + 1;
        ByteBuffer in = vertices(new float[][]{{far, 0, 0}, {far, 1, 0}, {far, 1, 1}, {far, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}});
        FloatBuffer out = MemoryUtil.memAllocFloat(64);
        try {
            assertEquals(6, EntityShadowCapture.append(out, 0, MemoryUtil.memAddress(in), STRIDE, 8, true, new Matrix4f()), "only the near quad");
            assertEquals(EntityShadowCapture.MAX_VERTICES - 3, EntityShadowCapture.append(out, EntityShadowCapture.MAX_VERTICES - 3,
                    MemoryUtil.memAddress(in), STRIDE, 8, true, new Matrix4f()), "no room for a quad");
            assertEquals(0, EntityShadowCapture.append(out, 0, MemoryUtil.memAddress(in), STRIDE, 3, true, new Matrix4f()), "an incomplete quad");
        } finally {
            MemoryUtil.memFree(in);
            MemoryUtil.memFree(out);
        }
    }

    @Test
    void theCaptureIsOpenOnlyInAFrameThatWantsIt() {
        EntityShadowCapture.beginFrame(7, false, new Matrix4f());
        EntityShadowCapture.open();
        assertTrue(!EntityShadowCapture.capturing());
        EntityShadowCapture.beginFrame(8, true, new Matrix4f());
        EntityShadowCapture.open();
        assertTrue(EntityShadowCapture.capturing());
        EntityShadowCapture.close();
        assertTrue(!EntityShadowCapture.capturing());
        assertEquals(0, EntityShadowCapture.vertexCount(7), "another frame's");
    }

    @Test
    void theLightingMultipliesTheCascadesByTheEntityMap() {
        String comp = ShaderFiles.read("post/lighting.comp");
        assertTrue(comp.contains("layout(binding = 16) uniform sampler2D shadowEntityMap;"));
        assertTrue(comp.contains("layout(location = " + EntityShadowUniforms.MATRIX + ") uniform mat4 shadowEntity;"));
        assertTrue(comp.contains("layout(location = " + EntityShadowUniforms.PARAMS + ") uniform vec4 entityParams;"));
        assertTrue(comp.contains("visible *= mix(1.0, e, 1.0 - bandWeight(max(abs(clip.x), abs(clip.y))));"));
        assertTrue(!EntityShadowUniforms.compute(null, null, null, true).on());
        assertTrue(new VisualsConfig().entityShadows);
        VisualsOptionLayout.Item item = VisualsOptionLayout.PAGES.stream().flatMap(p -> p.blocks().stream()).flatMap(b -> b.items().stream())
                .filter(i -> i.key().equals("entity_shadows")).findFirst().orElseThrow();
        assertEquals("sun_shadows", item.requires());
    }
}
