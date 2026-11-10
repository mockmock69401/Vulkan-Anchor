#version 450

#include "light.glsl"
#include "fog.glsl"
//The visuals' scene light (terrain_light.glsl, terrain.json's storage images), written when LightOut is 1
#ifdef TERRAIN_PBR
//2026-11-10 (shader roadmap session 9): the PBR build (terrain_pbr.json, PipelineManager) has three more samplers before the storage
// images (the normal and specular atlases, the parallax lookup), and the material images after them (pbr_terrain.glsl's writeSceneMaterial)
#define SCENE_LIGHT_BINDING 7
#define SCENE_PBR_BINDING 9
#include "terrain_light.glsl"
#include "pbr_terrain.glsl"
#else
#define SCENE_LIGHT_BINDING 4
#include "terrain_light.glsl"
#endif

layout(binding = 2) uniform sampler2D Sampler0;

layout(binding = 1) uniform UBO {
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    float AlphaCutout;
    float LightOut;
    //The visuals (2026-10-28): water's texture opacity scale (WaterMath.surfaceAlpha; 1 with the visuals off)
    float WaterAlpha;
#ifdef TERRAIN_PBR
    //Session 9: x the normal maps' strength, y the parallax depth (0: none), z 1 when the parallax lookup texture is bound (PbrAtlas)
    vec4 PbrParams;
#endif
};


layout(location = 0) in float vertexDistance;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec2 lightLevels;
layout(location = 4) in vec3 albedoTint;
#ifdef TERRAIN_PBR
layout(location = 5) in vec3 relPos;
#endif

layout(location = 0) out vec4 fragColor;

void main() {
#ifdef TERRAIN_PBR
    //Session 9: the parallax moves the texel looked up along the view (pbr_terrain.glsl; none with it off or past its distance)
    vec2 uv = pbrParallaxUv(texCoord0, relPos, PbrParams);
    vec4 texel = textureGrad(Sampler0, uv, pbrDx, pbrDy);
#else
    vec4 texel = texture(Sampler0, texCoord0);
#endif
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
#ifdef TERRAIN_PBR
        //Session 9: the material of the fragment that wrote the albedo, under the same tag (pbr_terrain.glsl)
        if (vertexColor.a <= 1.5 && vertexDistance <= FogStart && texel.a >= TERRAIN_ALBEDO_MIN_ALPHA) {
            writeSceneMaterial(lightLevels, uv, relPos, PbrParams);
        }
#endif
        //2026-11-09: water marks its pixel for the lighting pass, which tells water from ice by it (terrain_light.glsl)
        if (vertexColor.a > 1.5 && nearest) {
            writeWaterMark();
        }
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
