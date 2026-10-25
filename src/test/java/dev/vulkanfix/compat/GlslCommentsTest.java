package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reforged's GlslConverter reads uniforms token by token and takes a trailing comment for part of the declaration:
 * Fancy Trail's composite.fsh "uniform sampler2D DiffuseSampler; //particle" throws "not admitted type: //particle",
 * which fails NightfallCraft's mod loading.
 */
class GlslCommentsTest {
    @Test
    void dropsTrailingLineComment() {
        assertEquals("uniform sampler2D DiffuseSampler; \nuniform sampler2D DiffuseSampler2; \n",
                GlslComments.strip("uniform sampler2D DiffuseSampler; //particle\nuniform sampler2D DiffuseSampler2; //original\n"));
    }

    @Test
    void dropsBlockCommentsButKeepsTheirLineBreaks() {
        String src = "uniform float A; /* one\ntwo */ uniform float B;\nvoid main() {}\n";
        String out = GlslComments.strip(src);
        assertEquals("uniform float A;  \n uniform float B;\nvoid main() {}\n", out);
        assertEquals(src.split("\n", -1).length, out.split("\n", -1).length);
    }

    @Test
    void leavesCodeWithoutCommentsUntouched() {
        String src = "#version 150\n\nuniform vec4 ColorModulator;\nout vec4 fragColor;\nvoid main() { fragColor = ColorModulator / 2.0; }\n";
        assertEquals(src, GlslComments.strip(src));
    }

    @Test
    void unterminatedBlockCommentRunsToTheEnd() {
        assertEquals("uniform float A;  \n", GlslComments.strip("uniform float A; /* open\n"));
    }

    @Test
    void mixinStripsBothShaderStagesOfReforgedsConverter() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = GlslCommentsTest.class.getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/reforged/GlslCommentStripMixin.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, 0);
        }
        assertEquals("Lorg/spongepowered/asm/mixin/Mixin;", node.invisibleAnnotations.get(0).desc);
        List<Integer> ordinals = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (m.visibleAnnotations == null) {
                continue;
            }
            for (AnnotationNode a : m.visibleAnnotations) {
                if (a.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;")) {
                    assertEquals(List.of("process"), value(a, "method"));
                    assertEquals(true, value(a, "argsOnly"));
                    ordinals.add((Integer) value(a, "ordinal"));
                }
            }
        }
        ordinals.sort(null);
        assertEquals(List.of(0, 1), ordinals);
    }

    private static Object value(AnnotationNode a, String key) {
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            if (a.values.get(i).equals(key)) {
                return a.values.get(i + 1);
            }
        }
        return null;
    }
}
