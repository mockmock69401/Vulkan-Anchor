package net.vulkanmod.visuals.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * What frame() knows and the post-process needs later in the same frame (design 2026-10-10 P7c 3.1): the inverse
 * projections for the two depths, the view, the sun's projection, the fog and the weather. postProcess() uses it only
 * when its serial is the current frame's. Final fix R2: the fluid the game camera is in (Camera.getFluidInCamera), which says
 * under water whatever colour the fog has (swamp water, modded fogs).
 */
public record AtmosphereFrame(long serial, Matrix4f invVanillaProjection, Matrix4f invLodProjection, Matrix4f invModelView,
                              Matrix4f viewProjection, float cameraY, float seaLevel, float fogRed, float fogGreen,
                              float fogBlue, boolean fogCoversAllRendering, boolean lodDrawn, float timeOfDay, float rain, float thunder,
                              float[] sunrise, float[] skyColour, float skyFogEnd, float[] rippleOffset, CloudFrame clouds, CameraFluid cameraFluid) {
    /** The fluid around the camera (vanilla's FogType without its game types): lava and powder snow are not water. */
    public enum CameraFluid {
        NONE, WATER, LAVA, POWDER_SNOW
    }

    /** A frame with the camera in no fluid. */
    public AtmosphereFrame(long serial, Matrix4f invVanillaProjection, Matrix4f invLodProjection, Matrix4f invModelView,
                           Matrix4f viewProjection, float cameraY, float seaLevel, float fogRed, float fogGreen,
                           float fogBlue, boolean fogCoversAllRendering, boolean lodDrawn, float timeOfDay, float rain, float thunder,
                           float[] sunrise, float[] skyColour, float skyFogEnd, float[] rippleOffset, CloudFrame clouds) {
        this(serial, invVanillaProjection, invLodProjection, invModelView, viewProjection, cameraY, seaLevel, fogRed, fogGreen, fogBlue,
                fogCoversAllRendering, lodDrawn, timeOfDay, rain, thunder, sunrise, skyColour, skyFogEnd, rippleOffset, clouds, CameraFluid.NONE);
    }

    public AtmosphereFrame {
        if (cameraFluid == null) cameraFluid = CameraFluid.NONE;
    }

    /** The camera is inside water (any water: swamp and mangrove water too). */
    public boolean cameraInWater() {
        return this.cameraFluid == CameraFluid.WATER;
    }

    /** Final fix R2: the same frame with the camera's fluid. */
    public AtmosphereFrame withCameraFluid(CameraFluid fluid) {
        //2026-10-23 (CTE2: the view under water turned bright some 15 s in): from inside water the view is the water's fog throughout.
        // Vanilla's water fog clears as the player gets used to the water, and once its end passed the render distance the sky mode
        // (and the lighting, VisualsRenderer) switched on at once
        return new AtmosphereFrame(this.serial, this.invVanillaProjection, this.invLodProjection, this.invModelView, this.viewProjection,
                this.cameraY, this.seaLevel, this.fogRed, this.fogGreen, this.fogBlue, this.fogCoversAllRendering || fluid == CameraFluid.WATER,
                this.lodDrawn, this.timeOfDay,
                this.rain, this.thunder, this.sunrise, this.skyColour, this.skyFogEnd, this.rippleOffset, this.clouds, fluid);
    }

    /**
     * vanillaProjection is vanilla's GL projection (depth -1..1); its depth is decoded through Reforged's 0..1 remap
     * (ReforgedProjection). "Fog covers everything" is the LOD composite's rule (CompositeUniforms).
     */
    public static AtmosphereFrame of(long serial, Matrix4fc vanillaProjection, Matrix4fc lodProjection, Matrix4fc modelView, double cameraY,
                                     float seaLevel, FogParameters fog, float vanillaRenderDistance, boolean lodDrawn,
                                     float timeOfDay, float rain, float thunder, float[] sunrise) {
        return new AtmosphereFrame(serial, ReforgedProjection.vulkanDepth(vanillaProjection).invert(), new Matrix4f(lodProjection).invert(),
                new Matrix4f(modelView).invert(), new Matrix4f(vanillaProjection).mul(modelView), (float) cameraY, seaLevel, fog.red(), fog.green(), fog.blue(), fog.environmentalEnd() < vanillaRenderDistance,
                lodDrawn, timeOfDay, rain, thunder, sunrise == null ? null : sunrise.clone(), null, 0, null, null);
    }

    /**
     * P7e-2: the same frame with vanilla's sky colour (ClientLevel.getSkyColor at the camera), for the reflections' fallback sky,
     * and vanilla's sky fog end, the render distance in blocks.
     */
    public AtmosphereFrame withSky(float[] sky, float skyFogEnd) {
        return new AtmosphereFrame(this.serial, this.invVanillaProjection, this.invLodProjection, this.invModelView, this.viewProjection,
                this.cameraY, this.seaLevel, this.fogRed, this.fogGreen, this.fogBlue, this.fogCoversAllRendering, this.lodDrawn, this.timeOfDay,
                this.rain, this.thunder, this.sunrise, sky == null ? null : sky.clone(), skyFogEnd, this.rippleOffset, this.clouds, this.cameraFluid);
    }

    /** P7e-3: the same frame with the ripples' noise offsets (RippleMath.offsets: the octaves' x and z, then the camera's x and z fraction). */
    public AtmosphereFrame withRipple(float[] offsets) {
        return new AtmosphereFrame(this.serial, this.invVanillaProjection, this.invLodProjection, this.invModelView, this.viewProjection,
                this.cameraY, this.seaLevel, this.fogRed, this.fogGreen, this.fogBlue, this.fogCoversAllRendering, this.lodDrawn, this.timeOfDay,
                this.rain, this.thunder, this.sunrise, this.skyColour, this.skyFogEnd, offsets == null ? null : offsets.clone(), this.clouds, this.cameraFluid);
    }

    /** P7h design 3.3: the same frame with vanilla's cloud layer (null: no clouds this frame). */
    public AtmosphereFrame withClouds(CloudFrame clouds) {
        return new AtmosphereFrame(this.serial, this.invVanillaProjection, this.invLodProjection, this.invModelView, this.viewProjection,
                this.cameraY, this.seaLevel, this.fogRed, this.fogGreen, this.fogBlue, this.fogCoversAllRendering, this.lodDrawn, this.timeOfDay,
                this.rain, this.thunder, this.sunrise, this.skyColour, this.skyFogEnd, this.rippleOffset, clouds, this.cameraFluid);
    }
}
