//W10: ambient occlusion, shared by its three passes (ao_depth.comp, ao.comp, ao_blur.comp) and the composite (scene_copy.comp).
// No uniforms or bindings: it imports depthutils.glsl for SCREEN2NDC. AmbientOcclusion (Java) mirrors every constant and function
// here and in the three passes: change both together.
//
// All three images are at half resolution on the lighting pass's grid (boundary 3.1): texel q stands for full pixel 2q.
//   aoDepth (r32f, ao_depth.comp)  the view distance in blocks of pixel 2q; 0 for sky; minus the distance for plant and light-source
//                                  pixels (FoliageMarker's marks), which are no occluders: their blades and flames are thinner
//                                  than a texel and would speckle; the same for water, ice, glass and slime (W5's kind), flat
//                                  sheets over what lies behind
//   aoRaw   (r32f, ao.comp)        the visibility 0..1 at full (default) strength, AO_TAKE_NEIGHBOURS for plant and light-source
//                                  texels (they take the blurred value of the ground around them), AO_NONE for sky, water, ice,
//                                  glass and slime (W5's translucent kind: water takes no AO, and its 1 would lighten the shore's)
//   aoOut   (rgba16f, ao_blur.comp) r the blurred visibility (1 = none), g the texel's view distance (0 sky): the composite's aoTex
//                                  (binding 13), read with aoAt
//
// The occlusion is a horizon search in AO_SLICES screen-space slices per texel (Jimenez, Wu, Pesce, Jarabo 2016, "Practical
// realtime strategies for accurate indirect occlusion": the slice formulation and its cosine-weighted arc integral; Bavoil, Sainz,
// Dimitrov 2008, "Image-space horizon-based ambient occlusion": horizons measured as elevation above the tangent plane, with a
// distance falloff). The horizon of each side is the largest elevation of a sample above the texel's tangent plane (W5's normal), so
// a flat plane has none at every angle and texel rounding, and samples beyond the radius (a foreground edge in front of the
// background) fade out: no dark halos. The visibility of each slice is divided by that slice's unoccluded value, so an open plane is
// exactly 1. The slice rotation and the step offsets follow a fixed AO_PATTERN x AO_PATTERN screen pattern which the blur's
// (2 AO_BLUR_RADIUS + 1)^2 window averages whole: no noise is left to crawl while the view moves, nothing is accumulated over time.

#import <vulkanmod:visuals/util/depthutils.glsl>

//World radius of the search in blocks near the camera, and its growth per block of view distance (far LOD terrain keeps occlusion
// at the scale of its coarser blocks)
const float AO_RADIUS = 4.0;
const float AO_RADIUS_GROWTH = 0.01;
//Slices per texel (each searched on both sides) and samples per side
const int AO_SLICES = 2;
const int AO_STEPS = 6;
//The search radius in half-resolution texels is capped (cache, cost near the camera); below AO_MIN_RADIUS_TEXELS (far away on small
// screens) there is no occlusion, full occlusion from AO_FULL_RADIUS_TEXELS
const float AO_MAX_RADIUS_TEXELS = 64.0;
const float AO_MIN_RADIUS_TEXELS = 1.0;
const float AO_FULL_RADIUS_TEXELS = 2.0;
//A sample's elevation counts fully up to this share of the radius and fades to nothing at the radius
const float AO_FALLOFF_START = 0.6;
//A sample must stand this share of the texel's distance above the tangent plane before it rises (depth precision, about 1e-3 relative)
const float AO_DEPTH_BIAS = 0.002;
//The visibility's exponent at full strength (the setting at 100%)
const float AO_POWER = 1.5;
//The screen pattern's side; the step offsets run through it with this stride (coprime with AO_PATTERN^2)
const int AO_PATTERN = 3;
const int AO_JITTER_STRIDE = 4;
//The blur: a (2 AO_BLUR_RADIUS + 1)^2 window, a neighbour's weight falling by e for each (share x distance + min) blocks of distance
// difference, times its normal agreement to this power
const int AO_BLUR_RADIUS = 1;
const float AO_BLUR_DEPTH_SHARE = 0.03;
const float AO_BLUR_DEPTH_MIN = 0.05;
const float AO_BLUR_NORMAL_POWER = 8.0;
//The composite's upsampling: depth tolerance as in the blur
const float AO_UPSAMPLE_DEPTH_SHARE = 0.03;
const float AO_UPSAMPLE_DEPTH_MIN = 0.1;
//aoRaw's marks (see above)
const float AO_TAKE_NEIGHBOURS = -1.0;
const float AO_NONE = -2.0;
//The two depths a texel's view ray is taken between (their difference: view bobbing moves the eye inside the projection, so the
// direction of one point near the eye would swing with every step)
const float AO_RAY_NEAR = 0.5;
const float AO_RAY_FAR = 0.9;
//aoDepth of geometry is at least this (0 is sky)
const float AO_MIN_DISTANCE = 0.001;
const float AO_PI = 3.14159265;
//Below this a light (or a lightmap difference) counts as none
const float AO_LIGHT_MIN = 0.00001;

//The unit view ray through half-resolution texel position t (texel q's centre is t = q: the full pixel 2q's centre), for a full
// image of fullSize (row 0 at the top; the projection takes GL screen coordinates)
vec3 aoRay(mat4 invProj, vec2 t, vec2 fullSize) {
    vec2 uv = vec2((2.0*t.x + 0.5)/fullSize.x, 1.0 - (2.0*t.y + 0.5)/fullSize.y);
    vec4 n = invProj*vec4(SCREEN2NDC(vec3(uv, AO_RAY_NEAR)), 1.0);
    vec4 f = invProj*vec4(SCREEN2NDC(vec3(uv, AO_RAY_FAR)), 1.0);
    return normalize(f.xyz/f.w - n.xyz/n.w);
}

//The search radius in blocks at a view distance
float aoWorldRadius(float dist) {
    return AO_RADIUS + AO_RADIUS_GROWTH*dist;
}

//A world radius in half-resolution texels at a view distance (texelsPerBlock: texels a block spans at distance 1)
float aoRadiusTexels(float radius, float dist, float texelsPerBlock) {
    return radius*texelsPerBlock/max(dist, AO_MIN_DISTANCE);
}

//The texel's place in the screen pattern, 0 .. AO_PATTERN^2 - 1
int aoPatternIndex(ivec2 q) {
    return (q.x % AO_PATTERN) + AO_PATTERN*(q.y % AO_PATTERN);
}

//The slices' rotation (a share of one slice's angle) and the steps' offset (a share of one step) for a pattern index
float aoRotation(int k) {
    return (float(k) + 0.5)/float(AO_PATTERN*AO_PATTERN);
}

float aoJitter(int k) {
    return (float((k*AO_JITTER_STRIDE) % (AO_PATTERN*AO_PATTERN)) + 0.5)/float(AO_PATTERN*AO_PATTERN);
}

//Step i's distance in texels along the slice for a radius of rTex texels (at least one texel: never the texel itself; denser near it)
float aoStepTexels(int i, float jitter, float rTex) {
    float s = (float(i) + jitter)/float(AO_STEPS);
    return 1.0 + s*s*max(rTex - 1.0, 0.0);
}

//A sample's weight by its distance len from the texel's point, for a world radius r
float aoFalloff(float len, float r) {
    return clamp((r - len)/max(r*(1.0 - AO_FALLOFF_START), AO_MIN_DISTANCE), 0.0, 1.0);
}

//The cosine-weighted arc from the view direction to horizon angle h, in a slice whose projected normal is at angle n (Jimenez 2016)
float aoArc(float h, float n) {
    return 0.25*(-cos(2.0*h - n) + cos(n) + 2.0*h*sin(n));
}

//The pass's visibility (1 = none) at the composite's strength (slot 10 .x, 0..1; 0 gives 1 exactly)
float aoStrengthApplied(float visibility, float strength) {
    return mix(1.0, clamp(visibility, 0.0, 1.0), clamp(strength, 0.0, 1.0));
}

//Reads aoOut at full-resolution pixel p (row 0 at the top) for a pixel at view distance dist: an even coordinate reads one texel, an
// odd one the two around it, weighted by how well their distances match; when none matches the closest in distance; 1 without any
// geometry texel
float aoAt(sampler2D aoTex, ivec2 p, float dist) {
    ivec2 size = textureSize(aoTex, 0);
    ivec2 q0 = p >> 1;
    ivec2 odd = p & 1;
    float sum = 0.0;
    float weights = 0.0;
    float closest = 1e30;
    float fallback = 1.0;
    for (int j = 0; j <= odd.y; j++) {
        for (int i = 0; i <= odd.x; i++) {
            vec4 t = texelFetch(aoTex, min(q0 + ivec2(i, j), size - 1), 0);
            if (!(t.g > 0.0)) {
                continue;
            }
            float miss = abs(t.g - dist);
            float w = exp(-miss/(AO_UPSAMPLE_DEPTH_SHARE*dist + AO_UPSAMPLE_DEPTH_MIN));
            sum += w*t.r;
            weights += w;
            if (miss < closest) {
                closest = miss;
                fallback = t.r;
            }
        }
    }
    return weights > 1e-6 ? sum/weights : fallback;
}

//The share of a pixel's light that is the lightmap's no-light floor (the ambient every surface keeps), per channel, from the
// lightmap's sky-only, block-only and no-light colours
vec3 aoFloorShare(vec3 skyOnly, vec3 blockOnly, vec3 dark) {
    vec3 total = dark + max(skyOnly - dark, vec3(0.0)) + max(blockOnly - dark, vec3(0.0));
    return mix(vec3(0.0), dark/max(total, vec3(AO_LIGHT_MIN)), greaterThan(total, vec3(AO_LIGHT_MIN)));
}

//The factor per channel that takes the occluded part out of a pixel's light: perLight is all of it (the composite's), occludable the
// part the occlusion darkens (the sky light's ambient part and the lightmap's floor), occlusion the visibility at strength
vec3 aoOccludedLight(vec3 perLight, vec3 occludable, float occlusion) {
    vec3 left = perLight - (1.0 - clamp(occlusion, 0.0, 1.0))*clamp(occludable, vec3(0.0), perLight);
    vec3 f = clamp(left/max(perLight, vec3(AO_LIGHT_MIN)), 0.0, 1.0);
    return mix(vec3(1.0), f, greaterThan(perLight, vec3(AO_LIGHT_MIN)));
}
