package net.vulkanmod.render.chunk;

import net.minecraft.world.phys.Vec3;
import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10-31 (the user: Tensura's boss cutscenes showed only sky): the section grid followed the camera, so a cinematic camera that
 * spent one frame ~230 blocks away (Bosses Rise interpolates from the origin) reset every section around the boss; the boss's own
 * section was never rebuilt (the graph only rebuilds what it reaches), vanilla does not draw entities in uncompiled sections, and the
 * camera bone that moves the cutscene never moved. Vanilla's ViewArea follows the player (LevelRenderer.setupRender).
 */
class GridCentreTest {
    @Test
    void theGridFollowsThePlayer() {
        Vec3 player = new Vec3(590.5, 200, -4000.5), camera = new Vec3(565.7, 195.6, -3770.3);
        assertSame(player, WorldRenderer.gridCentre(player, camera));
    }

    @Test
    void withoutAPlayerItFollowsTheCamera() {
        Vec3 camera = new Vec3(1, 2, 3);
        assertSame(camera, WorldRenderer.gridCentre(null, camera));
    }

    @Test
    void setupRendererRepositionsAroundTheGridCentre() throws Exception {
        List<String> calls = Bytecode.calls("net/vulkanmod/render/chunk/WorldRenderer", "setupRenderer");
        assertTrue(calls.contains("WorldRenderer.gridCentre"), calls.toString());
        assertTrue(calls.indexOf("WorldRenderer.gridCentre") < calls.indexOf("SectionGrid.repositionCamera"), calls.toString());
    }
}
