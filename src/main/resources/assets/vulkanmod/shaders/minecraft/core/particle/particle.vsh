#version 450

#include "fog.glsl"

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;

layout(binding = 0) uniform UniformBufferObject {
   mat4 MVP;
};

layout(binding = 3) uniform sampler2D Sampler2;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out float vertexDistance;
//The visuals' scene light (terrain_light.glsl): the levels, interpolated, and the colour before the lightmap
layout(location = 3) out vec2 sceneLightLevels;
layout(location = 4) out vec4 albedoTint;
//2026-11-11 (shader roadmap session 13, ParticleLookMath): the realistic shape (0: the sprite), the tint group (0: none) and the seed,
// carried in the light's free bits (PARTICLE_SHAPE_SHIFT in the block half, PARTICLE_GROUP_SHIFT in the sky half) and in u
layout(location = 5) flat out ivec2 particleLook;
layout(location = 6) flat out float particleSeed;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);

    //The light's levels are 0..240 each: the low 8 bits of each half (a quad without the look's flags is unchanged)
    ivec2 light = UV2 & ivec2(0xFF);
    particleLook = (UV2 >> 8) & ivec2(0xF);

    vertexDistance = fog_distance(Position.xyz, 0);
    texCoord0 = UV0;
    particleSeed = 0.0;
    if (particleLook.x != 0) {
        //Realistic: u = seed * 2 + corner (ParticleLookMath.seedU), v = 0 (top) or 1
        particleSeed = floor(UV0.x * 0.5 + 0.25);
        texCoord0 = vec2(UV0.x - 2.0 * particleSeed, UV0.y);
    }
    vertexColor = Color * texelFetch(Sampler2, light / 16, 0);
    albedoTint = Color;
    sceneLightLevels = vec2(light.y, light.x) / 16.0;
}
