//2026-11-21 (the user: "유성(별똥별) 옵션과 별빛이 불규칙하게 더 빛나는 (star scintillation) 옵션도 추가해줘. 실험적 기능에 넣어놔"): the night
// sky's events, Experimental > Sky. SkyEventMath.java mirrors every const and the Java-checkable functions here: change both together
// (SkyEventTest compares them). Needs galaxy.glsl (galaxyHash) imported first.
//
// UNITS. Light is W2's scene units (postmath.glsl). Directions are WORLD space unit vectors (y up) from the camera; skyDir is the stars'
// frame (animestars.glsl animeSkyDir), in which the stars stay put as the sky turns.
//
// SCINTILLATION (starTwinkle): each star's light swells and dips on its own, now and then flaring well above its usual brightness. Three
// sines of fixed rates (TWINKLE_RATE_*, radians a second, not multiples of each other, so the sum never repeats in a way the eye catches)
// each take a phase from a smooth noise field over skyDir (TWINKLE_CELLS cells per unit: a cell is about two degrees, wider than any star,
// so a star is one phase, and about four cells lie between neighbouring stars, so they keep their own). The fixed rates keep the phase's
// change across one star to what the field gives (a rate that varied over the sky would spread a star's phase further every second).
// Their mean s (-1..1) dims the star by up to TWINKLE_DIM and above TWINKLE_FLARE_FROM brightens it by TWINKLE_FLARE x the excess squared:
// mostly a gentle shimmer, sometimes a flash. Stars low in the sky twinkle more (through more air): x TWINKLE_LOW at the horizon, 1 at the
// top.
//
// METEORS (meteors): METEOR_LANES lanes, each a row of time slots; a slot holds at most one meteor (METEOR_CHANCE), at a random time in it,
// for METEOR_LIFE_MIN..MAX seconds. A slot lasts METEOR_LANES x METEOR_CHANCE x 60 / rate seconds, so the lanes together bring rate meteors
// a minute on average. A meteor starts METEOR_HEIGHT_MIN..MAX up the sky (the sine of its elevation), at any compass direction, and runs
// METEOR_LENGTH_MIN..MAX radians along a great circle heading down the sky (METEOR_HEADING_SPREAD about straight down). Its head is a soft
// point METEOR_WIDTH pixels wide (at least METEOR_WIDTH_MIN radians), its tail METEOR_TAIL of its run behind the head, fading toward its end;
// it flares in and out over its life (sin^METEOR_FADE). Below METEOR_HORIZON (a direction's y) it is gone.
//
// 2026-11-22 (the user: "떨어지는 유성 궤적을 하늘에 맞게 약간 둥근 궤적을 그리게 하고, 베지어 곡선으로 속도 조절 가능하게 만들어줘. 지속시간도
// 추가하고, 실험적 기능에 넣어줘"): THE BEND. A meteor runs along a small circle through s that leaves along h and curves toward the ground:
// its heading turns shape.x x lean radians per radian of run (geodesic curvature; METEOR_BEND at a curve of 100 %), lean (0..1) how much
// it crosses the sky sideways (0 heading straight down: it stays straight). The circle's pole c is n cos(tilt) + s sin(tilt), n the
// great circle's pole on the ground's side and tilt = atan(the curvature), so the circle's angular radius is pi/2 - tilt.
// THE SPEED. The head's share of the run follows a cubic bezier of its life's share, as CSS cubic-bezier(x1, y1, x2, y2) (ease: x1 y1
// x2 y2, x in 0..1 so it is a function): x(t) = u solved by METEOR_EASE_STEPS halvings, then y(t). THE DURATION. shape.y scales the
// life (SkyEventMath keeps the longest life inside the shortest slot).
//
// WHAT TO CALL
//   starTwinkle(skyDir, y, params): the factor (>= 0) on a star's light along skyDir, y the world direction's y; 1 with params.x 0.
//   meteors(dir, params, shape, ease, weight, pixelAngle): the light (rgb, scene units) to add to a sky pixel along world dir; weight the
//   night's star weight (0 by day: nothing evaluated); pixelAngle the angle one pixel spans (radians).
//   params the composite's skyEvents (SkyEventMath's slot): x the twinkle's strength (0: off), y meteors a minute (0: off), z the time in
//   seconds (wrapped at SKY_EVENT_TIME_WRAP), w the meteors' brightness (1 = default). shape the composite's meteorShape (x the bend's
//   curvature, y the life's factor), ease its meteorEase (x1, y1, x2, y2).

const float TWINKLE_CELLS = 30.0;
const float TWINKLE_RATE_A = 1.7;
const float TWINKLE_RATE_B = 2.9;
const float TWINKLE_RATE_C = 4.6;
const float TWINKLE_DIM = 0.4;
const float TWINKLE_FLARE_FROM = 0.3;
const float TWINKLE_FLARE = 3.0;
const float TWINKLE_LOW = 1.6;
const float METEOR_LANES = 4.0;
const float METEOR_CHANCE = 0.6;
const float METEOR_LIFE_MIN = 0.45;
const float METEOR_LIFE_MAX = 1.1;
const float METEOR_HEIGHT_MIN = 0.35;
const float METEOR_HEIGHT_MAX = 0.9;
const float METEOR_LENGTH_MIN = 0.14;
const float METEOR_LENGTH_MAX = 0.38;
const float METEOR_HEADING_SPREAD = 1.1;
const float METEOR_TAIL = 0.5;
const float METEOR_WIDTH = 0.9;
const float METEOR_WIDTH_MIN = 0.0009;
const float METEOR_FADE = 0.6;
const float METEOR_LIGHT = 2.4;
const float METEOR_HORIZON = 0.04;
const float METEOR_R = 0.86;
const float METEOR_G = 0.95;
const float METEOR_B = 1.0;
const float METEOR_BEND = 1.2;
const float METEOR_EASE_STEPS = 12.0;
const float SKY_EVENT_TIME_WRAP = 3600.0;

//Smooth value noise 0..1 over p (the cell corners' galaxyHash, seed apart per field)
float skyEventNoise(vec3 p, int seed) {
    vec3 c = floor(p);
    vec3 f = p - c;
    vec3 u = f*f*(3.0 - 2.0*f);
    ivec3 i = ivec3(c) + ivec3(0, 0, seed*104729);
    float n000 = galaxyHash(i), n100 = galaxyHash(i + ivec3(1, 0, 0)), n010 = galaxyHash(i + ivec3(0, 1, 0)), n110 = galaxyHash(i + ivec3(1, 1, 0));
    float n001 = galaxyHash(i + ivec3(0, 0, 1)), n101 = galaxyHash(i + ivec3(1, 0, 1)), n011 = galaxyHash(i + ivec3(0, 1, 1)), n111 = galaxyHash(i + ivec3(1, 1, 1));
    return mix(mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y), mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y), u.z);
}

//The twinkle's brightness factor for the mean s (-1..1) of the three sines at strength k (> 0)
float twinkleFactor(float s, float k) {
    float flare = max(s - TWINKLE_FLARE_FROM, 0.0);
    return max(1.0 + k*(TWINKLE_DIM*s + TWINKLE_FLARE*flare*flare), 0.0);
}

float starTwinkle(vec3 skyDir, float y, vec4 params) {
    if (!(params.x > 0.0)) {
        return 1.0;
    }
    vec3 q = skyDir*TWINKLE_CELLS;
    float t = params.z;
    const float TAU = 6.2831853;
    float s = (sin(t*TWINKLE_RATE_A + TAU*skyEventNoise(q, 1)) + sin(t*TWINKLE_RATE_B + TAU*skyEventNoise(q, 2))
            + sin(t*TWINKLE_RATE_C + TAU*skyEventNoise(q, 3)))/3.0;
    float k = params.x*mix(TWINKLE_LOW, 1.0, clamp(y, 0.0, 1.0));
    return twinkleFactor(s, k);
}

//The cubic bezier (0,0) (x1,y1) (x2,y2) (1,1) at t, one axis: p1, p2 its control points' coordinates on it
float bezierAxis(float t, float p1, float p2) {
    float r = 1.0 - t;
    return 3.0*r*r*t*p1 + 3.0*r*t*t*p2 + t*t*t;
}

//The head's share of the run at the life's share u (0..1) for ease (x1, y1, x2, y2): x(t) = u by halving (x rises with t), then y(t)
float meteorEased(float u, vec4 ease) {
    float lo = 0.0, hi = 1.0;
    for (int i = 0; i < int(METEOR_EASE_STEPS); i++) {
        float mid = 0.5*(lo + hi);
        if (bezierAxis(mid, ease.x, ease.z) < u) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    return clamp(bezierAxis(0.5*(lo + hi), ease.y, ease.w), 0.0, 1.0);
}

//Lane l's meteor at time t: false if none is in the sky now; else its start direction s, its heading h (a unit vector across s), n the
// pole of the great circle along h on the ground's side and the tilt of the small circle it runs along (0: that great circle), its run (radians along the circle), its head's
// share of the run done (0..1, eased) and its life's envelope
bool meteorAt(float lane, float t, float rate, vec4 shape, vec4 ease, out vec3 s, out vec3 h, out vec3 n, out float tilt, out float run,
              out float done, out float envelope) {
    float slot = METEOR_LANES*METEOR_CHANCE*60.0/rate;
    //each lane's slots start at their own offset, so slots of different lanes do not begin together
    float lt = t + lane*slot/METEOR_LANES;
    float index = floor(lt/slot);
    float local = lt - index*slot;
    ivec3 id = ivec3(int(index), int(lane), 7717);
    s = vec3(0.0);
    h = vec3(0.0);
    n = vec3(0.0);
    tilt = 0.0;
    run = 0.0;
    done = 0.0;
    envelope = 0.0;
    if (!(galaxyHash(id) < METEOR_CHANCE)) {
        return false;
    }
    float life = mix(METEOR_LIFE_MIN, METEOR_LIFE_MAX, galaxyHash(id + ivec3(0, 0, 1)))*shape.y;
    float begin = galaxyHash(id + ivec3(0, 0, 2))*max(slot - life, 0.0);
    float age = local - begin;
    if (!(life > 0.0) || age < 0.0 || age > life) {
        return false;
    }
    float azimuth = 6.2831853*galaxyHash(id + ivec3(0, 0, 3));
    float up = mix(METEOR_HEIGHT_MIN, METEOR_HEIGHT_MAX, galaxyHash(id + ivec3(0, 0, 4)));
    float across = sqrt(max(1.0 - up*up, 0.0));
    s = vec3(cos(azimuth)*across, up, sin(azimuth)*across);
    //down the sky at s (toward the horizon below it) and sideways, turned by the heading's spread
    vec3 down = normalize(vec3(0.0, -1.0, 0.0) + s*up);
    vec3 side = cross(down, s);
    float turn = (galaxyHash(id + ivec3(0, 0, 5)) - 0.5)*2.0*METEOR_HEADING_SPREAD;
    h = down*cos(turn) + side*sin(turn);
    //the bend: the great circle's pole on the ground's side, tilted toward s (lean: how far the heading is from straight down)
    n = cross(s, h);
    if (n.y > 0.0) {
        n = -n;
    }
    float lean = clamp(-n.y/max(across, 1e-4), 0.0, 1.0);
    tilt = atan(max(shape.x, 0.0)*lean);
    run = mix(METEOR_LENGTH_MIN, METEOR_LENGTH_MAX, galaxyHash(id + ivec3(0, 0, 6)));
    float share = age/life;
    done = meteorEased(share, ease);
    envelope = pow(sin(3.14159265*share), METEOR_FADE);
    return true;
}

vec3 meteors(vec3 dir, vec4 params, vec4 shape, vec4 ease, float weight, float pixelAngle) {
    if (!(params.y > 0.0) || !(params.w > 0.0) || !(weight > 0.0) || !(dir.y > METEOR_HORIZON)) {
        return vec3(0.0);
    }
    float sigma = max(pixelAngle*METEOR_WIDTH, METEOR_WIDTH_MIN);
    float light = 0.0;
    for (int l = 0; l < int(METEOR_LANES); l++) {
        vec3 s, h, n;
        float tilt, run, done, envelope;
        if (!meteorAt(float(l), params.z, params.y, shape, ease, s, h, n, tilt, run, done, envelope)) {
            continue;
        }
        //dir against the meteor's circle (pole c, angular radius pi/2 - tilt): the angle off it, and the arc along it from s (the
        // circle's frame: e1 from its centre toward s, h the way it runs)
        float ct = cos(tilt), st = sin(tilt);
        float off = asin(clamp(dot(dir, n*ct + s*st), -1.0, 1.0)) - tilt;
        if (abs(off) > 4.0*sigma) {
            continue;
        }
        float along = atan(dot(dir, h), dot(dir, s*ct - n*st))*ct;
        float head = run*done;
        float tail = min(head, run*METEOR_TAIL);
        float back = head - along;
        if (back < -4.0*sigma || back > tail + 4.0*sigma) {
            continue;
        }
        //the nearest point of the trail: its light fades from the head to the tail's end
        float at = clamp(back, 0.0, tail);
        float share = tail > 0.0 ? 1.0 - at/tail : 1.0;
        vec2 gap = vec2(back - at, off);
        light += envelope*share*share*exp(-dot(gap, gap)/(2.0*sigma*sigma));
    }
    float horizon = smoothstep(METEOR_HORIZON, METEOR_HORIZON + 0.1, dir.y);
    return vec3(METEOR_R, METEOR_G, METEOR_B)*(light*METEOR_LIGHT*params.w*min(weight, 1.0)*horizon);
}
