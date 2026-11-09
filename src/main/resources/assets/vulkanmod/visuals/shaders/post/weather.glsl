//2026-11-09 (session 6, the user: "vanilla's rain and snow look thick and too vanilla"): the shader-drawn rain and snow that replace
// vanilla's weather columns when the Shader Weather setting is on. No uniforms or bindings; the includer imports water.glsl and
// rainripple.glsl (2026-11-10: weatherImpact shares the rings' drops) and defines, BEFORE importing this:
//   uint weatherMapAt(ivec2 texel)        the precipitation map's packed column (PrecipitationMap; texel = world column & WEATHER_MAP_MASK)
//   vec3 weatherLight(vec3 rel, bool snow) the colour (display values) of a drop at rel (blocks from the camera)
// WeatherMath.java mirrors every const and function here: change both together (WeatherTest compares them). WeatherUniforms writes the
// parameters.
//
// UNITS. Lengths in blocks; directions are WORLD space unit vectors (y up) from the camera; colours are display values (the overlay is
// blended over the final, tone-mapped image, where vanilla's own weather was drawn).
//
// THE MODEL
//   - Drops are world-anchored: each layer's space is cut into cells (1, 2, 4 blocks wide and PERIOD times that tall: the far layers' cells
//     are bigger, so about as many drops cover a pixel at any distance) that fall with the rain (or the snow), and each cell holds one drop
//     at a random spot of its middle half. A view ray walks the cells it crosses (a 3D DDA, Amanatides and Woo 1987) out to the layer's end
//     or the scene, nearest first. So the drops have real parallax as the camera moves, and three layers (to 4, 12 and 32 blocks) give the
//     depth of the rain (Tatarchuk 2006, "Artist-Directable Real-Time Rain Rendering in City Environments": several layers of drops at
//     increasing distance).
//   - A falling drop seen over an exposure time T is a streak (Garg and Nayar 2006, "Photorealistic Rendering of Rain Streaks"; Garg and
//     Nayar 2004, "Detection and Removal of Rain from Videos"): the segment the drop sweeps relative to the camera, u T with u its
//     velocity minus the camera's. So the streaks stretch with the fall and turn with the camera's motion; the drop is brightest at its
//     latest end (the head). Each drop's coverage of a pixel is its streak's distance from the view ray against its width, the width
//     widened to the pixel's footprint and the coverage scaled down by as much (a streak thinner than a pixel stays as bright in sum, so
//     far rain thins out instead of flickering). A drop's radiance is about the light around it (Garg and Nayar 2004: a drop refracts a
//     wide cone of the environment), so it is a pale tint lit as vanilla lights its weather: the lightmap at the drop's sky and block light.
//   - Snowflakes are the same with a slow fall, a sway and a round flake (a short segment of the flake's motion).
//   - 2026-11-10: splashes on the ground (weatherSplash) in place of vanilla's rain particles: tiny rings and flecks where the rain hits the
//     top of an open column, world-anchored and short-lived (see SPLASH_*).
//   - Where: a column's drops fall only above vanilla's MOTION_BLOCKING height (the first free block: no rain under roofs, leaves or in
//     caves; water's surface stops it), in biomes with precipitation, as snow where vanilla's rule says so (Biome.getPrecipitationAt at
//     max(camera y - WEATHER_EVAL_DROP, height), WEATHER_TEMPERATURE_Y and the snow line from the CPU), and only in front of the scene's
//     depth.

//The layers: cell sizes 1 << layer blocks, from WEATHER_NEAR to each layer's end
const int WEATHER_LAYERS = 3;
const float WEATHER_LAYER_END_0 = 4.0;
const float WEATHER_LAYER_END_1 = 12.0;
const float WEATHER_LAYER_END_2 = 32.0;
//No drops nearer than this (the lens); they fade in over WEATHER_NEAR_FADE beyond it
const float WEATHER_NEAR = 0.5;
const float WEATHER_NEAR_FADE = 1.0;
//A drop sits in its cell's middle (margin to 1 - margin): its streak and sway stay inside the cell, so no ray in a neighbour misses it
const float WEATHER_MARGIN = 0.25;
//Cells walked per layer at most (a level ray crosses about 7 in each layer, a steep one a few more)
const int WEATHER_MAX_STEPS = 24;
//Drops per cell (each its own hash: its own spot, phase and presence)
const int WEATHER_DROPS = 2;
//The overlay stops once a pixel is this covered
const float WEATHER_OPAQUE = 0.98;
//A drop behind the scene fades out over this distance in front of it (no hard edge on the terrain)
const float WEATHER_DEPTH_FADE = 0.25;
//The hash's period in blocks (the camera's block arrives modulo this; the map's size divides it)
const int WEATHER_WRAP = 4096;
//The precipitation map: WEATHER_MAP_SIZE columns per side around the camera, addressed by world column & WEATHER_MAP_MASK
const int WEATHER_MAP_SIZE = 128;
const int WEATHER_MAP_MASK = 127;
//The packed column (PrecipitationMap.pack): 12 bits height + WEATHER_Y_BIAS, 12 bits snow line + bias, flags above
const int WEATHER_Y_BIAS = 2048;
const int WEATHER_FLAG_PRECIPITATION = 16777216;
const int WEATHER_FLAG_SNOW_LOW = 33554432;
const int WEATHER_FLAG_KNOWN = 67108864;
//Vanilla's rule: the temperature is the biome's up to y 80 and falls above it; the kind is taken WEATHER_EVAL_DROP below the camera
// (LevelRenderer.renderSnowAndRain's fancy radius) or at the column's height, whichever is higher
const int WEATHER_TEMPERATURE_Y = 80;
const int WEATHER_EVAL_DROP = 10;
//Rain: the near layer's cells RAIN_PERIOD blocks tall (one drop each), RAIN_WIDTH wide (half), RAIN_ALPHA at a streak's core, RAIN_TAIL of
// the head's brightness at its tail. The fall offset wraps at WEATHER_FALL_WRAP periods (320 blocks of rain), and the cells' heights are
// hashed modulo as many cells: the wrap moves every layer's frame by whole cells and draws the same drops
const float RAIN_PERIOD = 1.25;
const float RAIN_WIDTH = 0.011;
const float RAIN_ALPHA = 0.6;
const float RAIN_TAIL = 0.3;
//Snow: the near layer's cells SNOW_PERIOD blocks tall, SNOW_RADIUS, SNOW_ALPHA at its core, swaying SNOW_SWAY blocks either way
const float SNOW_PERIOD = 1.0;
const float SNOW_RADIUS = 0.03;
const float SNOW_ALPHA = 0.85;
const float SNOW_SWAY = 0.1;
const int WEATHER_FALL_WRAP = 256;
//Each drop's width and brightness vary by this much either way
const float WEATHER_SIZE_SPREAD = 0.3;
const float WEATHER_BRIGHT_SPREAD = 0.25;
//2026-11-10 (the user: "the rain bouncing off the grass still looks vanilla"): splashes on the ground in place of vanilla's rain particles.
// The ground is cut into SPLASH_CELL squares; each fires a splash once every SPLASH_PERIOD seconds at a random phase and a new random spot
// (in the cell's middle half), kept with the rain's share of cells (about vanilla's rate in full rain: 6 to 7 a block per second). A splash
// lives SPLASH_LIFE seconds: a ring growing to SPLASH_RADIUS (SPLASH_RING half thick) and SPLASH_FLECKS flecks (SPLASH_FLECK) thrown out to
// SPLASH_THROW times it, fading as (1 - age)^2. Only on the top of a rained-on column (the pixel within SPLASH_SURFACE of its MOTION_BLOCKING
// height: not under roofs, not on water, whose surface lies below the block line and keeps its ripples), out to SPLASH_END blocks, fading over
// the last SPLASH_FADE. The time arrives wrapped at SPLASH_WRAP periods (a multiple of the SPLASH_EVENTS spots each cell cycles through)
const float SPLASH_CELL = 0.5;
const float SPLASH_PERIOD = 0.55;
const float SPLASH_LIFE = 0.16;
const float SPLASH_RADIUS = 0.1;
const float SPLASH_RING = 0.008;
const float SPLASH_ALPHA = 0.65;
const int SPLASH_FLECKS = 4;
const float SPLASH_FLECK = 0.01;
const float SPLASH_THROW = 1.1;
const float SPLASH_SURFACE = 0.02;
const float SPLASH_END = 16.0;
const float SPLASH_FADE = 6.0;
const int SPLASH_EVENTS = 64;
const int SPLASH_WRAP = 1024;
//The ground's footprint grows as the view grazes it: the pixel's angle over max(|dir.y|, SPLASH_GRAZE)
const float SPLASH_GRAZE = 0.2;
//2026-11-10 (the user: "rain hitting the water looks low-effort; rebuild it from the references"): each raindrop that rings the water
// (rainripple.glsl's rainRippleDrop: the same spot and moment) first throws up a crown: for IMPACT_LIFE seconds a rim growing to IMPACT_CROWN
// (IMPACT_RIM half thick) on the surface, a jet rising IMPACT_JET and falling back (IMPACT_JET_WIDTH), and IMPACT_DROPLETS droplets
// (IMPACT_DROPLET) hopping IMPACT_HOP high and IMPACT_THROW out, fading as (1 - age), IMPACT_ALPHA at a core; out to IMPACT_END blocks,
// fading over the last IMPACT_FADE. Only on water a rained-on column holds at its top (the map's water flag)
const float IMPACT_LIFE = 0.12;
const float IMPACT_CROWN = 0.03;
const float IMPACT_RIM = 0.004;
const float IMPACT_JET = 0.045;
const float IMPACT_JET_WIDTH = 0.004;
const int IMPACT_DROPLETS = 2;
const float IMPACT_DROPLET = 0.005;
const float IMPACT_HOP = 0.05;
const float IMPACT_THROW = 0.07;
const float IMPACT_ALPHA = 0.7;
const float IMPACT_END = 10.0;
const float IMPACT_FADE = 4.0;
const int WEATHER_FLAG_WATER = 134217728;
//Hash constants (Teschner et al. 2003 primes, Wellons 2018's finaliser, as aurora.glsl)
const int WEATHER_PRIME_X = 73856093;
const int WEATHER_PRIME_Z = 19349663;
const int WEATHER_PRIME_Y = 50331653;
const int WEATHER_PRIME_SEED = 83492791;

uint weatherHash(ivec3 cell, uint seed) {
    uint h = (uint(cell.x)*uint(WEATHER_PRIME_X)) ^ (uint(cell.y)*uint(WEATHER_PRIME_Y)) ^ (uint(cell.z)*uint(WEATHER_PRIME_Z))
            ^ (seed*uint(WEATHER_PRIME_SEED));
    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    h *= 0x846ca68bu;
    h ^= h >> 16u;
    return h;
}

//Four values 0..1 (one byte each) of a hash
vec4 weatherBytes(uint h) {
    return vec4(float(h & 0xFFu), float((h >> 8u) & 0xFFu), float((h >> 16u) & 0xFFu), float(h >> 24u))/255.0;
}

float weatherLayerEnd(int layer) {
    return layer == 0 ? WEATHER_LAYER_END_0 : (layer == 1 ? WEATHER_LAYER_END_1 : WEATHER_LAYER_END_2);
}

//The packed column's MOTION_BLOCKING height (the first free block)
int weatherHeight(uint texel) {
    return int(texel & 0xFFFu) - WEATHER_Y_BIAS;
}

//0 no precipitation (or not known yet), 1 rain, 2 snow, for the camera's block y
int weatherKind(uint texel, int cameraY) {
    if ((texel & uint(WEATHER_FLAG_KNOWN)) == 0u || (texel & uint(WEATHER_FLAG_PRECIPITATION)) == 0u) {
        return 0;
    }
    int y = max(cameraY - WEATHER_EVAL_DROP, weatherHeight(texel));
    bool snow = y <= WEATHER_TEMPERATURE_Y ? (texel & uint(WEATHER_FLAG_SNOW_LOW)) != 0u
            : y >= int((texel >> 12u) & 0xFFFu) - WEATHER_Y_BIAS;
    return snow ? 2 : 1;
}

//The streak's half vector (blocks): the motion's (u T / 2) scaled down until it fits the cell's margins sideways (less the sway) and
// upward
vec3 weatherHalfStreak(vec3 motion, float cell, float cellHeight, float sway) {
    float side = length(motion.xz);
    float sideLimit = max(cell*WEATHER_MARGIN - sway, 0.0);
    float upLimit = cellHeight*WEATHER_MARGIN;
    float k = 1.0;
    if (side > sideLimit) {
        k = min(k, sideLimit/side);
    }
    if (abs(motion.y) > upLimit) {
        k = min(k, upLimit/abs(motion.y));
    }
    return motion*k;
}

//The closest approach of the view ray (from the camera along unit dir) and the segment a..b: x the distance, y the ray's distance there,
// z where on the segment (0 at a, 1 at b)
vec3 raySegment(vec3 dir, vec3 a, vec3 b) {
    vec3 e = b - a;
    float ee = dot(e, e);
    float de = dot(dir, e);
    float denom = ee - de*de;
    float s = denom > 1e-8 ? clamp((de*dot(dir, a) - dot(e, a))/denom, 0.0, 1.0) : 0.5;
    vec3 q = a + e*s;
    float t = max(dot(dir, q), 0.0);
    return vec3(length(dir*t - q), t, s);
}

//A drop's coverage of the pixel: its half width widened to the pixel's (pixel angle x distance) and the core alpha scaled down by as much
float weatherCoverage(float dist, float width, float t, float pixelAngle, float alpha) {
    float footprint = pixelAngle*t;
    float w = sqrt(width*width + footprint*footprint);
    return alpha*(width/w)*(1.0 - smoothstep(0.0, w, dist));
}

//One of a cell's drops (drop 0..WEATHER_DROPS - 1), blended under acc (premultiplied rgb, alpha). cell: the cell (x, z from the layer's origin cell, y in the falling frame);
// origin: the camera in the layer's frame (x, z from the origin cell's corner, y its height plus the fall); camera: xyz the camera's block
// (x, z modulo WEATHER_WRAP), w its y; frac: xyz the camera within its block, w the pixel's angle; params: x rain's and y snow's share of
// cells with a drop, z the coverage scale, w the sway angle; motion: xyz the streak's half vector u T / 2, w the fall offset
void weatherDrop(inout vec4 acc, vec3 dir, float sceneDist, ivec3 cell, int layer, int drop, bool isSnow, vec3 origin, vec4 camera, vec4 frac,
                 vec4 params, vec4 motion) {
    ivec3 cam = ivec3(camera.xyz);
    float size = float(1 << layer);
    float cellHeight = (isSnow ? SNOW_PERIOD : RAIN_PERIOD)*size;
    int mask = (WEATHER_WRAP >> layer) - 1;
    ivec3 world = ivec3(((cam.x >> layer) + cell.x) & mask, cell.y & ((WEATHER_FALL_WRAP >> layer) - 1), ((cam.z >> layer) + cell.z) & mask);
    uint seed = uint(layer + (isSnow ? 2*WEATHER_LAYERS : 0) + 4*WEATHER_LAYERS*drop);
    vec4 r = weatherBytes(weatherHash(world, seed));
    if (r.w >= (isSnow ? params.y : params.x)) {
        return;
    }
    vec3 a = (vec3(cell) + mix(vec3(WEATHER_MARGIN), vec3(1.0 - WEATHER_MARGIN), r.xzy))*vec3(size, cellHeight, size) - origin;
    //Most drops of a cell the ray crosses pass far from it: skip them before the map and the streak. Conservative (nothing drawn is
    // skipped): the streak, the sway, the widest drop and the pixel's footprint all bound how near the ray must pass the drop's spot
    float sway = isSnow ? SNOW_SWAY : 0.0;
    float t0 = dot(a, dir);
    float reach = length(motion.xyz) + sway;
    if (length(a - dir*t0) > reach + (isSnow ? SNOW_RADIUS : RAIN_WIDTH)*(1.0 + WEATHER_SIZE_SPREAD) + frac.w*(max(t0, 0.0) + reach)) {
        return;
    }
    ivec2 column = cam.xz + ivec2(floor(frac.xz + a.xz));
    uint texel = weatherMapAt(column & WEATHER_MAP_MASK);
    if (weatherKind(texel, int(floor(camera.w))) != (isSnow ? 2 : 1)) {
        return;
    }
    vec4 v = weatherBytes(weatherHash(world, seed + uint(WEATHER_LAYERS)));
    if (isSnow) {
        a.xz += SNOW_SWAY*vec2(sin(params.w + 6.2831853*v.x), cos(0.8*params.w + 6.2831853*v.y));
    }
    vec3 streak = weatherHalfStreak(motion.xyz, size, cellHeight, sway);
    //The whole streak above the column's height (none of it under a roof)
    if (camera.w + a.y - abs(streak.y) < float(weatherHeight(texel))) {
        return;
    }
    vec3 hit = raySegment(dir, a - streak, a + streak);
    float width = (isSnow ? SNOW_RADIUS : RAIN_WIDTH)*(1.0 + WEATHER_SIZE_SPREAD*(2.0*v.z - 1.0));
    float alpha = weatherCoverage(hit.x, width, hit.y, frac.w, isSnow ? SNOW_ALPHA : RAIN_ALPHA);
    float start = layer == 0 ? WEATHER_NEAR : weatherLayerEnd(layer - 1);
    float fadeIn = layer == 0 ? WEATHER_NEAR_FADE : size;
    float end = weatherLayerEnd(layer);
    alpha *= smoothstep(start, start + fadeIn, hit.y)*(1.0 - smoothstep(end - size, end, hit.y));
    alpha *= clamp((sceneDist - hit.y)/WEATHER_DEPTH_FADE, 0.0, 1.0);
    if (!isSnow) {
        alpha *= mix(RAIN_TAIL, 1.0, hit.z);
    }
    alpha *= params.z;
    if (alpha <= 0.0) {
        return;
    }
    vec3 colour = weatherLight(a + streak*(2.0*hit.z - 1.0), isSnow)*(1.0 + WEATHER_BRIGHT_SPREAD*(2.0*v.w - 1.0));
    acc.rgb += (1.0 - acc.a)*alpha*colour;
    acc.a += (1.0 - acc.a)*alpha;
}

//One kind's drops along the view ray: each layer's cells (size x cellHeight x size) in the frame falling with that kind, walked in 3D
// (Amanatides and Woo 1987) from the layer's start to its end or the scene, nearest first
void weatherWalk(inout vec4 acc, vec3 dir, float sceneDist, bool isSnow, vec4 camera, vec4 frac, vec4 params, vec4 motion) {
    ivec3 cam = ivec3(camera.xyz);
    for (int layer = 0; layer < WEATHER_LAYERS; layer++) {
        float tStart = layer == 0 ? WEATHER_NEAR : weatherLayerEnd(layer - 1);
        float tEnd = min(weatherLayerEnd(layer), sceneDist);
        if (tStart >= tEnd) {
            return;
        }
        int size = 1 << layer;
        vec3 cs = vec3(float(size), (isSnow ? SNOW_PERIOD : RAIN_PERIOD)*float(size), float(size));
        //The camera in this layer's frame: x, z from its cell's corner, y its height plus the fall (the frame moves up as the drops fall)
        vec2 corner = vec2(cam.xz & (size - 1)) + frac.xz;
        vec3 origin = vec3(corner.x, camera.w + motion.w, corner.y);
        vec3 p = origin + dir*tStart;
        ivec3 cell = ivec3(floor(p/cs));
        ivec3 stepCell = ivec3(dir.x >= 0.0 ? 1 : -1, dir.y >= 0.0 ? 1 : -1, dir.z >= 0.0 ? 1 : -1);
        vec3 tDelta = vec3(abs(dir.x) > 1e-6 ? cs.x/abs(dir.x) : 1e30, abs(dir.y) > 1e-6 ? cs.y/abs(dir.y) : 1e30,
                abs(dir.z) > 1e-6 ? cs.z/abs(dir.z) : 1e30);
        vec3 boundary = (vec3(cell) + vec3(greaterThan(stepCell, ivec3(0))))*cs;
        vec3 tMax = vec3(abs(dir.x) > 1e-6 ? tStart + (boundary.x - p.x)/dir.x : 1e30, abs(dir.y) > 1e-6 ? tStart + (boundary.y - p.y)/dir.y : 1e30,
                abs(dir.z) > 1e-6 ? tStart + (boundary.z - p.z)/dir.z : 1e30);
        float t = tStart;
        for (int i = 0; i < WEATHER_MAX_STEPS && t < tEnd; i++) {
            for (int k = 0; k < WEATHER_DROPS; k++) {
                weatherDrop(acc, dir, sceneDist, cell, layer, k, isSnow, origin, camera, frac, params, motion);
            }
            if (acc.a > WEATHER_OPAQUE) {
                return;
            }
            if (tMax.x <= tMax.y && tMax.x <= tMax.z) {
                t = tMax.x;
                cell.x += stepCell.x;
                tMax.x += tDelta.x;
            } else if (tMax.y <= tMax.z) {
                t = tMax.y;
                cell.y += stepCell.y;
                tMax.y += tDelta.y;
            } else {
                t = tMax.z;
                cell.z += stepCell.z;
                tMax.z += tDelta.z;
            }
        }
    }
}

//The weather over one pixel (premultiplied rgb, alpha) along dir up to the scene at sceneDist (blocks; very large for the sky): the
// rain's walk and the snow's (each only while the map around the camera holds that kind: its share above 0); the arguments as weatherDrop's
//2026-11-10: the crowns of the drops that ring the water (rainripple.glsl, imported with water.glsl before this file) over a scene pixel at
// rel along dir at dist; water: x the rings' time (seconds, reflection.comp's rainParams.x), y the rain on the water (its rainParams.y; 0:
// none), zw water.glsl's first ripple offset (the rings' anchor); premultiplied rgb and alpha
vec4 weatherImpact(vec3 rel, vec3 dir, float dist, vec4 camera, vec4 frac, vec4 params, vec4 water) {
    if (dist >= IMPACT_END || !(water.y > 0.0)) {
        return vec4(0.0);
    }
    ivec3 cam = ivec3(camera.xyz);
    vec3 w = frac.xyz + rel;
    uint texel = weatherMapAt((cam.xz + ivec2(floor(w.xz))) & WEATHER_MAP_MASK);
    float y = camera.w + rel.y;
    float top = float(weatherHeight(texel));
    if ((texel & uint(WEATHER_FLAG_WATER)) == 0u || weatherKind(texel, int(floor(camera.w))) != 1 || y >= top || y < top - 1.0) {
        return vec4(0.0);
    }
    vec2 pos = rel.xz + water.zw;
    float t = dist/max(abs(dir.y), SPLASH_GRAZE);
    float alpha = 0.0;
    for (int layer = 0; layer < 2; layer++) {
        vec2 q = pos/RAIN_RIPPLE_CELL + float(layer)*0.5;
        vec2 base = floor(q);
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                vec2 centre;
                float age;
                if (!rainRippleDrop(base + vec2(float(i), float(j)), layer, water.y, water.x, centre, age)) {
                    continue;
                }
                float tau = age*RAIN_RIPPLE_CYCLE/IMPACT_LIFE;
                if (tau >= 1.0) {
                    continue;
                }
                //The drop's spot from the camera, on this pixel's surface
                vec2 c = (centre - float(layer)*0.5)*RAIN_RIPPLE_CELL - water.zw;
                vec3 foot = vec3(c.x, rel.y, c.y);
                float fade = 1.0 - tau;
                float a = weatherCoverage(abs(length(rel.xz - c) - IMPACT_CROWN*sqrt(tau)), IMPACT_RIM, t, frac.w, IMPACT_ALPHA);
                vec3 jet = raySegment(dir, foot, foot + vec3(0.0, IMPACT_JET*4.0*tau*(1.0 - tau), 0.0));
                a = max(a, weatherCoverage(jet.x, IMPACT_JET_WIDTH, jet.y, frac.w, IMPACT_ALPHA));
                for (int k = 0; k < IMPACT_DROPLETS; k++) {
                    float angle = 6.2831853*(float(k)/float(IMPACT_DROPLETS) + centre.x*3.7 + centre.y*1.3);
                    vec3 d = foot + vec3(cos(angle)*IMPACT_THROW*tau, IMPACT_HOP*4.0*tau*(1.0 - tau), sin(angle)*IMPACT_THROW*tau);
                    vec3 h = raySegment(dir, d, d);
                    a = max(a, weatherCoverage(h.x, IMPACT_DROPLET, h.y, frac.w, IMPACT_ALPHA));
                }
                alpha = max(alpha, a*fade);
            }
        }
    }
    alpha *= (1.0 - smoothstep(IMPACT_END - IMPACT_FADE, IMPACT_END, dist))*params.z;
    if (alpha <= 0.0) {
        return vec4(0.0);
    }
    return vec4(weatherLight(rel, false)*alpha, alpha);
}

//2026-11-10: the splash over a scene pixel at rel (blocks from the camera; dir its unit view direction, dist its distance), premultiplied
// rgb and alpha; splash: x the time in seconds wrapped at SPLASH_WRAP periods; the other arguments as weatherDrop's
vec4 weatherSplash(vec3 rel, vec3 dir, float dist, vec4 camera, vec4 frac, vec4 params, vec4 splash) {
    if (dist >= SPLASH_END || params.x <= 0.0) {
        return vec4(0.0);
    }
    ivec3 cam = ivec3(camera.xyz);
    vec3 w = frac.xyz + rel;
    ivec2 column = cam.xz + ivec2(floor(w.xz));
    uint texel = weatherMapAt(column & WEATHER_MAP_MASK);
    if (weatherKind(texel, int(floor(camera.w))) != 1 || abs(camera.w + rel.y - float(weatherHeight(texel))) > SPLASH_SURFACE) {
        return vec4(0.0);
    }
    //The ground in world blocks modulo WEATHER_WRAP (exact enough in float: under 4096)
    vec2 g = vec2(cam.xz) + w.xz;
    ivec2 cell = ivec2(floor(g/SPLASH_CELL));
    vec2 inCell = g - vec2(cell)*SPLASH_CELL;
    int mask = int(float(WEATHER_WRAP)/SPLASH_CELL) - 1;
    vec4 r = weatherBytes(weatherHash(ivec3(cell & mask, 0), uint(8*WEATHER_LAYERS)));
    float cycle = splash.x/SPLASH_PERIOD + r.x;
    float event = floor(cycle);
    float age = fract(cycle)*SPLASH_PERIOD/SPLASH_LIFE;
    if (age >= 1.0) {
        return vec4(0.0);
    }
    vec4 e = weatherBytes(weatherHash(ivec3(cell & mask, int(event) & (SPLASH_EVENTS - 1)), uint(8*WEATHER_LAYERS + 1)));
    if (e.w >= params.x) {
        return vec4(0.0);
    }
    vec2 centre = mix(vec2(0.25), vec2(0.75), e.xy)*SPLASH_CELL;
    vec2 d = inCell - centre;
    float radius = SPLASH_RADIUS*sqrt(age);
    float t = dist/max(abs(dir.y), SPLASH_GRAZE);
    float alpha = weatherCoverage(abs(length(d) - radius), SPLASH_RING, t, frac.w, SPLASH_ALPHA);
    for (int k = 0; k < SPLASH_FLECKS; k++) {
        float a = 6.2831853*((float(k) + e.z)/float(SPLASH_FLECKS));
        vec2 p = centre + vec2(cos(a), sin(a))*radius*SPLASH_THROW;
        alpha = max(alpha, weatherCoverage(length(inCell - p), SPLASH_FLECK, t, frac.w, SPLASH_ALPHA));
    }
    alpha *= (1.0 - age)*(1.0 - age)*(1.0 - smoothstep(SPLASH_END - SPLASH_FADE, SPLASH_END, dist))*params.z;
    if (alpha <= 0.0) {
        return vec4(0.0);
    }
    return vec4(weatherLight(rel, false)*alpha, alpha);
}

vec4 weatherPixel(vec3 dir, float sceneDist, vec4 camera, vec4 frac, vec4 params, vec4 rain, vec4 snow) {
    vec4 acc = vec4(0.0);
    if (params.x > 0.0) {
        weatherWalk(acc, dir, sceneDist, false, camera, frac, params, rain);
    }
    if (params.y > 0.0 && acc.a <= WEATHER_OPAQUE) {
        weatherWalk(acc, dir, sceneDist, true, camera, frac, params, snow);
    }
    return acc;
}
