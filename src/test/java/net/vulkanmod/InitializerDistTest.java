package net.vulkanmod;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A dedicated server constructs every @Mod class whose dist matches. Initializer starts GLFW in its static block and registers
 * client listeners and a config screen, so it is a client-only entry point: a server never loads it (Forge 1.20.1 crashed at
 * construction with "Attempted to load class .../Screen for invalid dist DEDICATED_SERVER", 2026-11-02).
 */
class InitializerDistTest {
    @Test
    void initializerIsAClientOnlyEntryPoint() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("net/vulkanmod/Initializer.class")) {
            assertNotNull(in, "Initializer not compiled");
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
        }
        AnnotationNode mod = node.visibleAnnotations.stream()
                .filter(a -> a.desc.equals("Lnet/neoforged/fml/common/Mod;")).findFirst().orElseThrow();
        Object dist = null;
        for (int i = 0; i < mod.values.size(); i += 2) if (mod.values.get(i).equals("dist")) dist = mod.values.get(i + 1);
        assertEquals(List.of(List.of("Lnet/neoforged/api/distmarker/Dist;", "CLIENT")), toLists(dist));
    }

    // An enum array value arrives as a List of String[] {descriptor, name}
    private static Object toLists(Object value) {
        if (!(value instanceof List<?> list)) return value;
        return list.stream().map(e -> e instanceof String[] pair ? List.of(pair) : e).toList();
    }
}
