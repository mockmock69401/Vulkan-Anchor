package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiTerrainBufferContainer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiTerrainContainerUniformBufferWrapper;

/** A DhContainerUniform as DH's render API (DH 3.3.5 and later) takes it. */
public final class DhApiContainerUniform extends DhContainerUniform implements IDhApiTerrainContainerUniformBufferWrapper {
    @Override
    public void tryUpload(IDhApiTerrainBufferContainer container) {
        super.tryUpload((Object) container);
    }
}
