package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiGenericObjectVertexBufferContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DhRenderDefinitionTest {
    private final DhRenderDefinition def = new DhRenderDefinition();

    @Test
    void identity() {
        assertEquals("VulkanMod", this.def.getName());
        //DH on 1.20.1 hard-codes Minecraft's API as OpenGL and rejects any other
        assertEquals(EDhApiRenderingApi.OPEN_GL, this.def.getRenderApi());
        assertFalse(this.def.isNativeRenderer());
    }

    @Test
    void depthAsDhBuildsItsProjection() {
        assertEquals(EDhApiDepthDirection.FORWARD_Z, this.def.getDepthDirection());
        assertEquals(EDhApiDepthRange.NEG_ONE_TO_POS_ONE, this.def.getDepthRange());
    }

    @Test
    void everyRendererIsThere() {
        assertNotNull(this.def.getMetaRenderer());
        assertNotNull(this.def.getTerrainRenderer());
        assertNotNull(this.def.getSsaoRenderer());
        assertNotNull(this.def.getFogRenderer());
        assertNotNull(this.def.getFarFadeRenderer());
        assertNotNull(this.def.getAntiAliasRenderer());
        assertNotNull(this.def.getDebugWireframeRenderer());
        assertNotNull(this.def.getVanillaFadeRenderer());
        assertNotNull(this.def.getTestTriangleRenderer());
        assertNotNull(this.def.getGenericRenderer());
        assertInstanceOf(DhTerrain.class, this.def.getTerrainRenderer());
    }

    @Test
    void theNoOpRenderersAcceptCalls() {
        this.def.getMetaRenderer().runRenderPassSetup(null);
        this.def.getMetaRenderer().copyToMcTexture(null);
        this.def.getTerrainRenderer().render(null, true, null);
        this.def.getFogRenderer().render(null, null);
        this.def.getGenericRenderer().render(null, null, false);
        this.def.getGenericRenderer().close();
    }

    @Test
    void factories() {
        DhContainerUniform uniform = (DhContainerUniform) this.def.createLodContainerUniformWrapper();
        assertFalse(uniform.valid());
        uniform.close();
        IDhApiGenericObjectVertexBufferContainer generic = this.def.createGenericObjectVboContainer();
        assertSame(IDhApiGenericObjectVertexBufferContainer.EState.NEW, generic.getState());
        generic.setState(IDhApiGenericObjectVertexBufferContainer.EState.RENDER);
        assertSame(IDhApiGenericObjectVertexBufferContainer.EState.RENDER, generic.getState());
        generic.uploadDataToGpu();
        generic.close();
    }
}
