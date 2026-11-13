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
    //2026-11-11 (shader roadmap session 13, ParticleLook): the look groups' colour tints, group g (1..7) in column (g - 1) % 4
    mat4 ParticleTints;
    mat4 ParticleTints2;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float vertexDistance;
layout(location = 3) in vec2 sceneLightLevels;
layout(location = 4) in vec4 albedoTint;
layout(location = 5) flat in ivec2 particleLook;

layout(location = 0) out vec4 fragColor;

//The realistic shapes (VanillaParticles.Shape's ordinals). 2026-11-13 (the user: "use real images"): a realistic particle draws an image
// tile of the particle atlas (ParticleSprites; Sampler0 is that atlas then) after the post-process (DeferredParticles): white images
// tinted by the particle's colour, the flame's frames as brightness turned into fire (its colour at the edge, white-hot inside)
#define PARTICLE_SHAPE_FLAME 2

vec4 realisticSprite(int shape, vec4 texel, vec3 colour, float alpha) {
    if (shape == PARTICLE_SHAPE_FLAME) {
        float heat = texel.a;
        float peak = max(colour.r, max(colour.g, colour.b));
        vec3 hot = mix(colour, vec3(1.0, 0.96, 0.86) * peak, smoothstep(0.45, 1.0, heat));
        return vec4(hot * (0.55 + 0.45 * heat), heat * alpha);
    }
    return vec4(texel.rgb * colour, texel.a * alpha);
}

vec3 particleTint(int group) {
    int i = group - 1;
    return (i < 4 ? ParticleTints[i] : ParticleTints2[i - 4]).rgb;
}

void main() {
    vec3 tint = particleLook.y != 0 ? particleTint(particleLook.y) : vec3(1.0);
    if (particleLook.x != 0) {
        //A realistic particle's image, drawn after the post-process: no scene light (the composite never sees it)
        vec4 c = vertexColor * ColorModulator;
        vec4 sprite = realisticSprite(particleLook.x, texture(Sampler0, texCoord0), c.rgb * tint, c.a);
        if (sprite.a < 0.004) {
            discard;
        }
        fragColor = linear_fog(sprite, vertexDistance, FogStart, FogEnd, FogColor);
        return;
    }
    vec4 base = texture(Sampler0, texCoord0);
    vec4 color = base * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    color.rgb *= tint;
    if (LightOut > 0.5) {
        //2026-11-11 (the user: see-through snowflakes): a translucent particle's colour too (its alpha taken as 1), from which the
        // composite tells how much of the pixel it covers (scene_copy.comp, translucentParticle)
        writeSceneLight(sceneLightLevels, vec4((base * albedoTint * ColorModulator).rgb * tint, 1.0), vertexDistance <= FogStart);
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
