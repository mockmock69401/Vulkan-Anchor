//2026-10-29 (the user: Complementary Reimagined's High profile reflects on polished and metal blocks, its Low profile keeps only the
// sun's highlights): the solid blocks FoliageMarker marks smooth (lighting.glsl's METAL_ALPHA_CODE and POLISHED_ALPHA_CODE: metal
// blocks; polished stone, quartz, glazed terracotta, packed ice, gem blocks) get the sun's and the moon's highlight in the composite
// (scene_copy.comp, block reflections 1 and 2) and a screen-space reflection of the scene and the sky in reflection.comp (2).
// Import it after lighting.glsl (its codes); no uniforms or bindings. BlockReflectionMath mirrors every const and function here
// (BlockReflectionTest): change both together.
//
// THE MARK. The composite's alpha for these pixels at setting 2 (water.glsl's reflectionMark gives water +1 and ice/glass/slime -0.5)
// is POLISHED_MARK or METAL_MARK: below -1, so water.glsl's reflectivity and its hit test never take them for glass, and a reflected
// ray may stop on another smooth block.
//
// UNITS as water.glsl's: scene units (1.0 = a white surface in full daylight), world unit directions (y up).

//The composite's alpha for a polished and a metal block
const float POLISHED_MARK = -2.0;
const float METAL_MARK = -4.0;
//An alpha below this is a block's mark (between the glass mark and the polished one)
const float BLOCK_MARK_LIMIT = -1.5;
//Reflectance straight on (Schlick 1994's F0): polished stone as a dielectric of index about 1.5; metal this share of its own colour
// (2026-10-29: lower than a real metal's, whose diffuse light is 0: here the texture's lit colour stays under the reflection)
const float POLISHED_F0 = 0.04;
const float METAL_F0 = 0.45;
//The share of the Fresnel term mirrored: a grazing view of a polished floor stays well short of a mirror (its texture and wear)
const float POLISHED_WEIGHT = 0.45;
const float METAL_WEIGHT = 0.55;
//GGX roughness alpha (Walter et al. 2007, "Microfacet models for refraction through rough surfaces") of the highlight; the reflected
// rays tilt by up to BLOCK_RAY_SPREAD of it, a new tilt every frame (the TAA averages them into a soft reflection)
const float POLISHED_ROUGHNESS = 0.15;
const float METAL_ROUGHNESS = 0.3;
const float BLOCK_RAY_SPREAD = 0.6;
//The highlight: GGX's specular lobe D*F/N.V times HIGHLIGHT_SCALE (pi/4 would be physical for a light that lights a white diffuse
// surface to 1; 2026-10-29 in game that turned gold white under the morning sun, the tone curve's shoulder takes everything above 1),
// faded in over the first HIGHLIGHT_TERMINATOR of N.L; N.V is at least NDOTV_MIN and the highlight at most HIGHLIGHT_MAX
const float HIGHLIGHT_SCALE = 0.3;
const float HIGHLIGHT_TERMINATOR = 0.1;
const float NDOTV_MIN = 0.35;
const float HIGHLIGHT_MAX = 3.0;
//The moon's highlight at full night, as a share of the moonlight's strength (it lights the land at a few percent of the sun)
const float MOON_HIGHLIGHT = 0.08;
//A metal's tint: its colour over its brightest channel, never below METAL_TINT_MIN per channel (a dark texel still reflects)
const float METAL_TINT_MIN = 0.1;
//The reflective debug view's colour for these blocks
const float DEBUG_SMOOTH_RED = 0.2;
const float DEBUG_SMOOTH_GREEN = 1.0;
const float DEBUG_SMOOTH_BLUE = 0.3;

//The class of a FoliageMarker code: 0 none, 1 polished, 2 metal
int blockCodeClass(int code) {
    return code == METAL_ALPHA_CODE ? 2 : (code == POLISHED_ALPHA_CODE ? 1 : 0);
}

//The class of the composite's alpha: 0 none (water, glass, nothing), 1 polished, 2 metal
int blockReflectClass(float mark) {
    if (!(mark < BLOCK_MARK_LIMIT)) {
        return 0;
    }
    return mark < 0.5*(POLISHED_MARK + METAL_MARK) ? 2 : 1;
}

//The composite's alpha for a class at the block reflections setting (0 off, 1 highlights, 2 reflections): a mark only at 2
float blockReflectionMark(int cls, float mode) {
    if (!(mode > 1.5)) {
        return 0.0;
    }
    return cls == 2 ? METAL_MARK : (cls == 1 ? POLISHED_MARK : 0.0);
}

//A metal's tint from its (lit) colour: the colour over its brightest channel, at least METAL_TINT_MIN; white for black
vec3 metalTint(vec3 colour) {
    float m = max(colour.r, max(colour.g, colour.b));
    vec3 t = m > 0.000001 ? colour/m : vec3(1.0);
    return clamp(t, vec3(METAL_TINT_MIN), vec3(1.0));
}

//The share of the reflection mixed in per channel (weighted Schlick) for a class at cosTheta (the normal against the direction to the
// camera, or the half vector against it for a highlight); tint is the metal's (metalTint), ignored for polished blocks
vec3 blockReflectivity(int cls, float cosTheta, vec3 tint) {
    float c = clamp(cosTheta, 0.0, 1.0);
    float k = pow(1.0 - c, 5.0);
    if (cls == 2) {
        vec3 f0 = METAL_F0*tint;
        return METAL_WEIGHT*(f0 + (vec3(1.0) - f0)*k);
    }
    if (cls == 1) {
        return vec3(POLISHED_WEIGHT*(POLISHED_F0 + (1.0 - POLISHED_F0)*k));
    }
    return vec3(0.0);
}

float blockRoughness(int cls) {
    return cls == 2 ? METAL_ROUGHNESS : POLISHED_ROUGHNESS;
}

//GGX's normal distribution D(h) at N.H for roughness alpha
float ggxDistribution(float nDotH, float alpha) {
    float a2 = alpha*alpha;
    float d = nDotH*nDotH*(a2 - 1.0) + 1.0;
    return a2/(3.14159265*d*d);
}

//One light's highlight (scene units) on a block of class cls with world normal n, seen along v (toward the camera), from l (toward the
// light) of strength light (scene units: what it lights a white diffuse surface facing it to); tint as blockReflectivity's
vec3 blockHighlight(int cls, vec3 n, vec3 v, vec3 l, vec3 light, vec3 tint) {
    float nl = dot(n, l);
    float nv = dot(n, v);
    if (cls == 0 || !(nl > 0.0) || !(nv > 0.0)) {
        return vec3(0.0);
    }
    vec3 h = normalize(l + v);
    float d = ggxDistribution(max(dot(n, h), 0.0), blockRoughness(cls));
    vec3 f = blockReflectivity(cls, dot(v, h), tint);
    vec3 s = max(light, vec3(0.0))*f*(d*HIGHLIGHT_SCALE*smoothstep(0.0, HIGHLIGHT_TERMINATOR, nl)/max(nv, NDOTV_MIN));
    return min(s, vec3(HIGHLIGHT_MAX));
}

//The normal a reflected ray leaves by: n tilted by up to spread (a disc in its tangent plane) by two noise values 0..1
vec3 roughNormal(vec3 n, float spread, vec2 xi) {
    vec3 t = normalize(abs(n.y) < 0.99 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
    vec3 b = cross(n, t);
    float r = spread*sqrt(clamp(xi.x, 0.0, 1.0));
    float phi = 6.2831853*xi.y;
    return normalize(n + (t*cos(phi) + b*sin(phi))*r);
}
