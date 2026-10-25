package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTerrainRenderer;
import net.vulkanmod.visuals.vk.VkFrames;

/**
 * DhTerrain for DH 3.3.0 to 3.3.4: DH's internal terrain renderer interface, which DH's render definition hands DH's LOD renderer
 * (taken over by the legacydh mixins). It keeps the containers for the frame being recorded, as DhTerrain does.
 */
public final class LegacyDhTerrain implements IDhTerrainRenderer {
    public static final LegacyDhTerrain INSTANCE = new LegacyDhTerrain();

    private LegacyDhTerrain() {
    }

    @Override
    public void render(RenderParams param, boolean opaquePass, SortedArraySet<LodBufferContainer> containers, IProfilerWrapper profiler) {
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

    /** DH's container set as DhFrameCapture reads it. */
    static DhFrameCapture.Containers view(SortedArraySet<LodBufferContainer> set) {
        return new DhFrameCapture.Containers() {
            @Override
            public int size() {
                return set.size();
            }

            @Override
            public Object container(int i) {
                return set.get(i);
            }

            @Override
            public Object uniform(int i) {
                LodBufferContainer c = set.get(i);
                return c == null ? null : c.uniformContainer;
            }

            @Override
            public Object[] buffers(int i, boolean opaquePass) {
                LodBufferContainer c = set.get(i);
                if (c == null) return null;
                return opaquePass ? c.vboOpaqueWrappers : c.vboTransparentWrappers;
            }
        };
    }
}
