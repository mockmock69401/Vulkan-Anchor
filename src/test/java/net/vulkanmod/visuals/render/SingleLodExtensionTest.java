package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.api.FrameContext;
import net.vulkanmod.visuals.api.LodInputs;
import net.vulkanmod.visuals.api.VisualsExtension;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 6: two extensions that both give LOD inputs: the first is used, and the second is named in one warning. */
class SingleLodExtensionTest {
    private static LodInputs inputs(float distance) {
        return new LodInputs(null, 0, 0, 0, null, true, distance);
    }

    private static VisualsExtension giving(LodInputs in) {
        return new VisualsExtension() {
            @Override
            public LodInputs postInputs(FrameContext ctx) {
                return in;
            }
        };
    }

    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private org.apache.logging.log4j.core.Logger logger;
    private AbstractAppender appender;

    @BeforeEach
    void capture() {
        VisualsRenderer.resetLodWarningForTest();
        this.logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger("VulkanMod Visuals");
        this.appender = new AbstractAppender("single-lod", null, null, true) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.WARN) SingleLodExtensionTest.this.warnings.add(event.getMessage().getFormattedMessage());
            }
        };
        this.appender.start();
        this.logger.addAppender(this.appender);
    }

    @AfterEach
    void release() {
        this.logger.removeAppender(this.appender);
        this.appender.stop();
    }

    @Test
    void onlyTheFirstExtensionsInputsAreUsedAndTheWarningComesOnce() {
        LodInputs first = inputs(1), second = inputs(2);
        List<VisualsExtension> both = List.of(giving(null), giving(first), giving(second));
        assertSame(first, VisualsRenderer.lodInputs(both, null));
        assertSame(first, VisualsRenderer.lodInputs(both, null));
        assertEquals(1, this.warnings.size(), this.warnings.toString());
        assertTrue(this.warnings.get(0).contains("More than one"), this.warnings.get(0));
    }

    @Test
    void oneExtensionOrNoneWarnsNothing() {
        LodInputs only = inputs(1);
        assertSame(only, VisualsRenderer.lodInputs(List.of(giving(only), giving(null)), null));
        assertNull(VisualsRenderer.lodInputs(List.of(giving(null)), null));
        assertNull(VisualsRenderer.lodInputs(List.of(), null));
        assertTrue(this.warnings.isEmpty(), this.warnings.toString());
    }
}
