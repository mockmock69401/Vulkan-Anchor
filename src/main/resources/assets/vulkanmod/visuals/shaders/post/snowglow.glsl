//2026-11-18 (the user, with Complementary-family screenshots as pictures only: "the snow lit by the moon glows softly; can the Vulkan side
// do something like it without the scene getting darker?"): the composite's moonlit snow. MoonSnowMath.java mirrors it (MoonSnowTest
// compares the constants).
// THE LOOK: snow (and other near-white ground: SNOW_WHITE_LOW..SNOW_WHITE_HIGH on its darkest channel) under the moon takes a factor of at
// least 1 on its lit colour, so nothing gets darker: a broad, soft sheen where it faces the moon (the moon's direct share, so the moon's
// shadows stay on it), strongest where the view reflects toward the moon (SNOW_SHEEN_POWER: a wide lobe, no sharp highlight, so the snow toward the moon is brightest), and a little
// in the shade (SNOW_GLOW_AMBIENT). Cool lavender (SNOW_GLOW_TINT). It scales with the moon's weight (MoonShadowMath.weight: the moonlight
// setting, the phase, the moon's height and the rain), so it fades in and out with the moon's shadows at dusk and dawn and is gone by day
// and at new moon.

const float SNOW_WHITE_LOW = 0.62;
const float SNOW_WHITE_HIGH = 0.8;
//Without the texel's own albedo: vanilla's colour over its lightmap, the lightmap at least this (the night's sky light is dim)
const float SNOW_LIGHTMAP_MIN = 0.02;
const float SNOW_GLOW_DIRECT = 0.4;
const float SNOW_GLOW_AMBIENT = 0.12;
const float SNOW_GLOW_SHEEN = 1.6;
const float SNOW_SHEEN_POWER = 3.0;
const vec3 SNOW_GLOW_TINT = vec3(0.86, 0.84, 1.0);

//How much of a texel (display albedo) is snow-white: its darkest channel between SNOW_WHITE_LOW and SNOW_WHITE_HIGH
float snowWhiteness(vec3 albedo) {
    return smoothstep(SNOW_WHITE_LOW, SNOW_WHITE_HIGH, min(albedo.r, min(albedo.g, albedo.b)));
}

//The factor (each channel at least 1) on the lit colour of a surface with display albedo, normal n (world, unit), seen along dir (camera
// to surface, unit), under the moon toward moonDir (unit) with its direct share 0..1 (the composite's directShare: facing, shadow, sky
// gate) and weight (lighting.y at night: MoonShadowMath.weight x the Sun Shading strength x the exposure)
vec3 moonSnowGlow(vec3 albedo, vec3 n, vec3 dir, vec3 moonDir, float directShare, float weight) {
    float white = snowWhiteness(albedo);
    float w = clamp(weight, 0.0, 1.0)*white;
    if (w <= 0.0) {
        return vec3(1.0);
    }
    float lobe = pow(clamp(dot(reflect(dir, n), moonDir), 0.0, 1.0), SNOW_SHEEN_POWER);
    float d = clamp(directShare, 0.0, 1.0);
    return vec3(1.0) + SNOW_GLOW_TINT*(w*(SNOW_GLOW_AMBIENT + SNOW_GLOW_DIRECT*d*(1.0 + SNOW_GLOW_SHEEN*lobe)));
}
