#version 450 core
//W2: the anti-aliased colour (display values) into the resumed main pass, sharpened by sharpen.x (PostMath.sharpenStrength;
// 0 = plain copy). An unsharp mask on the four-neighbour cross, clamped to the neighbourhood's range so it cannot overshoot into
// halos (the limiting idea of contrast-adaptive sharpening, Lottes 2019, AMD FidelityFX CAS, GPUOpen). Mirrored by PostMath.sharpen.
layout(binding = 0) uniform sampler2D aaTex;
layout(location = 0) uniform vec4 sharpen;//x the strength, y 1 when the weather overlay is blended, z the dither mode (dither.glsl; 0 when
// the input is already the dithered 8-bit image)
//2026-11-09 (session 6): the shader weather overlay (weather.comp), blended over the anti-aliased colour after the sharpening, so
// neither TAA's history nor SMAA touches the thin moving streaks
layout(binding = 1) uniform sampler2D weatherTex;
//2026-11-16 (shader roadmap session 26): water running down the lens (lenswater.glsl; LensWater), after TAA and SMAA so neither smears it,
// drawn while lensParams.y > 0
layout(location = 1) uniform vec4 lensParams;
#import <vulkanmod:visuals/post/lenswater.glsl>
#import <vulkanmod:visuals/post/dither.glsl>

out vec4 colour;

//Strength 1 adds the full difference from the four neighbours' mean
const float SHARPEN_GAIN = 0.25;

vec3 sharpened(ivec2 p, vec3 c) {
    ivec2 last = textureSize(aaTex, 0) - 1;
    vec3 n = texelFetch(aaTex, clamp(p + ivec2(0, -1), ivec2(0), last), 0).rgb;
    vec3 s = texelFetch(aaTex, clamp(p + ivec2(0, 1), ivec2(0), last), 0).rgb;
    vec3 e = texelFetch(aaTex, clamp(p + ivec2(1, 0), ivec2(0), last), 0).rgb;
    vec3 w = texelFetch(aaTex, clamp(p + ivec2(-1, 0), ivec2(0), last), 0).rgb;
    vec3 lo = min(c, min(min(n, s), min(e, w)));
    vec3 hi = max(c, max(max(n, s), max(e, w)));
    vec3 sharp = c + (4.0*c - n - s - e - w)*(sharpen.x*SHARPEN_GAIN);
    return clamp(sharp, lo, hi);
}

//Session 26: the colour at pixel position q (pixel centres at + 0.5), bilinear between its four texels (the image is bound nearest)
vec3 aaBilinear(vec2 q) {
    ivec2 last = textureSize(aaTex, 0) - 1;
    vec2 f = q - 0.5;
    ivec2 i = ivec2(floor(f));
    vec2 t = f - vec2(i);
    vec3 a = texelFetch(aaTex, clamp(i, ivec2(0), last), 0).rgb;
    vec3 b = texelFetch(aaTex, clamp(i + ivec2(1, 0), ivec2(0), last), 0).rgb;
    vec3 c = texelFetch(aaTex, clamp(i + ivec2(0, 1), ivec2(0), last), 0).rgb;
    vec3 d = texelFetch(aaTex, clamp(i + ivec2(1, 1), ivec2(0), last), 0).rgb;
    return mix(mix(a, b, t.x), mix(c, d, t.x), t.y);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c;
    float veil = 0.0;
    if (lensParams.y > 0.0) {
        //Session 26: the picture read a little away through the water on the lens (bilinear, not sharpened)
        vec2 size = vec2(textureSize(aaTex, 0));
        vec3 lens = lensWater(gl_FragCoord.xy, size, lensParams);
        vec2 at = clamp(gl_FragCoord.xy + lens.xy, vec2(0.5), size - 0.5);
        p = ivec2(at);
        c = lens.x != 0.0 || lens.y != 0.0 ? aaBilinear(at) : texelFetch(aaTex, p, 0).rgb;
        veil = lens.z;
    } else {
        c = texelFetch(aaTex, p, 0).rgb;
        if (sharpen.x > 0.0) {
            c = sharpened(p, c);
        }
    }
    if (sharpen.y > 0.5) {
        vec4 w = texelFetch(weatherTex, p, 0);
        c = c*(1.0 - w.a) + w.rgb;
    }
    //2026-11-26: TAA's float colour (SMAA off) randomly rounded into the 8-bit target (dither.glsl)
    colour = vec4(clamp(ditherOutput(veil > 0.0 ? lensVeil(c, veil) : c, gl_FragCoord.xy, sharpen.z), 0.0, 1.0), 1.0);
}
