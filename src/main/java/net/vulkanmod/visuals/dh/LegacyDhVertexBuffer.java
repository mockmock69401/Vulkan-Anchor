package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;

/**
 * A DhVertexBuffer as DH 3.3.0 to 3.3.4 takes it (its internal vertex buffer interface; getVertexCount is DH 3.3.2's addition and
 * harmless before). Created by DH's GL render definition, taken over by the legacydh mixins.
 */
public final class LegacyDhVertexBuffer extends DhVertexBuffer implements IVertexBufferWrapper {
    public LegacyDhVertexBuffer(String name) {
        super(name, DhStaging.INSTANCE.gpu());
    }
}
