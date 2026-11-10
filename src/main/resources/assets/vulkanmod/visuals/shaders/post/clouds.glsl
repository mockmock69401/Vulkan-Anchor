//Visuals P7h far clouds (design 2026-10-12 P7h 3.1, 3.2). CloudMath.java mirrors this for the tests: change both together.
// The including shader declares sampler2D cloudMap (clouds.png, 1 on cloud cells), vec4 cloudShape (the layer's bottom relative
// to the camera, its thickness, the LOD distance, 1 = on) and vec4 cloudOffset (the camera's x, z on the cloud map in blocks,
// vanilla's drift included). Positions are relative to the camera, y up
const float CLOUD_MAP = 256.0;
const float CLOUD_CELL = 12.0;
//Design 3.1: the cells blend over CLOUD_ROUND cells either side of their boundary; the top and bottom thin over CLOUD_EDGE blocks;
// the 0.5 threshold is softened by CLOUD_SOFT
const float CLOUD_ROUND = 0.25;
const float CLOUD_EDGE = 1.0;
const float CLOUD_SOFT = 0.1;
//Design 3.2: samples every CLOUD_STEP blocks, CLOUD_MIN_SAMPLES to CLOUD_MAX_SAMPLES; CLOUD_DENSITY per block
const float CLOUD_STEP = 6.0;
const int CLOUD_MIN_SAMPLES = 12;
const int CLOUD_MAX_SAMPLES = 48;
const float CLOUD_DENSITY = 0.6;
//Design 3.2: the light read CLOUD_LIGHT_NEAR and CLOUD_LIGHT_FAR blocks toward the sun (moon), never darker than CLOUD_SHADE_MIN
const float CLOUD_LIGHT_NEAR = 1.5;
const float CLOUD_LIGHT_FAR = 4.0;
const float CLOUD_SHADE_K = 1.2;
const float CLOUD_SHADE_MIN = 0.65;
//Final review: below CLOUD_SHADE_LEVEL of light height the shading fades out (the sun and moon swap at the horizon)
const float CLOUD_SHADE_LEVEL = 0.1;
//Gate G7h: the cloud colour is dimmed to vanilla's side-face brightness before the highlights are expanded (the including
// shaders apply it; white at 1.0 expands 5x and blooms)
const float CLOUD_ALBEDO = 0.8;
//Design 3.2: faded out from CLOUD_FADE_START of the LOD distance; steep rays count as at least CLOUD_MIN_HORIZONTAL level for the LOD cut
const float CLOUD_FADE_START = 0.75;
const float CLOUD_MIN_HORIZONTAL = 0.01;

float cloudTexel(vec2 cell) {
    return texelFetch(cloudMap, ivec2(mod(cell, CLOUD_MAP)), 0).r;
}

//Design 3.1: the horizontal cover 0..1 at camera-relative xz: the four nearest cells blended by smoothstep around their boundaries
float cloudCover(vec2 xz) {
    vec2 u = (cloudOffset.xy + xz)/CLOUD_CELL - 0.5;
    vec2 i = floor(u);
    vec2 w = smoothstep(0.5 - CLOUD_ROUND, 0.5 + CLOUD_ROUND, u - i);
    return mix(mix(cloudTexel(i), cloudTexel(i + vec2(1.0, 0.0)), w.x), mix(cloudTexel(i + vec2(0.0, 1.0)), cloudTexel(i + vec2(1.0)), w.x), w.y);
}

//Design 3.1: the density at camera-relative p: the cover times the layer's rounded height profile, thresholded softly at 0.5
float cloudDensity(vec3 p) {
    float h = p.y - cloudShape.x;
    if (h < 0.0 || h > cloudShape.y) {
        return 0.0;
    }
    float v = smoothstep(0.0, CLOUD_EDGE, min(h, cloudShape.y - h));
    return smoothstep(0.5 - CLOUD_SOFT, 0.5 + CLOUD_SOFT, cloudCover(p.xz)*v);
}

//Design 3.2: the ray's span inside the layer, cut to [0, maxDist] and to the LOD distance horizontally; y <= x: none
vec2 cloudInterval(vec3 origin, vec3 dir, float maxDist) {
    float bottom = cloudShape.x;
    float top = cloudShape.x + cloudShape.y;
    float t0 = 0.0;
    float t1 = maxDist;
    if (abs(dir.y) < 0.000001) {
        if (origin.y < bottom || origin.y > top) {
            return vec2(0.0);
        }
    } else {
        float ta = (bottom - origin.y)/dir.y;
        float tb = (top - origin.y)/dir.y;
        t0 = max(min(ta, tb), 0.0);
        t1 = min(max(ta, tb), maxDist);
    }
    t1 = min(t1, cloudShape.z/max(length(dir.xz), CLOUD_MIN_HORIZONTAL));
    return vec2(t0, t1);
}

//Design 3.2: front to back through the layer, each sample shaded toward lightDir and faded by its horizontal distance;
// x: the light-weighted coverage (times the cloud colour: the premultiplied colour), y: the coverage, z: the coverage-weighted distance
vec3 cloudMarch(vec3 origin, vec3 dir, float maxDist, vec3 lightDir) {
    vec2 span = cloudInterval(origin, dir, maxDist);
    if (cloudShape.w < 0.5 || span.y <= span.x) {
        return vec3(0.0);
    }
    float len = span.y - span.x;
    int n = int(clamp(ceil(len/CLOUD_STEP), float(CLOUD_MIN_SAMPLES), float(CLOUD_MAX_SAMPLES)));
    float dt = len/float(n);
    float shadeMin = mix(1.0, CLOUD_SHADE_MIN, smoothstep(0.0, CLOUD_SHADE_LEVEL, abs(lightDir.y)));
    float light = 0.0;
    float trans = 1.0;
    float dsum = 0.0;
    float wsum = 0.0;
    for (int i = 0; i < CLOUD_MAX_SAMPLES; i++) {
        if (i >= n || trans < 0.01) {
            break;
        }
        float t = span.x + (float(i) + 0.5)*dt;
        vec3 p = origin + dir*t;
        float d = cloudDensity(p);
        if (d <= 0.0) {
            continue;
        }
        float fade = 1.0 - smoothstep(CLOUD_FADE_START, 1.0, length(p.xz)/cloudShape.z);
        float a = (1.0 - exp(-d*CLOUD_DENSITY*dt))*fade;
        float shade = exp(-(cloudDensity(p + lightDir*CLOUD_LIGHT_NEAR) + cloudDensity(p + lightDir*CLOUD_LIGHT_FAR))*CLOUD_SHADE_K);
        float w = a*trans;
        light += w*mix(shadeMin, 1.0, shade);
        dsum += w*t;
        wsum += w;
        trans *= 1.0 - a;
    }
    return vec3(light, 1.0 - trans, wsum > 0.0 ? dsum/wsum : 0.0);
}

//Session 3 (2026-11-09): the realistic style (CloudMath's realistic part mirrors this). cloudOffset.z: its whole part the style's
// quality (0: vanilla's cell shape above, 1..3 low, medium, high), its fraction the frame's jitter phase (0 without TAA); cloudOffset.w
// the share of the sky the cumulus may cover (CloudUniforms: REAL_COVER x the coverage setting, more in rain). In this style
// cloudShape.x is the cumulus base relative to the camera (CloudMath.REAL_LIFT above vanilla's layer) and y its thickness.
// cloudMap's g, b, a channels hold CloudMath.noiseMap, each equalised to an even 0..1 and repeating every CLOUD_MAP texels (vanilla's
// 3072-block period, so the offset's wrap never shows): g the weather (where cumulus grow), b the cirrus streaks, a the towers' height.
// After 3b (2026-11-09) the cloud size and count settings live on the CPU side only: the size scales the g and a noises' cells and
// cloudShape.y (CloudMath.realThickness), the count removes whole clumps from g (CloudMath.noiseMap); nothing here reads them.
// The model follows Schneider and Vos 2015 ("The Real-time Volumetric Cloudscapes of Horizon Zero Dawn", SIGGRAPH Advances in Real-Time
// Rendering): a 2D coverage, a height profile with rounded bases and domed tops, 3D noise eroding the edges, Beer's law toward the light
// and their powder term. The light is integrated per segment as in Hillaire 2016 ("Physically Based Sky, Atmosphere and Cloud
// Rendering in Frostbite"), with the multiple-scattering octaves of Wrenninge, Kulla and Lundqvist 2013 ("Oz: The Great and Volumetric").
//The cumulus may cover this share of the sky at coverage 100% (before the noise erodes the edges)
const float REAL_COVER = 0.38;
//A tower's top: REAL_TOP_MIN of the layer where the weather is weakest, the whole layer at its strongest; the a channel lowers it by
// up to REAL_TOWER
const float REAL_TOP_MIN = 0.3;
const float REAL_TOWER = 0.45;
//Session 3b (2026-11-09): the density rises over REAL_BOTTOM of a tower's height from its base and thins from REAL_ROUND of it to the top.
// The wide bottom ramp lets the erosion reach the base: where the weather is weak (a clump's edges) the noise eats more of the ramp, so
// the base rises toward the edges and is lumpy, not cut flat at one height (session 3's 0.08 ramp left 94% of the bases within 3 blocks
// of each other: the user's "sliced" clouds). Each tower's base also sits up to REAL_BASE_LIFT of the layer higher where the a channel
// is low (shorter towers start higher), so neighbouring clumps differ in base as well as in top
const float REAL_BOTTOM = 0.35;
const float REAL_ROUND = 0.35;
const float REAL_BASE_LIFT = 0.25;
//The 3D noise eats up to REAL_ERODE from the edges: octaves of REAL_NOISE_CELL, REAL_NOISE_CELL2 and REAL_NOISE_CELL3 blocks (all divide
// the period), the finer two folded into lumps (1 - |2n - 1|: low inside rounded lumps, 1 along the creases, so the edge bulges into
// cauliflower) and weighted REAL_NOISE_DETAIL and REAL_NOISE_DETAIL2 (3b: the third octave, and lumps instead of session 3's billows,
// which left sharp ridges). Without the noise (the light march, the reflection) REAL_NOISE_MEAN is taken: the three octaves' mean
const float REAL_ERODE = 0.6;
const float REAL_NOISE_CELL = 48.0;
const float REAL_NOISE_CELL2 = 16.0;
const float REAL_NOISE_CELL3 = 6.0;
const float REAL_NOISE_DETAIL = 0.45;
const float REAL_NOISE_DETAIL2 = 0.25;
const float REAL_NOISE_MEAN = 0.6;
//Extinction per block at density 1
const float REAL_DENSITY = 0.45;
//Samples through the layer by quality (low, medium, high), at most one per REAL_STEP blocks of the span; the light march's samples
// reach REAL_LIGHT_STEP x 1, 4, 9, 16 blocks toward the light
const int REAL_SAMPLES_LOW = 16;
const int REAL_SAMPLES_MEDIUM = 32;
const int REAL_SAMPLES_HIGH = 56;
const int REAL_SAMPLES_MIN = 8;
const float REAL_STEP = 4.0;
const int REAL_LIGHT_LOW = 2;
const int REAL_LIGHT_MEDIUM = 3;
const int REAL_LIGHT_HIGH = 4;
const float REAL_LIGHT_STEP = 6.0;
//The phase: a forward lobe (the silver lining toward the sun) mixed with a REAL_BACK share of a backward one (Henyey-Greenstein,
// scaled so an even phase is 1); the multiple-scattering octaves scale the depth, the share and the lobes by REAL_OCTAVE each
const float REAL_FORWARD_G = 0.6;
const float REAL_BACK_G = -0.25;
const float REAL_BACK = 0.3;
const int REAL_OCTAVES = 3;
const float REAL_OCTAVE = 0.5;
//The powder term (Schneider and Vos 2015): thin cloud facing away from the sun is darker, by up to REAL_POWDER, over
// REAL_POWDER_DEPTH optical depth per unit extinction
const float REAL_POWDER = 0.6;
const float REAL_POWDER_DEPTH = 8.0;
//The sky light is REAL_AMBIENT_LOW of the top's at the base (the ground below is darker than the sky above)
const float REAL_AMBIENT_LOW = 0.25;
//Without a light march (the reflection) the depth toward the light is REAL_FLAT_DEPTH times the share of the layer above the sample
// (2026-11-09: matched to the light march's mean light per unit of coverage on reflected rays, 0.5)
const float REAL_FLAT_DEPTH = 2.0;
const int REAL_REFLECT_SAMPLES = 12;
//The cirrus: a sheet REAL_CIRRUS_HEIGHT blocks above the cumulus base, covering REAL_CIRRUS_COVER of the sky at coverage 100%, at most
// REAL_CIRRUS_OPACITY opaque, scattering forward with REAL_CIRRUS_G (ice), and REAL_CIRRUS_AMBIENT of the sky light
const float REAL_CIRRUS_HEIGHT = 480.0;
const float REAL_CIRRUS_COVER = 0.3;
const float REAL_CIRRUS_OPACITY = 0.45;
const float REAL_CIRRUS_G = 0.7;
const float REAL_CIRRUS_AMBIENT = 1.2;
//The 3D noise's hash: Teschner et al. 2003 primes combine the cell, then a 32-bit finaliser (Wellons 2018, public domain)
const int REAL_PRIME_X = 73856093;
const int REAL_PRIME_Y = 19349663;
const int REAL_PRIME_Z = 83492791;

//Session 11: cloudOffset.z's whole part from ANIME_Z on is the anime style (ANIME_Z plus its quality 0..2)
const int ANIME_Z = 4;

//True in the realistic style
bool cloudRealistic() {
    return cloudOffset.z >= 1.0 && cloudOffset.z < float(ANIME_Z);
}

//True in the anime style (session 11)
bool cloudAnime() {
    return cloudOffset.z >= float(ANIME_Z);
}

//cloudMap bilinearly at m (blocks on the cloud map), wrapped: the noise channels between texel centres
vec4 cloudNoise(vec2 m) {
    vec2 u = m/CLOUD_CELL - 0.5;
    vec2 i = floor(u);
    vec2 f = u - i;
    vec4 a = texelFetch(cloudMap, ivec2(mod(i, CLOUD_MAP)), 0);
    vec4 b = texelFetch(cloudMap, ivec2(mod(i + vec2(1.0, 0.0), CLOUD_MAP)), 0);
    vec4 c = texelFetch(cloudMap, ivec2(mod(i + vec2(0.0, 1.0), CLOUD_MAP)), 0);
    vec4 d = texelFetch(cloudMap, ivec2(mod(i + vec2(1.0), CLOUD_MAP)), 0);
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

//0..1 at lattice corner c, x and z wrapped into n cells
float realCorner(vec3 c, float n) {
    vec3 w = vec3(c.x - n*floor(c.x/n), c.y, c.z - n*floor(c.z/n));
    ivec3 i = ivec3(w);
    uint h = (uint(i.x)*uint(REAL_PRIME_X)) ^ (uint(i.y)*uint(REAL_PRIME_Y)) ^ (uint(i.z)*uint(REAL_PRIME_Z));
    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    h *= 0x846ca68bu;
    h ^= h >> 16u;
    return float(h & 0xFFFFFFu)/16777215.0;
}

//3D value noise 0..1 at x (lattice units), repeating every n cells along x and z
float realValueNoise(vec3 x, float n) {
    vec3 i = floor(x);
    vec3 f = x - i;
    vec3 u = f*f*(3.0 - 2.0*f);
    float a = mix(realCorner(i, n), realCorner(i + vec3(1.0, 0.0, 0.0), n), u.x);
    float b = mix(realCorner(i + vec3(0.0, 1.0, 0.0), n), realCorner(i + vec3(1.0, 1.0, 0.0), n), u.x);
    float c = mix(realCorner(i + vec3(0.0, 0.0, 1.0), n), realCorner(i + vec3(1.0, 0.0, 1.0), n), u.x);
    float d = mix(realCorner(i + vec3(0.0, 1.0, 1.0), n), realCorner(i + vec3(1.0), n), u.x);
    return mix(mix(a, b, u.y), mix(c, d, u.y), u.z);
}

//1 - |2n - 1| of the value noise at x (lattice units, repeating every n cells): low inside rounded lumps, 1 along the creases between them
float realLumps(vec3 x, float n) {
    return 1.0 - abs(2.0*realValueNoise(x, n) - 1.0);
}

//The erosion noise 0..1 at q (x, z on the cloud map, y above the base): octaves 0 its mean, 1 the first octave, 2 and 3 with the lumps
float realErosion(vec3 q, int octaves) {
    if (octaves <= 0) {
        return REAL_NOISE_MEAN;
    }
    float e = realValueNoise(q/REAL_NOISE_CELL, CLOUD_MAP*CLOUD_CELL/REAL_NOISE_CELL);
    if (octaves >= 2) {
        e = mix(e, realLumps(q/REAL_NOISE_CELL2, CLOUD_MAP*CLOUD_CELL/REAL_NOISE_CELL2), REAL_NOISE_DETAIL);
    }
    if (octaves >= 3) {
        e = mix(e, realLumps(q/REAL_NOISE_CELL3, CLOUD_MAP*CLOUD_CELL/REAL_NOISE_CELL3), REAL_NOISE_DETAIL2);
    }
    return e;
}

//2026-11-10 (session 7): each octave after the first mixes the erosion toward a noise of 0..1, so it keeps at least (1 - its detail)
// of what it was: where REAL_ERODE times what is left in the worst case already reaches the density d, the density is 0 whatever the
// later octaves add, and they are skipped (the cloud's fringes; the same 0 as the full sum). REAL_KEEP_MARGIN leaves room for rounding
const float REAL_KEEP_MARGIN = 0.999;

//realErosion for a density d: -1 where the octaves evaluated so far prove the density 0, else the erosion
float realErosionFor(vec3 q, int octaves, float d) {
    if (octaves <= 0) {
        return REAL_NOISE_MEAN;
    }
    float e = realValueNoise(q/REAL_NOISE_CELL, CLOUD_MAP*CLOUD_CELL/REAL_NOISE_CELL);
    if (octaves >= 2) {
        float keep = (1.0 - REAL_NOISE_DETAIL)*(octaves >= 3 ? 1.0 - REAL_NOISE_DETAIL2 : 1.0)*REAL_KEEP_MARGIN;
        if (REAL_ERODE*e*keep >= d) {
            return -1.0;
        }
        e = mix(e, realLumps(q/REAL_NOISE_CELL2, CLOUD_MAP*CLOUD_CELL/REAL_NOISE_CELL2), REAL_NOISE_DETAIL);
    }
    if (octaves >= 3) {
        if (REAL_ERODE*e*(1.0 - REAL_NOISE_DETAIL2)*REAL_KEEP_MARGIN >= d) {
            return -1.0;
        }
        e = mix(e, realLumps(q/REAL_NOISE_CELL3, CLOUD_MAP*CLOUD_CELL/REAL_NOISE_CELL3), REAL_NOISE_DETAIL2);
    }
    return e;
}

//The cumulus density 0..1 at camera-relative p: the weather over its coverage, the tower's height profile, eroded by the noise
float realDensity(vec3 p, int octaves) {
    float h = (p.y - cloudShape.x)/cloudShape.y;
    float f = min(cloudOffset.w, 1.0);
    if (!(h > 0.0 && h < 1.0) || !(f > 0.0)) {
        return 0.0;
    }
    vec2 m = cloudOffset.xy + p.xz;
    vec4 n = cloudNoise(m);
    float s = (n.g - 1.0 + f)/f;
    if (s <= 0.0) {
        return 0.0;
    }
    float lift = REAL_BASE_LIFT*(1.0 - n.a);
    float hp = (h - lift)/((1.0 - lift)*mix(REAL_TOP_MIN, 1.0, s)*mix(1.0 - REAL_TOWER, 1.0, n.a));
    if (!(hp > 0.0 && hp < 1.0)) {
        return 0.0;
    }
    float d = s*smoothstep(0.0, REAL_BOTTOM, hp)*(1.0 - smoothstep(REAL_ROUND, 1.0, hp));
    float er = realErosionFor(vec3(m.x, p.y - cloudShape.x, m.y), octaves, d);
    if (er < 0.0) {
        return 0.0;
    }
    float e = REAL_ERODE*er;
    return max((d - e)/(1.0 - e), 0.0);
}

//Henyey-Greenstein scaled so an even phase is 1
float realHg(float c, float g) {
    float k = 1.0 + g*g - 2.0*g*c;
    return (1.0 - g*g)/(k*sqrt(k));
}

//The realistic clouds along dir from origin up to maxDist: the cumulus (samples through the layer, lights samples toward lightDir or,
// with 0, the flat depth; octaves of erosion noise) from jitter 0..1 along each step, then the cirrus behind them.
// Returns (sunlit weight, sky-lit weight, coverage, coverage-weighted distance)
vec4 realMarch(vec3 origin, vec3 dir, float maxDist, vec3 lightDir, float jitter, int samples, int lights, int octaves) {
    if (cloudShape.w < 0.5) {
        return vec4(0.0);
    }
    float level = smoothstep(0.0, CLOUD_SHADE_LEVEL, abs(lightDir.y));
    float c = dot(dir, lightDir);
    float phase[REAL_OCTAVES];
    float g = level;
    for (int k = 0; k < REAL_OCTAVES; k++) {
        phase[k] = mix(realHg(c, REAL_FORWARD_G*g), realHg(c, REAL_BACK_G*g), REAL_BACK);
        g *= REAL_OCTAVE;
    }
    float powder = REAL_POWDER*(0.5 - 0.5*c);
    float sun = 0.0;
    float amb = 0.0;
    float trans = 1.0;
    float dsum = 0.0;
    float wsum = 0.0;
    vec2 span = cloudInterval(origin, dir, maxDist);
    if (span.y > span.x) {
        float len = span.y - span.x;
        int n = int(clamp(ceil(len/REAL_STEP), float(REAL_SAMPLES_MIN), float(max(samples, REAL_SAMPLES_MIN))));
        float dt = len/float(n);
        for (int i = 0; i < REAL_SAMPLES_HIGH; i++) {
            if (i >= n || trans < 0.01) {
                break;
            }
            float t = span.x + (float(i) + jitter)*dt;
            vec3 p = origin + dir*t;
            float d = realDensity(p, octaves);
            if (d <= 0.0) {
                continue;
            }
            float fade = 1.0 - smoothstep(CLOUD_FADE_START, 1.0, length(p.xz)/cloudShape.z);
            float sigma = d*REAL_DENSITY;
            float a = (1.0 - exp(-sigma*dt))*fade;
            float h = clamp((p.y - cloudShape.x)/cloudShape.y, 0.0, 1.0);
            float od = 0.0;
            if (lights > 0) {
                float prev = 0.0;
                for (int j = 1; j <= REAL_LIGHT_HIGH; j++) {
                    if (j > lights) {
                        break;
                    }
                    float reach = REAL_LIGHT_STEP*float(j*j);
                    od += realDensity(p + lightDir*(0.5*(reach + prev)), 0)*(reach - prev);
                    prev = reach;
                }
                od *= REAL_DENSITY;
            } else {
                od = REAL_FLAT_DEPTH*(1.0 - h);
            }
            od *= level;
            float s = 0.0;
            float share = 1.0;
            float depth = 1.0;
            for (int k = 0; k < REAL_OCTAVES; k++) {
                s += share*exp(-od*depth)*phase[k];
                share *= REAL_OCTAVE;
                depth *= REAL_OCTAVE;
            }
            s *= 1.0 - powder*exp(-sigma*REAL_POWDER_DEPTH);
            float w = a*trans;
            sun += w*s;
            amb += w*mix(REAL_AMBIENT_LOW, 1.0, h);
            dsum += w*t;
            wsum += w;
            trans *= 1.0 - a;
        }
    }
    float top = cloudShape.x + REAL_CIRRUS_HEIGHT;
    if (trans >= 0.01 && dir.y > 0.0 && origin.y < top) {
        float t = (top - origin.y)/dir.y;
        vec3 q = origin + dir*t;
        float r = length(q.xz)/cloudShape.z;
        float fc = min(REAL_CIRRUS_COVER*cloudOffset.w/REAL_COVER, 0.95);
        if (t < maxDist && r < 1.0 && fc > 0.0) {
            float v = clamp((cloudNoise(cloudOffset.xy + q.xz).b - 1.0 + fc)/fc, 0.0, 1.0);
            float a = v*v*REAL_CIRRUS_OPACITY*(1.0 - smoothstep(CLOUD_FADE_START, 1.0, r));
            float w = a*trans;
            sun += w*mix(realHg(c, REAL_CIRRUS_G*level), 1.0, 0.5);
            amb += w*REAL_CIRRUS_AMBIENT;
            dsum += w*t;
            wsum += w;
            trans *= 1.0 - a;
        }
    }
    return vec4(sun, amb, 1.0 - trans, wsum > 0.0 ? dsum/wsum : 0.0);
}

//Session 11 (2026-11-10): the anime style (CloudMath's anime part mirrors this), after Fantasy's clouds as seen in game (refs/anchor
// observations.md, session 11: smooth pillows with clean outlines, a white sunlit top and a sky-blue underside, peach at sunset, dark
// silhouettes at night; seen only, no code read). The clumps are the realistic style's: the g channel over the coverage cloudOffset.w,
// so the cloud size, count and coverage settings shape them alike; no erosion. Each clump is a pillow over the cloud map: its bottom and
// top (above the layer's base, cloudShape.x; cloudShape.y thick) meet ANIME_RIM up at its outline and part as the weather s rises inward,
// the top as sqrt(s / ANIME_EDGE) (a rounded side) toward ANIME_TOP_MIN..1 of the layer (with s, lowered by up to ANIME_TOWER where the
// a channel is low), the bottom down to the base over ANIME_BASE_EDGE (a flat base with a rounded rim). Two octaves of billowed value
// noise (ANIME_PUFF_CELL, ANIME_PUFF_CELL2 blocks; creases softened over ANIME_CREASE) raise the top in rounded puffs (ANIME_PUFF) and
// scallop the outline (ANIME_SCALLOP off the weather). The march finds the first sample inside a pillow and halves toward its surface;
// the colour is cel-shaded from the surface's normal: N.L wrapped by ANIME_WRAP, lit above ANIME_TERMINATOR (ANIME_SHADOW of the sun in
// shadow), ANIME_HIGHLIGHT more above ANIME_HIGHLIGHT_AT, bands ANIME_BAND_SOFT wide, and a rim ANIME_RIM_GAIN bright where the surface
// turns from the view (brighter toward the sun). Opaque inside the LOD fade. The light is the realistic style's (CloudUniforms: the
// sky's sunlight, kept bright at sunset, and the sky's colour above, both times the cloud colour)
const float ANIME_RIM = 0.15;
const float ANIME_EDGE = 0.35;
const float ANIME_BASE_EDGE = 0.12;
const float ANIME_TOP_MIN = 0.4;
const float ANIME_TOWER = 0.35;
const float ANIME_PUFF_CELL = 24.0;
const float ANIME_PUFF_CELL2 = 12.0;
const float ANIME_PUFF_DETAIL = 0.2;
const float ANIME_PUFF = 0.35;
const float ANIME_SCALLOP = 0.3;
const float ANIME_CREASE = 0.5;
//Samples every ANIME_STEP blocks of the span, ANIME_SAMPLES_MIN up to the quality's count; ANIME_REFINE halvings toward the surface
// (ANIME_REFLECT_REFINE in the reflection); the normal from the heights ANIME_NORMAL_STEP blocks either side (half a map texel: it
// smooths the weather's texel creases out of the bands)
const float ANIME_STEP = 3.0;
const int ANIME_SAMPLES_LOW = 24;
const int ANIME_SAMPLES_MEDIUM = 40;
const int ANIME_SAMPLES_HIGH = 64;
const int ANIME_SAMPLES_MIN = 12;
const int ANIME_REFINE = 6;
const int ANIME_REFLECT_REFINE = 2;
const float ANIME_NORMAL_STEP = 6.0;
const float ANIME_NORMAL_LOW = 0.2;
const float ANIME_NORMAL_HIGH = 0.8;
const float ANIME_WRAP = 0.35;
const float ANIME_TERMINATOR = 0.2;
const float ANIME_HIGHLIGHT_AT = 0.8;
const float ANIME_BAND_SOFT = 0.04;
const float ANIME_SHADOW = 0.1;
const float ANIME_HIGHLIGHT = 0.3;
//While the light is at the horizon (the sun and moon swap) the bands give way to ANIME_FLAT
const float ANIME_FLAT = 0.6;
const float ANIME_RIM_AT = 0.8;
const float ANIME_RIM_SOFT = 0.06;
const float ANIME_RIM_GAIN = 0.5;
const float ANIME_RIM_BACK = 0.3;

//2D value noise 0..1 at x (lattice units) on lattice layer y, repeating every n cells
float animeNoise(vec2 x, float y, float n) {
    vec2 i = floor(x);
    vec2 f = x - i;
    vec2 u = f*f*(3.0 - 2.0*f);
    float a = realCorner(vec3(i.x, y, i.y), n);
    float b = realCorner(vec3(i.x + 1.0, y, i.y), n);
    float c = realCorner(vec3(i.x, y, i.y + 1.0), n);
    float d = realCorner(vec3(i.x + 1.0, y, i.y + 1.0), n);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

//|2n - 1| softened over ANIME_CREASE, 0..1: rounded puffs, low along the creases between them
float animeBillow(float n) {
    float x = 2.0*n - 1.0;
    return (sqrt(x*x + ANIME_CREASE*ANIME_CREASE) - ANIME_CREASE)/(sqrt(1.0 + ANIME_CREASE*ANIME_CREASE) - ANIME_CREASE);
}

//(bottom, top) above the layer's base in blocks at m (blocks on the cloud map); top below bottom where no cloud stands. For a sample h
// blocks above the base (negative: none) it returns (0, -1) as soon as the weather alone proves h outside (above the highest top or
// below the lowest bottom any puff could give), before the puffs' hashes: the same inside-or-not answer, cheaper
vec2 animeShape(vec2 m, float h) {
    float f = min(cloudOffset.w, 1.0);
    if (!(f > 0.0)) {
        return vec2(0.0, -1.0);
    }
    vec2 u = m/CLOUD_CELL - 0.5;
    vec2 i = floor(u);
    vec2 w = u - i;
    vec4 a = texelFetch(cloudMap, ivec2(mod(i, CLOUD_MAP)), 0);
    vec4 b = texelFetch(cloudMap, ivec2(mod(i + vec2(1.0, 0.0), CLOUD_MAP)), 0);
    vec4 c = texelFetch(cloudMap, ivec2(mod(i + vec2(0.0, 1.0), CLOUD_MAP)), 0);
    vec4 d = texelFetch(cloudMap, ivec2(mod(i + vec2(1.0), CLOUD_MAP)), 0);
    //The weather with smoothstep weights (bilinear's creases along the texel edges showed in the bands), the towers bilinear
    vec2 sw = w*w*(3.0 - 2.0*w);
    float s = (mix(mix(a.g, b.g, sw.x), mix(c.g, d.g, sw.x), sw.y) - 1.0 + f)/f;
    if (s <= 0.0) {
        return vec2(0.0, -1.0);
    }
    float tower = mix(mix(a.a, b.a, w.x), mix(c.a, d.a, w.x), w.y);
    float rim = ANIME_RIM*cloudShape.y;
    float towerTop = cloudShape.y*mix(1.0 - ANIME_TOWER, 1.0, tower);
    if (h >= 0.0) {
        float most = rim + (towerTop*mix(ANIME_TOP_MIN, 1.0, min(s, 1.0)) - rim)*sqrt(min(s/ANIME_EDGE, 1.0));
        float least = rim*(1.0 - sqrt(min(s/ANIME_BASE_EDGE, 1.0)));
        if (h >= most || h <= least) {
            return vec2(0.0, -1.0);
        }
    }
    float p = mix(animeBillow(animeNoise(m/ANIME_PUFF_CELL, 0.0, CLOUD_MAP*CLOUD_CELL/ANIME_PUFF_CELL)),
            animeBillow(animeNoise(m/ANIME_PUFF_CELL2, 1.0, CLOUD_MAP*CLOUD_CELL/ANIME_PUFF_CELL2)), ANIME_PUFF_DETAIL);
    float sp = s - ANIME_SCALLOP*(1.0 - p);
    if (sp <= 0.0) {
        return vec2(0.0, -1.0);
    }
    float high = towerTop*mix(ANIME_TOP_MIN, 1.0, min(sp, 1.0));
    float top = rim + (high - rim)*sqrt(min(sp/ANIME_EDGE, 1.0))*(1.0 - ANIME_PUFF + ANIME_PUFF*p);
    float bottom = rim*(1.0 - sqrt(min(sp/ANIME_BASE_EDGE, 1.0)));
    return vec2(bottom, top);
}

//True where camera-relative p is inside an anime cloud
bool animeInside(vec3 p) {
    float h = p.y - cloudShape.x;
    if (!(h > 0.0 && h < cloudShape.y)) {
        return false;
    }
    vec2 bt = animeShape(cloudOffset.xy + p.xz, h);
    return h > bt.x && h < bt.y;
}

//The surface's unit normal at camera-relative p: the bottom's and the top's normals (from their heights ANIME_NORMAL_STEP blocks either
// side) mixed by the height in the column, the bottom's below ANIME_NORMAL_LOW of it, the top's above ANIME_NORMAL_HIGH (a hard switch at
// the middle drew a seam along the sides)
vec3 animeNormal(vec3 p) {
    vec2 m = cloudOffset.xy + p.xz;
    vec2 e = vec2(ANIME_NORMAL_STEP, 0.0);
    vec2 c = animeShape(m, -1.0);
    vec2 px = animeShape(m + e, -1.0);
    vec2 nx = animeShape(m - e, -1.0);
    vec2 pz = animeShape(m + e.yx, -1.0);
    vec2 nz = animeShape(m - e.yx, -1.0);
    float w = smoothstep(ANIME_NORMAL_LOW, ANIME_NORMAL_HIGH, clamp((p.y - cloudShape.x - c.x)/max(c.y - c.x, 0.001), 0.0, 1.0));
    vec3 top = normalize(vec3(-(px.y - nx.y), 2.0*ANIME_NORMAL_STEP, -(pz.y - nz.y)));
    vec3 bottom = normalize(vec3(px.x - nx.x, -2.0*ANIME_NORMAL_STEP, pz.x - nz.x));
    return normalize(mix(bottom, top, w));
}

//The sunlit weight of the cel bands and the rim at normal n seen along dir, lit from lightDir
float animeLight(vec3 n, vec3 dir, vec3 lightDir) {
    float level = smoothstep(0.0, CLOUD_SHADE_LEVEL, abs(lightDir.y));
    float x = (dot(n, lightDir) + ANIME_WRAP)/(1.0 + ANIME_WRAP);
    float lit = smoothstep(ANIME_TERMINATOR - ANIME_BAND_SOFT, ANIME_TERMINATOR + ANIME_BAND_SOFT, x);
    float high = smoothstep(ANIME_HIGHLIGHT_AT - ANIME_BAND_SOFT, ANIME_HIGHLIGHT_AT + ANIME_BAND_SOFT, x);
    float band = mix(ANIME_FLAT, mix(ANIME_SHADOW, 1.0, lit) + ANIME_HIGHLIGHT*high, level);
    float edge = 1.0 - abs(dot(n, dir));
    float rim = smoothstep(ANIME_RIM_AT - ANIME_RIM_SOFT, ANIME_RIM_AT + ANIME_RIM_SOFT, edge)*ANIME_RIM_GAIN
            *mix(ANIME_RIM_BACK, 1.0, 0.5 + 0.5*dot(dir, lightDir));
    return band + rim;
}

//The anime clouds along dir from origin up to maxDist: samples through the layer from jitter 0..1 along each step, refine halvings
// toward the first surface, then its cel bands. Returns (sunlit weight, sky-lit weight, coverage, distance)
vec4 animeMarch(vec3 origin, vec3 dir, float maxDist, vec3 lightDir, float jitter, int samples, int refine) {
    vec2 span = cloudInterval(origin, dir, maxDist);
    if (cloudShape.w < 0.5 || span.y <= span.x) {
        return vec4(0.0);
    }
    float len = span.y - span.x;
    int n = int(clamp(ceil(len/ANIME_STEP), float(ANIME_SAMPLES_MIN), float(max(samples, ANIME_SAMPLES_MIN))));
    float dt = len/float(n);
    float prev = span.x;
    for (int i = 0; i < ANIME_SAMPLES_HIGH; i++) {
        if (i >= n) {
            break;
        }
        float t = span.x + (float(i) + jitter)*dt;
        if (!animeInside(origin + dir*t)) {
            prev = t;
            continue;
        }
        float lo = prev;
        float hi = t;
        for (int j = 0; j < ANIME_REFINE; j++) {
            if (j >= refine) {
                break;
            }
            float mid = 0.5*(lo + hi);
            if (animeInside(origin + dir*mid)) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        vec3 p = origin + dir*hi;
        float a = 1.0 - smoothstep(CLOUD_FADE_START, 1.0, length(p.xz)/cloudShape.z);
        return vec4(a*animeLight(animeNormal(p), dir, lightDir), a, a, hi);
    }
    return vec4(0.0);
}

//Session 3: the far clouds along dir from origin up to maxDist in the style cloudOffset.z picks, jitter 0..1 per pixel:
// (sunlit weight, sky-lit weight, coverage, mean distance). The vanilla shape's sky-lit weight is its coverage
vec4 farCloudMarch(vec3 origin, vec3 dir, float maxDist, vec3 lightDir, float jitter) {
    if (cloudAnime()) {
        int a = int(floor(cloudOffset.z)) - ANIME_Z;
        int samples = a >= 2 ? ANIME_SAMPLES_HIGH : (a == 1 ? ANIME_SAMPLES_MEDIUM : ANIME_SAMPLES_LOW);
        return animeMarch(origin, dir, maxDist, lightDir, fract(jitter + fract(cloudOffset.z)), samples, ANIME_REFINE);
    }
    if (!cloudRealistic()) {
        vec3 m = cloudMarch(origin, dir, maxDist, lightDir);
        return vec4(m.x, m.y, m.y, m.z);
    }
    int q = int(floor(cloudOffset.z));
    int samples = q >= 3 ? REAL_SAMPLES_HIGH : (q == 2 ? REAL_SAMPLES_MEDIUM : REAL_SAMPLES_LOW);
    int lights = q >= 3 ? REAL_LIGHT_HIGH : (q == 2 ? REAL_LIGHT_MEDIUM : REAL_LIGHT_LOW);
    return realMarch(origin, dir, maxDist, lightDir, fract(jitter + fract(cloudOffset.z)), samples, lights, q >= 2 ? 3 : 1);
}
