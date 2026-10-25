package net.vulkanmod.visuals.dh;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The DH API is a compile-only dependency: only classes under visuals/dh may name its types, and the presence
 * check itself must load and answer without the API on the class path. Only TYPE references count (class
 * entries, owners, descriptors); a string constant may name a resource path.
 */
class DhPresenceIsolationTest {
    private static final String SEIBEL = "com/seibel/";
    private static final String DH_DIR = "net/vulkanmod/visuals/dh/";
    /** The mixins that take DH 3.3.0 to 3.3.4's render definition over; applied only with such a DH (MixinPlugin.legacyDhMixin). */
    private static final String LEGACY_DH_MIXIN_DIR = "net/vulkanmod/mixin/visuals/legacydh/";
    /** Types only DH's render API (API 7.3, DH 3.3.5) has: DH 3.3.0 lacks them, so a class loaded with it must not name them. */
    private static final List<String> RENDER_API_ONLY = List.of(
            "com/seibel/distanthorizons/api/interfaces/render/renderDef/",
            "com/seibel/distanthorizons/api/objects/util/IDhApiReadOnlyList",
            "com/seibel/distanthorizons/api/objects/render/IDebugBox",
            "com/seibel/distanthorizons/api/enums/config/EDhApiDepthDirection",
            "com/seibel/distanthorizons/api/enums/config/EDhApiDepthRange");
    /** The DH classes used only through the render API (DhRegistration.install); everything else in the package loads with DH 3.3.0 too. */
    private static final List<String> RENDER_API_PATH = List.of("DhRenderDefinition", "DhTerrain", "DhApiVertexBuffer", "DhApiContainerUniform");

    private static Path mainClasses() throws Exception {
        return Path.of(DhPresence.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /** Every type name a class refers to, as the constant pool, members and annotations spell them. */
    private static List<String> typeReferences(byte[] bytes) {
        List<String> refs = new ArrayList<>();
        ClassReader reader = new ClassReader(bytes);
        char[] buf = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset == 0) continue;
            int tag = reader.readByte(offset - 1);
            if (tag == 7) refs.add(reader.readUTF8(offset, buf));
            else if (tag == 12) refs.add(reader.readUTF8(offset + 2, buf));
        }
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        for (FieldNode f : node.fields) refs.add(f.desc);
        for (MethodNode m : node.methods) {
            refs.add(m.desc);
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) {
                    refs.add(c.owner);
                    refs.add(c.desc);
                } else if (insn instanceof FieldInsnNode f) {
                    refs.add(f.owner);
                    refs.add(f.desc);
                } else if (insn instanceof InvokeDynamicInsnNode d) {
                    refs.add(d.desc);
                }
            }
        }
        if (node.visibleAnnotations != null) {
            for (AnnotationNode a : node.visibleAnnotations) refs.add(a.desc);
        }
        return refs;
    }

    private static void assertNoSeibel(String internalName) throws Exception {
        byte[] bytes = Files.readAllBytes(mainClasses().resolve(internalName + ".class"));
        for (String ref : typeReferences(bytes)) {
            assertTrue(!ref.contains(SEIBEL), internalName + " names " + ref);
        }
    }

    @Test
    void theGateClassesNameNoDhType() throws Exception {
        for (String c : List.of(
                "net/vulkanmod/visuals/dh/DhPresence",
                "net/vulkanmod/mixin/MixinPlugin",
                "net/vulkanmod/mixin/chunk/SectionLayerMixin",
                "net/vulkanmod/mixin/chunk/SectionLayerGuardMixin",
                "net/vulkanmod/mixin/chunk/SectionLayerLateMixin")) {
            assertNoSeibel(c);
        }
    }

    @Test
    void noMainClassOutsideTheDhPackageNamesADhType() throws Exception {
        Path root = mainClasses();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = root.relativize(p).toString().replace(File.separatorChar, '/');
                if (name.startsWith(DH_DIR) || name.startsWith(LEGACY_DH_MIXIN_DIR)) continue;
                for (String ref : typeReferences(Files.readAllBytes(p))) {
                    if (ref.contains(SEIBEL)) {
                        offenders.add(name + " -> " + ref);
                        break;
                    }
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void classesLoadedWithDh330NameNoRenderApiType() throws Exception {
        Path root = mainClasses();
        List<String> offenders = new ArrayList<>();
        List<Path> dirs = List.of(root.resolve(DH_DIR), root.resolve(LEGACY_DH_MIXIN_DIR));
        for (Path dir : dirs) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                    String name = root.relativize(p).toString().replace(File.separatorChar, '/');
                    String simple = name.substring(name.lastIndexOf('/') + 1).replaceAll("[$.].*", "");
                    if (name.startsWith(DH_DIR) && RENDER_API_PATH.contains(simple)) continue;
                    //getDepthDirection's mixin applies only from DH 3.3.2, which has the enum it names
                    if (simple.equals("GlRenderDefinitionDepthDirectionMixin")) continue;
                    for (String ref : typeReferences(Files.readAllBytes(p))) {
                        if (RENDER_API_ONLY.stream().anyMatch(ref::contains)) offenders.add(name + " -> " + ref);
                    }
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void classifyRunsWithoutTheDhApiOnTheClassPath() throws Exception {
        // Only the main class directory, parent = platform loader: neither the API jar nor Forge is visible.
        URL[] only = {mainClasses().toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(only, ClassLoader.getPlatformClassLoader())) {
            Class<?> presence = Class.forName("net.vulkanmod.visuals.dh.DhPresence", true, loader);
            assertTrue(presence.getClassLoader() == loader, "must be loaded by the isolated loader");
            Class<?> state = Class.forName("net.vulkanmod.visuals.dh.DhPresence$State", true, loader);
            Object result = presence.getMethod("classify", boolean.class, boolean.class).invoke(null, false, false);
            assertEquals("ABSENT", result.toString());
            assertEquals(state, result.getClass());
        }
    }
}
