package net.vulkanmod.gl;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-10-30 (audit): glTexSubImage2D through _texSubImage2D (BMC5 Wakes fix) reads the caller's pixel size and keeps the mip level. */
class GlTextureUploadTest {

    @Test
    void theClientPixelSizeFollowsFormatAndType() {
        assertEquals(3, GlTexture.sourcePixelSize(GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, 4));
        assertEquals(4, GlTexture.sourcePixelSize(GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 4));
        assertEquals(4, GlTexture.sourcePixelSize(GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, 4));
        assertEquals(1, GlTexture.sourcePixelSize(GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, 4));
        assertEquals(16, GlTexture.sourcePixelSize(GL11.GL_RGBA, GL11.GL_FLOAT, 4));
        assertEquals(8, GlTexture.sourcePixelSize(GL11.GL_RGBA, GL30.GL_HALF_FLOAT, 4));
        assertEquals(4, GlTexture.sourcePixelSize(0x1234, GL11.GL_UNSIGNED_BYTE, 4), "unknown: the image's size");
    }

    @Test
    void theMipLevelReachesTheCopy() throws Exception {
        String s = Files.readString(Path.of("src/main/java/net/vulkanmod/gl/GlTexture.java"));
        assertTrue(s.contains("this.vulkanImage.uploadSubTextureAsync(level, width, height"));
        assertFalse(s.contains("uploadSubTextureAsync(0, width, height"));
        assertFalse(s.contains("getByteBuffer(width, height, boundTexture.vulkanImage.formatSize"));
    }
}
