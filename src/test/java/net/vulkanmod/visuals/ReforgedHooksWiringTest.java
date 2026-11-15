package net.vulkanmod.visuals;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static net.vulkanmod.visuals.Bytecode.calls;
import static net.vulkanmod.visuals.Bytecode.inOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Reforged-class mixins of the port, now source edits: the plant and light-source marks, and the mesh upload report. */
class ReforgedHooksWiringTest {
    private static final String BLOCK = "net/vulkanmod/render/chunk/build/BlockRenderer";

    /** The static putQuadData packs each vertex colour with the mark as its alpha. */
    @Test
    void theQuadColourCarriesTheMark() throws Exception {
        MethodNode put = null;
        for (MethodNode m : Bytecode.read(BLOCK).methods) {
            if (m.name.equals("putQuadData") && (m.access & Opcodes.ACC_STATIC) != 0) put = m;
        }
        assertTrue(put != null, "static putQuadData");
        boolean marked = false;
        for (AbstractInsnNode insn : put.instructions) {
            if (insn instanceof MethodInsnNode c && c.name.equals("pack") && c.owner.endsWith("ColorUtil$RGBA")) {
                MethodInsnNode before = Bytecode.previousCall(c);
                //2026-10-21: the mark with the wind's vertex marks (vertexAlpha starts from markedAlpha)
                assertEquals("FoliageMarker.vertexAlpha", Bytecode.name(before), "the alpha argument is the mark");
                marked = true;
            }
        }
        assertTrue(marked);
    }

    @Test
    void eachBlockNotesItsEmissionFirst() throws Exception {
        //Session 23: the marks moved to setMarks, which renderFrapi (a FRAPI model through vanilla's block renderer) calls too
        List<String> marks = calls(BLOCK, "setMarks");
        assertTrue(inOrder(marks, List.of("BlockState.getLightEmission", "FoliageMarker.setEmissive")), marks.toString());
        List<String> c = calls(BLOCK, "renderBatched");
        assertTrue(inOrder(c, List.of("BlockRenderer.setMarks", "BlockRenderer.tessellateBlock")), c.toString());
        List<String> frapi = calls(BLOCK, "renderFrapi");
        assertTrue(inOrder(frapi, List.of("BlockRenderer.setMarks", "BlockRenderDispatcher.renderBatched")), frapi.toString());
    }

    /** 2026-10-15: a full mesh upload of the current section releases its held light change. */
    @Test
    void aFullMeshUploadIsReported() throws Exception {
        List<String> c = calls("net/vulkanmod/render/chunk/build/TaskDispatcher", "doSectionUpdate");
        assertTrue(inOrder(c, List.of("CompileResult.matchesCurrentSection", "CompileResult.updateSection", "LightUpdates.meshUploaded")), c.toString());
        assertEquals(1, Bytecode.count(c, "LightUpdates.meshUploaded"));
    }
}
