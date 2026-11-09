//The visuals' scene light (2026-10-23, TerrainLightMath in the visuals mirrors this; change both together). Each fragment of the
// terrain, entities, block models and particles keeps its window depth and its interpolated sky and block levels in one 32-bit
// value, depth on top, so an atomic min keeps the nearest fragment of a pixel whatever order things are drawn in; and, when it was
// the nearest so far, its colour before the lightmap (the albedo), tagged with its depth's low bits. The visuals clear the light to
// all ones (none) and the albedo to 0 (not written).
const float TERRAIN_LIGHT_DEPTH_STEPS = 1048576.0;
const uint TERRAIN_LIGHT_DEPTH_MAX = 1048575u;
const float TERRAIN_LIGHT_LEVEL_STEPS = 4.0;
const uint TERRAIN_LIGHT_LEVEL_MAX = 60u;
//The albedo: rgb in bits 0..23, the light value's depth step's low 6 bits at 24..29, bit 30 set by a tinted fragment, bit 31 set
// when written
const uint TERRAIN_ALBEDO_WRITTEN = 2147483648u;
const uint TERRAIN_ALBEDO_TINTED = 1073741824u;
const uint TERRAIN_ALBEDO_TAG_MASK = 63u;
//Only opaque texels (blended ones are mixed with what lies behind) and only unfogged ones (vanilla's fog is in their pixel)
const float TERRAIN_ALBEDO_MIN_ALPHA = 0.999;
//A vertex colour whose channels differ by more than this share of the brightest carries a biome tint (shade and AO are grey)
const float TERRAIN_ALBEDO_TINT_MIN = 0.01;
//2026-11-09 (the user: seen from high above, frozen rivers and seas changed look across a line): a water fragment (terrain.fsh's
// WATER_FLAG) leaves its window depth in the albedo image as a water mark, which the lighting pass compares with the depth right after
// the translucent terrain (lighting.comp's waterMarked): far away the depth cannot tell ice's top on a whole y from water's at 8/9,
// but it can tell whether the translucent surface there is the water's own fragment. The mark is the depth's float bits: a depth in
// (0, 1] has bits 30 and 31 clear (below TERRAIN_WATER_MARK_LIMIT), so a mark is never an albedo (which sets bit 31, and whose
// readers skip anything without it). It replaces "nothing written" (0), an albedo, or a farther water's mark (positive floats order
// as their bits); an albedo stored later (a particle's) replaces it. TERRAIN_WATER_MARK_MIN keeps a depth of 0 from reading as 0
const float TERRAIN_WATER_MARK_MIN = 1.0e-20;
const uint TERRAIN_WATER_MARK_LIMIT = 1073741824u;
//Within this view depth (blocks) a depth step is under ~0.01 block (step ~ depth^2/(0.05 near x 2^20)), so an equal light value means
// one plane; past it a hidden surface just behind shares the step. The held light reaches less far
const float TERRAIN_ALBEDO_LAYER_DISTANCE = 24.0;

uint terrainLightLevel(float level) {
    return min(uint(round(clamp(level, 0.0, 15.0) * TERRAIN_LIGHT_LEVEL_STEPS)), TERRAIN_LIGHT_LEVEL_MAX);
}

//depth: gl_FragCoord.z; light: (sky, block) levels 0..15
uint packTerrainLight(float depth, vec2 light) {
    uint d = min(uint(clamp(depth, 0.0, 1.0) * TERRAIN_LIGHT_DEPTH_STEPS), TERRAIN_LIGHT_DEPTH_MAX);
    return (d << 12) | (terrainLightLevel(light.x) << 6) | terrainLightLevel(light.y);
}

//albedo: display values 0..1; light: the fragment's packTerrainLight value
uint packTerrainAlbedo(vec3 albedo, uint light, bool tinted) {
    uvec3 c = uvec3(round(clamp(albedo, 0.0, 1.0) * 255.0));
    return TERRAIN_ALBEDO_WRITTEN | (tinted ? TERRAIN_ALBEDO_TINTED : 0u) | (((light >> 12) & TERRAIN_ALBEDO_TAG_MASK) << 24)
            | (c.b << 16) | (c.g << 8) | c.r;
}

//depth: gl_FragCoord.z
uint packWaterMark(float depth) {
    return floatBitsToUint(clamp(depth, TERRAIN_WATER_MARK_MIN, 1.0));
}

//tint: the vertex colour without the lightmap (terrain.vsh's albedoTint)
bool terrainTinted(vec3 tint) {
    return max(tint.r, max(tint.g, tint.b)) - min(tint.r, min(tint.g, tint.b)) > TERRAIN_ALBEDO_TINT_MIN*max(tint.r, max(tint.g, tint.b));
}

#ifdef SCENE_LIGHT_BINDING
//The includer defines the first of the two bindings (its pipeline JSON's storageImages, after its samplers)
layout(binding = SCENE_LIGHT_BINDING, r32ui) uniform restrict uimage2D TerrainLight;
layout(binding = SCENE_LIGHT_BINDING + 1, r32ui) uniform restrict uimage2D TerrainAlbedo;

//light: (sky, block) levels; colour: the fragment's colour before the lightmap and the fog; unfogged: no vanilla fog on it; tinted:
// a biome-tinted layer (terrainTinted)
//Writes packed (the albedo of the fragment with light value v) while v is still the pixel's light, a few tries (each failed swap means
// another write: a nearer fragment's must stay). With backOff, not over a tinted albedo with its tag that landed after this fragment
// read stale (the albedo before its atomic min; 0 for none): one already there then is a farther surface's whose tag only looks alike
void writeAlbedoNear(ivec2 p, uint v, uint packed, uint stale, bool backOff) {
    uint tag = packed & (TERRAIN_ALBEDO_TAG_MASK << 24);
    uint seen = imageAtomicOr(TerrainAlbedo, p, 0u);
    for (int i = 0; i < 4; i++) {
        if (imageAtomicOr(TerrainLight, p, 0u) != v) {
            return;
        }
        if (backOff && (seen & TERRAIN_ALBEDO_TINTED) != 0u && (seen & (TERRAIN_ALBEDO_TAG_MASK << 24)) == tag && seen != stale) {
            return;
        }
        uint was = imageAtomicCompSwap(TerrainAlbedo, p, seen, packed);
        if (was == seen) {
            return;
        }
        seen = was;
    }
}

//2026-11-02 (BMC5: grass sides speckled under the held light at night): the grass side's tinted overlay lies on its dirt layer at the
// same light value and vanilla's LEQUAL depth test shows the overlay, drawn last; which of the two fragments' writes lands last is
// the GPU's choice. So, near enough for an equal value to mean one plane, a tinted fragment writes at an equal value too, and nothing
// replaces a tinted albedo with its own tag written since: the overlay's albedo is kept whichever lands first (it writes after its
// atomic min, so after the dirt's read before its own). Two tinted fragments at one value keep the first. Farther, the nearer
// fragment's plain store as before. 2026-11-09: returns whether this fragment is the pixel's nearest so far (its light value at most
// the one before): only then may a water fragment leave its mark (the terrain's fragment shader runs before the depth test, and
// water hidden behind the shore would otherwise mark the shore)
bool writeSceneLight(vec2 light, vec4 colour, bool unfogged, bool tinted) {
    ivec2 p = ivec2(gl_FragCoord.xy);
    //2026-10-30 (audit): never past the image (a target larger than the main one)
    if (any(greaterThanEqual(p, imageSize(TerrainLight)))) {
        return false;
    }
    bool writes = unfogged && colour.a >= TERRAIN_ALBEDO_MIN_ALPHA;
    bool layers = 1.0/gl_FragCoord.w <= TERRAIN_ALBEDO_LAYER_DISTANCE;
    tinted = tinted && layers;
    uint stale = writes && layers && !tinted ? imageAtomicOr(TerrainAlbedo, p, 0u) : 0u;
    uint v = packTerrainLight(gl_FragCoord.z, light);
    uint before = imageAtomicMin(TerrainLight, p, v);
    if (!writes) {
        return v <= before;
    }
    uint packed = packTerrainAlbedo(colour.rgb, v, tinted);
    if (!layers) {
        if (v < before) {
            imageStore(TerrainAlbedo, p, uvec4(packed));
        }
    } else if (v < before || (v == before && tinted)) {
        writeAlbedoNear(p, v, packed, stale, !(v < before && tinted));
    }
    return v <= before;
}

void writeSceneLight(vec2 light, vec4 colour, bool unfogged) {
    writeSceneLight(light, colour, unfogged, false);
}

//2026-11-09: a water fragment's mark (see TERRAIN_WATER_MARK_MIN), when writeSceneLight said it is the pixel's nearest so far. A few
// tries, as writeAlbedoNear: each failed swap means another write landed (a nearer water's mark must stay)
void writeWaterMark() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    if (any(greaterThanEqual(p, imageSize(TerrainAlbedo)))) {
        return;
    }
    uint mark = packWaterMark(gl_FragCoord.z);
    uint seen = imageAtomicOr(TerrainAlbedo, p, 0u);
    for (int i = 0; i < 4; i++) {
        if (seen != 0u && seen < TERRAIN_WATER_MARK_LIMIT && seen <= mark) {
            return;
        }
        uint was = imageAtomicCompSwap(TerrainAlbedo, p, seen, mark);
        if (was == seen) {
            return;
        }
        seen = was;
    }
}
#endif
