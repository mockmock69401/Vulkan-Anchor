package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-19: vanilla's round entity shadow is left out while the sun or moon shadow maps shade (VanillaBlobShadows). */
class VanillaBlobShadowsTest {
    @Test
    void hiddenOnlyInAFrameWithShadowMaps() {
        VanillaBlobShadows.frame(10, true);
        assertTrue(VanillaBlobShadows.hidden(10));
        assertFalse(VanillaBlobShadows.hidden(11), "a later frame that did not run the frame flow keeps vanilla's disc");
        VanillaBlobShadows.frame(11, false);
        assertFalse(VanillaBlobShadows.hidden(11), "caves, rain, a new moon or shadows off: vanilla's disc");
        assertFalse(VanillaBlobShadows.hidden(10));
    }

    @Test
    void theFrameSetsItBeforeTheEntitiesAndTheMixinIsListed() throws IOException {
        String renderer = Files.readString(Path.of("src/main/java/net/vulkanmod/visuals/render/VisualsRenderer.java"));
        //2026-11-24: not while the End star casts (its maps take no entities: vanilla's round shadow stays under them)
        assertTrue(renderer.contains("VanillaBlobShadows.frame(serial, this.shadowFrame != null && !starLight);"));
        assertTrue(renderer.indexOf("this.shadowFrame = this.shadowsOn()") < renderer.indexOf("VanillaBlobShadows.frame("));
        String mixins = Files.readString(Path.of("src/main/resources/vulkanmod.mixins.json"));
        assertTrue(mixins.contains("\"visuals.EntityRenderDispatcherShadowMixin\""));
        String mixin = Files.readString(Path.of("src/main/java/net/vulkanmod/mixin/visuals/EntityRenderDispatcherShadowMixin.java"));
        assertTrue(mixin.contains("method = \"renderShadow\"") && mixin.contains("VanillaBlobShadows.hidden(VkFrames.serial())"));
    }
}
