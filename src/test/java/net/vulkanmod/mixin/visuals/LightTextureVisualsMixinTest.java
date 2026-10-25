package net.vulkanmod.mixin.visuals;

import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static net.vulkanmod.visuals.Bytecode.calls;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Moonlight design 3.1 and P7j design 3.4: the lightmap mixin raises the night sky factor and flattens the sky brightness. */
class LightTextureVisualsMixinTest {
    private static final String MIXIN = "net/vulkanmod/mixin/visuals/LightTextureVisualsMixin";

    private static AnnotationNode annotation(MethodNode m, String desc) {
        return m.visibleAnnotations.stream().filter(n -> n.desc.equals(desc)).findFirst().orElseThrow();
    }

    /**
     * Review focus 3: in vanilla's updateLightTexture, slot 4 is stored exactly twice, with 1 (lightning flash) and with
     * skyDarken * 0.95 + 0.05. If an update moves the factor, this fails instead of the mixin changing another variable.
     */
    @Test
    void slotFourIsTheSkyFactor() throws Exception {
        MethodNode update = Bytecode.read("net/minecraft/client/renderer/LightTexture").methods.stream()
                .filter(m -> m.name.equals("updateLightTexture") && m.desc.equals("(F)V")).findFirst().orElseThrow();
        Set<Integer> stored = new HashSet<>();
        int stores = 0;
        for (AbstractInsnNode insn : update.instructions) {
            if (insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.FSTORE && v.var == 4) {
                stores++;
                AbstractInsnNode p = Bytecode.previous(insn);
                stored.add(p.getOpcode());
                if (p.getOpcode() == Opcodes.FADD) {
                    assertTrue(Bytecode.previous(p) instanceof LdcInsnNode l && Float.valueOf(0.05f).equals(l.cst), "skyDarken * 0.95 + 0.05");
                }
            }
        }
        assertEquals(2, stores);
        assertEquals(Set.of(Opcodes.FCONST_1, Opcodes.FADD), stored);
    }

    @Test
    void modifiesSlotFourOfUpdateLightTextureOnStore() throws Exception {
        List<Object> values = annotation(Bytecode.method(MIXIN, "visuals$moonlight"), "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;").values;
        assertEquals(List.of("updateLightTexture"), values.get(values.indexOf("method") + 1));
        assertEquals(4, values.get(values.indexOf("index") + 1));
        AnnotationNode at = (AnnotationNode) values.get(values.indexOf("at") + 1);
        assertEquals("STORE", at.values.get(at.values.indexOf("value") + 1));
    }

    @Test
    void appliesOnlyWithASkyNoFlashThePostProcessAndTheRenderer() throws Exception {
        List<String> c = calls(MIXIN, "visuals$moonlight");
        assertTrue(c.containsAll(List.of("DimensionType.hasSkyLight", "ClientLevel.getSkyFlashTime", "PostProcessSettings.enabled",
                "Visuals.active", "PostProcessSettings.moonlight", "ClientLevel.getTimeOfDay", "ClientLevel.getRainLevel", "ClientLevel.getThunderLevel",
                "Moonlight.skyFactor")), c.toString());
    }

    /** The first getBrightness call of each texel (the sky's) goes through SunShadows. */
    @Test
    void theSkyBrightnessIsFlattenedWhileTheShadowsRun() throws Exception {
        MethodNode m = Bytecode.method(MIXIN, "visuals$skyBrightness");
        List<Object> values = annotation(m, "Lorg/spongepowered/asm/mixin/injection/Redirect;").values;
        assertEquals(List.of("updateLightTexture"), values.get(values.indexOf("method") + 1));
        AnnotationNode at = (AnnotationNode) values.get(values.indexOf("at") + 1);
        assertEquals("Lnet/minecraft/client/renderer/LightTexture;getBrightness(Lnet/minecraft/world/level/dimension/DimensionType;I)F",
                at.values.get(at.values.indexOf("target") + 1));
        assertEquals(0, at.values.get(at.values.indexOf("ordinal") + 1));
        assertTrue(calls(MIXIN, "visuals$skyBrightness").contains("SunShadows.skyBrightness"));
    }
}
