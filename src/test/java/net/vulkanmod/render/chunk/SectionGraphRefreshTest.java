package net.vulkanmod.render.chunk;

import net.vulkanmod.render.chunk.build.task.CompileResult;
import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DH stage 1 gate A3: an uncompiled section has no visibility, so the section graph's walk stops at it. When its mesh lands
 * and opens a way through, the graph must be walked again, or a still camera keeps a partial graph (whole sections missing)
 * until something else asks for an update.
 */
class SectionGraphRefreshTest {
    private static final String WORLD = "net/vulkanmod/render/chunk/WorldRenderer";
    private static final String DISPATCHER = "net/vulkanmod/render/chunk/build/TaskDispatcher";

    @Test
    void aMeshThatChangesVisibilityEmptinessOrBlockEntitiesChangesTheGraph() {
        assertTrue(CompileResult.changesGraph(0L, true, false, 0x3fL, false, false), "first mesh of an uncompiled section");
        assertTrue(CompileResult.changesGraph(0x3fL, false, false, 0x3fL, true, false), "emptiness alone");
        assertTrue(CompileResult.changesGraph(0x3fL, false, false, 0x0fL, false, false), "visibility alone");
        //the walk collects the block entity sections: a first chest in a section must be walked to be drawn
        assertTrue(CompileResult.changesGraph(0x3fL, false, false, 0x3fL, false, true), "block entities gained");
        assertTrue(CompileResult.changesGraph(0x3fL, false, true, 0x3fL, false, false), "block entities lost");
        assertFalse(CompileResult.changesGraph(0x3fL, false, true, 0x3fL, false, true), "a rebuild with the same shape");
    }

    @Test
    void theDispatcherAsksBeforeApplyingTheMesh() throws Exception {
        List<String> c = Bytecode.calls(DISPATCHER, "doSectionUpdate");
        assertTrue(Bytecode.inOrder(c, List.of("CompileResult.matchesCurrentSection", "CompileResult.changesGraph", "CompileResult.updateSection")), c.toString());
    }

    /** uploadSections sets graphNeedsUpdate to true from updateSections' answer (the line Reforged dropped). */
    @Test
    void appliedMeshesScheduleAGraphUpdate() throws Exception {
        MethodNode setup = Bytecode.method(WORLD, "uploadSections");
        boolean called = false, scheduled = false;
        for (AbstractInsnNode insn : setup.instructions) {
            if (insn instanceof MethodInsnNode m && m.name.equals("updateSections") && m.owner.equals(DISPATCHER)) called = true;
            if (called && insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("graphNeedsUpdate")) {
                AbstractInsnNode value = Bytecode.previous(f);
                scheduled |= value instanceof InsnNode i && i.getOpcode() == Opcodes.ICONST_1;
                break;
            }
        }
        assertTrue(called, "uploadSections applies the compiled meshes");
        assertTrue(scheduled, "and schedules a graph update when they change it");
        assertEquals("Z", returnType(Bytecode.method(DISPATCHER, "updateSections")));
    }

    private static String returnType(MethodNode m) {
        return m.desc.substring(m.desc.indexOf(')') + 1);
    }
}
