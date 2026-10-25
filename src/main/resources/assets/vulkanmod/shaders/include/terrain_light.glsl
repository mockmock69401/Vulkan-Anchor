//The visuals' scene light (2026-10-23, TerrainLightMath in the visuals mirrors this; change both together). Each fragment of the
// terrain, entities, block models and particles keeps its window depth and its interpolated sky and block levels in one 32-bit
// value, depth on top, so an atomic min keeps the nearest fragment of a pixel whatever order things are drawn in; and, when it was
// the nearest so far, its colour before the lightmap (the albedo), tagged with its depth's low bits. The visuals clear the light to
// all ones (none) and the albedo to 0 (not written).
const float TERRAIN_LIGHT_DEPTH_STEPS = 1048576.0;
const uint TERRAIN_LIGHT_DEPTH_MAX = 1048575u;
const float TERRAIN_LIGHT_LEVEL_STEPS = 4.0;
const uint TERRAIN_LIGHT_LEVEL_MAX = 60u;
//The albedo: rgb in bits 0..23, the light value's depth step's low 7 bits at 24..30, bit 31 set when written
const uint TERRAIN_ALBEDO_WRITTEN = 2147483648u;
const uint TERRAIN_ALBEDO_TAG_MASK = 127u;
//Only opaque texels (blended ones are mixed with what lies behind) and only unfogged ones (vanilla's fog is in their pixel)
const float TERRAIN_ALBEDO_MIN_ALPHA = 0.999;

uint terrainLightLevel(float level) {
    return min(uint(round(clamp(level, 0.0, 15.0) * TERRAIN_LIGHT_LEVEL_STEPS)), TERRAIN_LIGHT_LEVEL_MAX);
}

//depth: gl_FragCoord.z; light: (sky, block) levels 0..15
uint packTerrainLight(float depth, vec2 light) {
    uint d = min(uint(clamp(depth, 0.0, 1.0) * TERRAIN_LIGHT_DEPTH_STEPS), TERRAIN_LIGHT_DEPTH_MAX);
    return (d << 12) | (terrainLightLevel(light.x) << 6) | terrainLightLevel(light.y);
}

//albedo: display values 0..1; light: the fragment's packTerrainLight value
uint packTerrainAlbedo(vec3 albedo, uint light) {
    uvec3 c = uvec3(round(clamp(albedo, 0.0, 1.0) * 255.0));
    return TERRAIN_ALBEDO_WRITTEN | (((light >> 12) & TERRAIN_ALBEDO_TAG_MASK) << 24) | (c.b << 16) | (c.g << 8) | c.r;
}

#ifdef SCENE_LIGHT_BINDING
//The includer defines the first of the two bindings (its pipeline JSON's storageImages, after its samplers)
layout(binding = SCENE_LIGHT_BINDING, r32ui) uniform restrict uimage2D TerrainLight;
layout(binding = SCENE_LIGHT_BINDING + 1, r32ui) uniform writeonly restrict uimage2D TerrainAlbedo;

//light: (sky, block) levels; colour: the fragment's colour before the lightmap and the fog; unfogged: no vanilla fog on it
void writeSceneLight(vec2 light, vec4 colour, bool unfogged) {
    ivec2 p = ivec2(gl_FragCoord.xy);
    uint v = packTerrainLight(gl_FragCoord.z, light);
    uint before = imageAtomicMin(TerrainLight, p, v);
    if (v < before && unfogged && colour.a >= TERRAIN_ALBEDO_MIN_ALPHA) {
        imageStore(TerrainAlbedo, p, uvec4(packTerrainAlbedo(colour.rgb, v)));
    }
}
#endif
