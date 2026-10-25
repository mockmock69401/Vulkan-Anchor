package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.ILodContainerUniformBufferWrapper;

/** A DhContainerUniform as DH 3.3.0 to 3.3.4 takes it (its internal container uniform interface). */
public final class LegacyDhContainerUniform extends DhContainerUniform implements ILodContainerUniformBufferWrapper {
    @Override
    public void tryUpload(LodBufferContainer container) {
        super.tryUpload((Object) container);
    }
}
