#version 460

#include "light.glsl"
#include "fog.glsl"

layout (binding = 0) uniform UniformBufferObject {
    mat4 MVP;
    vec4 WindPhase;
    vec4 WindParams;
};

layout (push_constant) uniform pushConstant {
    vec3 ChunkOffset;
};

layout (binding = 3) uniform sampler2D Sampler2;


layout (location = 0) out float vertexDistance;
layout (location = 1) out vec4 vertexColor;
layout (location = 2) out vec2 texCoord0;
//The visuals' terrain light: the vertex's sky and block levels, interpolated across the face (terrain_light.glsl)
layout (location = 3) out vec2 lightLevels;
//The colour before the lightmap (tint, face shade, AO; the alpha holds the wind marks, so only rgb), for the scene albedo
layout (location = 4) out vec3 albedoTint;


layout (location = 0) in ivec4 Position;
layout (location = 1) in vec4 Color;
layout (location = 2) in uvec2 UV0;

const float UV_INV = 1.0 / 32768.0;

const vec3 POSITION_INV = vec3(1.0 / 2048.0);
const vec3 POSITION_OFFSET = vec3(4.0);

//The visuals' wind (2026-10-21, FoliageWind and FoliageMarker in the visuals): the vertex alpha's wind marks. A plant vertex is
// 254 at its base (still), 250 one plant block up, 249 two (a double plant's top); leaves 248. The marks leave this stage as the
// plant mark (254.25 / 255) and 1, which the post-processing reads
const int WIND_PLANT_BASE = 254;
const int WIND_PLANT_ONE = 250;
const int WIND_PLANT_TWO = 249;
const int WIND_LEAF = 248;
const float WIND_PLANT_ALPHA = 254.25 / 255.0;
//Every wave fits the period (FoliageWind.PERIOD, WindParams.xy wraps the camera there) a whole number of times along x and z
const float WIND_K = 6.2831853 / 240.0;
const vec2 WIND_MAIN = vec2(20.0, 8.0);
const vec2 WIND_CROSS = vec2(-9.0, 21.0);
const vec2 WIND_GUST = vec2(5.0, 2.0);
const vec2 WIND_FLUTTER = vec2(53.0, -37.0);
//The wind blows along the main wave (the ripples' main flow, RippleMath.FLOW[0])
const vec2 WIND_DIR = vec2(0.928477, 0.371391);
//2026-10-22 (CTE2: far canopies rippled): the sway fades out between these distances (blocks, FoliageWind's): farther a leaf's move
// is under a pixel and only shimmers its texture, and the waves' travel read as ripples rolling over the forest
const vec2 WIND_LEAF_FADE = vec2(16.0, 40.0);
const vec2 WIND_PLANT_FADE = vec2(32.0, 64.0);

//The share of the sway at a camera-relative position: 1 near, 0 past fade.y
float windFade(vec3 rel, vec2 fade) {
    return 1.0 - smoothstep(fade.x, fade.y, length(rel));
}

//The wind's push at a camera-relative position: x along the wind (the lean, 0..1 with gusts), y across it, z up and down (-1..1).
// flutter: the share of the quick short wave (1 for plants; 0 for leaves since 2026-10-24, CTE2: "the leaves tremble": over a
// wavelength of under four blocks a leaf cube's corners moved apart several times a second and twisted its texture)
vec3 windAt(vec3 rel, float flutterShare) {
    vec2 w = rel.xz + WindParams.xy;
    float swell = sin(WIND_K * dot(w, WIND_MAIN) - WindPhase.x);
    float cross = sin(WIND_K * dot(w, WIND_CROSS) - WindPhase.y);
    float gust = 0.6 + 0.4 * sin(WIND_K * dot(w, WIND_GUST) - WindPhase.z);
    float flutter = sin(WIND_K * dot(w, WIND_FLUTTER) - WindPhase.w);
    //Without the flutter the bob follows the cross wave: slow, as the swell
    float bob = mix(cross, flutter, flutterShare);
    return vec3(gust * (0.55 + 0.3 * swell + 0.15 * cross), 0.3 * cross + 0.2 * flutter * flutterShare, 0.5 * swell * bob);
}

//A vertex's displacement for its wind mark (none for anything else)
vec3 windOffset(int mark, vec3 rel) {
    float plant = mark == WIND_PLANT_ONE ? 1.0 : (mark == WIND_PLANT_TWO ? 2.0 : 0.0);
    if (plant > 0.0 && WindParams.z > 0.0) {
        float near = windFade(rel, WIND_PLANT_FADE);
        if (near <= 0.0) {
            return vec3(0.0);
        }
        vec3 f = windAt(rel, 1.0);
        vec2 xz = (WIND_DIR * f.x + vec2(-WIND_DIR.y, WIND_DIR.x) * f.y) * (WindParams.z * plant * near);
        //A bent plant is a little lower at the top
        return vec3(xz.x, -0.25 * dot(xz, xz) / plant, xz.y);
    }
    if (mark == WIND_LEAF && WindParams.w > 0.0) {
        float near = windFade(rel, WIND_LEAF_FADE);
        if (near <= 0.0) {
            return vec3(0.0);
        }
        vec3 f = windAt(rel, 0.0);
        vec2 xz = WIND_DIR * (f.x - 0.55) + vec2(-WIND_DIR.y, WIND_DIR.x) * f.y;
        return vec3(xz.x, f.z, xz.y) * (WindParams.w * near);
    }
    return vec3(0.0);
}

void main() {
    const vec3 baseOffset = bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8);
    vec4 pos = vec4(fma(Position.xyz, POSITION_INV, ChunkOffset + baseOffset), 1.0);
    const int mark = int(Color.a * 255.0 + 0.5);
    pos.xyz += windOffset(mark, pos.xyz);
    gl_Position = MVP * pos;

    vertexDistance = fog_distance(pos.xyz, 0);
    vertexColor = Color * sample_lightmap2(Sampler2, Position.a);
    if (mark >= WIND_LEAF && mark <= WIND_PLANT_ONE) {
        vertexColor.a = mark == WIND_LEAF ? 1.0 : WIND_PLANT_ALPHA;
    }
    texCoord0 = UV0 * UV_INV;
    //As sample_lightmap2 reads the packed light: block in bits 4..7, sky in bits 12..15
    const uint packedLight = uint(Position.a);
    lightLevels = vec2(bitfieldExtract(packedLight, 12, 4), bitfieldExtract(packedLight, 4, 4));
    albedoTint = Color.rgb;
}

















