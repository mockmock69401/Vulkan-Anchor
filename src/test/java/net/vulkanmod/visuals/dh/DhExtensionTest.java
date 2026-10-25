package net.vulkanmod.visuals.dh;

import net.vulkanmod.visuals.Bytecode;
import net.vulkanmod.visuals.lod.LodCompositeUniforms;
import net.vulkanmod.visuals.render.FogParameters;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DH runs its uploads and its terrain callback inside its own hook, before the visuals frame records the copies: a DH buffer
 * is drawable only in commands recorded after DhStaging.flush in the same frame, so the extension flushes before anything else.
 */
class DhExtensionTest {
    @Test
    void recordOffscreenFlushesTheStagingFirst() throws Exception {
        MethodNode m = Bytecode.method("net/vulkanmod/visuals/dh/DhExtension", "recordOffscreen");
        MethodInsnNode first = null;
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode c) {
                first = c;
                break;
            }
        }
        assertTrue(first != null, "recordOffscreen makes a call");
        assertEquals("DhStaging.flush", Bytecode.name(first));
    }

    /** LodInputs counts the LOD distance in sections of 512 blocks; DH's is a radius in chunks. */
    @Test
    void theLodDistanceIsInSectionsOf512Blocks() {
        assertEquals(8f, DhExtension.lodDistanceSections(256));
        assertEquals(0.5f, DhExtension.lodDistanceSections(16));
    }

    /** DH's far plane is (LOD blocks + 512) * 2 (RenderUtil.getFarClipPlaneDistanceInBlocks): the fallback when DH's config cannot be read. */
    @Test
    void theFarPlaneGivesTheLodDistanceWhenTheConfigIsUnreadable() {
        assertEquals(256, DhExtension.lodChunksFromFar(9216f));
        assertEquals(0, DhExtension.lodChunksFromFar(0f));
    }

    /** DH's GL renderer discards LOD fragments nearer than its near clip distance plus 16 blocks (the clip and the discard circle never touch). */
    @Test
    void theDiscardDistanceIsTheNearClipDistancePlusSixteen() {
        assertEquals(115.1f, DhExtension.discardDistance(99.1f), 1e-4f);
        assertEquals(0f, DhExtension.discardDistance(Float.NaN));
    }

    /** A quad count past the shared index buffer is drawn only as far as the indices go. */
    @Test
    void aDrawStopsAtTheSharedIndexCapacity() {
        assertEquals(100, DhExtension.drawableQuads(100, 4096));
        assertEquals(4096, DhExtension.drawableQuads(5000, 4096));
        assertEquals(0, DhExtension.drawableQuads(-1, 4096));
    }

    private static LodCompositeUniforms.Values composite(FogParameters fog, boolean environmental, float vanillaRenderDistance) {
        return LodCompositeUniforms.compute(new Matrix4f(), new Matrix4f(), DhExtension.compositeFog(fog, environmental, vanillaRenderDistance));
    }

    /** The composite's fog at a LOD distance d, as lod_composite.frag works it out. */
    private static float fogAt(LodCompositeUniforms.Values v, float d) {
        float span = v.fogRange().y - v.fogRange().x;
        if (v.fogColour().w <= 0 || span <= 0) return 0;
        return Math.max(0, Math.min(1, (d - v.fogRange().x) / span)) * v.fogColour().w;
    }

    /** Vanilla's FogRenderer.setupFog: the fluid in the camera, a mob effect's fog (blindness, darkness) or thick fog replace the terrain fog. */
    @Test
    void environmentalFogIsAFluidAMobEffectOrThickFog() {
        assertFalse(DhExtension.environmentalFog(false, false, false));
        assertTrue(DhExtension.environmentalFog(true, false, false));
        assertTrue(DhExtension.environmentalFog(false, true, false));
        assertTrue(DhExtension.environmentalFog(false, false, true));
    }

    /**
     * Ruling R20: the terrain fog DH moves out of the way (DH's MixinFogRenderer) and the vanilla terrain fog
     * itself (which would paint every LOD past the render distance in the fog colour) give the composite no fog; the distance fog on
     * LODs is the post-process atmosphere's, through the LOD depth.
     */
    @Test
    void terrainFogLeavesTheCompositeWithoutFog() {
        FogParameters movedOut = new FogParameters(0.6f, 0.7f, 0.9f, 1, 420694206942069f, 42069420694206942069f, 420694206942069f, 42069420694206942069f);
        assertEquals(0f, composite(movedOut, false, 192).fogColour().w);
        FogParameters vanilla = new FogParameters(0.6f, 0.7f, 0.9f, 1, 172.8f, 192f, 172.8f, 192f);
        assertEquals(0f, composite(vanilla, false, 192).fogColour().w);
        assertEquals(0f, fogAt(composite(vanilla, false, 192), 5000));
        //Terrain fog ends at the render distance exactly (RD 6: 96 - clamp(9.6, 4, 64)): still none
        FogParameters terrainAtRd6 = new FogParameters(0.6f, 0.7f, 0.9f, 1, 86.4f, 96f, 86.4f, 96f);
        assertEquals(0f, composite(terrainAtRd6, false, 96).fogColour().w);
    }

    /** Environmental fog nearer than the vanilla render distance (water at RD 12, lava, blindness, thick fog): every LOD is the fog colour. */
    @Test
    void fogThatCoversTheRenderDistanceMakesEveryLodTheFogColour() {
        FogParameters water = new FogParameters(0.05f, 0.1f, 0.4f, 1, -8f, 96f, -8f, 96f);
        LodCompositeUniforms.Values v = composite(water, true, 192);
        assertEquals(0.05f, v.fogColour().x);
        assertEquals(0.1f, v.fogColour().y);
        assertEquals(0.4f, v.fogColour().z);
        assertEquals(1f, fogAt(v, 0));
        assertEquals(1f, fogAt(v, 300));
        assertEquals(1f, fogAt(v, 9000));
        FogParameters blind = new FogParameters(0, 0, 0, 1, 0f, 5f, 0f, 5f);
        assertEquals(1f, fogAt(composite(blind, true, 384), 200));
        assertEquals(0f, composite(blind, true, 384).fogColour().x);
        FogParameters lava = new FogParameters(0.6f, 0.1f, 0, 1, 0.25f, 1f, 0.25f, 1f);
        assertEquals(1f, fogAt(composite(lava, true, 192), 150));
        //Thick fog: start RD*0.05, end min(RD, 192)*0.5
        FogParameters thick = new FogParameters(0.5f, 0.5f, 0.5f, 1, 9.6f, 96f, 9.6f, 96f);
        assertEquals(1f, fogAt(composite(thick, true, 192), 150));
    }

    /**
     * Review fix: vanilla clamps the water fog's end to the render distance, so at RD 2-6 it equals RD*16 and does not cover it. The
     * vanilla range then goes in as it is, as a linear fog: every LOD past the render distance is fully fogged.
     */
    @Test
    void waterFogClampedToTheRenderDistanceStillFogsTheLods() {
        FogParameters water = new FogParameters(0.05f, 0.1f, 0.4f, 1, -8f, 96f, -8f, 96f);
        LodCompositeUniforms.Values v = composite(water, true, 96);
        assertEquals(1f, v.fogColour().w);
        assertEquals(1f, fogAt(v, 96));
        assertEquals(1f, fogAt(v, 200));
        assertEquals(1f, fogAt(v, 9000));
        assertEquals(0.5f, fogAt(v, 44), 1e-6f);
        //RD 2: the end clamped to 32
        FogParameters rd2 = new FogParameters(0.05f, 0.1f, 0.4f, 1, -8f, 32f, -8f, 32f);
        assertEquals(1f, fogAt(composite(rd2, true, 32), 32));
    }
}
