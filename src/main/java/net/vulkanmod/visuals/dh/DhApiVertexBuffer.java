package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiVertexBufferWrapper;

/** A DhVertexBuffer as DH's render API (DH 3.3.5 and later) takes it: the methods are DhVertexBuffer's. */
public final class DhApiVertexBuffer extends DhVertexBuffer implements IDhApiVertexBufferWrapper {
    public DhApiVertexBuffer(String name, DhStaging.Gpu gpu) {
        super(name, gpu);
    }
}
