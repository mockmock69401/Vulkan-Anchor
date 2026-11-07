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

    @Test
    void theKeepListIgnoresCommentsBlanksAndCase() throws IOException {
        Path keep = Files.writeString(mods.resolve(IncompatibleModDisabler.KEEP_FILE), "# mine\n\n  Flerovium \n");

        assertEquals(Set.of("flerovium"), IncompatibleModDisabler.readKeepList(keep));
        assertEquals(Set.of(), IncompatibleModDisabler.readKeepList(mods.resolve("absent.txt")));
    }

    @Test
    void theMessageNamesEveryFileInBothLanguages() {
        Outcome outcome = new Outcome(List.of("a.jar"), List.of("b.jar"), List.of(), List.of("c.jar"));

        for (boolean korean : List.of(true, false)) {
            String message = IncompatibleModDisabler.message(outcome, korean);
            assertTrue(message.contains("a.jar") && message.contains("b.jar") && message.contains("c.jar"), message);
            assertTrue(message.contains(IncompatibleModDisabler.KEEP_FILE), message);
        }
    }

    @Test
    void theShippedListNamesTheConflictingModsByTheirRealIds() {
        List<String> ids = List.of(CompatMods.loadLines("/assets/vulkanmod/compat/incompatible_mod_ids.txt"));

        assertTrue(ids.containsAll(List.of("embeddium", "oculus", "sodiumoptionsapi", "colorwheel", "iris_search")), ids.toString());
        assertTrue(ids.containsAll(List.of("modernui", "acceleratedrendering", "particle_core", "modpackupdatechecker")), ids.toString());
        // Jar names, not ids: these would never match a mod
        assertFalse(ids.contains("irissearch"));
        assertFalse(ids.contains("mekalus"));
        assertFalse(ids.contains("modpack-update-checker"));
        // The README's tested packs keep colorwheel_patcher; the others are not on the list at all
        assertFalse(ids.contains("colorwheel_patcher"));
        assertFalse(ids.contains("distanthorizons"));
        assertFalse(ids.contains("vulkanmod"));
        ids.forEach(id -> assertEquals(id.toLowerCase(), id));
    }
}
