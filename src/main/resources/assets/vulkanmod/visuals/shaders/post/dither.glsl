//2026-11-26 (the user, of the snow under a contrast boost: "여전히 밴딩현상이 보이는데 해결할 순 없어?"; then "디더 조정 옵션을 추가하고
// 더 강하게"): the random rounding into the 8-bit screen, by the Output Dither setting (PostMath.DITHER_*: 0 off, 1 fine, 2 strong).
// postfinal.glsl dithers the final colour; with TAA on that image goes through TAA's 16-bit float history, which averages the dither
// away, so the passes after it that write 8 bits (SMAA's blend, the blit when SMAA is off) dither TAA's colour again, and only then
// (VkPostProcess: their mode is 0 when their input is already the dithered 8-bit image, so the strong dither is never added twice).
//   fine:   uniform noise just under one 8-bit step peak to peak (postfinal.glsl's DITHER): a value on the 8-bit grid stays, the steps
//           soften at their edges but their middles stay flat
//   strong: triangular noise, up to one step either way (the sum of two noises, the second the first's with x and y swapped: near
//           independent of it, where the same pattern shifted was not): the rounding error no longer depends on the colour,
//           so no contour is left in the flattest gradient, at the cost of a grain of about half a step (Wannamaker et al. 2000, "A
//           theory of nonsubtractive dither")
// PostMath mirrors the constants (OutputDitherTest). No uniforms or bindings.

const float OUTPUT_DITHER = 0.0039137257;
const float OUTPUT_DITHER_STRONG = 0.003921569;

//Interleaved gradient noise (Jimenez 2014), as postmath.glsl's interleavedGradientNoise
float outputDitherNoise(vec2 pixel) {
    return fract(52.9829189*fract(dot(pixel, vec2(0.06711056, 0.00583715))));
}

//A display colour d at pixel (its position; its integer part is used) randomly rounded toward the 8-bit grid by mode
vec3 ditherOutput(vec3 d, vec2 pixel, float mode) {
    vec2 at = floor(pixel);
    float n = outputDitherNoise(at);
    if (mode > 1.5) {
        return d + (n + outputDitherNoise(at.yx) - 1.0)*OUTPUT_DITHER_STRONG;
    }
    return mode > 0.5 ? d + (n - 0.5)*OUTPUT_DITHER : d;
}
