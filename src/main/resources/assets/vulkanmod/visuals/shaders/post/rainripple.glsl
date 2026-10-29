//2026-10-29 (the user: add an effect where raindrops hit the water): while it rains, drops ring the open water. Each cell of a
// world-anchored grid (RAIN_RIPPLE_CELL blocks, two staggered layers) holds one drop that lands at a hashed spot and time in every
// RAIN_RIPPLE_CYCLE seconds; its ring widens to RAIN_RIPPLE_RADIUS and fades. The rings tilt the water's normal (reflection.comp),
// so they show in the mirrored sky and the refracted floor. Import after water.glsl (its hash); no uniforms or bindings.
// RainRippleMath mirrors every const and function here (RainRippleTest).

//The grid: a drop per cell per cycle in each layer; the second layer is offset by half a cell and half a cycle
const float RAIN_RIPPLE_CELL = 0.8;
const float RAIN_RIPPLE_CYCLE = 1.1;
//A ring: radius at the end of its life, half width of its crest (blocks), slope at a fresh crest
const float RAIN_RIPPLE_RADIUS = 0.5;
const float RAIN_RIPPLE_WIDTH = 0.06;
const float RAIN_RIPPLE_SLOPE = 0.6;
//A crest catches this share of the light the water mirrors there (under an overcast sky the tilt alone hardly shows)
const float RAIN_RIPPLE_SHINE = 0.35;
//The share of cells with a drop at full rain (thunder included)
const float RAIN_RIPPLE_DENSITY = 0.85;
//A ring fades out as its crest covers fewer pixels: none at RAIN_RIPPLE_FADE_NONE pixels across, full from RAIN_RIPPLE_FADE_FULL
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

//The slope (d height / d x, d height / d z) of the rings at pos (blocks, the water's x, z plus water.glsl's first ripple offset, so
// anchored to the world), at time (seconds), for rain 0..1 (the share of cells with a drop), footprint the blocks one pixel covers
vec2 rainRippleSlope(vec2 pos, float time, float rain, float footprint) {
    float r = clamp(rain, 0.0, 1.0)*RAIN_RIPPLE_DENSITY;
    float pixels = footprint > 0.000001 ? RAIN_RIPPLE_WIDTH*2.0/footprint : 1000000.0;
    float fade = smoothstep(RAIN_RIPPLE_FADE_NONE, RAIN_RIPPLE_FADE_FULL, pixels);
    if (!(r > 0.0) || fade <= 0.0) {
        return vec2(0.0);
    }
    float n = rainRippleCells();
    vec2 slope = vec2(0.0);
    for (int layer = 0; layer < 2; layer++) {
        vec2 q = pos/RAIN_RIPPLE_CELL + float(layer)*0.5;
        vec2 base = floor(q);
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                vec2 c = base + vec2(float(i), float(j));
                vec2 w = c - n*floor(c/n);
                uint h = rippleHash(ivec2(w), uint(7 + layer));
                if (rainRippleHash(h, 0) > r) {
                    continue;
                }
                vec2 centre = c + 0.2 + 0.6*vec2(rainRippleHash(h, 1), rainRippleHash(h, 2));
                float age = fract(time/RAIN_RIPPLE_CYCLE + float(layer)*0.5 + rainRippleHash(h, 1)*7.0 + rainRippleHash(h, 2)*3.0);
                vec2 d = (q - centre)*RAIN_RIPPLE_CELL;
                float dist = length(d);
                if (!(dist > 0.0001) || dist > RAIN_RIPPLE_RADIUS + 3.0*RAIN_RIPPLE_WIDTH) {
                    continue;
                }
                //The crest: a Gaussian ring at age x RADIUS; its height's derivative along the radius, fading as it widens
                float x = (dist - age*RAIN_RIPPLE_RADIUS)/RAIN_RIPPLE_WIDTH;
                float life = (1.0 - age)*(1.0 - age);
                slope += d/dist*(-2.0*x*exp(-x*x))*life;
            }
        }
    }
    return slope*(RAIN_RIPPLE_SLOPE*fade);
}

//The light a ring's crest adds, as a share of what the water mirrors there: by the slope rainRippleSlope gave, at most RAIN_RIPPLE_SHINE
float rainRippleShine(vec2 slope) {
    return min(length(slope)/RAIN_RIPPLE_SLOPE, 1.0)*RAIN_RIPPLE_SHINE;
}
