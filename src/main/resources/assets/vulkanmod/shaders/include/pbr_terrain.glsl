//2026-11-10 (shader roadmap session 9, PBR): the terrain's LabPBR materials. terrain.fsh's PBR build (TERRAIN_PBR, terrain_pbr.json,
// PipelineManager) includes it after terrain_light.glsl. The resource packs' normal (_n) and specular (_s) maps are stitched into two
// atlases laid out as the block atlas (PbrAtlas): each terrain fragment that writes its albedo also writes its material, under the same
// depth tag, into two more R32_UINT images (TerrainLightTarget.NORMAL_NAME, SPECULAR_NAME), which the composite and the reflection pass
// read (visuals/post/pbr.glsl). PbrMath mirrors every constant and packing here (PbrMathTest): change both together.
//
// The format is LabPBR 1.3 (shaderLABS wiki, "LabPBR Material Standard"): _n r, g the normal's x, y in DirectX order (y down the
// texture), its z rebuilt as sqrt(1 - x^2 - y^2), b the material AO, a the height (255 the top, 0 a quarter of the texture deep); _s r
// the perceptual smoothness, g F0 up to 229 and the predefined metals 230..255, b porosity up to 64 and subsurface scattering from 65,
// a the emission up to 254 (255 none).
//
// The tangent frame comes from the screen derivatives of the position and the texture coordinate (the cotangent frame of Schueler 2006,
// "Normal mapping without precomputed tangents", ShaderX5): VulkanMod's compressed terrain vertex has no normal or tangent. The parallax
// march follows Glimmer's (Copyright (c) 2024 Josh Britain, MIT License; THIRD_PARTY_NOTICES.md): a linear search for the first layer
// under the height map, then halvings.

layout(binding = 4) uniform sampler2D Sampler3;
layout(binding = 5) uniform sampler2D Sampler4;
//The parallax lookup (PbrAtlas.spriteMap): per atlas cell its place in its sprite (x, y) and the sprite's width and height, in cells
layout(binding = 6) uniform usampler2D Sampler5;

#ifdef SCENE_PBR_BINDING
layout(binding = SCENE_PBR_BINDING, r32ui) uniform restrict uimage2D TerrainNormal;
layout(binding = SCENE_PBR_BINDING + 1, r32ui) uniform restrict uimage2D TerrainSpecular;
#endif

//The normal: octahedral x and y in 11 bits each, the material AO in 3, a written bit, the light value's depth tag on top
const float PBR_NORMAL_STEPS = 2047.0;
const float PBR_AO_STEPS = 7.0;
const uint PBR_NORMAL_WRITTEN = 33554432u;
const uint PBR_TAG_SHIFT = 26u;
//The specular: smoothness and F0 as stored (8 bits each), porosity (0..15) or scattering (16..31) in 5 bits, the emission in 5, the tag
const float PBR_POROSITY_STEPS = 15.0;
const float PBR_EMISSION_STEPS = 31.0;
//A face normal within acos(PBR_SNAP_MIN) of a block axis is that axis
const float PBR_SNAP_MIN = 0.98;
//The parallax: layers of the linear search, halvings after it, the distance (blocks) it fades out over, the share of the depth a
// height step must be under the ray by
const int PBR_PARALLAX_STEPS = 24;
const int PBR_PARALLAX_REFINE = 5;
const float PBR_PARALLAX_FADE_START = 12.0;
const float PBR_PARALLAX_FADE_END = 20.0;
const float PBR_PARALLAX_MIN_COS = 0.08;

vec2 pbrOctEncode(vec3 n) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    vec2 o = n.xy;
    if (n.z < 0.0) {
        o = (1.0 - abs(n.yx))*vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return o;
}

//n: a unit world normal; ao 0..1; light: the fragment's packTerrainLight value
uint packTerrainNormal(vec3 n, float ao, uint light) {
    uvec2 q = uvec2(round(clamp(pbrOctEncode(n)*0.5 + 0.5, 0.0, 1.0)*PBR_NORMAL_STEPS));
    uint a = uint(round(clamp(ao, 0.0, 1.0)*PBR_AO_STEPS));
    return (((light >> 12) & TERRAIN_ALBEDO_TAG_MASK) << PBR_TAG_SHIFT) | PBR_NORMAL_WRITTEN | (a << 22) | (q.y << 11) | q.x;
}

//s: the specular texel (0..1 as sampled)
uint packTerrainSpecular(vec4 s, uint light) {
    uvec4 b = uvec4(round(clamp(s, 0.0, 1.0)*255.0));
    uint scatter = b.b <= 64u ? uint(round(float(b.b)/64.0*PBR_POROSITY_STEPS)) : 16u + uint(round(float(b.b - 65u)/190.0*PBR_POROSITY_STEPS));
    uint emission = b.a >= 255u ? 0u : uint(round(float(b.a)/254.0*PBR_EMISSION_STEPS));
    return (((light >> 12) & TERRAIN_ALBEDO_TAG_MASK) << PBR_TAG_SHIFT) | (emission << 21) | (scatter << 16) | (b.g << 8) | b.r;
}

//The fragment's tangent frame: columns along u, along v (down the texture) and the face normal, in camera-relative world space. Taken
// once at the top of the fragment (derivatives need every fragment of the quad)
mat3 pbrTangentFrame(vec2 uv, vec3 pos) {
    vec3 dpx = dFdx(pos);
    vec3 dpy = dFdy(pos);
    vec2 dux = dFdx(uv);
    vec2 duy = dFdy(uv);
    vec3 n = cross(dpx, dpy);
    float len = length(n);
    n = len > 1e-12 ? n/len : -normalize(pos + vec3(0.0, 1e-6, 0.0));
    if (dot(n, pos) > 0.0) {
        n = -n;
    }
    vec3 a = abs(n);
    float m = max(a.x, max(a.y, a.z));
    if (m >= PBR_SNAP_MIN) {
        n = a.x == m ? vec3(sign(n.x), 0.0, 0.0) : (a.y == m ? vec3(0.0, sign(n.y), 0.0) : vec3(0.0, 0.0, sign(n.z)));
    }
    vec3 dp2perp = cross(dpy, n);
    vec3 dp1perp = cross(n, dpx);
    vec3 t = dp2perp*dux.x + dp1perp*duy.x;
    vec3 b = dp2perp*dux.y + dp1perp*duy.y;
    float scale = max(dot(t, t), dot(b, b));
    float inv = scale > 0.0 ? inversesqrt(scale) : 0.0;
    return mat3(t*inv, b*inv, n);
}

//A LabPBR normal texel to a world normal through the frame; strength 0 is the face's own normal
vec3 pbrMappedNormal(vec4 texel, mat3 tbn, float strength) {
    vec2 xy = texel.rg*2.0 - 1.0;
    vec3 t = vec3(xy, sqrt(clamp(1.0 - dot(xy, xy), 0.0, 1.0)));
    vec3 n = tbn*t;
    float len = length(n);
    n = len > 1e-6 ? n/len : tbn[2];
    n = normalize(mix(tbn[2], n, clamp(strength, 0.0, 2.0)));
    //Never turned away from the camera's side of the face
    return dot(n, tbn[2]) > 0.05 ? n : tbn[2];
}

mat3 pbrFrame;
vec2 pbrDx, pbrDy;

//The parallax-shifted texture coordinate (texCoord0 itself without the lookup, past the distance, or with the depth at 0)
vec2 pbrParallaxUv(vec2 uv, vec3 pos, vec4 params) {
    pbrFrame = pbrTangentFrame(uv, pos);
    pbrDx = dFdx(uv);
    pbrDy = dFdy(uv);
    float dist = length(pos);
    if (!(params.y > 0.0) || params.z < 0.5 || dist >= PBR_PARALLAX_FADE_END) {
        return uv;
    }
    ivec2 atlas = textureSize(Sampler3, 0);
    ivec2 cells = textureSize(Sampler5, 0);
    vec2 cell = vec2(atlas)/vec2(cells);
    ivec2 at0 = clamp(ivec2(uv*vec2(cells)), ivec2(0), cells - 1);
    uvec4 r = texelFetch(Sampler5, at0, 0);
    if (r.z == 0u || r.w == 0u) {
        return uv;
    }
    vec2 origin = vec2(at0 - ivec2(r.xy))*cell/vec2(atlas);
    vec2 size = vec2(r.zw)*cell/vec2(atlas);
    vec3 v = normalize(-pos)*pbrFrame;
    if (v.z < PBR_PARALLAX_MIN_COS) {
        return uv;
    }
    float fade = 1.0 - smoothstep(PBR_PARALLAX_FADE_START, PBR_PARALLAX_FADE_END, dist);
    //The ray in the sprite's own coordinates (0..1 across it), one layer of depth per step; 1 depth is a quarter of the sprite (LabPBR)
    vec2 local = (uv - origin)/size;
    vec3 step = vec3(-v.xy/v.z*(0.25*params.y*fade), 1.0)/float(PBR_PARALLAX_STEPS);
    vec3 at = vec3(local, 0.0);
    vec3 before = at;
    float depth = 1.0 - textureGrad(Sampler3, origin + fract(at.xy)*size, pbrDx, pbrDy).a;
    if (depth <= 1.0/255.0) {
        return uv;
    }
    for (int i = 0; i < PBR_PARALLAX_STEPS && depth > at.z; i++) {
        before = at;
        at += step;
        depth = 1.0 - textureGrad(Sampler3, origin + fract(at.xy)*size, pbrDx, pbrDy).a;
    }
    vec3 lo = before;
    vec3 hi = at;
    for (int i = 0; i < PBR_PARALLAX_REFINE; i++) {
        vec3 mid = 0.5*(lo + hi);
        float d = 1.0 - textureGrad(Sampler3, origin + fract(mid.xy)*size, pbrDx, pbrDy).a;
        if (d > mid.z) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    return origin + fract(lo.xy)*size;
}

#ifdef SCENE_PBR_BINDING
//Writes both values while v is still the pixel's light, a few tries (terrain_light.glsl's writeAlbedoNear without the tinted layers):
// each failed swap means another fragment wrote, and a nearer one's must stay
void writeMaterialNear(ivec2 p, uint v, uint normal, uint specular) {
    uint seenN = imageAtomicOr(TerrainNormal, p, 0u);
    uint seenS = imageAtomicOr(TerrainSpecular, p, 0u);
    bool doneN = false;
    bool doneS = false;
    for (int i = 0; i < 4; i++) {
        if (imageAtomicOr(TerrainLight, p, 0u) != v) {
            return;
        }
        if (!doneN) {
            uint was = imageAtomicCompSwap(TerrainNormal, p, seenN, normal);
            doneN = was == seenN;
            seenN = was;
        }
        if (!doneS) {
            uint was = imageAtomicCompSwap(TerrainSpecular, p, seenS, specular);
            doneS = was == seenS;
            seenS = was;
        }
        if (doneN && doneS) {
            return;
        }
    }
}

//The material of a fragment that wrote its albedo (opaque, unfogged, not water): light its levels as writeSceneLight took them, uv the
// (parallax-shifted) texture coordinate. pbrParallaxUv took the frame
void writeSceneMaterial(vec2 light, vec2 uv, vec3 pos, vec4 params) {
    ivec2 p = ivec2(gl_FragCoord.xy);
    if (any(greaterThanEqual(p, imageSize(TerrainNormal)))) {
        return;
    }
    uint v = packTerrainLight(gl_FragCoord.z, light);
    //A fragment already behind a nearer one (most of the overdraw) reads no maps
    if (imageAtomicOr(TerrainLight, p, 0u) != v) {
        return;
    }
    vec4 nt = textureGrad(Sampler3, uv, pbrDx, pbrDy);
    vec4 st = textureGrad(Sampler4, uv, pbrDx, pbrDy);
    writeMaterialNear(p, v, packTerrainNormal(pbrMappedNormal(nt, pbrFrame, params.x), nt.b, v), packTerrainSpecular(st, v));
}
#endif
