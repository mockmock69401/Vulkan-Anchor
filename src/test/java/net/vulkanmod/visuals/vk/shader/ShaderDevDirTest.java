package net.vulkanmod.visuals.vk.shader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 2026-11-25: the dev shader folder is read before the classpath, so an edited file is what a hot reload compiles. */
class ShaderDevDirTest {
    private static final String ID = "vulkanmod:visuals/lib/fake_dev_only.glsl";

    @Test
    void anEditedFileInTheDevFolderWins(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(ShaderSource.resourcePath(ID));
        Files.createDirectories(file.getParent());
        Files.writeString(file, "float edited();\n");
        assertEquals("float edited();\n", ShaderSource.folderThenClasspath(dir).apply(ID));
        Files.writeString(file, "float editedAgain();\n");
        assertEquals("float editedAgain();\n", ShaderSource.folderThenClasspath(dir).apply(ID), "read again on every call");
    }

    @Test
    void aFileNotInTheDevFolderComesFromTheClasspath(@TempDir Path dir) {
        String real = ShaderCatalog.visuals().all().get(0).stages().get(0).id();
        String fromJar = ShaderSource.classpath().apply(real);
        assertNotNull(fromJar);
        assertEquals(fromJar, ShaderSource.folderThenClasspath(dir).apply(real));
    }
}
