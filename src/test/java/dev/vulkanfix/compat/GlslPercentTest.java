package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reforged's CodeParser splits a code line on " \t\n\r\f,(){}%" and wraps the raw tokens around each "%" in mod():
 * Fancy Trail's rgb_trail.fsh "mix(COLORS[i % 16], layerRainbow, RainbowMix)" turns into "mod(COLORS[i, 16])",
 * which fails to compile and fails NightfallCraft's mod loading. GLSL 4.50 has an integer "%"; keep CodeParser
 * away from it.
 */
class GlslPercentTest {
    private static final File REFORGED = new File("libs/VulkanMod-Reforged_1.20.1-0.2.0-ALPHA.jar");
    private static final String LINE = "        vec3 layerTint = mix(COLORS[i % 16], layerRainbow, RainbowMix);";

    /** Reforged's real CodeParser.parseCodeLine, loaded on its own so its classes stay off the test classpath. */
    private static Method parseCodeLine() throws Exception {
        assumeTrue(REFORGED.isFile(), "VulkanMod-Reforged jar not in libs/");
        URLClassLoader loader = new URLClassLoader(new URL[]{REFORGED.toURI().toURL()}, GlslPercentTest.class.getClassLoader());
        return loader.loadClass("net.vulkanmod.vulkan.shader.parser.CodeParser").getMethod("parseCodeLine", String.class);
    }

    @Test
    void reforgedManglesAnIndexRemainder() throws Exception {
        String out = (String) parseCodeLine().invoke(null, LINE);
        assertTrue(out.contains("mod(COLORS[i, 16])"), out);
    }

    @Test
    void protectedRemainderSurvivesReforgedsParser() throws Exception {
        String out = GlslPercent.restore((String) parseCodeLine().invoke(null, GlslPercent.protect(LINE)));
        assertTrue(out.replace(" ", "").contains("mix(COLORS[i%16],layerRainbow,RainbowMix)"), out);
        assertFalse(out.contains("mod("), out);
        assertFalse(out.contains(GlslPercent.PLACEHOLDER), out);
    }

    @Test
    void protectAndRestoreRoundTrip() {
        String src = "int a = b % 4; a %= 3; c = d%e;";
        assertFalse(GlslPercent.protect(src).contains("%"));
        assertEquals(src, GlslPercent.restore(GlslPercent.protect(src)));
        assertEquals("float x = 1.0;", GlslPercent.protect("float x = 1.0;"));
    }

    @Test
    void mixinWrapsReforgedsParseCodeLine() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = GlslPercentTest.class.getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/reforged/CodeParserPercentMixin.class")) {
            assertNotNull(in);
            new ClassReader(in).accept(node, 0);
        }
        List<String> kinds = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (m.visibleAnnotations == null) {
                continue;
            }
            for (AnnotationNode a : m.visibleAnnotations) {
                if (a.desc.endsWith("/ModifyVariable;") || a.desc.endsWith("/Inject;")) {
                    assertEquals("[parseCodeLine]", String.valueOf(a.values.get(a.values.indexOf("method") + 1)));
                    kinds.add(a.desc.substring(a.desc.lastIndexOf('/') + 1));
                }
            }
        }
        kinds.sort(null);
        assertEquals(List.of("Inject;", "ModifyVariable;"), kinds);
    }
}
