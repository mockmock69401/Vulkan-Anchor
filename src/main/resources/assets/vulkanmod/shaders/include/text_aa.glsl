//2026-10-30: text anti-aliasing (TextCoverage mirrors this; change both together). The font pages are mipmapped and sampled
// trilinearly. Minified glyphs (footprint over a texel per pixel) come back with thin strokes at low coverage: thickened by the
// footprint (at most MAX_BOOST). Magnified glyphs are sampled sharp-bilinear: each texel flat inside, blended only over the last
// screen pixel before the next one (plain linear magnification turned 1-texel strokes into pale tents). Only empty texels are
// discarded (no depth for them).
const float MAX_BOOST = 3.0;
const float DISCARD_ALPHA = 1.0/255.0;
//World text (perspective) cuts its coverage here instead of blending: sign text draws before the board, and blended edge fragments
// wrote depth over what lay behind it (the wall showed through as light halos). GUI text (orthographic) blends.
const float WORLD_CUTOUT = 0.5;

//Texels per screen pixel along the glyph's steeper axis
float textFootprint(vec2 uv, vec2 texSize) {
    vec2 t = uv * texSize;
    return max(length(dFdx(t)), length(dFdy(t)));
}

//Sharp bilinear in texel space: t the texel coordinate, m screen pixels per texel
vec2 textSharp(vec2 t, vec2 m) {
    vec2 c = floor(t - 0.5) + 0.5;
    vec2 f = t - c;
    return c + clamp((f - 0.5) * m + 0.5, 0.0, 1.0);
}

//The glyph texel at uv; footprint out for textCoverageAt
vec4 textSample(sampler2D s, vec2 uv, out float footprint) {
    vec2 size = vec2(textureSize(s, 0));
    vec2 t = uv * size;
    //derivatives before the branch (footprint varies per pixel; implicit ones are undefined in non-uniform control flow)
    vec2 ux = dFdx(uv), uy = dFdy(uv);
    vec2 tx = ux * size, ty = uy * size;
    footprint = max(length(tx), length(ty));
    if (footprint < 1.0) {
        vec2 m = 1.0 / max(vec2(length(vec2(tx.x, ty.x)), length(vec2(tx.y, ty.y))), vec2(1e-4));
        return textureLod(s, textSharp(t, m) / size, 0.0);
    }
    return textureGrad(s, uv, ux, uy);
}

//Coverage: magnified as sampled, minified thickened; world text (perspective) then cut at WORLD_CUTOUT
float textCoverageAt(float a, float footprint, float perspective) {
    a = clamp(a, 0.0, 1.0);
    float c = footprint < 1.0 ? a : 1.0 - pow(1.0 - a, min(footprint, MAX_BOOST));
    return perspective > 0.5 ? (c >= WORLD_CUTOUT ? 1.0 : 0.0) : c;
}

//antialias: the TextAntialias uniform (1 on); off keeps vanilla's 0.1 cut
bool textDiscard(float a, float antialias) {
    return antialias > 0.5 ? a < DISCARD_ALPHA : a < 0.1;
}
