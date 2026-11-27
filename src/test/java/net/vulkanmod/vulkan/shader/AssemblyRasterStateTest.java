package net.vulkanmod.vulkan.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 2026-11-27 (stability test, Nightfall): the topology field is 4 bits but was read with a 5-bit mask, so front-face culling (the cull
 * mode's low bit, right above it) turned a triangle list (3) into topology 19 and vkCreateGraphicsPipelines got an invalid value.
 */
class AssemblyRasterStateTest {
    @Test
    void everyFieldComesBackAsEncoded() {
        int[] culls = {VK_CULL_MODE_NONE, VK_CULL_MODE_FRONT_BIT, VK_CULL_MODE_BACK_BIT, VK_CULL_MODE_FRONT_AND_BACK};
        int[] modes = {VK_POLYGON_MODE_FILL, VK_POLYGON_MODE_LINE, VK_POLYGON_MODE_POINT};
        int[] faces = {VK_FRONT_FACE_COUNTER_CLOCKWISE, VK_FRONT_FACE_CLOCKWISE};
        for (int topology = VK_PRIMITIVE_TOPOLOGY_POINT_LIST; topology <= VK_PRIMITIVE_TOPOLOGY_PATCH_LIST; topology++) {
            for (int cull : culls) {
                for (int mode : modes) {
                    for (int face : faces) {
                        int state = PipelineState.AssemblyRasterState.encode(cull, topology, mode, face);
                        String what = "topology " + topology + " cull " + cull + " mode " + mode + " face " + face;
                        assertEquals(topology, PipelineState.AssemblyRasterState.decodeTopology(state), what);
                        assertEquals(cull, PipelineState.AssemblyRasterState.decodeCullMode(state), what);
                        assertEquals(mode, PipelineState.AssemblyRasterState.decodePolygonMode(state), what);
                        assertEquals(face, PipelineState.AssemblyRasterState.decodeFrontFace(state), what);
                    }
                }
            }
        }
    }

    @Test
    void frontFaceCullingKeepsATriangleList() {
        int state = PipelineState.AssemblyRasterState.encode(VK_CULL_MODE_FRONT_BIT, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL,
                VK_FRONT_FACE_COUNTER_CLOCKWISE);
        assertEquals(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, PipelineState.AssemblyRasterState.decodeTopology(state));
    }

    @Test
    void theFieldsDoNotOverlap() {
        int topology = PipelineState.AssemblyRasterState.TOPOLOGY_MASK << PipelineState.AssemblyRasterState.TOPOLOGY_OFFSET;
        int cull = PipelineState.AssemblyRasterState.CULL_MODE_MASK << PipelineState.AssemblyRasterState.CULL_MODE_OFFSET;
        int face = PipelineState.AssemblyRasterState.FRONT_FACE_MASK << PipelineState.AssemblyRasterState.FRONT_FACE_OFFSET;
        int mode = PipelineState.AssemblyRasterState.POLYGON_MODE_MASK;
        assertEquals(0, topology & cull);
        assertEquals(0, topology & mode);
        assertEquals(0, cull & face);
        assertEquals(0, mode & cull);
    }

    @Test
    void onlyLinePipelinesTakeTheLineWidth() {
        assertTrue(PipelineState.AssemblyRasterState.drawsLines(PipelineState.AssemblyRasterState.encode(VK_CULL_MODE_FRONT_BIT,
                VK_PRIMITIVE_TOPOLOGY_LINE_LIST, VK_POLYGON_MODE_FILL, VK_FRONT_FACE_COUNTER_CLOCKWISE)));
        assertTrue(PipelineState.AssemblyRasterState.drawsLines(PipelineState.AssemblyRasterState.encode(VK_CULL_MODE_NONE,
                VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_LINE, VK_FRONT_FACE_COUNTER_CLOCKWISE)));
        assertFalse(PipelineState.AssemblyRasterState.drawsLines(PipelineState.AssemblyRasterState.encode(VK_CULL_MODE_FRONT_AND_BACK,
                VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_POLYGON_MODE_FILL, VK_FRONT_FACE_COUNTER_CLOCKWISE)));
    }
}
