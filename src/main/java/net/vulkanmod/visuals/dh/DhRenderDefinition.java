package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.enums.config.EDhApiMcRenderingFadeMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiCustomRenderRegister;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.AbstractDhApiRenderDefinition;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.IDhApiAntiAliasRenderer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.IDhApiDebugWireframeRenderer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.IDhApiFarFadeRenderer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.IDhApiFogRenderer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.IDhApiGenericRenderer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.IDhApiMetaRenderer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.IDhApiSsaoRenderer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.IDhApiTerrainRenderer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.IDhApiTestTriangleRenderer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.IDhApiVanillaFadeRenderer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiTerrainContainerUniformBufferWrapper;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiVertexBufferWrapper;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.render.IDebugBox;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The fork's renderer for DH: DH keeps its LOD data, generation and buffer building, and hands the buffers to this
 * definition. It reports OpenGL (DH on 1.20.1 accepts no other API for Minecraft) and forward depth over -1..1, the
 * projection DH builds for an OpenGL renderer. Only the terrain renderer and the buffer factories do anything; the far and
 * vanilla fades stay empty (stage 3, spec 4.4: edgeChoice).
 */
public final class DhRenderDefinition extends AbstractDhApiRenderDefinition {
    public static final String NAME = "VulkanMod";

    private static final IDhApiMetaRenderer META = new IDhApiMetaRenderer() {
        @Override
        public void runRenderPassSetup(DhApiRenderParam renderParams) {
        }

        @Override
        public void runRenderPassCleanup(DhApiRenderParam renderParams) {
        }

        @Override
        public void copyToMcTexture(DhApiRenderParam renderParams) {
        }

        @Override
        public void clearDhDepthAndColorTextures(DhApiRenderParam renderParams) {
        }
    };
    private static final IDhApiSsaoRenderer SSAO = p -> { };
    private static final IDhApiFogRenderer FOG = (p, f) -> { };
    /** Empty: see edgeChoice. */
    static final IDhApiFarFadeRenderer FAR_FADE = p -> { };
    private static final IDhApiAntiAliasRenderer ANTI_ALIAS = p -> { };
    /** Empty: see edgeChoice. */
    static final IDhApiVanillaFadeRenderer VANILLA_FADE = p -> { };
    private static final IDhApiTestTriangleRenderer TEST_TRIANGLE = p -> { };
    private static final IDhApiDebugWireframeRenderer WIREFRAME = new IDhApiDebugWireframeRenderer() {
        @Override
        public void beginRenderBatch(DhApiRenderParam apiRenderParams) {
        }

        @Override
        public void renderBox(IDebugBox box) {
        }

        @Override
        public void endRenderBatch(DhApiRenderParam apiRenderParams) {
        }
    };
    private static final IDhApiGenericRenderer GENERIC = new IDhApiGenericRenderer() {
        @Override
        public void render(DhApiRenderParam renderEventParam, IDhApiCustomRenderRegister renderRegister, boolean renderingWithSsao) {
        }

        @Override
        public void close() {
        }
    };

    /** The LOD/vanilla edge treatments compared in stage 3 (spec 4.4). */
    /**
     * Hands DH a new definition (DH's render API, DH 3.3.5 and later). Here and not in DhRegistration, which loads with DH 3.3.0 to
     * 3.3.4 too: checking this call needs this class's superclass, which only the render API has.
     */
    static CompletableFuture<DhApiResult<String>> offerToDh() {
        return DhApi.Delayed.renderProxy.setRenderDefinitionOverrideAsync(new DhRenderDefinition(), NAME);
    }

    public enum EdgeTreatment {DH_FADES, COMPOSITE_RULE, EDGE_COVER, NONE}

    /** The treatment in use, and whether DH's vanilla fade and far fade are drawn. */
    public record EdgeChoice(EdgeTreatment treatment, boolean dhVanillaFade, boolean dhFarFade) {}

    /**
     * Stage 3, spec 4.4, chosen by comparing the four in the same scenes: the boundary is DH's own near discard (terrain.frag) with the
     * visuals' edge cover over built sections' sides next to unbuilt ones (under its own setting); DH's fades are never drawn,
     * whatever DH's fade mode says. DH's vanilla fade has next to nothing to fade
     * from RD 7 on (dhVanillaFadeMaxWeight: 0 at RD 7 and 11-23, at most 8.4 % on the square's corner pixels otherwise), its far fade
     * starts past the LOD radius and the visuals' atmosphere already hazes the LOD horizon; a composite rule "no LOD nearer than the
     * vanilla distance" opened sky-coloured gaps at the vanilla edge.
     */
    static EdgeChoice edgeChoice(EDhApiMcRenderingFadeMode dhVanillaFadeMode, boolean edgeCoverSetting) {
        return new EdgeChoice(edgeCoverSetting ? EdgeTreatment.EDGE_COVER : EdgeTreatment.NONE, false, false);
    }

    /** Where DH's vanilla fade would start (blocks): 1.5 x (its automatic near clip + 16), DH GlDhVanillaFadeShader and RenderUtil. */
    static float dhVanillaFadeStart(int renderDistanceChunks) {
        float overdraw = renderDistanceChunks <= 2 ? 0.2f : renderDistanceChunks <= 4 ? 0.3f : renderDistanceChunks <= 6 ? 0.6f
                : renderDistanceChunks <= 10 ? 0.8f : 0.9f;
        return 1.5f * (Math.max(1, renderDistanceChunks * 16 * overdraw) + 16);
    }

    /** The largest weight DH's vanilla fade (smoothstep from 1.5 to 1.9 times near clip + 16) puts on any vanilla pixel. */
    static float dhVanillaFadeMaxWeight(int renderDistanceChunks) {
        float start = dhVanillaFadeStart(renderDistanceChunks), end = start / 1.5f * 1.9f;
        float t = Math.max(0, Math.min(1, (vanillaFarthest(renderDistanceChunks) - start) / (end - start)));
        return t * t * (3 - 2 * t);
    }

    /** Vanilla terrain's farthest pixel (blocks): the corner of its square, RD * 16 * sqrt 2. */
    static float vanillaFarthest(int renderDistanceChunks) {
        return renderDistanceChunks * 16 * (float) Math.sqrt(2);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public EDhApiDepthDirection getDepthDirection() {
        return EDhApiDepthDirection.FORWARD_Z;
    }

    @Override
    public EDhApiDepthRange getDepthRange() {
        return EDhApiDepthRange.NEG_ONE_TO_POS_ONE;
    }

    @Override
    public EDhApiRenderingApi getRenderApi() {
        return EDhApiRenderingApi.OPEN_GL;
    }

    @Override
    public boolean isNativeRenderer() {
        return false;
    }

    @Override
    public IDhApiMetaRenderer getMetaRenderer() {
        return META;
    }

    @Override
    public IDhApiTerrainRenderer getTerrainRenderer() {
        return DhTerrain.INSTANCE;
    }

    @Override
    public IDhApiSsaoRenderer getSsaoRenderer() {
        return SSAO;
    }

    @Override
    public IDhApiFogRenderer getFogRenderer() {
        return FOG;
    }

    @Override
    public IDhApiFarFadeRenderer getFarFadeRenderer() {
        return FAR_FADE;
    }

    @Override
    public IDhApiAntiAliasRenderer getAntiAliasRenderer() {
        return ANTI_ALIAS;
    }

    @Override
    public IDhApiDebugWireframeRenderer getDebugWireframeRenderer() {
        return WIREFRAME;
    }

    @Override
    public IDhApiVanillaFadeRenderer getVanillaFadeRenderer() {
        return VANILLA_FADE;
    }

    @Override
    public IDhApiTestTriangleRenderer getTestTriangleRenderer() {
        return TEST_TRIANGLE;
    }

    @Override
    public IDhApiGenericRenderer getGenericRenderer() {
        return GENERIC;
    }

    @Override
    public IDhApiVertexBufferWrapper createVboWrapper(String name) {
        return new DhApiVertexBuffer(name, DhStaging.INSTANCE.gpu());
    }

    @Override
    public IDhApiTerrainContainerUniformBufferWrapper createLodContainerUniformWrapper() {
        return new DhApiContainerUniform();
    }

    /** DH's generic objects (its clouds, beacon beams, other mods' boxes) are not drawn. */
    @Override
    public IDhApiGenericObjectVertexBufferContainer createGenericObjectVboContainer() {
        return new IDhApiGenericObjectVertexBufferContainer() {
            private volatile EState state = EState.NEW;

            @Override
            public void uploadDataToGpu() {
            }

            @Override
            public void updateCpuSideVertexData(List<DhApiRenderableBox> uploadBoxList) {
            }

            @Override
            public EState getState() {
                return this.state;
            }

            @Override
            public void setState(EState state) {
                this.state = state;
            }

            @Override
            public void close() {
            }
        };
    }
}
