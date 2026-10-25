//W2: the final colour (post_final.frag draws it, post_ldr.comp stores it for TAA/SMAA). Mirrored by PostMath.finalColour;
// the uniforms are PostUniforms.Values.write's push constants (layout in PostUniforms). Scene units: see postmath.glsl.
#import <vulkanmod:visuals/post/postmath.glsl>

layout(binding = 0) uniform sampler2D sceneTex;//linear HDR scene, scene units
layout(binding = 1) uniform sampler2D bloomTex;//bloom level 0 (sum of the levels, scene units)
layout(binding = 2) uniform sampler2D shaftsTex;//light-shaft amount in .r
layout(location = 0) uniform vec4 exposureParams;//exposure, tone knee, bloom mix, 1 / bloom levels
layout(location = 1) uniform vec4 shaftParams;//shaft colour (scaled by the setting), 1 when the shafts are read
layout(location = 2) uniform vec4 warmParams;//warm tint, saturation
layout(location = 3) uniform vec4 coolParams;//cool tint, contrast - 1
layout(location = 4) uniform vec4 screenParams;//1 / width, 1 / height, vignette strength, unused

//Just under one 8-bit step peak to peak: values already on the 8-bit grid stay where they are
const float DITHER = 0.0039137257;
//Vignette: tan^2 of the corner angle for the cos^4 law
const float VIGNETTE_TAN2 = 0.16;

//Natural vignetting, cos^4 law (Ray 2002, "Applied Photographic Optics"): 1 / (1 + r^2 tan^2)^2, r = 1 in the corners
float vignette(vec2 fragCoord) {
    vec2 size = 1.0/screenParams.xy;
    vec2 d = fragCoord - 0.5*size;
    float r2 = dot(d, d)/(0.25*dot(size, size));
    float q = 1.0 + r2*VIGNETTE_TAN2;
    return mix(1.0, 1.0/(q*q), screenParams.z);
}

//Negative and NaN components as 0 (explicitly: max(NaN, 0) may return either operand)
vec3 nonNegative(vec3 c) {
    return mix(max(c, vec3(0.0)), vec3(0.0), isnan(c));
}

vec4 finalColour(vec2 fragCoord) {
    vec3 c = nonNegative(texelFetch(sceneTex, ivec2(fragCoord), 0).rgb);
    vec2 uv = fragCoord*screenParams.xy;
    //Bloom mixed over the scene (an energy-keeping lerp, as in Jimenez 2014), then the light shafts added
    if (exposureParams.z > 0.0) {
        c = mix(c, nonNegative(texture(bloomTex, uv).rgb)*exposureParams.w, exposureParams.z);
    }
    if (shaftParams.w > 0.0) {
        c += shaftParams.rgb*max(texture(shaftsTex, uv).r, 0.0);
    }
    c = toneMap(c*(exposureParams.x*vignette(fragCoord)), exposureParams.y);
    //Split toning: cool shade to warm highlights with the square root of the luminance
    vec3 tint = mix(coolParams.rgb, warmParams.rgb, sqrt(clamp(luminance(c), 0.0, 1.0)));
    c *= tint;
    c = clamp(mix(vec3(luminance(c)), c, warmParams.w), 0.0, 1.0);
    vec3 d = contrastCurve(linearToDisplay(c), coolParams.w);
    //Random rounding to the 8-bit output (dither against banding in skies)
    d += (interleavedGradientNoise(floor(fragCoord)) - 0.5)*DITHER;
    return vec4(clamp(d, 0.0, 1.0), 1.0);
}
