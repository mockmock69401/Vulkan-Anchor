package net.vulkanmod.mixin.visuals;

import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static net.vulkanmod.visuals.Bytecode.calls;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W9 test 5: the jitter mixin is registered, targets GameRenderer and changes only the level's projection. */
class GameRendererJitterMixinTest {
    private static final String MIXIN = "net/vulkanmod/mixin/visuals/GameRendererJitterMixin";
    private static final String RESET = "resetProjectionMatrix";

    private static Object value(AnnotationNode a, String key) {
        for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(key)) return a.values.get(i + 1);
        return null;
    }

    @Test
    void theMixinIsListedAndTargetsGameRenderer() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanmod.mixins.json")) {
            assertNotNull(in);
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"visuals.GameRendererJitterMixin\""));
        }
        ClassNode node = Bytecode.read(MIXIN);
        List<AnnotationNode> all = new ArrayList<>();
        if (node.visibleAnnotations != null) all.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) all.addAll(node.invisibleAnnotations);
        AnnotationNode mixin = all.stream().filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
        assertEquals(List.of(Type.getType("Lnet/minecraft/client/renderer/GameRenderer;")), value(mixin, "value"));
    }

    @Test
    void itModifiesTheProjectionRenderLevelResetsAndAppliesTaaJitter() throws Exception {
        MethodNode handler = Bytecode.method(MIXIN, "visuals$jitter");
        AnnotationNode modify = handler.visibleAnnotations.stream().filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyArg;"))
                .findFirst().orElseThrow();
        assertEquals(List.of("renderLevel"), value(modify, "method"));
        assertEquals(0, value(modify, "index"));
        AnnotationNode at = (AnnotationNode) value(modify, "at");
        assertEquals("INVOKE", value(at, "value"));
        assertEquals("Lnet/minecraft/client/renderer/GameRenderer;" + RESET + "(Lorg/joml/Matrix4f;)V", value(at, "target"));
        assertTrue(calls(MIXIN, "visuals$jitter").contains("TaaJitter.apply"));
    }

    /** Final fix R4: the jitter is scaled by the bound (render-scaled) main framebuffer, with the window only as the fallback. */
    @Test
    void theJitterTakesTheLevelsFramebufferSize() throws Exception {
        List<String> calls = calls(MIXIN, "visuals$jitter");
        assertTrue(Bytecode.inOrder(calls, List.of("VkContext.captureMainTarget", "TaaJitter.levelSize", "TaaJitter.apply")), calls.toString());
    }

    /** Vanilla calls resetProjectionMatrix once in renderLevel (the level's projection); the hand's call is in another method. */
    @Test
    void renderLevelResetsTheProjectionExactlyOnceAndTheHandDoesSoElsewhere() throws Exception {
        ClassNode gr = Bytecode.read("net/minecraft/client/renderer/GameRenderer");
        int inLevel = 0, elsewhere = 0;
        for (MethodNode m : gr.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c && c.name.equals(RESET) && c.desc.equals("(Lorg/joml/Matrix4f;)V")) {
                    if (m.name.equals("renderLevel")) inLevel++;
                    else elsewhere++;
                }
            }
        }
        assertEquals(1, inLevel);
        assertTrue(elsewhere >= 1, "renderItemInHand sets its own projection");
    }

    /** The matrix offset in place is the local renderLevel then hands to LevelRenderer.renderLevel (terrain, sky, LOD renderer, visuals frame). */
    @Test
    void theJitteredMatrixIsTheOneTheLevelRendererReceives() throws Exception {
        MethodNode level = Bytecode.read("net/minecraft/client/renderer/GameRenderer").methods.stream()
                .filter(m -> m.name.equals("renderLevel")).findFirst().orElseThrow();
        int reset = -1, levelArg = -1;
        for (AbstractInsnNode insn : level.instructions) {
            if (!(insn instanceof MethodInsnNode c)) continue;
            AbstractInsnNode p = Bytecode.previous(insn);
            if (c.name.equals(RESET)) reset = ((VarInsnNode) p).var;
            if (c.owner.equals("net/minecraft/client/renderer/LevelRenderer") && c.name.equals("renderLevel")) levelArg = ((VarInsnNode) p).var;
        }
        assertTrue(reset >= 0);
        assertEquals(reset, levelArg);
    }
}
