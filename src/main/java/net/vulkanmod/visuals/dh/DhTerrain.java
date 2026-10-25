package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.interfaces.render.renderDef.IDhApiTerrainRenderer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiTerrainBufferContainer;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.util.IDhApiReadOnlyList;
import net.vulkanmod.visuals.vk.VkFrames;

/**
 * DH's terrain renderer: keeps the containers DH hands it for the frame being recorded (DhFrameCapture); DhExtension draws them.
 * With -Dvulkanmod.dh.stats it also counts the containers for the statistics line.
 */
final class DhTerrain implements IDhApiTerrainRenderer {
    static final DhTerrain INSTANCE = new DhTerrain();

    private DhTerrain() {
    }

    /**
     * Records no draws: DH calls this from its hook in the same pass that ran its uploads, before the visuals frame records the
     * copies, so a DH buffer is drawable only in commands recorded after {@link DhStaging#flush} in the same frame (the draw goes
     * in DhExtension.recordOffscreen, after the flush).
     */
    @Override
    public void render(DhApiRenderParam param, boolean opaquePass, IDhApiReadOnlyList<? extends IDhApiTerrainBufferContainer> containers) {
        if (DhRegistration.disabled() || param == null || containers == null) return;
        //Nothing is thrown into DH's hook: a failure switches DH drawing off with one warning
        try {
            DhFrameCapture.INSTANCE.store(VkFrames.serial(), param, opaquePass, view(containers));
            if (DhStats.ENABLED && opaquePass) {
                for (int i = 0; i < containers.size(); i++) DhStats.container(containers.get(i));
            }
        } catch (RuntimeException e) {
            DhInternals.disable("DH's terrain callback failed (" + e + ")", e);
        }
    }

    /** The render API's container list as DhFrameCapture reads it. */
    static DhFrameCapture.Containers view(IDhApiReadOnlyList<? extends IDhApiTerrainBufferContainer> list) {
        return new DhFrameCapture.Containers() {
            @Override
            public int size() {
                return list.size();
            }

            @Override
            public Object container(int i) {
                return list.get(i);
            }

            @Override
            public Object uniform(int i) {
                IDhApiTerrainBufferContainer c = list.get(i);
                return c == null ? null : c.getUniformContainer();
            }

            @Override
            public Object[] buffers(int i, boolean opaquePass) {
                IDhApiTerrainBufferContainer c = list.get(i);
                if (c == null) return null;
                return opaquePass ? c.getVboOpaqueWrappers() : c.getVboTransparentWrappers();
            }
        };
    }
}
