package net.vulkanmod.visuals.render;

import com.mojang.blaze3d.systems.RenderSystem;

//Replacement for Sodium 0.6+'s FogParameters, which does not exist in Sodium 0.5
public record FogParameters(float red, float green, float blue, float alpha, float environmentalStart, float environmentalEnd, float renderStart, float renderEnd) {
    public static final FogParameters NONE = new FogParameters(0, 0, 0, 0, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE);

    //1.20.1 has a single fog range, it is whatever FogRenderer.setupFog last pushed into the render system
    public static FogParameters fromRenderSystem() {
        float[] colour = RenderSystem.getShaderFogColor();
        float start = RenderSystem.getShaderFogStart();
        float end = RenderSystem.getShaderFogEnd();
        return new FogParameters(colour[0], colour[1], colour[2], colour[3], start, end, start, end);
    }
}
