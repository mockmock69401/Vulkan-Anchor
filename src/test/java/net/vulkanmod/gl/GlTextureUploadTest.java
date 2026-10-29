package net.vulkanmod.gl;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-30 (audit): glTexSubImage2D keeps the mip level it was given. */
class GlTextureUploadTest {

    @Test
    void theMipLevelReachesTheCopy() throws Exception {
        String s = Files.readString(Path.of("src/main/java/net/vulkanmod/gl/GlTexture.java"));
        assertTrue(s.contains("this.vulkanImage.uploadSubTextureAsync(level, width, height, xOffset, yOffset, 0, 0, width, upload);"));
        assertFalse(s.contains("uploadSubTextureAsync(0, width, height, xOffset, yOffset, 0, 0, width, upload)"));
        assertFalse(s.contains("boundTexture.uploadSubImage(xOffset"));
    }
}
