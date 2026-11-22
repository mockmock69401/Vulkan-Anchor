package net.vulkanmod.compat;

import net.vulkanmod.compat.IncompatibleModDisabler.ModJar;
import net.vulkanmod.compat.IncompatibleModDisabler.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What gets turned off at startup, and what the player is told. */
class IncompatibleModDisablerTest {
    private static final Set<String> INCOMPATIBLE = Set.of("embeddium", "sodiumoptionsapi");

    @TempDir
    Path mods;

    private ModJar jar(String name, Set<String> ids, Set<String> required) throws IOException {
        return new ModJar(Files.writeString(mods.resolve(name), "jar"), ids, required);
    }

    @Test
    void aListedModsJarBecomesDisabledAndOthersStay() throws IOException {
        Outcome outcome = IncompatibleModDisabler.disable(List.of(
                jar("embeddium.jar", Set.of("embeddium"), Set.of()),
                jar("create.jar", Set.of("create"), Set.of())), INCOMPATIBLE);

        assertEquals(List.of("embeddium.jar"), outcome.disabled());
        assertFalse(Files.exists(mods.resolve("embeddium.jar")));
        assertTrue(Files.exists(mods.resolve("embeddium.jar.disabled")));
        assertTrue(Files.exists(mods.resolve("create.jar")));
    }

    @Test
    void modIdsCompareWithoutCase() throws IOException {
        Outcome outcome = IncompatibleModDisabler.disable(List.of(jar("e.jar", Set.of("Embeddium"), Set.of())), INCOMPATIBLE);

        assertEquals(List.of("e.jar"), outcome.disabled());
    }

    @Test
    void nothingListedChangesNothing() throws IOException {
        Outcome outcome = IncompatibleModDisabler.disable(List.of(jar("create.jar", Set.of("create"), Set.of())), INCOMPATIBLE);

        assertTrue(outcome.disabled().isEmpty());
        assertTrue(outcome.dependents().isEmpty());
        assertTrue(Files.exists(mods.resolve("create.jar")));
    }

    @Test
    void anOlderDisabledCopyIsNeverOverwritten() throws IOException {
        Path older = Files.writeString(mods.resolve("embeddium.jar.disabled"), "older");

        Outcome outcome = IncompatibleModDisabler.disable(List.of(jar("embeddium.jar", Set.of("embeddium"), Set.of())), INCOMPATIBLE);

        assertEquals(List.of("embeddium.jar"), outcome.disabled());
        assertEquals("older", Files.readString(older));
        assertEquals("jar", Files.readString(mods.resolve("embeddium.jar.1.disabled")));
        assertFalse(Files.exists(mods.resolve("embeddium.jar")));
    }

    @Test
    void anEnabledModRequiringATurnedOffModIsReportedButLeftAlone() throws IOException {
        Outcome outcome = IncompatibleModDisabler.disable(List.of(
                jar("api.jar", Set.of("sodiumoptionsapi"), Set.of()),
                jar("addon.jar", Set.of("addon"), Set.of("SodiumOptionsApi", "forge")),
                jar("create.jar", Set.of("create"), Set.of("forge"))), INCOMPATIBLE);

        assertEquals(List.of("addon.jar"), outcome.dependents());
        assertTrue(Files.exists(mods.resolve("addon.jar")));
    }

    @Test
    void aModNestedInAnotherJarIsReportedAndItsContainerStays() throws IOException {
        Path container = mods.resolve("bundle.zip");
        try (FileSystem zip = FileSystems.newFileSystem(URI.create("jar:" + container.toUri()), Map.of("create", "true"))) {
            Path nested = Files.writeString(zip.getPath("embeddium.jar"), "jar");

            Outcome outcome = IncompatibleModDisabler.disable(List.of(new ModJar(nested, Set.of("embeddium"), Set.of())), INCOMPATIBLE);

            assertEquals(List.of("embeddium.jar"), outcome.notRenamable());
            assertTrue(outcome.disabled().isEmpty());
            assertTrue(Files.exists(nested));
        }
    }

    /** A jar in the mods folder that carries the given jars in META-INF/jarjar, like Sodium 0.8's NeoForge wrapper. */
    private Path wrapper(String name, String... nested) throws IOException {
        Path file = mods.resolve(name);
        try (FileSystem zip = FileSystems.newFileSystem(URI.create("jar:" + file.toUri()), Map.of("create", "true"))) {
            Files.createDirectories(zip.getPath("META-INF/jarjar"));
            Files.writeString(zip.getPath("META-INF/neoforge.mods.toml"), "");
            for (String jar : nested) Files.writeString(zip.getPath("META-INF/jarjar/" + jar), "jar");
        }
        return file;
    }

    /** A nested jar's path as FML hands it over: on the wrapper's own file system. */
    private static Path nested(FileSystem zip, String name) {
        return zip.getPath("META-INF/jarjar/" + name);
    }

    @Test
    void aConflictingModNestedInAWrapperTurnsTheWrapperOff() throws IOException {
        Path sodium = wrapper("sodium-neoforge-0.8.13.jar", "sodium-mod.jar", "fabric-api-base.jar");
        // Open while it is renamed, as FML has it; the JDK's zip file system then fails to close (it looks the moved file up)
        FileSystem zip = FileSystems.newFileSystem(sodium);
        try {
            Outcome outcome = IncompatibleModDisabler.disable(List.of(
                    new ModJar(nested(zip, "sodium-mod.jar"), Set.of("sodium"), Set.of()),
                    new ModJar(nested(zip, "fabric-api-base.jar"), Set.of("fabric_api_base"), Set.of()),
                    jar("create.jar", Set.of("create"), Set.of("fabric_api_base"))),
                    Set.of("sodium"), IncompatibleModDisabler.NestedJarIndex.read(mods)::get);

            assertEquals(List.of("sodium-neoforge-0.8.13.jar"), outcome.disabled());
            assertTrue(outcome.notRenamable().isEmpty());
            // The wrapper's bundled library may come from another jar next time: no one is told they need it
            assertTrue(outcome.dependents().isEmpty(), outcome.dependents().toString());
        } finally {
            try {
                zip.close();
            } catch (java.nio.file.NoSuchFileException expected) {
                // the wrapper was renamed under it
            }
        }
        assertFalse(Files.exists(sodium));
        assertTrue(Files.exists(mods.resolve("sodium-neoforge-0.8.13.jar.disabled")));
        assertTrue(Files.exists(mods.resolve("create.jar")));
    }

    @Test
    void aWrapperWithAModOfItsOwnStaysAndThePlayerIsTold() throws IOException {
        Path bundle = wrapper("bundle.jar", "embeddium.jar");
        try (FileSystem zip = FileSystems.newFileSystem(bundle)) {
            Outcome outcome = IncompatibleModDisabler.disable(List.of(
                    new ModJar(bundle, Set.of("bundle"), Set.of()),
                    new ModJar(nested(zip, "embeddium.jar"), Set.of("embeddium"), Set.of())),
                    INCOMPATIBLE, IncompatibleModDisabler.NestedJarIndex.read(mods)::get);

            assertTrue(outcome.disabled().isEmpty());
            assertEquals(List.of("embeddium.jar (bundle.jar)"), outcome.failed());
        }
        assertTrue(Files.exists(bundle));
    }

    @Test
    void aClientOnlyAddOnGoesWithItsModButABothSidesDependentIsOnlyReported() throws IOException {
        Outcome outcome = IncompatibleModDisabler.disable(List.of(
                jar("sodium.jar", Set.of("sodium"), Set.of()),
                new ModJar(Files.writeString(mods.resolve("gbf.jar"), "jar"), Set.of("gbf"), Set.of("geckolib", "sodium"), Set.of("sodium")),
                new ModJar(Files.writeString(mods.resolve("gbf-extra.jar"), "jar"), Set.of("gbf_extra"), Set.of("GBF"), Set.of("gbf")),
                jar("content.jar", Set.of("content"), Set.of("sodium")),
                jar("geckolib.jar", Set.of("geckolib"), Set.of())), Set.of("sodium"));

        assertEquals(List.of("sodium.jar"), outcome.disabled());
        assertEquals(Set.of("gbf.jar", "gbf-extra.jar"), Set.copyOf(outcome.addons()));
        assertEquals(List.of("content.jar"), outcome.dependents());
        assertTrue(Files.exists(mods.resolve("gbf.jar.disabled")));
        assertTrue(Files.exists(mods.resolve("gbf-extra.jar.disabled")));
        assertTrue(Files.exists(mods.resolve("content.jar")));
        assertTrue(Files.exists(mods.resolve("geckolib.jar")));
    }

    @Test
    void theIndexMapsEachNestedJarToItsWrapperAndSkipsTheRest() throws IOException {
        Path sodium = wrapper("sodium.jar", "sodium-mod.jar");
        Files.writeString(mods.resolve("notes.txt"), "not a jar");
        Path broken = Files.writeString(mods.resolve("broken.jar"), "not a zip");

        Map<String, Path> index = IncompatibleModDisabler.NestedJarIndex.read(mods);

        assertEquals(sodium, index.get("sodium-mod.jar"));
        assertEquals(1, index.size(), index.toString());
        assertTrue(Files.exists(broken));
        assertTrue(IncompatibleModDisabler.NestedJarIndex.read(mods.resolve("absent")).isEmpty());
    }

    @Test
    void theKeepListIgnoresCommentsBlanksAndCase() throws IOException {
        Path keep = Files.writeString(mods.resolve(IncompatibleModDisabler.KEEP_FILE), "# mine\n\n  Flerovium \n");

        assertEquals(Set.of("flerovium"), IncompatibleModDisabler.readKeepList(keep));
        assertEquals(Set.of(), IncompatibleModDisabler.readKeepList(mods.resolve("absent.txt")));
    }

    @Test
    void theMessageNamesEveryFileInBothLanguages() {
        Outcome outcome = new Outcome(List.of("a.jar"), List.of("d.jar"), List.of("b.jar"), List.of(), List.of("c.jar"));

        for (boolean korean : List.of(true, false)) {
            String message = IncompatibleModDisabler.message(outcome, korean);
            assertTrue(message.contains("a.jar") && message.contains("b.jar") && message.contains("c.jar") && message.contains("d.jar"), message);
            assertTrue(message.contains(IncompatibleModDisabler.KEEP_FILE), message);
        }
    }

    @Test
    void theShippedListNamesTheConflictingModsByTheirRealIds() {
        List<String> ids = List.of(CompatMods.loadLines("/assets/vulkanmod/compat/incompatible_mod_ids.txt"));

        assertTrue(ids.containsAll(List.of("sodium", "iris", "sodiumoptionsapi", "colorwheel", "iris_search", "sodium_extra")), ids.toString());
        assertTrue(ids.containsAll(List.of("kerria", "modpackupdatechecker")), ids.toString());
        // Jar names, not ids: these would never match a mod
        assertFalse(ids.contains("irissearch"));
        assertFalse(ids.contains("mekalus"));
        assertFalse(ids.contains("modpack-update-checker"));
        // The README's tested packs keep colorwheel_patcher; the others are not on the list at all
        assertFalse(ids.contains("colorwheel_patcher"));
        assertFalse(ids.contains("distanthorizons"));
        assertFalse(ids.contains("vulkanmod"));
        // Runs on this branch (BMC5)
        assertFalse(ids.contains("particle_core"));
        ids.forEach(id -> assertEquals(id.toLowerCase(), id));
    }
}
