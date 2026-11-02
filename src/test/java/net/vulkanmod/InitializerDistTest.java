package net.vulkanmod;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A dedicated server constructs the @Mod class too. Forge links Initializer to find its constructor, and the verifier loads the
 * types its methods hand around: a config-screen lambda returning a Screen stopped the server ("Attempted to load class
 * net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER", 2026-11-02). Initializer names no client type;
 * the client setup lives in ClientInitializer, called only on the client.
 */
class InitializerDistTest {
    private static final List<String> CLIENT_ONLY = List.of(
            "net/minecraft/client/", "com/mojang/blaze3d/", "net/minecraftforge/client/", "org/lwjgl/");
    private static final Set<String> OWN_TYPES = Set.of(
            "net/vulkanmod/Initializer", "net/vulkanmod/ClientInitializer", "net/vulkanmod/config/Config");

    @Test
    void initializerNamesNoClientType() throws Exception {
        List<String> found = new ArrayList<>();
        for (String s : utf8Constants("net/vulkanmod/Initializer.class")) {
            for (String prefix : CLIENT_ONLY) if (s.contains(prefix)) found.add(s);
        }
        assertEquals(List.of(), found);
    }

    @Test
    void initializerNamesOnlyServerSafeOwnTypes() throws Exception {
        List<String> found = new ArrayList<>();
        for (String s : utf8Constants("net/vulkanmod/Initializer.class")) {
            int at = s.indexOf("net/vulkanmod/");
            while (at >= 0) {
                int end = at;
                while (end < s.length() && (Character.isJavaIdentifierPart(s.charAt(end)) || s.charAt(end) == '/')) end++;
                String type = s.substring(at, end);
                if (!OWN_TYPES.contains(type)) found.add(type);
                at = s.indexOf("net/vulkanmod/", end);
            }
        }
        assertEquals(List.of(), found);
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
