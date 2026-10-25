package net.vulkanmod.visuals.lod;

import net.vulkanmod.visuals.render.FogParameters;
import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.shader.ShaderCatalog;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodCompositeUniformsTest {
    private static final Matrix4f LOD = new Matrix4f().perspectiveLH(1.2f, 16f / 9, 4f, 16000f, false);
    private static final Matrix4f MAIN = new Matrix4f().perspectiveLH(1.2f, 16f / 9, 0.05f, 512f, false);

    private static FogParameters fog(float alpha, float start, float end) {
        return new FogParameters(0.1f, 0.2f, 0.3f, alpha, start, end, start, end);
    }

    @Test
    void theLodProjectionIsInvertedAndTheMainOneKept() {
        LodCompositeUniforms.Values v = LodCompositeUniforms.compute(LOD, MAIN, FogParameters.NONE);
        Matrix4f identity = new Matrix4f(v.lodClipToView()).mul(LOD);
        assertTrue(identity.equals(new Matrix4f(), 1e-3f), identity.toString());
        assertEquals(MAIN, v.viewToMainClip());
    }

    /** The LOD depth, unprojected and reprojected in the main projection, gives the depth the main pass would write there. */
    @Test
    void aLodDepthCarriesIntoTheMainDepth() {
        LodCompositeUniforms.Values v = LodCompositeUniforms.compute(LOD, MAIN, FogParameters.NONE);
        org.joml.Vector4f lodClip = new org.joml.Vector4f(0.25f, -0.5f, 60f, 1f);
        lodClip = LOD.transform(lodClip);
        float lodDepth = lodClip.z / lodClip.w;
        org.joml.Vector4f view = v.lodClipToView().transform(new org.joml.Vector4f(lodClip.x / lodClip.w, lodClip.y / lodClip.w, lodDepth, 1f));
        view.div(view.w);
        org.joml.Vector4f main = v.viewToMainClip().transform(view);
        org.joml.Vector4f direct = MAIN.transform(new org.joml.Vector4f(0.25f, -0.5f, 60f, 1f));
        assertEquals(direct.z / direct.w, main.z / main.w, 1e-4f);
    }

    @Test
    void fogIsPassedOnlyWhenItHasStrengthAndRange() {
        LodCompositeUniforms.Values on = LodCompositeUniforms.compute(LOD, MAIN, fog(1f, 100f, 400f));
        assertEquals(new Vector4f(0.1f, 0.2f, 0.3f, 1f), on.fogColour());
        assertEquals(new Vector4f(100f, 400f, 0f, 0f), on.fogRange());
        Vector4f off = new Vector4f(0, 0, 0, 0);
        LodCompositeUniforms.Values noAlpha = LodCompositeUniforms.compute(LOD, MAIN, fog(0f, 100f, 400f));
        assertEquals(off, noAlpha.fogColour());
        assertEquals(off, noAlpha.fogRange());
        //Vanilla's "no fog" start and end (start past end), and an empty range
        assertEquals(off, LodCompositeUniforms.compute(LOD, MAIN, FogParameters.NONE).fogColour());
        assertEquals(off, LodCompositeUniforms.compute(LOD, MAIN, fog(1f, 100f, 100f)).fogColour());
        //A narrow range is a real range: the fog is kept, the shader guards a zero span itself
        assertEquals(new Vector4f(100f, 100.5f, 0f, 0f), LodCompositeUniforms.compute(LOD, MAIN, fog(1f, 100f, 100.5f)).fogRange());
    }

    @Test
    void writesEveryLocationTheShaderDeclares() {
        ShaderCatalog.Program p = ShaderCatalog.visuals().get(LodComposite.PROGRAM);
        LooseUniformWriter w = new LooseUniformWriter(GlslForVulkan.toVulkan(p.source(p.stages().get(1), ShaderSource.classpath()), p.defines()).meta());
        LodCompositeUniforms.compute(LOD, MAIN, fog(1f, 100f, 400f)).write(w);
        assertTrue(w.buffer().remaining() >= 2 * 64 + 2 * 16, "two mat4 and two vec4: " + w.buffer().remaining());
    }
}
