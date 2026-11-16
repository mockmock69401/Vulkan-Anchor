//2026-11-16 (the user: "사막 지형에서는 비가 내리지 않기 때문에 비온 후 마르는 이팩트도 생기면 안돼"): whether the rain falls on a pixel's
// ground, for the wet ground (wetground.glsl) and PBR's wetness (pbr.glsl). WetClimateMap.java mirrors the constants and the lookup
// (WetClimateTest compares them). The includer declares, before importing this:
//   usampler2D wetClimateMap   WetClimateMap's texels: WET_CLIMATE_SIZE square, cell & WET_CLIMATE_MASK, WET_CLIMATE_CELL blocks a cell
//   vec4 wetClimate            xy the camera's x, z wrapped at WET_CLIMATE_WRAP, z 1 with the map (0: no map, rain everywhere as before),
//                              w 1 where it rains at the camera (for pixels past WET_CLIMATE_REACH)
// A texel: bit 0 known (its chunk was loaded), bit 1 vanilla's rule rains on the cell's ground (Biome.getPrecipitationAt RAIN: not in
// deserts, badlands or savannas, not where it snows), bits 2..5 the wrap tag ((cell x >> 7) & 3 | ((cell z >> 7) & 3) << 2).

const int WET_CLIMATE_SIZE = 128;
const int WET_CLIMATE_MASK = 127;
const int WET_CLIMATE_SHIFT = 7;
const int WET_CLIMATE_CELL = 4;
const float WET_CLIMATE_WRAP = 2048.0;
const float WET_CLIMATE_REACH = 252.0;

//1 where the rain falls on the ground at rel (blocks from the camera, world axes), 0 where it does not (or the cell is not known yet)
float wetClimateAt(vec3 rel) {
    if (!(wetClimate.z > 0.0)) {
        return 1.0;
    }
    if (abs(rel.x) > WET_CLIMATE_REACH || abs(rel.z) > WET_CLIMATE_REACH) {
        return wetClimate.w;
    }
    ivec2 c = ivec2(floor((wetClimate.xy + rel.xz)/float(WET_CLIMATE_CELL)));
    uint w = texelFetch(wetClimateMap, c & WET_CLIMATE_MASK, 0).r;
    int tag = ((c.x >> WET_CLIMATE_SHIFT) & 3) | (((c.y >> WET_CLIMATE_SHIFT) & 3) << 2);
    if ((w & 1u) == 0u || int((w >> 2u) & 15u) != tag) {
        return 0.0;
    }
    return (w & 2u) != 0u ? 1.0 : 0.0;
}
