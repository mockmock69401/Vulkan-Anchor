//W5: what the clean lighting_blur.comp and the composite need to read lighting.comp's outputs, and the normals both use. No uniforms
// or bindings: any shader can import it (it imports depthutils.glsl for FAR and SCREEN2NDC). LightingMath mirrors every constant and
// function here: change both together.
//
// lighting.comp writes, per half-resolution texel q (the full-resolution pixel 2q):
//   lightOut.r  always 1 (lighting.comp writes it; the ambient occlusion is W10's own image, the composite's aoTex at binding 13)
//   lightOut.g  RELATIVE SUN FACING F, 0..FACING_MAX: the direct sunlight the surface's orientation lets it receive, relative to flat
//               ground in full sun: facing(N.L)/max(facing(up.L), GROUND_FACING_MIN). 1 = as much as flat ground facing up while the
//               sun is above sin(elevation) = GROUND_FACING_MIN (about 6 degrees; below it the ground itself gets facing(up.L)/0.1 < 1),
//               0 = turned away from the sun, above 1 = turned toward a low sun more than the ground is (capped at FACING_MAX). Far away
//               (few pixels per block) it fades to 1 (B2). Water, ice, glass, plant and light-source pixels are 1 (no face shading,
//               B11, B13). With sun shading off it is 1.
//   lightOut.b  view distance in blocks of the surface the texel shows, SKY_DISTANCE (negative) for sky
//   lightOut.a  translucent kind: > 0 water (the length in blocks the view ray travels through it, up to WATER_MAX_LENGTH), < 0
//               ice/glass/slime (minus that length), 0 anything else
//   sunOut.r    SUN VISIBILITY V, 0..1: the share of the sun the shadow maps let through (1 = lit, also with no shadow maps drawn or
//               sun shading off; 0 on faces turned away from the sun, which are not looked up). Far away small shadows are averaged
//               out (B2); large ones (islands, mountains) stay.
//   sunOut.gba  the view-space unit normal the texel was lit with (the sun direction for plant pixels, 0 for sky)
//   plantSunOut per full-resolution pixel: a plant pixel's own V (it is lit as if facing the sun, F = 1), -1 for every other pixel
// What the composite binds: lightTex is lighting_blur.comp's output, lightOut's own values (a plain copy since final fix R5: the AO
// in .r is no longer blurred, it is 1); sunTex is the raw sunOut; plantSunTex the raw plantSunOut. None of the three is defined when the pass did not run
// (slot 10 .z = 0): read none of them then.
//
// The composite combines them as:
//   lt        = lightingAt(lightTex, sunTex, p, dist, normal): normal is the pixel's own normal made as the pass makes it -- for an
//               LOD pixel lodFaceNormal(texelFetch(lodLight, lodTexel(p, size), 0).r, east, up), for a vanilla pixel
//               snapNormal(depthNormal(mainDepth, invVanillaProj, p, size, view), east, up) -- or vec3(0.0) for sky, plant and
//               light-source pixels (and wherever the cost is not wanted: then only distances weigh). east and up are world east and up in
//               view space; from the composite's invView (slot 2, view to world): east = vec3(invView[0][0], invView[1][0], invView[2][0]),
//               up = vec3(invView[0][1], invView[1][1], invView[2][1]).
//   direct    = lt.y (= F*V) for ordinary pixels, plantSun for plant pixels (isPlant and plantSun >= 0), and no sun shading at all
//               for light-source pixels (isEmissive)
//   factor    = sunShadingAt(direct, skyLight, sunWeight, sunTerm), sunTerm = slot 10 .w (LightingMath.sunHeightTerm: 0 low sun ..
//               1 high sun; the shade and sun levels follow it). shadeLevel(sunTerm) / sunLevel(sunTerm) are the two levels (for a
//               share-of-light debug view: sunPart = (sun - shade)*share/(shade + (sun - shade)*share)).
//   lit pixel = the pixel's sky-lit light x factor (block light is left alone). sunWeight is slot 10 .y (setting x day x sky exposure),
//               skyLight the pixel's sky light 0..1. Water, ice and glass have F = 1 but are still gated by their own sky light
//               (skyGate inside sunShadingAt), like every pixel. sunWeight 0 gives factor 1 exactly: the vanilla look (B20). Use the factor,
//               and read any lighting texture, only when slot 10 .z (the pass ran) is 1.
//   sunShare(direct, skyLight) is the share of the light that is direct sun, for the warm/cool light colour (B15).
//   lt.z      the translucent kind at the pixel: its sign is boundary 3.2's sceneOut.a convention (> 0 water, < 0 ice/glass/slime,
//               0 no reflection); the composite turns it into the reflection weight it writes there (the magnitude is a length, not a
//               weight).

#import <vulkanmod:visuals/util/depthutils.glsl>

//The texel lighting_blur.comp's AO blur reaches on each side (boundary 3.9)
const int BLUR_RADIUS = 2;
//Depth tolerance of the blur: a neighbour's weight falls by e for each (BLUR_DEPTH_SHARE x centre distance + BLUR_DEPTH_MIN) blocks of
// distance difference
const float BLUR_DEPTH_SHARE = 0.05;
const float BLUR_DEPTH_MIN = 0.1;
//The composite's sun shading: the sky-lit light is multiplied by the shade level in full shade and by the sun level on sunlit flat
// ground (direct = 1), linearly in between and beyond (direct up to FACING_MAX). Both levels follow the sun's height through the
// per-frame sun term t (LightingMath.sunHeightTerm, slot 10 .w): SHADE_LEVEL / SUN_LEVEL with a high sun (t = 1: noon, S1 S6 S14),
// LOW_SUN_SHADE_LEVEL / LOW_SUN_SUN_LEVEL with a low one (t = 0 below sin(elevation) SUN_TERM_LOW: the sunset of S2), smoothly between
// up to SUN_TERM_HIGH. Tuning round 1: noon terrain was half as bright as the references with too little sun/shade contrast.
//2026-10-28: SUN_LEVEL 3.2 (was 2.5): sunlit leaves next to Complementary Reimagined's were a quarter darker
//2026-11-03 (BMC5: noon sand white): 0.65 and 2.88 (x0.9); open ground was a quarter brighter than Complementary Reimagined's
const float SHADE_LEVEL = 0.65;
const float SUN_LEVEL = 2.88;
const float LOW_SUN_SHADE_LEVEL = 0.55;
const float LOW_SUN_SUN_LEVEL = 1.1;
const float SUN_TERM_LOW = 0.2;
const float SUN_TERM_HIGH = 0.8;
const float FACING_MAX = 1.5;
//Direct sun needs sky light: none below SKY_GATE_LOW (caves, deep overhangs: no daylight leaking in, B14/S8), all above SKY_GATE_HIGH
const float SKY_GATE_LOW = 0.35;
const float SKY_GATE_HIGH = 0.9;
//lightOut.b for sky texels
const float SKY_DISTANCE = -1.0;
//Upsampling (lightingAt): depth tolerance as in the blur, and the power of the normal agreement
const float UPSAMPLE_DEPTH_SHARE = 0.03;
const float UPSAMPLE_DEPTH_MIN = 0.1;
const float UPSAMPLE_NORMAL_POWER = 8.0;
//FoliageMarker's alpha marks in the 8-bit main colour: plant quads and light sources (round(alpha*255))
const int PLANT_ALPHA_CODE = 254;
const int EMISSIVE_ALPHA_CODE = 253;
//2026-10-28: FoliageMarker.ORE_ALPHA (an ore's quads; lit as any block, the composite adds its specks' glow)
const int ORE_ALPHA_CODE = 252;
//2026-10-28: FoliageMarker.EMISSIVE_FLUID_ALPHA (a light-emitting fluid: a light source with a stronger glow)
const int EMISSIVE_FLUID_ALPHA_CODE = 251;
//2026-10-29: FoliageMarker.METAL_ALPHA and POLISHED_ALPHA (smooth blocks: blockreflect.glsl's highlights and reflections)
const int METAL_ALPHA_CODE = 246;
const int POLISHED_ALPHA_CODE = 245;

//boundary 3.9: the weight of the texel at offset (i, j) whose view distance is sampleDist, for a centre at centreDist. A tent in space
// ((BLUR_RADIUS+1)^2 at the centre) times a falloff in the distance difference relative to the centre's distance.
float blurWeight(int i, int j, float centreDist, float sampleDist) {
    float tent = float((BLUR_RADIUS + 1 - abs(i))*(BLUR_RADIUS + 1 - abs(j)));
    return tent*exp(-abs(sampleDist - centreDist)/(BLUR_DEPTH_SHARE*abs(centreDist) + BLUR_DEPTH_MIN));
}

//The FoliageMarker code of a main-colour alpha
int alphaCode(float alpha) {
    return int(floor(alpha*255.0 + 0.5));
}

bool isPlant(float alpha) {
    return alphaCode(alpha) == PLANT_ALPHA_CODE;
}

bool isEmissive(float alpha) {
    int code = alphaCode(alpha);
    return code == EMISSIVE_ALPHA_CODE || code == EMISSIVE_FLUID_ALPHA_CODE;
}

//Direct sun only where the sky reaches
float skyGate(float skyLight) {
    return smoothstep(SKY_GATE_LOW, SKY_GATE_HIGH, skyLight);
}

//The share of direct sun for the light colour: 0 shade .. 1 sunlit ground (up to FACING_MAX)
float sunShare(float direct, float skyLight) {
    return direct*skyGate(skyLight);
}

//The shade and sun levels for the sun term t (slot 10 .w)
float shadeLevel(float sunTerm) {
    return mix(LOW_SUN_SHADE_LEVEL, SHADE_LEVEL, clamp(sunTerm, 0.0, 1.0));
}

float sunLevel(float sunTerm) {
    return mix(LOW_SUN_SUN_LEVEL, SUN_LEVEL, clamp(sunTerm, 0.0, 1.0));
}

//The factor on a pixel's sky-lit light. direct = F*V (or plantSun); sunTerm is slot 10 .w; sunWeight 0 gives 1 (the vanilla look)
float sunShadingAt(float direct, float skyLight, float sunWeight, float sunTerm) {
    float shade = shadeLevel(sunTerm);
    float lit = shade + (sunLevel(sunTerm) - shade)*sunShare(direct, skyLight);
    return mix(1.0, lit, clamp(sunWeight, 0.0, 1.0));
}

//Reads the half-resolution lighting at full-resolution pixel p (row 0 at the top). lightTex is the blurred lightOut, sunTex sunOut;
// dist is the pixel's own view distance (negative for sky), normal its view-space normal or vec3(0.0) when unknown. Texel q stands for
// full pixel 2q, so an even coordinate reads one texel and an odd one the two around it, each weighted by how well its distance (and
// normal) match the pixel's; when none matches, the texel closest in distance is taken. Returns x = AO, y = direct = F*V (the product
// is interpolated, not its factors), z = the translucent kind of the best-matching texel (kinds are never blended), w = V.
vec4 lightingAt(sampler2D lightTex, sampler2D sunTex, ivec2 p, float dist, vec3 normal) {
    ivec2 size = textureSize(lightTex, 0);
    ivec2 q0 = p >> 1;
    ivec2 odd = p & 1;
    vec3 sum = vec3(0.0);
    float weights = 0.0;
    float bestWeight = 0.0;
    float bestKind = 0.0;
    float closest = 1e30;
    vec4 fallback = vec4(1.0, 1.0, 0.0, 1.0);
    bool useNormal = dot(normal, normal) > 0.0;
    for (int j = 0; j <= odd.y; j++) {
        for (int i = 0; i <= odd.x; i++) {
            ivec2 q = min(q0 + ivec2(i, j), size - 1);
            vec4 l = texelFetch(lightTex, q, 0);
            vec4 s = texelFetch(sunTex, q, 0);
            float w;
            float miss;
            if (dist < 0.0) {
                w = l.b < 0.0 ? 1.0 : 0.0;
                miss = l.b < 0.0 ? 0.0 : 1.0;
            } else if (l.b < 0.0) {
                w = 0.0;
                miss = 1e29;
            } else {
                miss = abs(l.b - dist);
                w = exp(-miss/(UPSAMPLE_DEPTH_SHARE*dist + UPSAMPLE_DEPTH_MIN));
                if (useNormal && dot(s.gba, s.gba) > 0.0) {
                    w *= pow(max(dot(normal, s.gba), 0.0), UPSAMPLE_NORMAL_POWER);
                }
            }
            sum += w*vec3(l.r, l.g*s.r, s.r);
            weights += w;
            if (w > bestWeight) {
                bestWeight = w;
                bestKind = l.a;
            }
            if (miss < closest) {
                closest = miss;
                fallback = vec4(l.r, l.g*s.r, l.a, s.r);
            }
        }
    }
    if (weights < 1e-6) {
        return fallback;
    }
    return vec4(sum.x/weights, sum.y/weights, bestKind, sum.z/weights);
}

// ---- normals: lighting.comp's, for the composite to make the same ones (lightingAt's normal) ----

//A rebuilt normal within acos(NORMAL_SNAP_MIN) of a block axis is that axis
const float NORMAL_SNAP_MIN = 0.8;

//World south (+z) in view space from world east and up: east x up (the view's rotation keeps cross products)
vec3 worldToView(vec3 w, vec3 east, vec3 up) {
    return east*w.x + up*w.y + cross(east, up)*w.z;
}

//boundary 3.1: lodLight's high byte is the LOD face index + 1 (down, up, north -z, south +z, west -x, east +x); 0 (none) or anything
// else falls back to world up, a normal that does not depend on the neighbours (B1)
const vec3 FACE_NORMALS[7] = vec3[7](vec3(0.0, 1.0, 0.0), vec3(0.0, -1.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, -1.0),
        vec3(0.0, 0.0, 1.0), vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0));

//An LOD pixel's view-space normal from its lodLight texel
vec3 lodFaceNormal(uint packed, vec3 east, vec3 up) {
    uint face = (packed >> 8u) & 0xFFu;
    return worldToView(FACE_NORMALS[face <= 6u ? face : 0u], east, up);
}

//Blocks are axis aligned: a normal close to an axis is that axis (stable across the face, no depth noise)
vec3 snapNormal(vec3 n, vec3 east, vec3 up) {
    vec3 w = vec3(dot(n, east), dot(n, up), dot(n, cross(east, up)));
    vec3 a = abs(w);
    float m = max(a.x, max(a.y, a.z));
    if (m < NORMAL_SNAP_MIN) {
        return n;
    }
    vec3 axis = a.x == m ? vec3(sign(w.x), 0.0, 0.0) : (a.y == m ? vec3(0.0, sign(w.y), 0.0) : vec3(0.0, 0.0, sign(w.z)));
    return worldToView(axis, east, up);
}

//A full-resolution pixel's view position from a depth (row 0 at the top), as viewpos.glsl's viewPosition(invProj, pixelUvGl(p), depth)
vec3 depthViewPosition(mat4 invProj, ivec2 p, ivec2 size, float depth) {
    vec2 uvGl = vec2((float(p.x) + 0.5)/float(size.x), 1.0 - (float(p.y) + 0.5)/float(size.y));
    vec4 view = invProj*vec4(SCREEN2NDC(vec3(uvGl, depth)), 1.0);
    return view.xyz/view.w;
}

//A neighbour's view position from depthTex, false for sky or outside the image
bool depthNeighbour(sampler2D depthTex, mat4 invProj, ivec2 p, ivec2 size, out vec3 view) {
    if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, size))) {
        return false;
    }
    float depth = texelFetch(depthTex, p, 0).r;
    if (depth == FAR) {
        return false;
    }
    view = depthViewPosition(invProj, p, size, depth);
    return true;
}

//The tangent along one screen axis: toward whichever neighbour lies nearer in distance (an edge does not bend the normal)
bool depthTangent(sampler2D depthTex, mat4 invProj, ivec2 p, ivec2 size, ivec2 axis, vec3 centre, out vec3 t) {
    vec3 a, b;
    bool hasA = depthNeighbour(depthTex, invProj, p + axis, size, a);
    bool hasB = depthNeighbour(depthTex, invProj, p - axis, size, b);
    float d = length(centre);
    if (hasA && (!hasB || abs(length(a) - d) <= abs(length(b) - d))) {
        t = a - centre;
        return true;
    }
    if (hasB) {
        t = centre - b;
        return true;
    }
    return false;
}

//depthNormal with the neighbours stride pixels apart (depthNormal is stride 1): lighting.comp tells a translucent's kind by it where
// the depth is coarse
vec3 depthNormalStride(sampler2D depthTex, mat4 invProj, ivec2 p, ivec2 size, vec3 centre, int stride) {
    vec3 tx, ty;
    vec3 toCamera = -normalize(centre);
    if (!depthTangent(depthTex, invProj, p, size, ivec2(stride, 0), centre, tx) || !depthTangent(depthTex, invProj, p, size, ivec2(0, stride), centre, ty)) {
        return toCamera;
    }
    vec3 n = cross(tx, ty);
    float len = length(n);
    if (len < 1e-12) {
        return toCamera;
    }
    n /= len;
    return dot(n, toCamera) < 0.0 ? -n : n;
}

//The view-space normal rebuilt from the main depth around pixel p (centre: p's view position), toward the camera; facing the camera
// when it cannot be rebuilt. Pass it through snapNormal as lighting.comp does.
vec3 depthNormal(sampler2D depthTex, mat4 invProj, ivec2 p, ivec2 size, vec3 centre) {
    return depthNormalStride(depthTex, invProj, p, size, centre, 1);
}
