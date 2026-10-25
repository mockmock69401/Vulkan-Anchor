#version 460
//2026-10-16: the edge cover, lit as vanilla lights terrain (texture x lightmap x face shade) so the post-process relights it alike.
// Seen from both sides (gate GEC round 2): from inside the built section the x-ray shows through its water and caves toward the
// hole, and its own boundary blocks are in front of the cover elsewhere. Final review I1: from inside, where the hole has LODs
// (the LOD depth is not its clear 1.0) they show instead (the composite drew them; the cover would replace them)

layout(binding = 0) uniform sampler2D atlas;
layout(binding = 1) uniform sampler2D lightmapTex;
layout(binding = 3) uniform sampler2D lodDepthTex;

layout(location = 0) in vec2 local;
layout(location = 1) in vec3 rel;
layout(location = 2) flat in vec4 sprite;
layout(location = 3) flat in vec2 light;
layout(location = 4) flat in float shade;
layout(location = 5) flat in int side;
layout(location = 6) in vec4 clip;
layout(location = 7) flat in float compareLod;//0: no LODs, the binding at 3 is not read

layout(location = 0) out vec4 colour;

//EdgeCover's sides: -X, +X, -Z, +Z, the face's outward normal
const vec3 SIDES[4] = vec3[4](vec3(-1.0, 0.0, 0.0), vec3(1.0, 0.0, 0.0), vec3(0.0, 0.0, -1.0), vec3(0.0, 0.0, 1.0));

void main() {
    bool inside = dot(SIDES[side], rel) >= 0.0;
    if (inside && compareLod > 0.5 && texture(lodDepthTex, clip.xy/clip.w*0.5 + 0.5).r < 1.0) {
        discard;
    }
    vec3 lit = texture(lightmapTex, (vec2(light.y, light.x) + 0.5)/16.0).rgb;
    colour = vec4(textureLod(atlas, mix(sprite.xy, sprite.zw, fract(local)), 0.0).rgb*lit*shade, 1.0);
}
