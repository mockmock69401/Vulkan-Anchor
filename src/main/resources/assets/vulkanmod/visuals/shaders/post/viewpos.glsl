//Visuals: a full-resolution pixel's view-space position (designs 2026-10-10 P7c 3.1, 2026-10-11 P7d 3.1), shared by
// scene_copy.comp and lighting.comp. The including shader declares mainDepth, lodDepth, invVanillaProj, invLodProj and
// depthParams, and imports depthutils.glsl first. Row 0 of the main depth is the top (Reforged); the LOD depth is in GL's
// layout (row 0 at the bottom); the projections take GL screen coordinates.

vec3 viewPosition(mat4 invProj, vec2 uvGl, float depth) {
    vec4 view = invProj*vec4(SCREEN2NDC(vec3(uvGl, depth)), 1.0);
    return view.xyz/view.w;
}

//The unit view ray through a pixel, as the difference of two points on it. Vanilla's view bobbing is a translation inside the
// projection (the eye moves by up to a tenth of a block each step), so the direction of one point near the eye swings with every step
// (2026-10-21: the sky's brightness pulsed while walking); the difference of two points cancels the translation
const float VIEW_RAY_NEAR = 0.5;
const float VIEW_RAY_FAR = 0.9;

vec3 viewRay(mat4 invProj, vec2 uvGl) {
    return normalize(viewPosition(invProj, uvGl, VIEW_RAY_FAR) - viewPosition(invProj, uvGl, VIEW_RAY_NEAR));
}

//A full-resolution pixel's centre in GL screen coordinates
vec2 pixelUvGl(ivec2 p, ivec2 size) {
    return vec2((float(p.x) + 0.5)/float(size.x), 1.0 - (float(p.y) + 0.5)/float(size.y));
}

//The LOD depth's texel for a full-resolution pixel
ivec2 lodTexel(ivec2 p, ivec2 size) {
    return ivec2(p.x, size.y - 1 - p.y);
}

//The composite clamps LODs past vanilla's far plane to exactly FAR - 2/(2^24-1): their position is the LOD depth's. The
// value, not a share of the far plane, marks them: vanilla clouds reach into the far plane's last tenth
bool clampedToLod(float depth) {
    return depth >= depthParams.x && depthParams.y > 0.5;
}

//2026-10-17 (PostMath.lodShowsThrough): an LOD short of vanilla's far plane is in the main depth as itself, read back at float
// precision with vanilla's near plane; the main depth agreeing with the LOD surface within a few steps shows that surface.
// 2026-10-20: 16 steps, not 4: while the field of view changes (sprint flying) the two depths of far LOD water were seen more than
// 4 steps apart, and the rows past 4 lost the water test (dark lines across the reflections)
const float LOD_MATCH_DEPTH_STEPS = 16.0;
const float LOD_MATCH_MIN = 0.25;

bool lodShowsThrough(float vanillaDist, float step, float lodDist) {
    return abs(lodDist - vanillaDist) <= max(LOD_MATCH_MIN, step);
}

//false for sky (nothing wrote the main depth); fromLod (2026-10-19): the LOD depth placed the pixel, so it is an LOD surface
bool scenePositionFrom(ivec2 p, ivec2 size, out vec3 view, out bool fromLod) {
    fromLod = false;
    float depth = texelFetch(mainDepth, p, 0).r;
    if (depth == FAR) {
        return false;
    }
    vec2 uvGl = pixelUvGl(p, size);
    view = viewPosition(invVanillaProj, uvGl, depth);
    if (clampedToLod(depth)) {
        float lod = texelFetch(lodDepth, lodTexel(p, size), 0).r;
        if (lod != FAR) {
            view = viewPosition(invLodProj, uvGl, lod);
            fromLod = true;
        }
    } else if (depthParams.y > 0.5) {
        float lod = texelFetch(lodDepth, lodTexel(p, size), 0).r;
        if (lod != FAR) {
            vec3 l = viewPosition(invLodProj, uvGl, lod);
            float d = length(view);
            float step = abs(length(viewPosition(invVanillaProj, uvGl, min(depth + LOD_MATCH_DEPTH_STEPS*5.9604645e-8, 1.0))) - d);
            if (lodShowsThrough(d, step, length(l))) {
                view = l;
                fromLod = true;
            }
        }
    }
    return true;
}

bool scenePosition(ivec2 p, ivec2 size, out vec3 view) {
    bool fromLod;
    return scenePositionFrom(p, size, view, fromLod);
}
