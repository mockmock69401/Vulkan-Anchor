package net.vulkanmod;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A dedicated server constructs every @Mod class whose dist matches. Initializer starts GLFW in its static block and registers
 * client listeners and a config screen, so it is a client-only entry point: a server never loads it (Forge 1.20.1 crashed at
 * construction with "Attempted to load class .../Screen for invalid dist DEDICATED_SERVER", 2026-11-02). The server's entry point
 * is ServerInitializer (Distant Horizons' world generation fixes).
 */
class InitializerDistTest {
    /** Absent from a dedicated server (NeoForge's own client event classes are there, so they are not listed). */
    private static final List<String> CLIENT_ONLY = List.of("net/minecraft/client/", "com/mojang/blaze3d/", "org/lwjgl/",
            "net/vulkanmod/Initializer");

    @Test
    void initializerIsAClientOnlyEntryPoint() throws Exception {
        assertEquals(List.of(List.of("Lnet/neoforged/api/distmarker/Dist;", "CLIENT")), dist("net/vulkanmod/Initializer.class"));
    }

    @Test
    void serverInitializerIsTheServersEntryPoint() throws Exception {
        assertEquals(List.of(List.of("Lnet/neoforged/api/distmarker/Dist;", "DEDICATED_SERVER")), dist("net/vulkanmod/ServerInitializer.class"));
    }

    // 2026-11-02: these run on a dedicated server, so they name no client type and not Initializer (loading it starts GLFW)
    @Test
    void serverSideClassesNameNoClientType() throws Exception {
        for (String c : List.of("net/vulkanmod/ServerInitializer", "net/vulkanmod/visuals/dh/DhPresence", "net/vulkanmod/visuals/dh/DhWorldGenGuard",
                "net/vulkanmod/visuals/dh/DhWorldGenIsolation", "net/vulkanmod/visuals/VisualsLog",
                "net/vulkanmod/mixin/visuals/WorldGenRegionBlendingMixin", "net/vulkanmod/mixin/visuals/ServerChunkCacheDhIsolationMixin")) {
            List<String> found = new ArrayList<>();
            for (String s : utf8Constants(c + ".class")) {
                for (String prefix : CLIENT_ONLY) if (s.contains(prefix + (prefix.endsWith("/") ? "" : ";")) || s.equals(prefix)) found.add(s);
            }
            assertEquals(List.of(), found, c);
        }
    }

    private Object dist(String resource) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " not compiled");
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
        }
        AnnotationNode mod = node.visibleAnnotations.stream()
                .filter(a -> a.desc.equals("Lnet/neoforged/fml/common/Mod;")).findFirst().orElseThrow();
        Object dist = null;
        for (int i = 0; i < mod.values.size(); i += 2) if (mod.values.get(i).equals("dist")) dist = mod.values.get(i + 1);
        return toLists(dist);
    }

    // An enum array value arrives as a List of String[] {descriptor, name}
    private static Object toLists(Object value) {
        if (!(value instanceof List<?> list)) return value;
        return list.stream().map(e -> e instanceof String[] pair ? List.of(pair) : e).toList();
    }

    // Every name and descriptor a class uses sits in its constant pool as a UTF-8 entry
    private List<String> utf8Constants(String resource) throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource + " not compiled");
            bytes = in.readAllBytes();
        }
        ClassReader reader = new ClassReader(bytes);
        List<String> out = new ArrayList<>();
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset == 0 || bytes[offset - 1] != 1) continue;
            int length = reader.readUnsignedShort(offset);
            out.add(new String(bytes, offset + 2, length, StandardCharsets.UTF_8));
        }
        return out;
    }
}
