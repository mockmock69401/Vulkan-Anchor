package net.vulkanmod.visuals.dh;

import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ruling R15: DH calls the terrain renderer before the visuals frame has recorded DH's staged copies, so the renderer only keeps
 * the frame and every draw is recorded in recordOffscreen, after the flush, inside the LOD target's pass.
 */
class DhExtensionWiringTest {
    private static final String TERRAIN = "net/vulkanmod/visuals/dh/DhTerrain";
    private static final String EXTENSION = "net/vulkanmod/visuals/dh/DhExtension";

    @Test
    void theTerrainCallbackRecordsNothing() throws Exception {
        List<String> calls = Bytecode.calls(TERRAIN, "render");
        assertFalse(calls.isEmpty());
        for (String c : calls) {
            assertFalse(c.substring(c.indexOf('.') + 1).startsWith("vkCmd"), "render records " + c);
            assertFalse(c.startsWith("LodTarget.") || c.startsWith("VkGraphicsProgram."), "render touches the target: " + c);
        }
        assertEquals(1, Bytecode.count(calls, "DhFrameCapture.store"), "render keeps the frame");
    }

    @Test
    void recordOffscreenDrawsInsideTheTargetPassAfterTheFlush() throws Exception {
        List<String> calls = Bytecode.calls(EXTENSION, "recordOffscreen");
        assertTrue(Bytecode.inOrder(calls, List.of("DhStaging.flush", "LodTarget.beginPass", "DhExtension.drawAll", "LodTarget.endPass")), calls.toString());
        assertTrue(Bytecode.calls(EXTENSION, "drawAll").contains("VK10.vkCmdDrawIndexed"));
        //Each drawAll sits between a beginPass and the endPass after it
        boolean open = false;
        for (String c : calls) {
            if (c.equals("LodTarget.beginPass")) open = true;
            if (c.equals("LodTarget.endPass")) open = false;
            if (c.equals("DhExtension.drawAll") || c.startsWith("VK10.vkCmdDraw")) assertTrue(open, "a draw outside the pass: " + calls);
        }
    }

    /** The composite goes into the main pass, and only there. */
    @Test
    void theCompositeIsDrawnInDrawIntoMain() throws Exception {
        assertEquals(1, Bytecode.count(Bytecode.calls(EXTENSION, "drawIntoMain"), "LodComposite.draw"));
        assertEquals(0, Bytecode.count(Bytecode.calls(EXTENSION, "recordOffscreen"), "LodComposite.draw"));
    }

    /** Ruling R20 (stage 2): the composite's fog is this frame's vanilla fog through compositeFog, never the raw vanilla range. */
    @Test
    void theCompositeFogGoesThroughCompositeFog() throws Exception {
        List<String> calls = Bytecode.calls(EXTENSION, "prepare");
        assertTrue(Bytecode.inOrder(calls, List.of("FogParameters.fromRenderSystem", "DhExtension.compositeFog", "LodCompositeUniforms.compute")), calls.toString());
        assertTrue(calls.contains("DhExtension.environmentalFogNow"), calls.toString());
    }

    /** The composite, the lighting and the atmosphere judge the fog against one render distance: the visuals' helper. */
    @Test
    void theRenderDistanceIsTheVisualsOne() throws Exception {
        assertTrue(Bytecode.calls(EXTENSION, "prepare").contains("VisualsRenderer.vanillaRenderDistance"));
        for (org.objectweb.asm.tree.MethodNode m : Bytecode.read(EXTENSION).methods) {
            assertFalse(m.name.equals("vanillaRenderDistance"), "DhExtension keeps its own copy");
        }
    }
}
