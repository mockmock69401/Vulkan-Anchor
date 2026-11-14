package net.vulkanmod.compat.render;

import net.vulkanmod.vulkan.VRenderSystem;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-14 (session 21, the advancement plaque without its icon): a GUI item is drawn with face culling as the game and mods last set
 * it through GL calls (VRenderSystem.glCull), as GL has it, not always on. Iceberg turns culling off to draw Advancement Plaques' icons
 * with a transform that winds the front face clockwise; forced on, that face was culled (ModernFix's faster item rendering draws only it,
 * so the icon was empty). VulkanMod's own framebuffer binds, which turn culling off where GL keeps it, do not count.
 */
class GuiItemRenderStateTest {
    private static final String STATE = "net/vulkanmod/compat/render/GuiItemRenderState.class";
    private static final String VRS = "net/vulkanmod/vulkan/VRenderSystem";

    @Test
    void prepareTakesTheGlCullStateBack() throws Exception {
        MethodNode prepare = method(STATE, "prepare");
        assertFalse(calls(prepare, "com/mojang/blaze3d/systems/RenderSystem", "enableCull"), "prepare must not force culling on");
        assertTrue(field(prepare, Opcodes.GETSTATIC, "glCull"), "prepare reads VRenderSystem.glCull");
        assertTrue(field(prepare, Opcodes.PUTSTATIC, "cull"), "prepare sets VRenderSystem.cull from it");
    }

    @Test
    void tooltipOverlayStillDrawsBothFaces() throws Exception {
        assertTrue(calls(method(STATE, "prepare"), VRS, "disableCull"));
    }

    @Test
    void vulkanModsOwnBoundariesLeaveTheGlCullState() throws Exception {
        //Each screen's state boundary turned GL culling off, so screens' items would have lost culling: glass showed its back edges
        String[] internal = {"net/vulkanmod/mixin/screen/ScreenM.class", STATE, "net/vulkanmod/compat/render/GuiEntityRenderState.class",
                "net/vulkanmod/vulkan/util/DrawUtil.class", "dev/vulkanfix/mixin/reforged/RenderScaleBlitMixin.class"};
        for (String resource : internal) {
            for (MethodNode m : read(resource).methods) {
                assertFalse(calls(m, "com/mojang/blaze3d/systems/RenderSystem", "disableCull"), resource + "." + m.name);
                assertFalse(calls(m, "com/mojang/blaze3d/systems/RenderSystem", "enableCull"), resource + "." + m.name);
            }
        }
    }

    @Test
    void snapshotsKeepTheGlCullState() throws Exception {
        String snapshot = "net/vulkanmod/compat/render/RenderStateSnapshot.class";
        assertTrue(field(method(snapshot, "<init>"), Opcodes.GETSTATIC, "glCull"));
        assertTrue(field(method(snapshot, "restore"), Opcodes.PUTSTATIC, "glCull"));
    }

    @Test
    void glCallsSetTheGlCullState() {
        boolean cull = VRenderSystem.cull, glCull = VRenderSystem.glCull;
        try {
            VRenderSystem.disableCullGl();
            assertFalse(VRenderSystem.cull);
            assertFalse(VRenderSystem.glCull);
            //a framebuffer bind (GlFramebuffer.beginRendering) switches only the Vulkan state
            VRenderSystem.enableCullGl();
            VRenderSystem.disableCull();
            assertFalse(VRenderSystem.cull);
            assertTrue(VRenderSystem.glCull);
        } finally {
            VRenderSystem.cull = cull;
            VRenderSystem.glCull = glCull;
        }
    }

    @Test
    void glEntryPointsUseTheGlCullCalls() throws Exception {
        String[][] entries = {
                {"net/vulkanmod/mixin/render/RenderSystemMixin.class", "enableCull", "enableCullGl"},
                {"net/vulkanmod/mixin/render/RenderSystemMixin.class", "disableCull", "disableCullGl"},
                {"net/vulkanmod/mixin/render/GlStateManagerM.class", "_enableCull", "enableCullGl"},
                {"net/vulkanmod/mixin/render/GlStateManagerM.class", "_disableCull", "disableCullGl"},
        };
        for (String[] e : entries) {
            MethodNode m = method(e[0], e[1]);
            assertTrue(calls(m, VRS, e[2]), e[0] + "." + e[1] + " must call VRenderSystem." + e[2]);
        }
        assertTrue(anyCall("net/vulkanmod/mixin/compatibility/gl/GL11M.class", "enableCullGl"));
        assertTrue(anyCall("net/vulkanmod/mixin/compatibility/gl/GL11M.class", "disableCullGl"));
        assertTrue(anyCall("net/vulkanmod/compat/opengl/GlFunctionRegistry.class", "enableCullGl")
                || anyCallInNested("net/vulkanmod/compat/opengl/GlFunctionRegistry", "enableCullGl"));
    }

    private static boolean calls(MethodNode m, String owner, String name) {
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean field(MethodNode m, int opcode, String name) {
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof FieldInsnNode f && f.owner.equals(VRS) && f.name.equals(name) && f.getOpcode() == opcode) {
                return true;
            }
        }
        return false;
    }

    private boolean anyCall(String resource, String name) throws Exception {
        for (MethodNode m : read(resource).methods) {
            if (calls(m, VRS, name)) {
                return true;
            }
        }
        return false;
    }

    /** GlFunctionRegistry registers its GL functions as lambdas, possibly in nested classes. */
    private boolean anyCallInNested(String owner, String name) throws Exception {
        for (int i = 1; i < 64; i++) {
            String resource = owner + "$" + i + ".class";
            if (getClass().getClassLoader().getResource(resource) == null) continue;
            if (anyCall(resource, name)) return true;
        }
        return false;
    }

    private MethodNode method(String resource, String name) throws Exception {
        for (MethodNode m : read(resource).methods) {
            if (m.name.equals(name)) {
                return m;
            }
        }
        throw new AssertionError("no method " + name + " in " + resource);
    }

    private ClassNode read(String resource) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " not compiled");
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }
}
