//W2: maths shared by the post-processing (mirrored in PostMath.java; every const here is a PostMath constant of the same value).
//
// SCENE UNITS (what every pass writing the scene image must produce):
//  - Linear light, Rec. 709 / sRGB primaries, before exposure, in rgba16f.
//  - 1.0 (SCENE_WHITE) = a white (albedo 1) surface in full daylight (sun plus sky, clear noon) at exposure 100. This is
//    vanilla's full-bright texel decoded from sRGB: a vanilla colour turned into scene units is vanillaToScene(colour)
//    (= displayToLinear), so an unlit pass-through of the game's image is already in scene units and looks as vanilla does.
//  - Lighting scales from there: a sunlit face may go somewhat above 1, shade and night below; moonlit terrain is typically
//    0.003..0.01 (refs/S3.png), which the final pass keeps readable (no toe in the tone curve).
//  - Sky and fog colours: vanilla's sky/fog colours (display values 0..1) are vanillaToScene(colour); a clear daytime sky
//    is then about 0.2..0.5 luminance, below a white surface as in nature. Light sources and the sun may exceed 1 freely:
//    the tone curve's shoulder rolls them off (SUN_DISC_LUMINANCE is a suggested peak for the sun disc).
//  - The final pass: scene, mixed with bloom (bloomTex / bloom levels, the same units), plus shaftLight x shaft image, times
//    exposure and vignette, tone mapped, graded, saturated, encoded to sRGB, contrast, dither.

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);//Rec. 709 linear luminance (ITU-R BT.709-6)
const float SCENE_WHITE = 1.0;
const float SUN_DISC_LUMINANCE = 24.0;

//sRGB transfer function (IEC 61966-2-1:1999)
const float SRGB_CUT = 0.0031308;
const float SRGB_DISPLAY_CUT = 0.04045;
const float SRGB_SLOPE = 12.92;
const float SRGB_SCALE = 1.055;
const float SRGB_OFFSET = 0.055;
const float SRGB_GAMMA = 2.4;

//Tone curve knee at tone strength 100 (the identity below it)
const float TONE_KNEE = 0.55;

//Interleaved gradient noise (Jimenez 2014, "Next Generation Post Processing in Call of Duty: Advanced Warfare")
const float IGN_X = 0.06711056;
const float IGN_Y = 0.00583715;
const float IGN_SCALE = 52.9829189;

float luminance(vec3 c) {
    return dot(c, LUMA);
}

//Linear [0, 1] to display (sRGB encoded) [0, 1]
vec3 linearToDisplay(vec3 x) {
    x = max(x, vec3(0.0));
    vec3 lo = x*SRGB_SLOPE;
    vec3 hi = SRGB_SCALE*pow(x, vec3(1.0/SRGB_GAMMA)) - SRGB_OFFSET;
    return mix(hi, lo, lessThanEqual(x, vec3(SRGB_CUT)));
}

float linearToDisplay(float x) {
    return linearToDisplay(vec3(x)).r;
}

//Display (sRGB encoded) [0, 1] to linear [0, 1]
vec3 displayToLinear(vec3 d) {
    d = max(d, vec3(0.0));
    vec3 lo = d/SRGB_SLOPE;
    vec3 hi = pow((d + SRGB_OFFSET)/SRGB_SCALE, vec3(SRGB_GAMMA));
    return mix(hi, lo, lessThanEqual(d, vec3(SRGB_DISPLAY_CUT)));
}

float displayToLinear(float d) {
    return displayToLinear(vec3(d)).r;
}

//A vanilla colour (texture, sky colour, fog colour; display values) in scene units
vec3 vanillaToScene(vec3 vanillaColour) {
    return displayToLinear(vanillaColour)*SCENE_WHITE;
}

//Per channel: identity below the knee, exponential shoulder above it toward 1 (C1 at the knee); after the linear section and
// shoulder of Uchimura 2017 ("HDR theory and practice", CEDEC) without the toe. knee 1 = the identity clipped at 1.
// 2026-10-28 (DeceasedCraft: sunlit snow clipped, 1.7% of a clear street above 240 against Complementary Reimagined's 0.2%): the
// shoulder is Reinhard's d/(d + w) above the knee instead of 1 - exp(-d/w): the same slope 1 at the knee and the same asymptote, but
// highlights approach white far more slowly (scene 2.0: display 0.95, was 0.99), so snow keeps its texture
vec3 toneMap(vec3 x, float knee) {
    x = max(x, vec3(0.0));
    float w = max(1.0 - knee, 1e-6);
    vec3 d = max(x - knee, vec3(0.0));
    vec3 shoulder = knee + w*d/(d + w);
    return min(mix(x, shoulder, greaterThan(x, vec3(knee))), vec3(1.0));
}

//Schlick's bias (Schlick 1994, Graphics Gems IV) written with k = 1/a - 2
vec3 schlickBias(vec3 t, float k) {
    return t/(k*(1.0 - t) + 1.0);
}

//Contrast about mid-grey on display values: Schlick's gain with slope 1 + k at 0.5 (k = 0 is the identity)
vec3 contrastCurve(vec3 d, float k) {
    d = clamp(d, 0.0, 1.0);
    vec3 low = schlickBias(2.0*d, k)*0.5;
    vec3 high = 1.0 - schlickBias(2.0 - 2.0*d, k)*0.5;
    return mix(high, low, lessThan(d, vec3(0.5)));
}

//Uniform noise in [0, 1) per pixel (pixel coordinates)
float interleavedGradientNoise(vec2 pixel) {
    return fract(IGN_SCALE*fract(dot(pixel, vec2(IGN_X, IGN_Y))));
}
