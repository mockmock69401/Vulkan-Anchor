//2026-11-24 (the user's seven Horror references: "5번째 사진은 내부(동굴) 색감용이야. 차갑고 어두운 초록 계열 색을 사용해줘", "6번째 사진처럼
// 광원 가까이서만 빛이 과장되게 밝게 보이도록 만들어줘", "동적 조명 포함 모든 광원의 범위도 조정해줘"): two palette lights for the composite
// (LightingUniforms slot 83, scene_copy.comp caveLight). No uniforms or bindings of its own.
//
// LIGHT REACH (caveLight.w; 1 is vanilla's, VisualsConfig.lightReach): the block light level L (0 to 15; torches, lamps, lava, the held
// light) is remapped to 15 (L/15)^(1/reach) before the lightmap, so with reach under 1 it falls off over fewer blocks while the source's
// own level stays 15; and the light within the top levels (from CORE_FROM to 15) grows by CORE per unit of (1/reach - 1), so it burns
// brighter right at the source and drops fast into the dark around it.
//
// CAVE (caveLight.rgb, the cave light colour's tint; 1, the default, changes nothing): where little sky light reaches (sky level under
// CAVE_SKY) the light takes the tint, fully at sky level 0. The light sources' own core (block level from CAVE_CORE_FROM up) keeps
// CAVE_CORE_KEEP of its colour.

const float LIGHT_REACH_CORE = 0.9;
const float LIGHT_REACH_CORE_FROM = 11.0;
const float CAVE_SKY = 9.0;
const float CAVE_CORE_FROM = 12.0;
const float CAVE_CORE_KEEP = 0.6;

//Block light level after the palette's reach
float lightReachLevel(float level, float reach) {
    if (reach > 0.999 && reach < 1.001) {
        return level;
    }
    return 15.0*pow(clamp(level/15.0, 0.0, 1.0), 1.0/max(reach, 0.05));
}

//The block-lit light's gain at that (remapped) level: brighter at the source with a short reach (1 at reach 1 and above)
float lightReachCore(float level, float reach) {
    return 1.0 + LIGHT_REACH_CORE*max(1.0/max(reach, 0.05) - 1.0, 0.0)*smoothstep(LIGHT_REACH_CORE_FROM, 15.0, level);
}

//The cave light's tint at a surface with sky level skyLevel and block level blockLevel (0 to 15)
vec3 caveTint(vec3 tint, float skyLevel, float blockLevel) {
    float cave = 1.0 - smoothstep(0.0, CAVE_SKY, skyLevel);
    cave *= 1.0 - CAVE_CORE_KEEP*smoothstep(CAVE_CORE_FROM, 15.0, blockLevel);
    return mix(vec3(1.0), tint, cave);
}
