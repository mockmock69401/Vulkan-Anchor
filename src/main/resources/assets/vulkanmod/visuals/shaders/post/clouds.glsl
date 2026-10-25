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
