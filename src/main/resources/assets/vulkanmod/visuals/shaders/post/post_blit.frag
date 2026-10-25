#version 450 core
//W2: the anti-aliased colour (display values) into the resumed main pass, sharpened by sharpen.x (PostMath.sharpenStrength;
// 0 = plain copy). An unsharp mask on the four-neighbour cross, clamped to the neighbourhood's range so it cannot overshoot into
// halos (the limiting idea of contrast-adaptive sharpening, Lottes 2019, AMD FidelityFX CAS, GPUOpen). Mirrored by PostMath.sharpen.
layout(binding = 0) uniform sampler2D aaTex;
layout(location = 0) uniform vec4 sharpen;

out vec4 colour;

//Strength 1 adds the full difference from the four neighbours' mean
const float SHARPEN_GAIN = 0.25;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c = texelFetch(aaTex, p, 0).rgb;
    if (sharpen.x <= 0.0) {
        colour = vec4(c, 1.0);
        return;
    }
    ivec2 last = textureSize(aaTex, 0) - 1;
    vec3 n = texelFetch(aaTex, clamp(p + ivec2(0, -1), ivec2(0), last), 0).rgb;
    vec3 s = texelFetch(aaTex, clamp(p + ivec2(0, 1), ivec2(0), last), 0).rgb;
    vec3 e = texelFetch(aaTex, clamp(p + ivec2(1, 0), ivec2(0), last), 0).rgb;
    vec3 w = texelFetch(aaTex, clamp(p + ivec2(-1, 0), ivec2(0), last), 0).rgb;
    vec3 lo = min(c, min(min(n, s), min(e, w)));
    vec3 hi = max(c, max(max(n, s), max(e, w)));
    vec3 sharp = c + (4.0*c - n - s - e - w)*(sharpen.x*SHARPEN_GAIN);
    colour = vec4(clamp(sharp, lo, hi), 1.0);
}
