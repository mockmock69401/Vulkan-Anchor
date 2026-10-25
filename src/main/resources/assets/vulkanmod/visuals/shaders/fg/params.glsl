//Frame generation (FG design 4, 5): the parameter block and the warp math every fg shader shares. WarpMath mirrors every constant and
// function here (same names, same values): change both together. The block is written by the pacer thread (FrameGenParams, 640
// bytes, std140). Pixel convention as TaaMath.reproject: top-left origin, u = x/width, v = 1 - y/height, centres at k + 0.5.

layout(binding = 8, std140) uniform FgParams {
    mat4 invRealProj;//the real frame's unjittered inverse projection (0..1 depth)
    mat4 invLodProj;//the real frame's inverse LOD projection
    mat4 genFromReal;//a real view position (w 1) or direction (w 0) to the generated frame's clip space
    mat4 realFromGen;//a generated view position or direction to the real frame's clip space
    mat4 invGenProj;//the generated frame's inverse projection
    vec4 size;//world w, h (pixels), ui w, h (swapchain pixels)
    vec4 flags;//x 1 when the LOD depth is valid, y 1 when the depth is valid (0 in menus), z the debug view (3 tints holes and edges), w 1 when the UI layer is valid
    vec4 vignette;//rgb the vignette's colour factor, a 1 when it is on
    vec4 crosshair;//x the number of crosshair quads (at most 8), y 1 when the world image is a warped frame (marks in alpha; debug view 3 tints only then)
    vec4 quads[16];//per crosshair quad two entries: the rect (x, y, w, h in swapchain pixels), then the uv rect (u, v, w, h)
};

const int SPLAT_FOOTPRINT = 2;
const int FILL_DIRECTIONS = 8;
const int FILL_RADIUS = 16;
const float DISOCCLUSION_DEPTH = 0.1;
const float SKY_DEPTH = 60000.0;
const float HOLE_MARK = 0.0;
const float EDGE_MARK = 0.5;
const float VALID_MARK = 1.0;
const uint EMPTY = 0xFFFFFFFFu;//an unwritten splat depth cell

//The unit direction through a pixel of a projection, as the difference of two points on the ray (view bobbing is a translation in
// the projection: see viewpos.glsl viewRay). uvGl has v up.
const float FG_RAY_NEAR = 0.5;
const float FG_RAY_FAR = 0.9;

vec3 fgUnproject(mat4 invProj, vec2 uvGl, float ndcZ) {
    vec4 h = invProj*vec4(uvGl*2.0 - 1.0, ndcZ, 1.0);
    return h.xyz/h.w;
}

vec3 fgViewRay(mat4 invProj, vec2 uvGl) {
    return normalize(fgUnproject(invProj, uvGl, FG_RAY_FAR) - fgUnproject(invProj, uvGl, FG_RAY_NEAR));
}

//WarpMath.viewPos: the point on pixel (u, v)'s ray at positive view depth viewDepth: A + (B - A)*s on the line through the near (NDC z 0)
// and far (z 1) points, so a translation inside the projection (view bobbing) stays exact
vec3 viewPos(mat4 invProj, float u, float v, float viewDepth) {
    vec3 a = fgUnproject(invProj, vec2(u, v), 0.0);
    vec3 b = fgUnproject(invProj, vec2(u, v), 1.0);
    return a + (b - a)*((-viewDepth - a.z)/(b.z - a.z));
}

//WarpMath.splatTarget: where the real pixel (x, y) (pixels, a centre is k + 0.5) with view depth viewDepth lands in the generated
// frame: target = {x, y, generated view depth (the clip w)}; false behind the camera
bool splatTarget(mat4 invReal, mat4 toGen, float x, float y, float viewDepth, vec2 wh, out vec3 target) {
    vec3 p = viewPos(invReal, x/wh.x, 1.0 - y/wh.y, viewDepth);
    vec4 clip = toGen*vec4(p, 1.0);
    target = vec3(0.0);
    if (clip.w <= 0.0) {
        return false;
    }
    vec2 uv = clip.xy/clip.w*0.5 + 0.5;
    target = vec3(uv.x*wh.x, (1.0 - uv.y)*wh.y, clip.w);
    return true;
}

//WarpMath.accept
bool accept(float sourceViewDepth, float expectedViewDepth) {
    return abs(sourceViewDepth - expectedViewDepth) <= DISOCCLUSION_DEPTH*expectedViewDepth;
}

//WarpMath.edgeClamp
ivec2 edgeClamp(vec2 pos, ivec2 wh) {
    return clamp(ivec2(floor(pos)), ivec2(0), wh - 1);
}

//WarpMath.depthBits: positive floats order as unsigned integers, so an integer atomic min keeps the nearest depth
uint depthBits(float viewDepth) {
    return floatBitsToUint(viewDepth);
}
