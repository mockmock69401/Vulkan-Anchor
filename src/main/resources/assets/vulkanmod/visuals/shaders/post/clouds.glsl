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

//Session 11 (2026-11-10), redone in session 18 (2026-11-13): the anime style (CloudMath's anime part mirrors this). After AstraLex's
// clear-sky cumulus, seen only (run in ref-oculus and looked at; refs/anchor/s18-astra), and the user's "rounder and hazier": big soft
// cotton clouds, banks of round domes melting into each other, no small detail, edges blurred evenly, bright tops shading to a
// lavender-grey base that dissolves into the sky, a cream silver lining toward the sun, fading into the horizon's haze.
// The clumps are still the realistic style's weather (the g channel over the coverage cloudOffset.w), so the cloud size, count and
// coverage settings shape them alike, but a clump is built of puffs: rounded domes on a lattice of ANIME_PUFF_CELL blocks (times the
// layer's thickness over ANIME_THICKNESS, so the size setting scales them), each centred up to ANIME_PUFF_JITTER cells off its cell's
// middle, there only where the weather at its centre's texel is cloudy, its radius ANIME_PUFF_MIN..ANIME_PUFF_MAX cells (down to
// ANIME_PUFF_SHRINK of that toward the clump's outline, over ANIME_EDGE of the weather), its profile (1 - (d / R)^2)^ANIME_PUFF_PROFILE
// (0.5 a sphere; more is softer at the rim) ANIME_PUFF_TALL of R up and down from a centre ANIME_PUFF_LOW..ANIME_PUFF_HIGH of R above the
// layer's base (with the weather; lowered by up to ANIME_TOWER where the a channel is low at its centre), so the base cuts it flat. The puffs in a
// column unite smoothly (a polynomial smooth max and min over ANIME_PUFF_BLEND cells: no crease where two meet). JITTER + MAX stays within
// 1, so the 2 x 2 cells on a point's side are all that can reach it (session 11's billowed noise pillows had steep walls and scalloped
// lumps; session 18's first spheres still showed their lumps and seams). The march keeps the 2 x 2 puffs (animeCells: their hashes and
// centre texels) while its samples stay on their side of the lattice, so a sample costs the union's arithmetic, not 4 hashes and fetches.
// The march takes each sample as dense as it is deep inside its column (ANIME_DENSITY per block), reached ANIME_SOFT blocks under the
// top but ANIME_SOFT_BASE above the bottom (the base dissolves), fades the clouds out between ANIME_FADE_NEAR and ANIME_FADE_FAR blocks
// away (and stops there; a fading cloud also thins, its outer ANIME_FADE_SHRINK blocks first, so the far ones shrink away), and lights
// the coverage-weighted point once: wrapped soft light (ANIME_WRAP) from ANIME_SHADOW to 1 between ANIME_SHADE_FROM and ANIME_SHADE_TO,
// darker by up to ANIME_CREASE_SHADE toward every puff's edge and by ANIME_LOW_LIT toward the layer's base (up to ANIME_LOW_REACH of it),
// the sky light from ANIME_AMBIENT_LOW at the base to 1 at the layer's top, and the silver lining: thin cloud (the coverage short of 1)
// toward the light glows with ANIME_SILVER of its forward scattering (Henyey-Greenstein ANIME_SILVER_G). The light is the realistic
// style's (CloudUniforms: the sky's sunlight, kept bright at sunset, and the sky's colour above, both times the cloud colour). The haze
// takes the clouds at ANIME_HAZE of their distance (their white tops and lavender bases stay); the fade, not the haze, takes them out
// before the horizon
//The default layer thickness (CloudMath.ANIME_THICKNESS; the size setting scales cloudShape.y from it)
const float ANIME_THICKNESS = 140.0;
const float ANIME_PUFF_CELL = 104.0;
const float ANIME_PUFF_JITTER = 0.12;
const float ANIME_PUFF_MIN = 0.6;
const float ANIME_PUFF_MAX = 0.88;
const float ANIME_PUFF_SHRINK = 0.6;
const float ANIME_PUFF_LOW = 0.15;
const float ANIME_PUFF_HIGH = 0.55;
const float ANIME_PUFF_TALL = 1.0;
const float ANIME_PUFF_PROFILE = 0.8;
const float ANIME_PUFF_BLEND = 0.35;
const float ANIME_EDGE = 0.5;
const float ANIME_TOWER = 0.35;
//Samples every ANIME_STEP blocks of the span, ANIME_SAMPLES_MIN up to the quality's count; the top's normal from its heights
// ANIME_NORMAL_STEP blocks either side
const float ANIME_STEP = 6.0;
const int ANIME_SAMPLES_LOW = 16;
const int ANIME_SAMPLES_MEDIUM = 28;
const int ANIME_SAMPLES_HIGH = 44;
const int ANIME_SAMPLES_MIN = 10;
const float ANIME_NORMAL_STEP = 12.0;
const float ANIME_SOFT = 30.0;
const float ANIME_SOFT_BASE = 60.0;
const float ANIME_DENSITY = 0.12;
const float ANIME_FADE_NEAR = 500.0;
const float ANIME_FADE_FAR = 1800.0;
const float ANIME_FADE_SHRINK = 24.0;
const float ANIME_HAZE = 0.5;
const float ANIME_WRAP = 0.8;
const float ANIME_SHADE_FROM = 0.0;
const float ANIME_SHADE_TO = 1.0;
const float ANIME_SHADOW = 0.4;
//While the light is at the horizon (the sun and moon swap) the light gives way to ANIME_FLAT
const float ANIME_FLAT = 0.6;
const float ANIME_CREASE_SHADE = 0.25;
const float ANIME_LOW_LIT = 0.65;
const float ANIME_LOW_REACH = 0.6;
const float ANIME_AMBIENT_LOW = 0.6;
const float ANIME_SILVER = 0.15;
const float ANIME_SILVER_G = 0.5;

//A lattice's cell in blocks: base times k (the layer's thickness over ANIME_THICKNESS), a whole number of cells to the map's period
float animeCell(float base, float k) {
    return CLOUD_MAP*CLOUD_CELL/max(1.0, floor(CLOUD_MAP*CLOUD_CELL/(base*k) + 0.5));
}

//The 32-bit hash of lattice cell c (x, z) on layer y, wrapped into n cells (realCorner's, unscaled)
uint animeHash(ivec2 c, int y, int n) {
    ivec2 w = c - n*ivec2(floor(vec2(c)/float(n)));
    uint h = (uint(w.x)*uint(REAL_PRIME_X)) ^ (uint(y)*uint(REAL_PRIME_Y)) ^ (uint(w.y)*uint(REAL_PRIME_Z));
    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    h *= 0x846ca68bu;
    h ^= h >> 16u;
    return h;
}

//The puff lattice's cell in blocks (the layer's thickness scales it)
float animePuffCell() {
    return animeCell(ANIME_PUFF_CELL, cloudShape.y/ANIME_THICKNESS);
}

//The lattice cell at the corner of the 2 x 2 cells on the side of u (m in cells) that can reach it
ivec2 animeCorner(vec2 u) {
    vec2 i = floor(u);
    return ivec2(i) - ivec2(lessThan(u - i, vec2(0.5)));
}

//The puff of lattice cell k (n cells to the period, cell blocks, coverage f): (centre x, z in cells, radius in blocks, its centre's height
// over its radius); radius 0 where none stands (the weather at its centre's texel is clear). The weather and the tower lift are that
// one texel's
vec4 animePuff(ivec2 k, int n, float cell, float f) {
    uint hc = animeHash(k, 0, n);
    vec2 p = vec2(k) + 0.5 + ANIME_PUFF_JITTER*(vec2(float(hc & 0xFFu), float((hc >> 8u) & 0xFFu))/127.5 - 1.0);
    vec4 t = texelFetch(cloudMap, ivec2(mod(floor(p*cell/CLOUD_CELL), CLOUD_MAP)), 0);
    float s = f > 0.0 ? (t.g - 1.0 + f)/f : 0.0;
    if (!(s > 0.0)) {
        return vec4(p, 0.0, 0.0);
    }
    float r = cell*mix(ANIME_PUFF_MIN, ANIME_PUFF_MAX, float((hc >> 16u) & 0xFFu)/255.0)*mix(ANIME_PUFF_SHRINK, 1.0, min(s/ANIME_EDGE, 1.0));
    return vec4(p, r, mix(ANIME_PUFF_LOW, ANIME_PUFF_HIGH, min(s, 1.0))*mix(1.0 - ANIME_TOWER, 1.0, t.a));
}

//The 2 x 2 puffs from lattice cell o; returns the highest top they can give (blocks above the base; negative: none stands)
float animeCells(ivec2 o, float cell, out vec4 c[4]) {
    float f = min(cloudOffset.w, 1.0);
    int n = int(floor(CLOUD_MAP*CLOUD_CELL/cell + 0.5));
    float top = -1.0;
    for (int j = 0; j < 4; j++) {
        c[j] = animePuff(o + ivec2(j & 1, j >> 1), n, cell, f);
        if (c[j].z > 0.0) {
            top = max(top, c[j].z*(c[j].w + ANIME_PUFF_TALL));
        }
    }
    return top < 0.0 ? top : top + 0.75*ANIME_PUFF_BLEND*cell;
}

//Puff c (animePuff) added to a column's (bottom, top, puffs) at u (cells; top below bottom: none yet): the smooth union (a polynomial
// smooth max and min over ANIME_PUFF_BLEND cells)
void animeAdd(vec2 u, float cell, vec4 c, inout vec3 col) {
    float r = c.z;
    vec2 d = (u - c.xy)*cell;
    float q2 = r*r - dot(d, d);
    if (!(r > 0.0) || q2 <= 0.0) {
        return;
    }
    float q = r*pow(q2/(r*r), ANIME_PUFF_PROFILE);
    float cy = r*c.w;
    float lo = max(cy - q*ANIME_PUFF_TALL, 0.0);
    float hi = cy + q*ANIME_PUFF_TALL;
    col.z = max(col.z, q/r);
    if (col.y < col.x) {
        col.xy = vec2(lo, hi);
    } else {
        float kb = ANIME_PUFF_BLEND*cell;
        float ht = max(kb - abs(col.y - hi), 0.0)/kb;
        float hb = max(kb - abs(col.x - lo), 0.0)/kb;
        col.y = max(col.y, hi) + ht*ht*kb*0.25;
        col.x = max(min(col.x, lo) - hb*hb*kb*0.25, 0.0);
    }
}

//A column's (bottom, top, puffs) after its puffs: top capped at the layer's; (0, -1, 0) where none stands
vec3 animeColumn(vec3 col) {
    return col.y > col.x ? vec3(col.x, min(col.y, cloudShape.y), col.z) : vec3(0.0, -1.0, 0.0);
}

//(bottom, top, puffs) above the layer's base in blocks at u (cells on the cloud map) from the 2 x 2 puffs c (animeCells) on its side:
// their smooth union in that column, puffs how near the column is to a puff's middle (1) from every puff's edge (0); top below bottom
// where no puff stands
vec3 animeUnion(vec2 u, float cell, vec4 c[4]) {
    vec3 col = vec3(0.0, -1.0, 0.0);
    for (int j = 0; j < 4; j++) {
        animeAdd(u, cell, c[j], col);
    }
    return animeColumn(col);
}

//The highest top any puff can give, in blocks above the layer's base
float animeTopBound(float cell) {
    return min(((ANIME_PUFF_HIGH + ANIME_PUFF_TALL)*ANIME_PUFF_MAX + 0.75*ANIME_PUFF_BLEND)*cell, cloudShape.y);
}

//How far a point h blocks above the base is inside column bt (bottom, top), in blocks under the top (above the bottom counted ANIME_SOFT /
// ANIME_SOFT_BASE: the base dissolves over more); 0 outside
float animeDepth(float h, vec3 bt) {
    return max(min((h - bt.x)*(ANIME_SOFT/ANIME_SOFT_BASE), bt.y - h), 0.0);
}

//The column (animeUnion's) at camera-relative p, and the surface's unit normal there: straight down at the column's bottom turning
// smoothly to the top's normal (from its heights ANIME_NORMAL_STEP blocks either side) at its top (the flat base's cut rims drew bright
// arcs). The five columns share the 3 x 3 puffs around p's cell, which hold every puff that can reach them (ANIME_NORMAL_STEP is under
// half a cell)
vec3 animeNormal(vec3 p, out vec3 c) {
    vec2 m = cloudOffset.xy + p.xz;
    float cell = animePuffCell();
    float f = min(cloudOffset.w, 1.0);
    int n = int(floor(CLOUD_MAP*CLOUD_CELL/cell + 0.5));
    vec2 u = m/cell;
    ivec2 o = ivec2(floor(u)) - 1;
    float e = ANIME_NORMAL_STEP/cell;
    vec3 col[5];
    for (int k = 0; k < 5; k++) {
        col[k] = vec3(0.0, -1.0, 0.0);
    }
    for (int j = 0; j < 9; j++) {
        vec4 puff = animePuff(o + ivec2(j % 3, j/3), n, cell, f);
        if (!(puff.z > 0.0)) {
            continue;
        }
        animeAdd(u, cell, puff, col[0]);
        animeAdd(u + vec2(e, 0.0), cell, puff, col[1]);
        animeAdd(u - vec2(e, 0.0), cell, puff, col[2]);
        animeAdd(u + vec2(0.0, e), cell, puff, col[3]);
        animeAdd(u - vec2(0.0, e), cell, puff, col[4]);
    }
    c = animeColumn(col[0]);
    float px = animeColumn(col[1]).y;
    float nx = animeColumn(col[2]).y;
    float pz = animeColumn(col[3]).y;
    float nz = animeColumn(col[4]).y;
    float w = smoothstep(0.0, 1.0, clamp((p.y - cloudShape.x - c.x)/max(c.y - c.x, 0.001), 0.0, 1.0));
    vec3 top = normalize(vec3(-(px - nx), 2.0*ANIME_NORMAL_STEP, -(pz - nz)));
    return normalize(mix(vec3(0.0, -1.0, 0.0), top, w));
}

//(sunlit weight, sky-lit weight) of a cloud point with normal n, puffs p (0 at every puff's edge), at height hc (0 the layer's base, 1
// its top), lit from lightDir
vec2 animeLight(vec3 n, float p, float hc, vec3 lightDir) {
    float level = smoothstep(0.0, CLOUD_SHADE_LEVEL, abs(lightDir.y));
    float x = (dot(n, lightDir) + ANIME_WRAP)/(1.0 + ANIME_WRAP);
    float lit = mix(ANIME_SHADOW, 1.0, smoothstep(ANIME_SHADE_FROM, ANIME_SHADE_TO, x))*mix(1.0 - ANIME_CREASE_SHADE, 1.0, smoothstep(0.0, 0.5, p))
            *mix(ANIME_LOW_LIT, 1.0, smoothstep(0.0, ANIME_LOW_REACH, hc));
    return vec2(mix(ANIME_FLAT, lit, level), mix(ANIME_AMBIENT_LOW, 1.0, clamp(hc, 0.0, 1.0)));
}

//The anime clouds' opacity at horizontal distance d from the camera: 1 to ANIME_FADE_NEAR, 0 from ANIME_FADE_FAR (into the haze)
float animeFade(float d) {
    return 1.0 - smoothstep(ANIME_FADE_NEAR, ANIME_FADE_FAR, d);
}

//The anime clouds along dir from origin up to maxDist (cut where the fade ends): samples through the layer from jitter 0..1 along each
// step, each as dense as it is deep inside its column, then the light at the coverage-weighted point and the silver lining by the
// coverage. Without shaded (the water's reflection, blurred anyway) the light skips the puffs' normal: the surface turns from straight down
// at the layer's base (through the side) to straight up at its top. Returns (sunlit weight, sky-lit weight, coverage, distance)
vec4 animeMarch(vec3 origin, vec3 dir, float maxDist, vec3 lightDir, float jitter, int samples, bool shaded) {
    vec2 span = cloudInterval(origin, dir, min(maxDist, ANIME_FADE_FAR/max(length(dir.xz), CLOUD_MIN_HORIZONTAL)));
    if (cloudShape.w < 0.5 || span.y <= span.x) {
        return vec4(0.0);
    }
    float len = span.y - span.x;
    int n = int(clamp(ceil(len/ANIME_STEP), float(ANIME_SAMPLES_MIN), float(max(samples, ANIME_SAMPLES_MIN))));
    float dt = len/float(n);
    float trans = 1.0;
    float tsum = 0.0;
    float wsum = 0.0;
    //The 2 x 2 puffs stay while the samples stay on their side of the lattice: their hashes and weather once per cell group, not per
    // sample, and samples above the group's highest top (or in a group with none) skip the union
    float cell = animePuffCell();
    float bound = animeTopBound(cell);
    ivec2 key = ivec2(0x7FFFFFFF);
    vec4 c[4];
    float groupTop = -1.0;
    for (int i = 0; i < ANIME_SAMPLES_HIGH; i++) {
        if (i >= n || trans < 0.01) {
            break;
        }
        float t = span.x + (float(i) + jitter)*dt;
        vec3 p = origin + dir*t;
        float h = p.y - cloudShape.x;
        if (!(h > 0.0 && h < bound)) {
            continue;
        }
        vec2 m = cloudOffset.xy + p.xz;
        vec2 u = m/cell;
        ivec2 o = animeCorner(u);
        if (o != key) {
            key = o;
            groupTop = animeCells(o, cell, c);
        }
        if (!(h < groupTop)) {
            continue;
        }
        float d = animeDepth(h, animeUnion(u, cell, c));
        if (d <= 0.0) {
            continue;
        }
        float fade = animeFade(length(p.xz));
        float a = (1.0 - exp(-ANIME_DENSITY*smoothstep(0.0, ANIME_SOFT, d - (1.0 - fade)*ANIME_FADE_SHRINK)*dt))*fade;
        float w = a*trans;
        tsum += w*t;
        wsum += w;
        trans *= 1.0 - a;
    }
    if (!(wsum > 0.0)) {
        return vec4(0.0);
    }
    float t = tsum/wsum;
    vec3 p = origin + dir*t;
    float hc = clamp((p.y - cloudShape.x)/cloudShape.y, 0.0, 1.0);
    vec3 col = vec3(0.0, 1.0, 1.0);
    float y = 2.0*smoothstep(0.0, 1.0, hc) - 1.0;
    vec3 nrm = shaded ? animeNormal(p, col) : vec3(0.0, y, sqrt(max(1.0 - y*y, 0.0)));
    vec2 l = animeLight(nrm, col.y > col.x ? col.z : 1.0, hc, lightDir);
    float cov = 1.0 - trans;
    float level = smoothstep(0.0, CLOUD_SHADE_LEVEL, abs(lightDir.y));
    float silver = ANIME_SILVER*(1.0 - cov)*realHg(dot(dir, lightDir), ANIME_SILVER_G*level);
    return vec4(cov*(l.x + silver), cov*l.y, cov, t*ANIME_HAZE);
}

//Session 3: the far clouds along dir from origin up to maxDist in the style cloudOffset.z picks, jitter 0..1 per pixel:
// (sunlit weight, sky-lit weight, coverage, mean distance). The vanilla shape's sky-lit weight is its coverage
vec4 farCloudMarch(vec3 origin, vec3 dir, float maxDist, vec3 lightDir, float jitter) {
    if (cloudAnime()) {
        int a = int(floor(cloudOffset.z)) - ANIME_Z;
        int samples = a >= 2 ? ANIME_SAMPLES_HIGH : (a == 1 ? ANIME_SAMPLES_MEDIUM : ANIME_SAMPLES_LOW);
        return animeMarch(origin, dir, maxDist, lightDir, fract(jitter + fract(cloudOffset.z)), samples, true);
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
