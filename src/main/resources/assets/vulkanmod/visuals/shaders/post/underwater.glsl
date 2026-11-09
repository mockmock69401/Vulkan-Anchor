//2026-11-09 (session 2b; the user: under water the view was far too clear, and the land above seen up through the surface did not
// ripple): the view with the camera inside water, for the composite (scene_copy.comp). No uniforms or bindings: the composite passes
// its slots 35..45 in. UnderwaterMath.java mirrors every const and function here: change both together (UnderwaterTest compares them).
//
// UNITS as water.glsl: scene units (postmath.glsl), blocks, world directions (y up) from the camera.
//
// THE WATER BETWEEN THE CAMERA AND WHAT IT SEES (underwaterMedium). Vanilla's linear water fog is pushed out of the way while the
// post-process runs (UnderwaterFog): this replaces it. Over d blocks of water a colour keeps exp(-sigma d) per channel (Beer-Lambert:
// red goes first, blue last) and the water adds the light it scatters toward the camera. That light is the daylight entering at the
// surface, dimmed by the depth it has to go down (the diffuse attenuation K, Mobley 1994, "Light and Water", ch. 3: downwelling light
// falls as exp(-K depth)), times the water's body colour (water.glsl's WATER_BODY, the colour deep water shows from above). Along the
// view the depth changes as h0 - s y, so the single-scattered light has a closed form:
//   integral 0..d of sigma exp(-sigma s) B exp(-K (h0 - s y)) ds = B exp(-K h0) sigma / (sigma - K y) (1 - exp(-(sigma - K y) d))
// Looking down the water fades to a darker blue than looking up; far away horizontally it is the body colour at the camera's depth;
// deeper down every direction darkens. The visibility setting divides sigma.
//
// THE SURFACE SEEN FROM BELOW (underwaterWindow). Where the view rises to the water's surface (the composite knows its height above the
// camera), the surface's rippled normal (water.glsl's rippleNormal, world anchored as the reflections') bends the view out into the air
// by Snell's law (water 1.333 to air 1). Inside the critical angle (asin(1/1.333), about 48.6 degrees from straight up: Snell's window)
// the sky is seen along the refracted direction, so the whole sky is squeezed into that cone and the horizon lies on its rim, and the
// ripples wobble it; beyond it the surface mirrors the water below (total internal reflection), whose colour is the medium's looking
// down. Between them Schlick's Fresnel term (in the less dense medium's angle, so it reaches 1 at the critical angle and the rim is
// continuous) mixes reflection and transmission. Radiance entering a denser medium rises by n^2 (the n^2 law of radiance: Mobley 1994,
// ch. 4), so the window is brighter than the sky itself.
//
// WHAT THE WINDOW AND THE MIRROR SHOW (2026-11-09, fix session after 2b; the user: from just under a river's surface, looking toward the
// horizon, almost nothing above the water showed: the sky and trees as a thin band at the top, the rest the water's own blue). The banks
// and trees stand low in the air's sky, so seen straight from the camera they lie outside the window, where the surface showed only
// the colour of endless deep water. Both sides now look along their own ray from where the view meets the surface: the window along the
// refracted ray (so the land above is squeezed into the window with the sky, rippled, as Snell's law puts it), the mirror along the
// reflected ray (so the surface mirrors the riverbed and the banks below it). The composite finds what lies along such a ray on its
// screen: the pixel of the ray's direction from the camera, then the pixel of the point that far along the ray (the camera is a few
// blocks from the surface; what it sees is farther). A ray off the screen, or onto the wrong side of the surface, fades to the sky
// (window) or the deep water (mirror), over UNDERWATER_LOOKUP_EDGE of the screen at its edges.

#import <vulkanmod:visuals/post/water.glsl>

//Extinction per block at visibility 100% (red most: pure water absorbs red far more than blue). Green and blue keep 5% after about
// 75 and 100 blocks, red after 25; the visibility setting divides them. Tuned on the refworld U scenes (session 2b: 0.30 / 0.11 / 0.09
// and 0.20 / 0.065 / 0.05 hid the kelp and the floor ten to twenty blocks away; Complementary Reimagined and Fantasy show their
// silhouettes there)
const float UNDERWATER_EXTINCTION_RED = 0.12;
const float UNDERWATER_EXTINCTION_GREEN = 0.04;
const float UNDERWATER_EXTINCTION_BLUE = 0.03;
//The daylight's attenuation per block of depth (Mobley's K_d: clear ocean water about 0.02 in blue, 0.07 in green, 0.4 in red per metre;
// a game look, gentler in red so shallow water is not black-red): 11 blocks down keeps about 70% of the blue
const float UNDERWATER_DIFFUSE_RED = 0.10;
const float UNDERWATER_DIFFUSE_GREEN = 0.045;
const float UNDERWATER_DIFFUSE_BLUE = 0.03;
//The scattered light's rate never falls below this share of sigma looking up (where depth falls along the view)
const float UNDERWATER_MIN_RATE = 0.2;
//The longest water path (sky pixels: nothing behind)
const float UNDERWATER_LONGEST = 1024.0;
//The visibility setting's range (ratio; the settings screen's 25..300%)
const float UNDERWATER_VISIBILITY_MIN = 0.25;
const float UNDERWATER_VISIBILITY_MAX = 3.0;
//Water's refractive index over air's
const float UNDERWATER_IOR = 1.333;
//The radiance gain into water (n^2) on what the window lets through
const float UNDERWATER_WINDOW_GAIN = 1.777;
//A view rises to the surface only above this sine (0.01 left the water's face at grazing views just under the surface as a line at the horizon)
const float UNDERWATER_MIN_UP = 0.001;
//A pixel lies on or behind the surface when it stands at least as high as the surface less this many blocks and this share of its
// distance (the depth's precision). By height, not by distance: at grazing views the surface's distance changes fast from pixel to
// pixel and the water's own face slipped under it as a bright line at the horizon
const float UNDERWATER_SURFACE_SHARE = 0.002;
const float UNDERWATER_SURFACE_SLACK = 0.05;
//Land behind the surface farther than this (blocks) counts as sky
const float UNDERWATER_LAND_MAX = 512.0;
//A screen lookup fades out over this share of the screen at its edges
const float UNDERWATER_LOOKUP_EDGE = 0.08;
//The shortest stretch of a ray (blocks) the lookup's second step takes: what the camera sees there is at least this far from the surface
const float UNDERWATER_LOOKUP_MIN = 1.0;

//The visibility setting as sigma's divisor: clamped to its range, 1 when not a number
float underwaterVisibility(float visibility) {
    return visibility >= UNDERWATER_VISIBILITY_MIN ? min(visibility, UNDERWATER_VISIBILITY_MAX)
            : (visibility < UNDERWATER_VISIBILITY_MIN ? UNDERWATER_VISIBILITY_MIN : 1.0);
}

//Extinction per block at a visibility setting (ratio)
vec3 underwaterExtinction(float visibility) {
    return vec3(UNDERWATER_EXTINCTION_RED, UNDERWATER_EXTINCTION_GREEN, UNDERWATER_EXTINCTION_BLUE)/underwaterVisibility(visibility);
}

vec3 underwaterDiffuse() {
    return vec3(UNDERWATER_DIFFUSE_RED, UNDERWATER_DIFFUSE_GREEN, UNDERWATER_DIFFUSE_BLUE);
}

//The water's own colour seen along a view of d blocks rising by y (sine; negative looking down) from h0 blocks under the surface,
// lit by light (rgb: the daylight entering at the surface, scene units). d is clamped to UNDERWATER_LONGEST
vec3 underwaterInscatter(float d, float y, float h0, vec3 light, float visibility) {
    float len = d > 0.0 ? min(d, UNDERWATER_LONGEST) : 0.0;
    vec3 sigma = underwaterExtinction(visibility);
    vec3 k = max(sigma - underwaterDiffuse()*y, sigma*UNDERWATER_MIN_RATE);
    vec3 body = vec3(WATER_BODY_RED, WATER_BODY_GREEN, WATER_BODY_BLUE)*max(light, vec3(0.0))*exp(-underwaterDiffuse()*max(h0, 0.0));
    return body*sigma/k*(vec3(1.0) - exp(-k*len));
}

//colour seen through d blocks of water (see underwaterInscatter)
vec3 underwaterMedium(vec3 colour, float d, float y, float h0, vec3 light, float visibility) {
    float len = d > 0.0 ? min(d, UNDERWATER_LONGEST) : 0.0;
    return colour*exp(-underwaterExtinction(visibility)*len) + underwaterInscatter(len, y, h0, light, visibility);
}

//The distance along dir to the surface surfaceUp blocks above the camera, or -1 where the view does not rise to it (no surface: 0)
float underwaterSurfaceDistance(float surfaceUp, vec3 dir) {
    return surfaceUp > 0.0 && dir.y > UNDERWATER_MIN_UP ? surfaceUp/dir.y : -1.0;
}

//Whether a pixel dist blocks away along a view rising by dirY (sky: nothing in front) lies on or behind the surface surfaceUp blocks
// above, which the view meets at t (underwaterSurfaceDistance; -1: never)
bool underwaterThroughSurface(float surfaceUp, float t, float dirY, float dist, bool sky) {
    return t > 0.0 && (sky || dirY*dist >= surfaceUp - UNDERWATER_SURFACE_SLACK - UNDERWATER_SURFACE_SHARE*dist);
}

//The view d (rising) leaving the water through the surface of upward normal n: the refracted direction, (0, 0, 0) past the critical
// angle (total internal reflection)
vec3 underwaterRefract(vec3 d, vec3 n) {
    return refract(d, -n, UNDERWATER_IOR);
}

//The share of the light the surface reflects back down for a view d meeting the upward normal n: Schlick's term in the air's angle
// (1 past the critical angle)
float underwaterReflectance(vec3 d, vec3 n) {
    vec3 r = underwaterRefract(d, n);
    if (dot(r, r) < 0.5) {
        return 1.0;
    }
    float c = clamp(dot(r, n), 0.0, 1.0);
    return WATER_F0 + (1.0 - WATER_F0)*pow(1.0 - c, FRESNEL_POWER);
}

//What the surface mirrors (it reflects the view d back down off the upward normal n): what the screen shows along the reflected ray
// (seen, at the underwater exposure; path blocks from the surface) with weight, else the endless water below (light: the daylight at
// the surface), each through the water between it and the surface
vec3 underwaterMirrored(vec3 d, vec3 n, vec3 seen, float path, float weight, vec3 light, float visibility) {
    float y = min(reflect(d, -n).y, 0.0);
    vec3 deep = underwaterInscatter(UNDERWATER_LONGEST, y, 0.0, light, visibility);
    return mix(deep, underwaterMedium(seen, path, y, 0.0, light, visibility), clamp(weight, 0.0, 1.0));
}

//The surface from below as the water just under it sees it along d, before the water between it and the camera: above (the sky or
// land along the refracted view, scene units) through the window, below (underwaterMirrored) reflected
vec3 underwaterWindow(vec3 d, vec3 n, vec3 above, vec3 below) {
    float f = underwaterReflectance(d, n);
    return above*(UNDERWATER_WINDOW_GAIN*(1.0 - f)) + below*f;
}

//A view-space direction's place on the screen (GL uv: 0..1, y up) for a perspective projection whose inverse has ix, iy on its diagonal
// (the inverse's x and y scale); (-1, -1) behind the camera
vec2 underwaterScreenOf(vec3 v, float ix, float iy) {
    if (v.z > -1e-4) {
        return vec2(-1.0);
    }
    return vec2(v.x/(-v.z*ix), v.y/(-v.z*iy))*0.5 + 0.5;
}

//How much a screen lookup at uv counts: 1 inside, falling to 0 at the screen's edges and 0 past them
float underwaterEdgeFade(vec2 uv) {
    vec2 e = min(uv, vec2(1.0) - uv)/UNDERWATER_LOOKUP_EDGE;
    return clamp(min(e.x, e.y), 0.0, 1.0);
}
