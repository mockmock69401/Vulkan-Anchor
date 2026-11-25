//2026-11-24 (the user's six Anime Nether references: "작은 불꽃은 어두운 빨간색으로", "큰 불꽃의 몸통은 노란색이면서 테두리는 하얀색, 더 겉은
// 옅은 파란색으로", "용암 느낌도 그라데이션으로 살리는 게 좋아보임 ... 애니메이션 프리셋의 네더에서만"): the Nether's fire and lava under the
// Anime palette (scene_copy.comp endLightParams.w, DimensionUniforms.netherLook; Default and Horror keep them as they were). The small
// flames' dark red is the particles' (ParticleLook.NETHER_ANIME). Imported after lighting.glsl (the marks), postmath.glsl, viewpos.glsl and galaxy.glsl;
// reads the composite's mainColour and mainDepth for the lava's shore.
//
//2026-11-25 (the user, of the thin rims around every shore and pillar: "테두리를 기준으로 잡지 말고 ... 얼룩처럼 나타났다 사라지게"): the
// flame's look no longer looks at where it ends, and the lava measures its shore softly (below); both take noise fixed in the world
// that drifts with time, so their blue comes and goes.
//
// FIRE (FoliageMarker.FIRE_ALPHA: fire and a lit campfire's flames; not the soul fire's, which keeps its own blue): cel-shaded. Its texels take a flat body (FIRE_BODY, the
// brighter ones FIRE_CORE), and blotches of sky blue pass over it (FIRE_BLOTCH_*). Each keeps the glow's brightness the
// composite gave it (its luminance), so the bloom spreads it as before.
//
// LAVA (EMISSIVE_FLUID_ALPHA), a pool's top only. 2026-11-25 (the user, after blotches all over: "얼룩을 용암 테두리를 따라 나타나도록 ...
// 자연스러운 그라데이션 형태", references 5 and 6): how near the shore a pixel lies is a soft measure (netherShore: the share of no lava among
// taps SHORE_NEAR, SHORE_MID and SHORE_FAR blocks away, weighted), not a hard outline. Cyan (LAVA_BLUE) blotches gather on the shore
// (2026-11-25, the user: "파란색 테두리가 라인에 딱 붙지 않고 있어", then "테두리 두께가 너무 칼같이 끊기고 있어. 테두리 중심의 얼룩 느낌"):
// a soft field, full against the shore and fading inward (RIBBON_*) in stretches that come and go (LAVA_BLOTCH_*), is held against a
// fine drifting noise (RIBBON_GRAIN_*), so the cyan breaks into soft-edged blobs toward the lava instead of ending at a line. Then
// ("여전히 얼룩의 두께가 구간마다 일정해보여. 그라데이션을 넣고 구간별 두께도"): how far in it reaches changes from stretch to stretch
// (RIBBON_REACH_*), and it grades from a deep blue at the shore (LAVA_BLUE) to a light cyan at its inner blobs (LAVA_BLUE_INNER).
// A tap of something much nearer than the lava (a pillar in front of it) is no shore and is left out. The lava's own colour does not
// follow the shore (2026-11-25, the user: "테두리에 나타나지 않은 부분을 잘 보면 중앙 용암 색과 다르게 보이고 있어"): where no ribbon shows,
// its edge is the lava of its middle. A bright yellow (LAVA_CORE) takes some deep orange (LAVA_BODY) in slow patches all over
// (LAVA_SWIRL_*).

const float NETHER_FIRE_CORE_R = 255.0;
const float NETHER_FIRE_CORE_G = 200.0;
const float NETHER_FIRE_CORE_B = 40.0;
const float NETHER_FIRE_BODY_R = 255.0;
const float NETHER_FIRE_BODY_G = 132.0;
const float NETHER_FIRE_BODY_B = 16.0;
//The texel brightness (largest channel of vanilla's colour) where the body turns to the core, and the band's softness
const float NETHER_FIRE_CORE_AT = 0.82;
const float NETHER_FIRE_SOFT = 0.04;
//The blotches on a flame: sky blue (BLUE_*), LIGHT times the glow's luminance there; their edge narrow
// (FROM .. TO), so a blotch does not fade through a greyish white
const float NETHER_FIRE_BLUE_R = 80.0;
const float NETHER_FIRE_BLUE_G = 175.0;
const float NETHER_FIRE_BLUE_B = 255.0;
const float NETHER_FIRE_BLOTCH_LIGHT = 0.75;
const float NETHER_FIRE_BLOTCH_SCALE = 1.6;
const float NETHER_FIRE_BLOTCH_SPEED = 1.2;
const float NETHER_FIRE_BLOTCH_FROM = 0.58;
const float NETHER_FIRE_BLOTCH_TO = 0.66;
const float NETHER_FIRE_BLOTCH_MOST = 0.6;
//2026-11-25 (the user: "일반 불은 끝 부분이 회색으로 보이고 있어 ... 일반불에 흰색 느낌을 줄여줘"): over a flame's dim tips the blue's light
// fell to a grey, so a blotch shows only where the texel is bright (from BRIGHT_FROM to BRIGHT_TO), and has no white heart
const float NETHER_FIRE_BLOTCH_BRIGHT_FROM = 0.7;
const float NETHER_FIRE_BLOTCH_BRIGHT_TO = 0.92;

const float NETHER_LAVA_CORE_R = 255.0;
const float NETHER_LAVA_CORE_G = 172.0;
const float NETHER_LAVA_CORE_B = 36.0;
const float NETHER_LAVA_BODY_R = 248.0;
const float NETHER_LAVA_BODY_G = 112.0;
const float NETHER_LAVA_BODY_B = 12.0;
const float NETHER_LAVA_BLUE_R = 22.0;
const float NETHER_LAVA_BLUE_G = 150.0;
const float NETHER_LAVA_BLUE_B = 230.0;
const float NETHER_LAVA_BLUE_INNER_R = 80.0;
const float NETHER_LAVA_BLUE_INNER_G = 215.0;
const float NETHER_LAVA_BLUE_INNER_B = 245.0;
//2026-11-25 (the user: "바닥의 노란 용암 색이 전체적으로 탁해보여"): at vanilla lava's full light a warm yellow turns a pale greenish one, so
// the lava keeps LIGHT of it; its texture's mottle shows by TEXTURE
const float NETHER_LAVA_LIGHT = 0.78;
const float NETHER_LAVA_TEXTURE = 0.18;
//The shore taps (blocks away) and their weights; the taps' pixels at least SHORE_MIN_PIXELS, at most SHORE_MAX_PIXELS
const float NETHER_SHORE_NEAR = 0.5;
const float NETHER_SHORE_MID = 1.2;
const float NETHER_SHORE_FAR = 2.5;
const float NETHER_SHORE_NEAR_WEIGHT = 0.5;
const float NETHER_SHORE_MID_WEIGHT = 0.3;
const float NETHER_SHORE_FAR_WEIGHT = 0.2;
const float NETHER_SHORE_MIN_PIXELS = 1.5;
const float NETHER_SHORE_MAX_PIXELS = 60.0;
//A tap this much nearer than the lava (blocks, plus a share of the distance) covers it and is no shore
const float NETHER_SHORE_COVER_BLOCKS = 2.0;
const float NETHER_SHORE_COVER_SHARE = 0.1;
//The orange patches: per block, per second, the noise where one starts (FROM) and is full (TO), and its most share of the orange
const float NETHER_LAVA_SWIRL_SCALE = 0.12;
const float NETHER_LAVA_SWIRL_SPEED = 0.06;
const float NETHER_LAVA_SWIRL_FROM = 0.42;
const float NETHER_LAVA_SWIRL_TO = 0.7;
const float NETHER_LAVA_SWIRL_MOST = 0.55;
//The field: from none at a shore measure of FROM to full by TO (a straight shore's edge is about 0.5), a wide stretch's (FROM_WIDE ..
// TO_WIDE, reaching about 2 blocks in) or a thin one's (FROM_THIN .. TO_THIN, a line at the shore), which by a slow noise of
// RIBBON_REACH_SCALE per block drifting by RIBBON_REACH_SPEED per second
const float NETHER_RIBBON_FROM_WIDE = -0.04;
const float NETHER_RIBBON_TO_WIDE = 0.3;
const float NETHER_RIBBON_FROM_THIN = 0.4;
const float NETHER_RIBBON_TO_THIN = 0.52;
//The grain's share of the field it takes to show (below 1: a blob shows a little before the field reaches the grain)
const float NETHER_RIBBON_GRAIN_SHARE = 0.85;
const float NETHER_RIBBON_REACH_SCALE = 0.2;
const float NETHER_RIBBON_REACH_SPEED = 0.1;
//The grain that breaks it into blobs: per block, per second, and how soft a blob's edge is
const float NETHER_RIBBON_GRAIN_SCALE = 1.3;
const float NETHER_RIBBON_GRAIN_SPEED = 0.3;
const float NETHER_RIBBON_GRAIN_SOFT = 0.18;
//The stretches where it shows: per block, per second, the noise where one starts (FROM) and is full (TO), its most share and light
const float NETHER_LAVA_BLOTCH_SCALE = 0.3;
const float NETHER_LAVA_BLOTCH_SPEED = 0.25;
const float NETHER_LAVA_BLOTCH_FROM = 0.32;
const float NETHER_LAVA_BLOTCH_TO = 0.62;
const float NETHER_LAVA_BLOTCH_MOST = 0.9;
const float NETHER_LAVA_BLOTCH_LIGHT = 0.6;
const float NETHER_LAVA_BLOTCH_INNER_LIGHT = 0.72;
//The faces that take the look: from a normal's y of POOL_FROM (none) to POOL_TO (all): a pool's top, not a fall's sides
const float NETHER_LAVA_POOL_FROM = 0.6;
const float NETHER_LAVA_POOL_TO = 0.9;

//Two octaves of value noise at q (xy where, z the time), about 0.5 on average
float netherNoise(vec3 q) {
    return 0.7*galaxyNoise(q) + 0.3*galaxyNoise(q*2.1 + vec3(17.0, 5.0, 9.0));
}

vec3 netherSrgb(float r, float g, float b) {
    return displayToLinear(vec3(r, g, b)/255.0);
}

//colour scaled to the luminance of lit (the glow the composite gave the pixel)
vec3 netherKeepLight(vec3 colour, vec3 lit) {
    return colour*(luminance(lit)/max(luminance(colour), 1e-4));
}

//A fire pixel's cel look: lit the composite's glow there, vanilla its colour (display), pos its world position (absolute blocks), t the
// time (seconds)
vec3 netherFireLook(vec3 lit, vec3 vanilla, vec3 pos, float t) {
    float m = max(vanilla.r, max(vanilla.g, vanilla.b));
    vec3 body = mix(netherSrgb(NETHER_FIRE_BODY_R, NETHER_FIRE_BODY_G, NETHER_FIRE_BODY_B),
            netherSrgb(NETHER_FIRE_CORE_R, NETHER_FIRE_CORE_G, NETHER_FIRE_CORE_B),
            smoothstep(NETHER_FIRE_CORE_AT - NETHER_FIRE_SOFT, NETHER_FIRE_CORE_AT + NETHER_FIRE_SOFT, m));
    float n = netherNoise(vec3(pos.xz*NETHER_FIRE_BLOTCH_SCALE + pos.y*0.7, t*NETHER_FIRE_BLOTCH_SPEED));
    float blotch = smoothstep(NETHER_FIRE_BLOTCH_FROM, NETHER_FIRE_BLOTCH_TO, n)
            *smoothstep(NETHER_FIRE_BLOTCH_BRIGHT_FROM, NETHER_FIRE_BLOTCH_BRIGHT_TO, m);
    vec3 blue = netherSrgb(NETHER_FIRE_BLUE_R, NETHER_FIRE_BLUE_G, NETHER_FIRE_BLUE_B);
    vec3 shown = netherKeepLight(body, lit);
    return mix(shown, netherKeepLight(blue, lit)*NETHER_FIRE_BLOTCH_LIGHT, blotch*NETHER_FIRE_BLOTCH_MOST);
}

//The share of no lava among 8 taps r pixels from p (rotated by turn); taps that cover the lava (much nearer than dist) left out
float netherShoreRing(ivec2 p, ivec2 size, float r, float turn, float dist) {
    float cover = dist - NETHER_SHORE_COVER_BLOCKS - NETHER_SHORE_COVER_SHARE*dist;
    float open = 0.0, counted = 0.0;
    for (int k = 0; k < 8; k++) {
        float a = float(k)*0.78539816 + turn;
        ivec2 q = clamp(p + ivec2(round(vec2(cos(a), sin(a))*r)), ivec2(0), size - 1);
        if (alphaCode(texelFetch(mainColour, q, 0).a) == EMISSIVE_FLUID_ALPHA_CODE) {
            counted += 1.0;
            continue;
        }
        if (length(viewPosition(invVanillaProj, pixelUvGl(q, size), texelFetch(mainDepth, q, 0).r)) < cover) {
            continue;
        }
        open += 1.0;
        counted += 1.0;
    }
    return counted > 0.0 ? open/counted : 0.0;
}

//How near the shore a lava pixel lies: 0 well inside, about 0.5 at a straight shore's edge, more in a narrow inlet
float netherShore(ivec2 p, ivec2 size, float dist, float pixelAngle) {
    float perBlock = 1.0/max(dist*pixelAngle, 1e-4);
    return NETHER_SHORE_NEAR_WEIGHT*netherShoreRing(p, size,
                    clamp(NETHER_SHORE_NEAR*perBlock, NETHER_SHORE_MIN_PIXELS, NETHER_SHORE_MAX_PIXELS), 0.39269908, dist)
            + NETHER_SHORE_MID_WEIGHT*netherShoreRing(p, size,
                    clamp(NETHER_SHORE_MID*perBlock, NETHER_SHORE_MIN_PIXELS, NETHER_SHORE_MAX_PIXELS), 0.0, dist)
            + NETHER_SHORE_FAR_WEIGHT*netherShoreRing(p, size,
                    clamp(NETHER_SHORE_FAR*perBlock, NETHER_SHORE_MIN_PIXELS, NETHER_SHORE_MAX_PIXELS), 0.19634954, dist);
}

//A lava pixel's look: lit the composite's glow, vanilla its colour (display), dist its distance (blocks), pixelAngle a pixel's angle,
// pos its world position (absolute blocks), t the time (seconds), up how much its face points up (the normal's y). 2026-11-24 (the user:
// "내려오는 용암과 바닥용암이 겹치는 부분에서 색감이 이상하게 보이고 있어"): only the pools' tops take it; a lava fall's faces (their shade made
// the core's yellow a dull olive) keep vanilla's
vec3 netherLavaLook(ivec2 p, ivec2 size, vec3 lit, vec3 vanilla, float dist, float pixelAngle, vec3 pos, float t, float up) {
    float pool = smoothstep(NETHER_LAVA_POOL_FROM, NETHER_LAVA_POOL_TO, up);
    if (!(pool > 0.0)) {
        return lit;
    }
    float shore = netherShore(p, size, dist, pixelAngle);
    float m = max(vanilla.r, max(vanilla.g, vanilla.b));
    float swirl = netherNoise(vec3(pos.xz*NETHER_LAVA_SWIRL_SCALE, t*NETHER_LAVA_SWIRL_SPEED + 40.0));
    vec3 c = mix(netherSrgb(NETHER_LAVA_CORE_R, NETHER_LAVA_CORE_G, NETHER_LAVA_CORE_B),
            netherSrgb(NETHER_LAVA_BODY_R, NETHER_LAVA_BODY_G, NETHER_LAVA_BODY_B),
            NETHER_LAVA_SWIRL_MOST*smoothstep(NETHER_LAVA_SWIRL_FROM, NETHER_LAVA_SWIRL_TO, swirl));
    vec3 shown = netherKeepLight(c, lit)*NETHER_LAVA_LIGHT*(1.0 - NETHER_LAVA_TEXTURE + NETHER_LAVA_TEXTURE*2.0*m*m);
    float reach = smoothstep(0.3, 0.7, netherNoise(vec3(pos.xz*NETHER_RIBBON_REACH_SCALE, t*NETHER_RIBBON_REACH_SPEED + 20.0)));
    float ribbon = smoothstep(mix(NETHER_RIBBON_FROM_THIN, NETHER_RIBBON_FROM_WIDE, reach),
            mix(NETHER_RIBBON_TO_THIN, NETHER_RIBBON_TO_WIDE, reach), shore);
    float stretch = smoothstep(NETHER_LAVA_BLOTCH_FROM, NETHER_LAVA_BLOTCH_TO,
            netherNoise(vec3(pos.xz*NETHER_LAVA_BLOTCH_SCALE, t*NETHER_LAVA_BLOTCH_SPEED)));
    float grain = netherNoise(vec3(pos.xz*NETHER_RIBBON_GRAIN_SCALE, t*NETHER_RIBBON_GRAIN_SPEED + 9.0));
    grain *= NETHER_RIBBON_GRAIN_SHARE;
    float blotch = smoothstep(grain - NETHER_RIBBON_GRAIN_SOFT, grain + NETHER_RIBBON_GRAIN_SOFT, ribbon*stretch);
    //Deep blue against the shore, a light cyan at the inner blobs
    float deep = smoothstep(0.35, 1.0, ribbon);
    vec3 blue = mix(netherKeepLight(netherSrgb(NETHER_LAVA_BLUE_INNER_R, NETHER_LAVA_BLUE_INNER_G, NETHER_LAVA_BLUE_INNER_B), lit)
                    *NETHER_LAVA_BLOTCH_INNER_LIGHT,
            netherKeepLight(netherSrgb(NETHER_LAVA_BLUE_R, NETHER_LAVA_BLUE_G, NETHER_LAVA_BLUE_B), lit)*NETHER_LAVA_BLOTCH_LIGHT, deep);
    return mix(lit, mix(shown, blue, blotch*NETHER_LAVA_BLOTCH_MOST), pool);
}
