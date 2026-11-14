package net.vulkanmod.compat.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.vulkanmod.vulkan.VRenderSystem;

public final class MaterialCullState {
    private static final int MODEL_DRAW = 1;
    private static final int REPAIRED_CULL = 2;
    private static final int ENTRY_CULL = 4;

    private static int drawState;

    private MaterialCullState() {
    }

    public static int beginDraw(MeshData mesh) {
        RenderSystem.assertOnRenderThread();
        int previous = drawState | (VRenderSystem.cull ? ENTRY_CULL : 0);
        drawState = mesh.drawState().format().contains(VertexFormatElement.NORMAL) ? MODEL_DRAW : 0;
        return previous;
    }

    public static void endDraw(int previous) {
        RenderSystem.assertOnRenderThread();
        try {

            if ((drawState & REPAIRED_CULL) != 0) {
                if ((previous & ENTRY_CULL) != 0) VRenderSystem.enableCull();
                else VRenderSystem.disableCull();
            }
        } finally {
            drawState = previous & (MODEL_DRAW | REPAIRED_CULL);
        }
    }

    public static void setupCull(Runnable originalSetup) {
        if ((drawState & MODEL_DRAW) != 0) {
            //2026-11-14 (session 21): only culling VulkanMod's own state changes left off (glCull still on, as GL has it). A GL call turning
            //it off is kept: Iceberg does, to draw Advancement Plaques' icons, whose front face its transform winds clockwise (repaired, the
            //icon showed its dark back face)
            if (!VRenderSystem.cull && VRenderSystem.glCull) {
                RenderSystem.assertOnRenderThread();
                VRenderSystem.enableCull();
                drawState |= REPAIRED_CULL;
            }
        } else originalSetup.run();
    }
}
