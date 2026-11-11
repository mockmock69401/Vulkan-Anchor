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
layout(location = 6) flat in float particleSeed;

layout(location = 0) out vec4 fragColor;

//The realistic shapes (VanillaParticles.Shape's ordinals)
#define PARTICLE_SHAPE_PUFF 1
#define PARTICLE_SHAPE_FLAME 2
#define PARTICLE_SHAPE_EMBER 3
#define PARTICLE_SHAPE_DROP 4
#define PARTICLE_SHAPE_BUBBLE 5
#define PARTICLE_SHAPE_ORB 6
#define PARTICLE_SHAPE_MOTE 7
#define PARTICLE_SHAPE_PETAL 8
#define PARTICLE_SHAPE_GLINT 9

float particleHash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float particleNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(particleHash(i), particleHash(i + vec2(1, 0)), f.x), mix(particleHash(i + vec2(0, 1)), particleHash(i + vec2(1, 1)), f.x), f.y);
}

//A shape's coverage from its field f (> 0 inside, about the distance to the edge in quad units): opaque inside, a one-pixel anti-aliased
// rim. The composite takes a particle apart from what lies behind it only where it covers part of a pixel (scene_copy.comp
// translucentParticle, tile by tile): a wide soft edge made tile-sized steps there and its faint fragments hid the puffs behind them (the
// depth), so the shapes are solid with smooth outlines and soft shading inside, and glows come from the bloom
float particleEdge(float f) {
    float w = max(fwidth(f), 1e-4);
    return clamp(f / w + 0.5, 0.0, 1.0);
}

/*
 * A realistic shape at q (-1..1 across the quad, y up), the particle's seed s: rgb a brightness factor over the particle's colour (the
 * sprite's mean colour times its vertex colour, SingleQuadParticleM), a its coverage. Round and smooth, without texels; sized so the area
 * it covers is the sprite's (ParticleLookMath.realisticSize).
 */
vec4 realisticShape(int shape, vec2 q, float s) {
    float r = length(q);
    vec2 o = vec2(particleHash(vec2(s, 1.7)), particleHash(vec2(2.3, s))) * 37.0;
    if (shape == PARTICLE_SHAPE_PUFF) {
        //A puff of smoke: a billowy outline (round lobes from two octaves of noise along the angle), lit a little from above and
        // shaded toward its rim, with soft mottling inside
        float a = atan(q.y, q.x);
        float lobes = particleNoise(vec2(a * 2.5, 0.0) + o) * 0.6 + particleNoise(vec2(a * 5.5, 3.0) - o) * 0.4;
        float edge = 0.6 + 0.35 * lobes;
        float m = particleNoise(q * 3.0 + o);
        float shade = 0.78 + 0.22 * clamp(0.5 + 0.6 * q.y, 0.0, 1.0) - 0.18 * smoothstep(0.3, 1.0, r / edge) + 0.1 * (m - 0.5);
        return vec4(vec3(shade + 0.12), particleEdge(edge - r));
    }
    if (shape == PARTICLE_SHAPE_FLAME) {
        //A tongue of flame: an ellipse taller than wide, pointed at its top, with a pale hot core low in it
        float w = 0.55 * (1.0 - 0.5 * clamp(q.y, -1.0, 1.0));
        float t = length(vec2(q.x / w, (q.y + 0.15) / 0.85));
        float core = smoothstep(0.8, 0.1, length(vec2(q.x / (w * 0.6), (q.y + 0.35) / 0.5)));
        return vec4(vec3(0.85) + vec3(0.15, 0.12, 0.05) * core + 0.15 * (1.0 - t), particleEdge((1.0 - t) * 0.6));
    }
    if (shape == PARTICLE_SHAPE_EMBER) {
        //A glowing ember: a round bead, hottest in its middle
        return vec4(vec3(0.75 + 0.25 * smoothstep(0.55, 0.0, r)), particleEdge(0.55 - r));
    }
    if (shape == PARTICLE_SHAPE_DROP) {
        //A droplet: a round body darker at its rim, a highlight up and to the left
        float spec = smoothstep(0.2, 0.0, length(q - vec2(-0.22, 0.25)));
        return vec4(vec3(0.75 + 0.2 * smoothstep(0.6, 0.0, r) + 0.5 * spec), particleEdge(0.6 - r));
    }
    if (shape == PARTICLE_SHAPE_BUBBLE) {
        //A bubble: a thin rim and a highlight (its inside clear)
        float spec = 0.16 - length(q - vec2(-0.26, 0.26));
        float rim = 0.07 - abs(r - 0.6);
        return vec4(vec3(1.0), max(particleEdge(rim), particleEdge(spec)));
    }
    if (shape == PARTICLE_SHAPE_ORB) {
        //A glowing orb: a round bead brightest in its middle
        return vec4(vec3(0.7 + 0.3 * smoothstep(0.45, 0.0, r)), particleEdge(0.45 - r));
    }
    if (shape == PARTICLE_SHAPE_MOTE) {
        //A small round mote
        return vec4(vec3(0.85 + 0.15 * (1.0 - r)), particleEdge(0.5 - r));
    }
    if (shape == PARTICLE_SHAPE_PETAL) {
        //A petal: an ellipse (the particle's roll turns it), lighter toward one end
        float t = length(q / vec2(0.5, 0.85));
        return vec4(vec3(0.85 + 0.15 * clamp(q.y, -1.0, 1.0)), particleEdge((1.0 - t) * 0.5));
    }
    //PARTICLE_SHAPE_GLINT: a four-point star, slender arms on a small bright middle
    float arms = max(0.12 * (1.0 - abs(q.y) / 0.9) - abs(q.x), 0.12 * (1.0 - abs(q.x) / 0.9) - abs(q.y));
    return vec4(vec3(0.8 + 0.2 * smoothstep(0.3, 0.0, r)), particleEdge(max(arms, 0.2 - r)));
}

vec3 particleTint(int group) {
    int i = group - 1;
    return (i < 4 ? ParticleTints[i] : ParticleTints2[i - 4]).rgb;
}

void main() {
    vec4 base;
    if (particleLook.x != 0) {
        base = realisticShape(particleLook.x, vec2(texCoord0.x * 2.0 - 1.0, 1.0 - texCoord0.y * 2.0), particleSeed);
    } else {
        base = texture(Sampler0, texCoord0);
    }
    vec4 color = base * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    vec3 tint = particleLook.y != 0 ? particleTint(particleLook.y) : vec3(1.0);
    color.rgb *= tint;
    if (LightOut > 0.5) {
        //2026-11-11 (the user: see-through snowflakes): a translucent particle's colour too (its alpha taken as 1), from which the
        // composite tells how much of the pixel it covers (scene_copy.comp, translucentParticle)
        writeSceneLight(sceneLightLevels, vec4((base * albedoTint * ColorModulator).rgb * tint, 1.0), vertexDistance <= FogStart);
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
