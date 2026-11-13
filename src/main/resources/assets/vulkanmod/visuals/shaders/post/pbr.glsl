//2026-11-10 (shader roadmap session 9, PBR): what the composite (scene_copy.comp) and the reflection pass (reflection.comp) need to read
// the terrain's LabPBR materials (the vanilla shader include pbr_terrain.glsl writes them, TerrainLightTarget holds them) and light
// with them. No uniforms or bindings; import it after lighting.glsl and blockreflect.glsl. Only the PBR builds (PBR defined:
// VisualsPrograms' .PBR programs) call it. PbrMath mirrors every constant and function here (PbrMathTest): change both together.
//
// THE MATERIAL. Two R32_UINT values per pixel, each tagged with the light value's depth step low bits as the albedo is (a value whose tag
// differs from the pixel's light belongs to another fragment and is not read): the normal (octahedral 11 + 11 bits, world space, the
// normal map already applied; the material AO in 3 bits; a written bit) and the specular texel (LabPBR 1.3's smoothness and F0 as
// stored, porosity 0..15 or scattering 16..31 in 5 bits, the emission in 5).
//
// THE MARK. A PBR pixel that mirrors (its smoothness, wet or not, at least PBR_REFLECT_SMOOTH_MIN, with the PBR reflections on) carries
// -(PBR_MARK_BASE + its smoothness in 0..15 + 16 x its reflectance code + 1024 under a film of rain water) in the composite's alpha: below
// every other mark (water +1, glass -0.5, polished -2, metal -4), exact in half floats (at most 1655). The code is 0..22 a dielectric's
// F0 (sqrt steps up to 0.9), 23..30 LabPBR's predefined metals 230..237, 31..38 a metal coloured by its albedo (its F0's brightness;
// session 16, which moved the film from 512 to 1024 to make room). Under a film the reflection takes the face's own normal: the
// water's surface is flat over the texture's bumps (a bumpy one sparkled noisily in the rain).
//
// Literature: GGX (Walter et al. 2007, as blockreflect.glsl), Schlick 1994's Fresnel term, the predefined metals' F0 from LabPBR's n
// and k at normal incidence ((n - 1)^2 + k^2)/((n + 1)^2 + k^2); wet materials after Lagarde 2013 ("Water drop 2b: Dynamic rain and its
// effects", seblagarde.wordpress.com) and Jensen, Legakis and Dorsey 1999 ("Rendering of wet materials"): a porous surface soaks the
// water up and darkens (the water in its pores keeps light from leaving), a closed one gathers a film on top that reflects. LabPBR's
// porosity (0..64 of the specular blue: sand 64, wool 38, wood 12, metals 0) says which. The decoding of LabPBR's channels and the
// metals' tint follow Glimmer's material.glsl (Copyright (c) 2024 Josh Britain, MIT License; THIRD_PARTY_NOTICES.md).

const uint PBR_NORMAL_WRITTEN = 33554432u;
const uint PBR_TAG_SHIFT = 26u;
const uint PBR_TAG_MASK = 63u;
const float PBR_NORMAL_STEPS = 2047.0;
const float PBR_AO_STEPS = 7.0;
const float PBR_POROSITY_STEPS = 15.0;
const float PBR_EMISSION_STEPS = 31.0;
//LabPBR's F0 range for dielectrics (229/255) and its first metal
const float PBR_F0_MAX = 0.898039;
const int PBR_METAL_FIRST = 230;
const int PBR_METAL_ALBEDO = 255;
//GGX alpha never below this (a mirror's highlight of the sun would be a pixel of infinite strength)
const float PBR_ALPHA_MIN = 0.03;
//A face's sun is shaded by the normal map at most this much more than the face itself
const float PBR_FACING_RATIO_MAX = 2.5;
const float PBR_FACING_MIN = 0.05;
//Subsurface scattering (leaves, wool, skin): the sunlight a fully scattering texel receives as a share of a face toward the sun
const float PBR_SSS_SHARE = 0.45;
//The material AO's share on the ambient light
const float PBR_AO_STRENGTH = 1.0;
//Wet: the sky light (levels) over which a surface gets the rain (15 is open sky above), the film's smoothness and F0, how much darker
// a fully porous wet surface gets, the smoothness a porous wet one reaches at most, the share of the film on a vertical face
const float PBR_WET_SKY_LOW = 13.5;
const float PBR_WET_SKY_HIGH = 14.75;
const float PBR_WET_FILM_SMOOTHNESS = 0.88;
const float PBR_WET_FILM_F0 = 0.02;
const float PBR_WET_DARKEN = 0.55;
const float PBR_WET_POROUS_SMOOTHNESS = 0.4;
const float PBR_WET_SIDE_FILM = 0.35;
const float PBR_WET_SATURATION = 0.25;
//The highlight as blockreflect.glsl's (its HIGHLIGHT_SCALE, HIGHLIGHT_TERMINATOR, NDOTV_MIN, HIGHLIGHT_MAX)
//A metal's diffuse light when its reflection is drawn (a metal's colour is all reflection; 1 keeps the texture lit)
const float PBR_METAL_DIFFUSE = 0.35;
//Reflections: from this smoothness on, full from PBR_REFLECT_SMOOTH_FULL
const float PBR_REFLECT_SMOOTH_MIN = 0.45;
const float PBR_REFLECT_SMOOTH_FULL = 0.75;
//The rough rays' tilt per GGX alpha
const float PBR_RAY_SPREAD = 1.0;
//The screen-space march runs where at least this share is mirrored (below it the sky alone: a wet film seen from above mirrors 2..5%;
// marching all of it cost 1.2 ms over rainy ground at 1440p)
const float PBR_REFLECT_MIN_MARCH = 0.08;
//The emission's gain on the albedo (scene units), before the block glow setting's
const float PBR_EMISSION_GAIN = 1.0;
//The mark (and the film share from which a surface counts as under a film)
const float PBR_MARK_BASE = 8.0;
const float PBR_MARK_LIMIT = -7.5;
const float PBR_SMOOTH_CODES = 15.0;
const float PBR_MARK_FILM = 1024.0;
const float PBR_FILM_FLAT = 0.5;
const int PBR_F0_CODES = 22;
const int PBR_CODE_METAL_FIRST = 23;
const int PBR_CODE_METAL_ALBEDO = 31;
//2026-11-13 (session 16): a metal coloured by its albedo (LabPBR 255) takes its albedo, in linear units, as its F0 (as LabPBR defines it
// and as Glimmer does). Its mark carries that F0's brightest channel in PBR_ALBEDO_F0_STEPS + 1 sqrt steps (codes 31..38); the hue
// comes from the pixel's colour. Before, the F0 was the albedo over its brightest channel: 1 in that channel for every such metal, a
// perfect mirror (with Faithful PBR every metal is 255: a netherite block mirrored the grass round it and vanished, gold went green)
const int PBR_ALBEDO_F0_STEPS = 7;
//The debug view's colour for PBR reflections
const float DEBUG_PBR_RED = 1.0;
const float DEBUG_PBR_GREEN = 0.85;
const float DEBUG_PBR_BLUE = 0.2;

//LabPBR's predefined metals 230..237 (iron, gold, aluminium, chrome, copper, lead, platinum, silver): F0 from their n and k
const vec3 PBR_METAL_F0[8] = vec3[8](vec3(0.5312, 0.5124, 0.4958), vec3(0.9442, 0.7761, 0.3734), vec3(0.9123, 0.9139, 0.9197),
        vec3(0.5556, 0.5545, 0.5548), vec3(0.9260, 0.7209, 0.5042), vec3(0.6325, 0.6259, 0.6415), vec3(0.6788, 0.6424, 0.5884),
        vec3(0.9620, 0.9495, 0.9221));

struct PbrMaterial {
    vec3 normal;//world, unit
    float ao;
    float smoothness;
    float f0;//dielectric F0 (0 for metals)
    int metal;//-1 none, 0..7 a predefined metal, 8 a metal coloured by its albedo
    float porosity;
    float sss;
    float emission;
    bool specular;//the specular texel is not all 0 (a sprite without a map is)
};

vec3 pbrOctDecode(vec2 e) {
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx))*vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

bool pbrTagged(uint value, uint light) {
    return ((value >> PBR_TAG_SHIFT) & PBR_TAG_MASK) == ((light >> 12) & PBR_TAG_MASK);
}

//The material at a pixel from its light value and its two material values; false when they are not this pixel's front fragment's
bool pbrMaterial(uint light, uint n, uint s, out PbrMaterial m) {
    m = PbrMaterial(vec3(0.0, 1.0, 0.0), 1.0, 0.0, 0.0, -1, 0.0, 0.0, 0.0, false);
    if ((n & PBR_NORMAL_WRITTEN) == 0u || !pbrTagged(n, light) || !pbrTagged(s, light)) {
        return false;
    }
    vec2 e = vec2(float(n & 2047u), float((n >> 11) & 2047u))/PBR_NORMAL_STEPS*2.0 - 1.0;
    m.normal = pbrOctDecode(e);
    m.ao = float((n >> 22) & 7u)/PBR_AO_STEPS;
    uint r = s & 255u;
    uint g = (s >> 8) & 255u;
    uint b = (s >> 16) & 31u;
    uint a = (s >> 21) & 31u;
    m.specular = (s & 67108863u) != 0u;
    m.smoothness = float(r)/255.0;
    if (int(g) >= PBR_METAL_FIRST) {
        m.metal = int(g) - PBR_METAL_FIRST < 8 ? int(g) - PBR_METAL_FIRST : 8;
    } else {
        m.f0 = float(g)/255.0;
    }
    if (b < 16u) {
        m.porosity = float(b)/PBR_POROSITY_STEPS;
    } else {
        m.sss = float(b - 16u)/PBR_POROSITY_STEPS;
    }
    m.emission = float(a)/PBR_EMISSION_STEPS;
    return true;
}

//How wet a surface is: the frame's wetness (PbrUniforms: the rain at the camera, eased in and out) where the sky is open above it
float pbrWetAt(float wetness, float skyLevel) {
    return clamp(wetness, 0.0, 1.0)*smoothstep(PBR_WET_SKY_LOW, PBR_WET_SKY_HIGH, skyLevel);
}

//Rain on the material (only where it has a specular map: without one nothing says how it takes water): the film on a closed surface
// (up faces most) raises its smoothness and F0 and lies flat over its bumps (the normal toward the face's, face), a porous one soaks it
// up, darkens and gets a little glossier. Returns the diffuse factor; film receives the film's share
float pbrWet(inout PbrMaterial m, float wet, vec3 face, out float film) {
    film = 0.0;
    if (!(wet > 0.0) || !m.specular) {
        return 1.0;
    }
    film = wet*(1.0 - m.porosity)*mix(PBR_WET_SIDE_FILM, 1.0, clamp(face.y, 0.0, 1.0));
    m.normal = normalize(mix(m.normal, face, film));
    float soak = wet*m.porosity;
    m.smoothness = mix(m.smoothness, max(m.smoothness, PBR_WET_FILM_SMOOTHNESS), film);
    m.smoothness = mix(m.smoothness, max(m.smoothness, PBR_WET_POROUS_SMOOTHNESS), soak);
    if (m.metal < 0) {
        m.f0 = mix(m.f0, max(m.f0, PBR_WET_FILM_F0), max(film, soak));
    }
    return 1.0 - PBR_WET_DARKEN*soak;
}

//A wet porous surface's colour is deeper as well as darker (the water lets more of the light scattered inside out at a lower angle)
vec3 pbrWetColour(vec3 c, float darken) {
    float soak = (1.0 - darken)/PBR_WET_DARKEN;
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return max(mix(vec3(l), c, 1.0 + PBR_WET_SATURATION*soak), vec3(0.0))*darken;
}

float pbrAlpha(float smoothness) {
    float r = 1.0 - clamp(smoothness, 0.0, 1.0);
    return max(r*r, PBR_ALPHA_MIN);
}

//F0 per channel: a dielectric's grey, a predefined metal's, or for metal 8 the albedo (the texture's colour, display values) in linear
// units (postmath.glsl's displayToLinear)
vec3 pbrF0(PbrMaterial m, vec3 albedo) {
    if (m.metal < 0) {
        return vec3(m.f0);
    }
    return m.metal < 8 ? PBR_METAL_F0[m.metal] : displayToLinear(clamp(albedo, vec3(0.0), vec3(1.0)));
}

vec3 pbrFresnel(vec3 f0, float cosTheta) {
    float k = pow(1.0 - clamp(cosTheta, 0.0, 1.0), 5.0);
    return f0 + (vec3(1.0) - f0)*k;
}

//The facing ratio a normal-mapped texel's direct sun takes over its face's: facing(nm.l)/facing(nb.l), where the face sees the sun
float pbrFacingRatio(vec3 nm, vec3 nb, vec3 l) {
    float face = dot(nb, l);
    if (!(face > 0.0)) {
        return 1.0;
    }
    return min(clamp(dot(nm, l), 0.0, 1.0)/max(face, PBR_FACING_MIN), PBR_FACING_RATIO_MAX);
}

//The direct sun a scattering texel receives: at least its share of what a face toward the sun gets (visibility: the face's, or an
// estimate from the sky light where the face is turned away and was not looked up)
float pbrScatter(float direct, float sss, float visibility) {
    return max(direct, PBR_SSS_SHARE*clamp(sss, 0.0, 1.0)*clamp(visibility, 0.0, 1.0));
}

//One light's highlight (scene units) on the material seen along v (toward the camera) from l, of strength light (blockHighlight's
// units and limits); f0 per channel
vec3 pbrHighlight(PbrMaterial m, vec3 f0, vec3 v, vec3 l, vec3 light) {
    vec3 n = m.normal;
    float nl = dot(n, l);
    float nv = dot(n, v);
    if (!(nl > 0.0) || !(nv > 0.0)) {
        return vec3(0.0);
    }
    vec3 h = normalize(l + v);
    float d = ggxDistribution(max(dot(n, h), 0.0), pbrAlpha(m.smoothness));
    vec3 f = pbrFresnel(f0, dot(v, h));
    vec3 s = max(light, vec3(0.0))*f*(d*HIGHLIGHT_SCALE*smoothstep(0.0, HIGHLIGHT_TERMINATOR, nl)/max(nv, NDOTV_MIN));
    return min(s, vec3(HIGHLIGHT_MAX));
}

//Whether the material mirrors (with the PBR reflections on)
bool pbrReflects(PbrMaterial m) {
    return m.smoothness >= PBR_REFLECT_SMOOTH_MIN;
}

//The reflectance code (see THE MARK); albedo as pbrF0's
int pbrF0Code(PbrMaterial m, vec3 albedo) {
    if (m.metal >= 0) {
        if (m.metal < 8) {
            return PBR_CODE_METAL_FIRST + m.metal;
        }
        float b = displayToLinear(clamp(max(albedo.r, max(albedo.g, albedo.b)), 0.0, 1.0));
        return PBR_CODE_METAL_ALBEDO + int(round(sqrt(b)*float(PBR_ALBEDO_F0_STEPS)));
    }
    return int(round(sqrt(clamp(m.f0/PBR_F0_MAX, 0.0, 1.0))*float(PBR_F0_CODES)));
}

float pbrMark(PbrMaterial m, float film, vec3 albedo) {
    return -(PBR_MARK_BASE + round(clamp(m.smoothness, 0.0, 1.0)*PBR_SMOOTH_CODES) + 16.0*float(pbrF0Code(m, albedo))
            + (film >= PBR_FILM_FLAT ? PBR_MARK_FILM : 0.0));
}

bool isPbrMark(float mark) {
    return mark < PBR_MARK_LIMIT;
}

//A mark's smoothness, reflectance code and whether it is under a film
void pbrUnmark(float mark, out float smoothness, out int code, out bool film) {
    float m = max(-mark - PBR_MARK_BASE, 0.0);
    film = m >= PBR_MARK_FILM - 0.5;
    if (film) {
        m -= PBR_MARK_FILM;
    }
    code = int(floor(m/16.0 + 0.001));
    smoothness = clamp((m - 16.0*float(code))/PBR_SMOOTH_CODES, 0.0, 1.0);
}

//F0 per channel for a reflectance code, for the mirror (the reflection pass); tint is the metal's colour (blockreflect.glsl's metalTint
// of the lit colour: its hue, brightest channel 1), scaled for an albedo metal by the brightness its code carries (session 16)
vec3 pbrCodeF0(int code, vec3 tint) {
    if (code >= PBR_CODE_METAL_ALBEDO) {
        float q = float(min(code - PBR_CODE_METAL_ALBEDO, PBR_ALBEDO_F0_STEPS))/float(PBR_ALBEDO_F0_STEPS);
        return tint*(q*q);
    }
    if (code >= PBR_CODE_METAL_FIRST) {
        return PBR_METAL_F0[code - PBR_CODE_METAL_FIRST];
    }
    float q = float(code)/float(PBR_F0_CODES);
    return vec3(q*q*PBR_F0_MAX);
}

//2026-11-13 (session 16): the mirrored share's Fresnel term over a rough surface: Schlick's rise toward grazing reaches at most the
// smoothness instead of 1 (Lagarde 2011, "Adopting a physically based shading model", seblagarde.wordpress.com: the environment's
// Fresnel term with roughness). A rough surface's microfacets turn most of a grazing view away from the mirror direction and shadow
// each other; the plain term mirrored the sky at 100% on every grazing face, so Faithful PBR's cobblestone, sand and ice (smoothness
// 0.6 .. 0.66) wore a blue sky sheen, a glass look the same pack does not have under other LabPBR shaders
vec3 pbrFresnelRough(vec3 f0, float cosTheta, float smoothness) {
    float k = pow(1.0 - clamp(cosTheta, 0.0, 1.0), 5.0);
    return f0 + (max(vec3(clamp(smoothness, 0.0, 1.0)), f0) - f0)*k;
}

//The share of the reflection mixed in per channel at cosTheta for a mark's smoothness and F0 (fading in over the smoothness range)
vec3 pbrReflectivity(float smoothness, vec3 f0, float cosTheta) {
    return pbrFresnelRough(f0, cosTheta, smoothness)*smoothstep(PBR_REFLECT_SMOOTH_MIN, PBR_REFLECT_SMOOTH_FULL, smoothness);
}
