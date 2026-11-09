#version 450

#include "light.glsl"
#include "fog.glsl"
//The visuals' scene light (terrain_light.glsl, terrain.json's storage images), written when LightOut is 1
#define SCENE_LIGHT_BINDING 4
#include "terrain_light.glsl"

layout(binding = 2) uniform sampler2D Sampler0;

layout(binding = 1) uniform UBO {
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    float AlphaCutout;
    float LightOut;
    //The visuals (2026-10-28): water's texture opacity scale (WaterMath.surfaceAlpha; 1 with the visuals off)
    float WaterAlpha;
};


layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec2 lightLevels;
layout(location = 4) in vec3 albedoTint;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    vec4 color = texel * vertexColor;
    //Water (terrain.vsh's WATER_FLAG in the vertex alpha): its texture at WaterAlpha of its opacity
    if (vertexColor.a > 1.5) {
        color.a = texel.a * WaterAlpha;
    }
    if (color.a < AlphaCutout) {
        discard;
    }
    if (LightOut > 0.5) {
        bool nearest = writeSceneLight(lightLevels, vec4(texel.rgb * albedoTint, texel.a), vertexDistance <= FogStart, terrainTinted(albedoTint));
        //2026-11-09: water marks its pixel for the lighting pass, which tells water from ice by it (terrain_light.glsl)
        if (vertexColor.a > 1.5 && nearest) {
            writeWaterMark();
        }
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
