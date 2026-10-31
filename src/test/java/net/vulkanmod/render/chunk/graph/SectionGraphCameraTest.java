package net.vulkanmod.render.chunk.graph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10-31 (the user: Tensura's boss cutscenes showed only sky): Bosses Rise moves the cinematic camera by overriding
 * Camera.getPosition() only; getBlockPosition() kept the player's block. The graph started its search there, a section beside and
 * behind the cinematic camera, which the frustum rejected, so no terrain and no entity was drawn for the whole cutscene.
 */
class SectionGraphCameraTest {
    private static final String GRAPH = "net/vulkanmod/render/chunk/graph/SectionGraph";

    @Test
    void theCameraBlockIsTheBlockHoldingTheCameraPosition() {
        assertEquals(new BlockPos(601, 208, -4011), SectionGraph.cameraBlock(new Vec3(601.81, 208.09, -4010.83)));
        assertEquals(new BlockPos(-1, -1, -1), SectionGraph.cameraBlock(new Vec3(-0.5, -0.5, -0.5)));
    }

    @Test
    void theSearchStartsFromTheCameraPositionNotItsBlockField() throws Exception {
        for (String method : List.of("update", "initializeQueueForFullUpdate")) {
            List<String> calls = Bytecode.calls(GRAPH, method);
            assertFalse(calls.contains("Camera.getBlockPosition"), method + ": " + calls);
        }
        assertTrue(Bytecode.calls(GRAPH, "initializeQueueForFullUpdate").contains("SectionGraph.cameraBlock"));
    }
}
