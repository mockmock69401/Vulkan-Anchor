#version 450
#include "fog.glsl"
//The visuals' scene light (terrain_light.glsl, the JSON's storage images), written when LightOut is 1
#define SCENE_LIGHT_BINDING 4
#include "terrain_light.glsl"

layout(binding = 2) uniform sampler2D Sampler0;

layout(binding = 1) uniform UBO{
    vec4 ColorModulator;
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    float LightOut;
};

layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec2 sceneLightLevels;
layout(location = 4) in vec4 albedoTint;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    if (LightOut > 0.5) {
        writeSceneLight(sceneLightLevels, texture(Sampler0, texCoord0) * albedoTint * ColorModulator, vertexDistance <= FogStart);
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}