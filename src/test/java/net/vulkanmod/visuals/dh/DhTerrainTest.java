package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiTerrainBufferContainer;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.util.IDhApiReadOnlyList;
import net.vulkanmod.visuals.vk.VkFrames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M5: DH's terrain callback never throws into DH; a failure switches DH drawing off with one warning. */
class DhTerrainTest {
    @AfterEach
    void reset() {
        DhFrameCapture.INSTANCE.take(VkFrames.serial());
        DhVertexBuffer.closeAllNow();
        DhInternals.resetForTest();
    }

    /** A list DH hands over that breaks while it is read. */
    private static final class Broken implements IDhApiReadOnlyList<IDhApiTerrainBufferContainer> {
        @Override
        public IDhApiTerrainBufferContainer get(int index) {
            throw new IllegalStateException("list changed while it was read");
        }

        @Override
        public int size() {
            return 1;
        }
    }

    private static DhApiRenderParam param() {
        DhApiRenderParam p = new DhApiRenderParam();
        p.mcProjectionMatrix.setIdentity();
        p.mcModelViewMatrix.setIdentity();
        p.dhModelViewMatrix.setIdentity();
        p.dhProjectionMatrix.setIdentity();
        p.nearClipPlane = 1;
        p.farClipPlane = 100;
        return p;
    }

    @Test
    void aFailingCallbackSwitchesDhOffWithOneWarningAndThrowsNothing() {
        try (LogCapture log = LogCapture.visuals()) {
            assertDoesNotThrow(() -> DhTerrain.INSTANCE.render(param(), true, new Broken()));
            assertDoesNotThrow(() -> DhTerrain.INSTANCE.render(param(), false, new Broken()));
            assertFalse(DhInternals.ok());
            assertEquals(1, log.warnings().size(), log.warnings().toString());
            assertTrue(log.warnings().get(0).contains("list changed while it was read"), log.warnings().get(0));
        }
    }
}
