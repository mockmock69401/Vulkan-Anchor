package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.lod.LodClamp;
import net.vulkanmod.visuals.vk.LooseUniformWriter;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** lighting.comp's uniform block and scene_copy.comp's lighting and water vec4s (designs 2026-10-11 P7d 3.1, P7e-1 3.2-3.4). */
public final class LightingUniforms {
    // lighting.comp uniform locations
    public static final int INV_VANILLA_PROJ = 0, INV_LOD_PROJ = 1, PROJ = 2, SUN = 3, DEPTH = 4, UP = 5;
    /** 2026-10-19: world east in view space, for the LOD pixels' face normals (lighting.comp). */
    public static final int EAST = 16;
    // scene_copy.comp: AO strength, sun shading weight, 1 when the lighting pass ran, LightingMath.sunHeightTerm; the water
    // colour's strength and ambient, and how far the sun is up (2026-10-15, the light's exposure; rain left out)
    public static final int COPY_LIGHTING = 10, COPY_WATER = 11;
    // scene_copy.comp, P7n design 4.2: the sun's colour and the light colour strength; the sky shade's colour and the sun's weight for
    // the colour (day x sky exposure, gate G7n: not the Sun Shading strength, which kept full sun at a share of 0.37)
    public static final int COPY_LIGHT_SUN = 22, COPY_LIGHT_SHADE = 23;
    // scene_copy.comp, 2026-10-28: torch light's colour (ColourMath.BLOCK_LIGHT) and the block light colour setting; the moonlight's colour
    // (ColourMath.MOON_LIGHT) and its strength on the sky-lit light (light colour setting x the night)
    public static final int COPY_BLOCK_LIGHT = 31, COPY_MOON_LIGHT = 32;
    // scene_copy.comp, 2026-10-28: the block glow (light sources' bright texels) and the ore glow settings
    public static final int COPY_GLOW = 33;

    public record Values(boolean on, Matrix4f invVanillaProjection, Matrix4f invLodProjection, Matrix4f projection, float[] sunView,
                         boolean lodValid, float aoStrength, float sunWeight, float[] upView, boolean vanillaCopies, float waterStrength,
                         float waterAmbient, float cameraYFrac, float[] lightSun, float sunHeightTerm, float sunUp, float blockDay, float[] eastView,
                         float blockLightColour, float moonLight, float blockGlow, float oreGlow, float blockReflections) {
        public void writeLighting(LooseUniformWriter w) {
            w.putMat4(INV_VANILLA_PROJ, this.invVanillaProjection).putMat4(INV_LOD_PROJ, this.invLodProjection).putMat4(PROJ, this.projection);
            w.putVec4(SUN, this.sunView[0], this.sunView[1], this.sunView[2], this.sunWeight > 0 ? 1 : 0);
            w.putVec4(DEPTH, LodClamp.CLAMP_THRESHOLD, this.lodValid ? 1 : 0, this.vanillaCopies ? 1 : 0, 0);
            w.putVec4(UP, this.upView[0], this.upView[1], this.upView[2], this.cameraYFrac);
            w.putVec4(EAST, this.eastView[0], this.eastView[1], this.eastView[2], 0);
        }

        public void writeCopy(LooseUniformWriter w) {
            w.putVec4(COPY_LIGHTING, this.aoStrength, this.sunWeight, this.on ? 1 : 0, this.sunHeightTerm);
            w.putVec4(COPY_WATER, this.waterStrength, this.waterAmbient, this.sunUp, this.blockDay);
            w.putVec4(COPY_LIGHT_SUN, this.lightSun[0], this.lightSun[1], this.lightSun[2], this.lightSun[3]);
            w.putVec4(COPY_LIGHT_SHADE, this.lightSun[5], this.lightSun[6], this.lightSun[7], this.lightSun[4]);
            w.putVec4(COPY_BLOCK_LIGHT, ColourMath.BLOCK_LIGHT[0], ColourMath.BLOCK_LIGHT[1], ColourMath.BLOCK_LIGHT[2], this.blockLightColour);
            w.putVec4(COPY_MOON_LIGHT, ColourMath.MOON_LIGHT[0], ColourMath.MOON_LIGHT[1], ColourMath.MOON_LIGHT[2], this.moonLight);
            w.putVec4(COPY_GLOW, this.blockGlow, this.oreGlow, this.blockReflections, 0);
        }
    }

    private LightingUniforms() {
    }

    /**
     * P7n design 4.2: the sun's light colour, the strength and the sun's weight for the colour; without the sun shading nothing is
     * coloured (all would read as shade).
     */
    static float[] lightSun(float[] sunLight, float[] shade, float strength, float weight) {
        return new float[]{sunLight[0], sunLight[1], sunLight[2], strength, weight, shade[0], shade[1], shade[2]};
    }

    /** No lighting: the copy multiplies by 1, colours no water and the pass is skipped. */
    public static Values off() {
        return off(1, 0, 0, 0, 0, 0);
    }

    /**
     * No lighting pass, with the frame's sun-up factor and its product with the camera's sky exposure for the copy's exposures, and the
     * block light colour and glow settings (torch light and glow need no pass).
     */
    static Values off(float sunUp, float sunUpExposed, float blockLightColour, float blockGlow, float oreGlow, float blockReflections) {
        return new Values(false, new Matrix4f(), new Matrix4f(), new Matrix4f(), new float[]{0, 1, 0}, false, 0, 0, new float[]{0, 1, 0}, false, 0, 1, 0, new float[]{1, 1, 1, 0, 0, 1, 1, 1}, 0, sunUp,
                sunUpExposed, new float[]{1, 0, 0}, blockLightColour, 0, blockGlow, oreGlow, blockReflections);
    }

    /**
     * choice is this frame's (frame()); frame is this frame's AtmosphereFrame or null; lodValid is the atmosphere's (this
     * frame's LOD depth at the main target's size, and with it the LOD opaque copy); skyExposure from SkyExposure;
     * vanillaCopies when both of vanilla's depth copies were taken this frame at this size (VkDepthCapture.captured).
     */
    public static Values compute(PostProcessSettings s, LightingChoice choice, AtmosphereFrame frame, long serial, boolean lodValid, float skyExposure,
                                 boolean vanillaCopies) {
        return compute(s, choice, frame, serial, lodValid, skyExposure, vanillaCopies, frame != null && frame.skyColour() != null);
    }

    /**
     * Final fix R1: hasSky is whether the dimension has sky light. Without the lighting pass the copy still gets this frame's sun-up
     * factor and its product with the sky exposure (slot 11 .z, .w: the composite's exposures no longer depend on the pass), 0 without
     * sky light (no day there: the End's fixed noon is no day for the block light).
     */
    public static Values compute(PostProcessSettings s, LightingChoice choice, AtmosphereFrame frame, long serial, boolean lodValid, float skyExposure,
                                 boolean vanillaCopies, boolean hasSky) {
        if (frame == null || frame.serial() != serial) return off(1, 0, s.blockLightColour(), s.blockGlow(), s.oreGlow(), s.blockReflections());
        if (choice.serial() != serial || !choice.any()) {
            float up = hasSky ? SunMath.dayFactor(SunMath.sunDirection(frame.timeOfDay()).y, 0) : 0;
            return off(up, up * Math.max(0, Math.min(1, skyExposure)), s.blockLightColour(), s.blockGlow(), s.oreGlow(), s.blockReflections());
        }
        Vector3f sun = SunMath.sunDirection(frame.timeOfDay());
        Matrix4f modelView = new Matrix4f(frame.invModelView()).invert();
        Vector3f view = modelView.transformDirection(new Vector3f(sun)).normalize();
        Vector3f up = modelView.transformDirection(new Vector3f(0, 1, 0)).normalize();
        Vector3f east = modelView.transformDirection(new Vector3f(1, 0, 0)).normalize();
        float day = SunMath.dayFactor(sun.y, frame.rain());
        float sunDay = day * Math.max(0, Math.min(1, skyExposure));
        float sunWeight = choice.sun() ? s.sunShading() * sunDay : 0;
        return new Values(true, frame.invVanillaProjection(), frame.invLodProjection(), new Matrix4f(frame.invVanillaProjection()).invert(),
                new float[]{view.x, view.y, view.z}, lodValid, choice.ao() ? s.aoStrength() : 0, sunWeight, new float[]{up.x, up.y, up.z},
                vanillaCopies, choice.water() ? s.waterDepth() : 0, WaterMath.ambient(day), (float) (frame.cameraY() - Math.floor(frame.cameraY())),
                lightSun(ColourMath.sunLight(sun.y, s.sunWarmth()), ColourMath.skyShade(s.sunWarmth(), sun.y), choice.sun() ? s.lightColour() : 0, choice.sun() ? sunDay : 0), LightingMath.sunHeightTerm(sun.y),
                SunMath.dayFactor(sun.y, 0), SunMath.dayFactor(sun.y, 0) * Math.max(0, Math.min(1, skyExposure)), new float[]{east.x, east.y, east.z},
                s.blockLightColour(), choice.sun() && hasSky ? moonStrength(s.lightColour(), sun.y) : 0, s.blockGlow(), s.oreGlow(), s.blockReflections());
    }

    /**
     * 2026-10-28: the moonlight colour's strength on the sky-lit light: the light colour setting as far as the sun is below the horizon,
     * fading in as the day factor fades out above it (SunMath.dayFactor of the sun's depth), so a setting sun's light stays warm.
     */
    static float moonStrength(float lightColour, float sunHeight) {
        return lightColour * SunMath.dayFactor(-sunHeight, 0);
    }
}
