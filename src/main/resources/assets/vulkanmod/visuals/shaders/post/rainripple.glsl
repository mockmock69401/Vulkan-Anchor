//2026-10-29 (the user: add an effect where raindrops hit the water): while it rains, drops ring the open water. Each cell of a
// world-anchored grid (RAIN_RIPPLE_CELL blocks, two staggered layers) holds one drop that lands at a hashed spot and time in every
// RAIN_RIPPLE_CYCLE seconds. The rings tilt the water's normal (reflection.comp), so they show in the mirrored sky and the refracted
// floor; the weather pass (weather.comp, session 6) draws each drop's crown at the same spot and moment (rainRippleDrop). Import after
// water.glsl (its hash); no uniforms or bindings. RainRippleMath mirrors every const and function here (RainRippleTest).
//
// 2026-11-10 (the user: "rain hitting the water looks low-effort; rebuild it from the references"): the references seen close up (session 6
// scene WC, 1.5 blocks over calm sea in rain; refs/anchor/observations.md) show many small, thin, faint rings (SEUS: three or four crests
// about 0.015 blocks apart, out to 0.07..0.14 blocks, some 25 a block at a time, contrast about 1/5 of ours) and dark dimples where drops just
// hit (AstraLex), where the old rings were sparse, half a block wide, single and bright. A drop's ring is now a short wave packet: crests
// RAIN_RIPPLE_WAVE apart inside a Gaussian envelope RAIN_RIPPLE_WIDTH wide (capillary ripples from a drop travel as such a packet), its
// centre running out to RAIN_RIPPLE_RADIUS, fast at first and slowing (1 - (1 - age)^2), dying as (1 - age)^2; a fresh drop is the packet
// at r about 0: a small dimple. Four times the cells of the old grid.

//The grid: a drop per cell per cycle in each layer; the second layer is offset by half a cell and half a cycle
const float RAIN_RIPPLE_CELL = 0.25;
const float RAIN_RIPPLE_CYCLE = 0.8;
//A ring: where its packet's centre ends, the crests' spacing, the packet's half width (blocks), slope at a fresh crest
const float RAIN_RIPPLE_RADIUS = 0.13;
const float RAIN_RIPPLE_WAVE = 0.03;
const float RAIN_RIPPLE_WIDTH = 0.03;
const float RAIN_RIPPLE_SLOPE = 0.22;
//A crest catches this share of the light the water mirrors there (under an overcast sky the tilt alone hardly shows)
const float RAIN_RIPPLE_SHINE = 0.12;
//The share of cells with a drop at full rain (thunder included)
const float RAIN_RIPPLE_DENSITY = 0.85;
//The rings fade out as a crest covers fewer pixels: none at RAIN_RIPPLE_FADE_NONE pixels across, full from RAIN_RIPPLE_FADE_FULL
const float RAIN_RIPPLE_FADE_NONE = 0.5;
const float RAIN_RIPPLE_FADE_FULL = 2.0;

//The cells along one axis of the ripple period (water.glsl's RIPPLE_PERIOD; the cell divides it, so the wrap keeps every drop)
float rainRippleCells() {
    return floor(RIPPLE_PERIOD/RAIN_RIPPLE_CELL + 0.5);
}

//0..1 from a cell's hash: bits 0-9, 10-19 or 20-29 by which
float rainRippleHash(uint h, int which) {
    return float((h >> uint(which*10)) & 1023u)/1023.0;
}

//Cell c (in cells of the layer's grid) of layer 0 or 1: false when it holds no drop at this rain (0..1); else its drop's centre (in the
// layer's cells) and age (0..1 of the cycle)
bool rainRippleDrop(vec2 c, int layer, float rain, float time, out vec2 centre, out float age) {
    float n = rainRippleCells();
    vec2 w = c - n*floor(c/n);
    uint h = rippleHash(ivec2(w), uint(7 + layer));
    centre = c + 0.2 + 0.6*vec2(rainRippleHash(h, 1), rainRippleHash(h, 2));
    age = fract(time/RAIN_RIPPLE_CYCLE + float(layer)*0.5 + rainRippleHash(h, 1)*7.0 + rainRippleHash(h, 2)*3.0);
    return rainRippleHash(h, 0) <= clamp(rain, 0.0, 1.0)*RAIN_RIPPLE_DENSITY;
}

//The packet's slope along the radius (d height / d r, a fresh crest about 1) at distance dist from a drop of this age
float rainRipplePacket(float dist, float age) {
    float front = RAIN_RIPPLE_RADIUS*(1.0 - (1.0 - age)*(1.0 - age));
    float x = (dist - front)/RAIN_RIPPLE_WIDTH;
    return -sin(6.2831853*(dist - front)/RAIN_RIPPLE_WAVE)*exp(-x*x)*(1.0 - age)*(1.0 - age);
}

//The slope (d height / d x, d height / d z) of the rings at pos (blocks, the water's x, z plus water.glsl's first ripple offset, so
// anchored to the world), at time (seconds), for rain 0..1 (the share of cells with a drop), footprint the blocks one pixel covers
vec2 rainRippleSlope(vec2 pos, float time, float rain, float footprint) {
    float pixels = footprint > 0.000001 ? RAIN_RIPPLE_WAVE*0.5/footprint : 1000000.0;
    float fade = smoothstep(RAIN_RIPPLE_FADE_NONE, RAIN_RIPPLE_FADE_FULL, pixels);
    if (!(rain > 0.0) || fade <= 0.0) {
        return vec2(0.0);
    }
    vec2 slope = vec2(0.0);
    for (int layer = 0; layer < 2; layer++) {
        vec2 q = pos/RAIN_RIPPLE_CELL + float(layer)*0.5;
        vec2 base = floor(q);
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                vec2 centre;
                float age;
                if (!rainRippleDrop(base + vec2(float(i), float(j)), layer, rain, time, centre, age)) {
                    continue;
                }
                vec2 d = (q - centre)*RAIN_RIPPLE_CELL;
                float dist = length(d);
                if (!(dist > 0.0001) || dist > RAIN_RIPPLE_RADIUS + 2.5*RAIN_RIPPLE_WIDTH) {
                    continue;
                }
                slope += d/dist*rainRipplePacket(dist, age);
            }
        }
    }
    return slope*(RAIN_RIPPLE_SLOPE*fade);
}

//The light a ring's crest adds, as a share of what the water mirrors there: by the slope rainRippleSlope gave, at most RAIN_RIPPLE_SHINE
float rainRippleShine(vec2 slope) {
    return min(length(slope)/RAIN_RIPPLE_SLOPE, 1.0)*RAIN_RIPPLE_SHINE;
}
