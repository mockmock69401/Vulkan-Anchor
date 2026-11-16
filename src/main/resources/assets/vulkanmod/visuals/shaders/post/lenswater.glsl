//2026-11-16 (shader roadmap session 26; the user: "물에 들어갔다 나올 때 물이 흘러내리는 효과도 구현해줘", Experimental > Interaction): water
// running down the lens after the camera comes up out of the water. Drawn where the final colour is read (post_final.frag, or post_blit.frag
// after TAA and SMAA, so neither smears it): every pixel reads the picture a little away, as through water on the glass.
//2026-11-17 (session 26, revised after the user's reference video, "물에서 나오는 영상을 참고해서 효과를 수정해줘"; the video was looked at,
// nothing else): one sheet of water over the whole lens, no drops.
//  - The sheet warps the picture in smooth swirls (about LENS_SCALE across a screen height, up to LENS_BEND screen heights away; more at the
//    bottom of the screen, where the water gathers), folded on itself (one noise bends where the other is read), so straight edges turn ragged and the sky into marbled bands.
//  - It flows down the screen while it runs off: its top edge leaves the top of the screen slowly and reaches the bottom at LENS_CLEAR_TIME,
//    faster and faster; the warp weakens from LENS_FADE_START and is gone by LENS_DURATION.
//  - Right out of the water it is a little milky (LENS_VEIL, gone by LENS_VEIL_TIME): duller colours and a slight lift.
// lensParams: x seconds since the camera came out, y wetness x setting (0 = off), z a seed for this time (0..1), w unused (LensWater writes
// them; WakeTest checks the consts). Screen units: screen heights, y down from the top.

//Seconds until the lens is clear, and until the sheet's top edge has run off the bottom of the screen; how it speeds up (the share of the
// way down is the share of the time to this power: slow at first, as the top of the screen stays wet a while)
const float LENS_DURATION = 1.2;
const float LENS_CLEAR_TIME = 1.05;
const float LENS_CLEAR_CURVE = 2.5;
//When the warp starts to weaken (seconds), its swirls across a screen height, how far it reads the picture away at most (screen heights)
const float LENS_FADE_START = 0.25;
const float LENS_SCALE = 7.5;
const float LENS_BEND = 0.032;
//How much of the warp the top of the screen gets (the water gathers low on the lens: the full warp at the bottom)
const float LENS_TOP_SHARE = 0.5;
//How fast the swirls flow down (screen heights: a t + b t^2), and how soft the sheet's top edge is (screen heights)
const float LENS_FLOW_SPEED = 0.35;
const float LENS_FLOW_ACCEL = 0.6;
const float LENS_EDGE = 0.3;
//The milky look right out of the water: how much, and for how long (seconds)
const float LENS_VEIL = 0.12;
const float LENS_VEIL_TIME = 0.6;

uint lensHashU(uvec2 v) {
    uint h = v.x*0x8da6b343u ^ v.y*0xd8163841u;
    h ^= h >> 15;
    h *= 0x2c1b3c6du;
    h ^= h >> 12;
    h *= 0x297a2d39u;
    h ^= h >> 15;
    return h;
}

//0..1 from the cell and a channel
float lensHash(vec2 cell, float channel) {
    return float(lensHashU(uvec2(ivec2(cell) + 4096) + uvec2(uint(channel)*7919u, uint(channel)*104729u)) & 0xFFFFu)/65535.0;
}

//Value noise, -0.5..0.5
float lensNoise(vec2 p, float channel) {
    vec2 i = floor(p);
    vec2 f = p - i;
    vec2 u = f*f*(3.0 - 2.0*f);
    float a = lensHash(i, channel);
    float b = lensHash(i + vec2(1.0, 0.0), channel);
    float c = lensHash(i + vec2(0.0, 1.0), channel);
    float d = lensHash(i + vec2(1.0, 1.0), channel);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y) - 0.5;
}

//The sheet's sideways and downward bend at q (about -1..1), t seconds out
vec2 lensSwirl(vec2 q, float t, float seed) {
    float flow = LENS_FLOW_SPEED*t + LENS_FLOW_ACCEL*t*t;
    vec2 p = vec2(q.x, q.y - flow)*LENS_SCALE + seed*97.0;
    //Folded: a slower noise moves where the bend is read, and the sheet changes shape as it flows
    vec2 w = vec2(lensNoise(p*0.55 + vec2(0.0, 0.9*t), 1.0), lensNoise(p*0.55 - vec2(0.0, 0.9*t), 2.0));
    vec2 r = p + 2.2*w;
    vec2 d = vec2(lensNoise(r, 3.0), lensNoise(r, 4.0)) + 0.45*vec2(lensNoise(r*2.3, 5.0), lensNoise(r*2.3, 6.0));
    return d*2.6;
}

//At fragCoord (pixels, row 0 at the top) on a screen of size pixels: xy how far away to read the picture (pixels), z the milky veil (0..1,
// lensVeil)
vec3 lensWater(vec2 fragCoord, vec2 size, vec4 params) {
    float t = params.x;
    float wet = clamp(params.y, 0.0, 2.0);
    if (!(wet > 0.0) || !(t >= 0.0) || t >= LENS_DURATION) {
        return vec3(0.0);
    }
    vec2 q = fragCoord/size.y;
    //The top edge: from just above the screen to its bottom at LENS_CLEAR_TIME, faster and faster
    float top = -LENS_EDGE + (1.0 + LENS_EDGE)*pow(min(t/LENS_CLEAR_TIME, 1.0), LENS_CLEAR_CURVE);
    float cover = smoothstep(top, top + LENS_EDGE, q.y);
    float amount = cover*(1.0 - smoothstep(LENS_FADE_START, LENS_DURATION, t))*mix(LENS_TOP_SHARE, 1.0, clamp(q.y, 0.0, 1.0));
    if (!(amount > 0.0)) {
        return vec3(0.0);
    }
    vec2 bend = lensSwirl(q, t, params.z)*LENS_BEND*amount*wet;
    float veil = LENS_VEIL*min(wet, 1.0)*cover*(1.0 - smoothstep(0.0, LENS_VEIL_TIME, t));
    return vec3(bend*size.y, veil);
}

//The picture's colour c (display values) under the veil: duller and a little lifted
vec3 lensVeil(vec3 c, float veil) {
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return mix(c, vec3(l*1.05 + 0.08), veil);
}
