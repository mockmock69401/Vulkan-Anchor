//W4: sky, atmosphere and light colours. AtmosphereMath.java mirrors every const and function here for the tests: change both
// together (AtmosphereTest compares the consts). No uniforms or bindings: any shader can import it (the clean shafts.comp does,
// for the three SHAFT_ constants). It imports postmath.glsl (LUMA, luminance).
//
// UNITS. Colours are W2's scene units (postmath.glsl): linear Rec. 709, 1.0 = a white surface in full daylight at exposure 100;
// a clear noon sky is about 0.2 (zenith) to 0.65 (horizon). Directions are WORLD space unit vectors, y up, from the camera.
// Distances are blocks. Heights are blocks above the dimension's sea level.
//
// INPUTS. The per-frame state is the struct Atmosphere (seven vec4, AtmosphereUniforms writes them):
//   composite (scene_copy.comp): Atmosphere(slot3, slot4, slot5, slot6, slot7, slot9, slot25)
//   reflection: the same seven vec4 in the slots W6 chooses, written with
//               AtmosphereUniforms.writeBlock(w, AtmosphereUniforms.block(settings, frame, frame.skyColour() != null), slots...)
// Composite slots 26, 27 and 28 (not part of the struct), for keeping vanilla's stars (below): 26 rgb vanilla's sky colour and
// 27 rgb vanilla's fog colour at the camera in scene units (w 1 when known); 28 x the star weight (night() x (1 - rain): vanilla
// draws stars only in the dark), y the strength of vanilla's sunrise/sunset fan (its sunrise colour's alpha, 0 when it draws
// none), zw 0.
// and, for the far clouds, the struct CloudLight (the cloud block's slots 3..5, after cloudShape and cloudOffset):
//   composite: CloudLight(slot14, slot15, slot16)   reflection: CloudLight(slot16, slot17, slot18)
//
// Atmosphere fields (the meaning of every component; the shader functions below are the way to use them):
//   sun        xyz direction toward the sun (world, unit; the moon is -xyz); w the MODE:
//              ATMOSPHERE_NONE   (0) no atmosphere this frame: leave sky pixels and haze alone (atmosphereOn false)
//              ATMOSPHERE_SKY    (1) a dimension with sky light: the full model
//              ATMOSPHERE_NO_SKY (2) no sky light (Nether, End: B17): the dimension's fog colour, no sun, no gradient
//              ATMOSPHERE_FOG    (3) vanilla's environmental fog covers the view (under water, in lava, blindness): its colour
//   sunLight   SKY: rgb the sunlight that scatters in the air (scene units per unit of optical depth and phase), w the overcast
//              share (rain greys the sky, 0..1). NO_SKY and FOG: rgb the fog colour (vanilla's, in scene units), w 0
//   moonLight  rgb the moonlight that scatters in the air (fades in as the sun's light fades out), w night() (0 day .. 1 night)
//   depth      rgb the Rayleigh optical depth of one air mass, w the haze (Mie) optical depth of one air mass
//   ambient    rgb the isotropic sky dome's brightness at full depth, scene units: multiply-scattered light, the low sun's
//              twilight glow, night-sky light and, in rain, the diffused sunlight; along a path it builds up as
//              1 - exp(-AMBIENT_DEPTH mass). w air masses per block of view distance for the aerial haze far away
//              (fog strength x distance haze / AtmosphereUniforms.AIR_MASS_BLOCKS; hazeMass adds the near veil on that rate)
//   glow       rgb the sun-side glow's colour at the glow lobe's mean, w the moon glow's share of moonLight
//   fog        x the ground fog's density per block at sea level (fog strength and rain applied; NO_SKY and FOG: the uniform
//              density), y 1 / fog height (blocks^-1; 0 in NO_SKY and FOG: height independent), z the camera's height above sea
//              level (clamped at 0), w the sun's reddening air mass (SKY: sunLight is the light through SKY_REDDENING of the
//              extinction along it, which airlight lifts for light scattered high in the air and the low haze band deepens; 0 in NO_SKY
//              and FOG)
// CloudLight fields: lit rgb the colour of a fully sunlit cloud (scene units, CLOUD_ALBEDO and vanilla's cloud colour already
// applied: do not multiply again; vanilla's cloud colour carries the day/night and weather brightness, so night clouds are dark), ambient rgb the sky light every cloud sample gets, light xyz the direction the clouds are lit
// from (the sun, the moon at night: pass it as clouds.glsl's cloudMarch lightDir); w components 0.
//
// WHAT TO CALL (all return finite, non-negative colours):
//   sky pixel (no geometry):     skyColour(a, dir) + sunGlow(a, dir), then the sun/moon disc and the stars (below)
//   a surface:                   applyHaze(a, colour, dir, dist, hazeAmount(a, skyLight, isLod)), or haze(...) for the two factors
//   the far clouds over the sky: r = cloudOver(a, c, cloudMarch(...), dir, 1.0), then sky*(1 - r.a) + r.rgb
//   environmental fog / no sky:  nothing extra: every function above switches on the mode (hazeAmount too)
//   reflected sky (B8, W6):      skyColour(a, r) + sunGlow(a, r) for the reflected direction r, plus W6's disc/glint
// dist is the VIEW DISTANCE in blocks from the camera to the surface the eye meets first (W5's lightTex.b, or the length of the
// pixel's view position): for water, ice and glass that is the translucent surface, not the floor behind it (the water's own
// depth colour is W6's). The reflection pass replaces part of an already hazed water pixel: it hazes the colour it reflects with
// applyHaze(a, reflected, viewDir, the water pixel's dist, amount) before mixing it in (reflected sky included), so a far
// reflection fades like the water around it.
// hazeAmount(a, skyLight, isLod): skyLight is the pixel's sky light 0..1, the same value the composite gives W5's sunShadingAt
// (from the per-pixel light data or the lightmap coordinate it already reads for vanilla pixels); LOD pixels pass isLod = true
// (full haze). In ATMOSPHERE_SKY a vanilla pixel's haze scales with its sky light, so cave walls get no daylight veil (a camera
// in a cave looking out at sunlit terrain still sees that terrain fully hazed: accepted). In ATMOSPHERE_FOG vanilla's own fog is
// already in vanilla pixels, so they get none (0) and only LOD pixels get the weak ENV fog (the LOD composite already fogs LODs,
// so this only settles them into the fog colour). In ATMOSPHERE_NO_SKY every pixel gets 1. NONE: 0.
// SUN AND MOON DISCS: the disc for sky and reflected pixels is W6's export (boundary section 6); the composite draws it on sky
// pixels at about postmath.glsl's SUN_DISC_LUMINANCE. The clean screen-space shafts depend on it: their mask keeps only scene
// luminance above SHAFT_THRESHOLD (1.5), which the disc always passes. The sky alone passes only in the glow core: skyColour +
// sunGlow is above 1.5 within about 4 degrees of a noon sun and about 6 degrees of the S2 sunset sun (height 0.19) at the default
// sun glow of 50 (about 6 and 8 degrees at sun glow 100); the plain sky without the glow never is (at most about 1.1).
// STARS: skyColour has none. Keep vanilla's: on sky pixels add
//   vanillaStars(a, dir, vanillaToScene(mainColour), slot26, slot27, slot28)
// It takes what vanilla drew above its own sky and fog colours, keeps only its white part (stars are white; vanilla's red-orange
// sunrise/sunset fan is not), drops it on the sun's side while vanilla's fan is drawn, and weighs it by the star weight (none by
// day, so vanilla's sun texture never comes back over W6's disc). So the fan never doubles the sunset sky. At night it also keeps
// vanilla's moon (white): if W6 draws its own moon disc, the composite skips this residue inside that disc.

#import <vulkanmod:visuals/post/postmath.glsl>

//Screen-space light shafts (the clean shafts.comp, boundary 3.7): 44 steps from the pixel to the sun at half resolution. Assuming
// FOV 70 at 2560x1440 and a walk of up to 1.2 screen widths (1536 half-resolution texels), a step is at most ~35 texels, under
// half the vanilla sun disc's ~77-texel radius, so no rings (longer walks only reach a sun off screen past a far corner, where the
// beams are faint); 44 bilinear taps per texel keep the pass cheap. Each step keeps 0.963 of the last, so the disc at the far end
// of a long walk weighs 0.963^44 ~ 0.19 (beams fade softly with their length). Only scene luminance above 1.5 casts beams: the sun
// disc (W6's, drawn by the composite near SUN_DISC_LUMINANCE) and the glow core within a few degrees of the sun, never the plain sky
// (at most about 1.1, toward a sun at height about 0.24: about 25% under)
const int SHAFT_SAMPLES = 44;
const float SHAFT_DECAY = 0.963;
const float SHAFT_THRESHOLD = 1.5;

//Modes (Atmosphere.sun.w)
const float ATMOSPHERE_NONE = 0.0;
const float ATMOSPHERE_SKY = 1.0;
const float ATMOSPHERE_NO_SKY = 2.0;
const float ATMOSPHERE_FOG = 3.0;

//Kasten and Young 1989, "Revised optical air mass tables and approximation formula" (Applied Optics 28): the relative air mass
// for an elevation e in degrees is 1 / (sin e + A (e + B)^-C), about 38 at the horizon. The sky caps it at SKY_MAX_AIR_MASS, which
// sets how white the horizon gets (the uncapped air mass would bleach it)
const float AIR_MASS_A = 0.50572;
const float AIR_MASS_B = 6.07995;
const float AIR_MASS_C = 1.6364;
const float SKY_MAX_AIR_MASS = 16.0;
//Henyey and Greenstein 1941 phase asymmetry: the haze in the sky (a broad brightening toward the sun) and the sun-side glow lobe
const float HAZE_G = 0.3;
const float GLOW_G = 0.93;
//The glow grows toward the horizon like the in-scatter of a haze of this optical depth per air mass
const float GLOW_DEPTH = 0.2;
//The isotropic sky dome (Atmosphere.ambient) builds up along a path like a medium of this optical depth per air mass: flatter
// than the Rayleigh gradient, so a twilight sky stays bright up to the zenith
const float AMBIENT_DEPTH = 0.7;
//The sky's sunlight (Atmosphere.sunLight) is reddened by this share of the extinction along the sun's air mass (fog.w)
const float SKY_REDDENING = 0.7;
//2026-11-09 (session 1a: "the sunset is only red", "the sunrise is grey"): the low haze band (lowBand). The references (Luxium,
// Complementary Reimagined, Fantasy) draw a sun 10 degrees up the way a real sky looks with the sun 2 degrees up: an orange band about
// 10 to 15 degrees tall at the horizon, pink and lavender above it, blue at the top. Deepening the whole column's reddening for that turned
// the zenith grey (a channel's column-averaged light falls as one over its depth, which Rayleigh's weighting cancels), so the deeper
// reddening lives in a band of low haze only (the boundary layer, after the two-layer picture of Preetham, Shirley and Smits 1999, "A
// practical analytic model for daylight", where haze sits low and Rayleigh air above). Its optical depth along m air masses is
// LOW_DEPTH m^2 / SKY_MAX_AIR_MASS (opaque at the horizon, about 70% at 10 degrees, 27% at 20, 14% at 30), while the sun is low: full below sun
// height LOW_SUN_LOW, none above LOW_SUN_HIGH; it fades out after dusk (LOW_NIGHT_LOW .. LOW_NIGHT_HIGH) and with the overcast
const float LOW_DEPTH = 0.6;
const float LOW_SUN_LOW = 0.15;
const float LOW_SUN_HIGH = 0.45;
const float LOW_NIGHT_LOW = -0.2;
const float LOW_NIGHT_HIGH = -0.05;
//The band's light is the sky's sunlight reddened LOW_REDDENING times more, but to no more than the reddening of LOW_LIGHT_MASS air masses
// in all (the sky's own cap, AtmosphereUniforms.SUN_MAX_AIR_MASS: a set sun's band stays burnt orange), kept at the sky's sunlight's
// luminance, times LOW_SCATTER. Its haze scatters grey (no Rayleigh weighting: an orange light stays orange)
const float LOW_REDDENING = 4.5;
const float LOW_LIGHT_MASS = 20.0;
const float LOW_SCATTER = 1.0;
//The band's phase: LOW_ISO isotropic, the rest a Henyey-Greenstein lobe of LOW_G (warm toward the sun, a pink belt opposite). As the sun
// sinks from LOW_SHADOW_HIGH to LOW_SHADOW_LOW the low air away from it falls into the earth's shadow: then only the band within
// LOW_SHADOW_COS of the sun's direction (cosine) is lit
const float LOW_G = 0.3;
const float LOW_ISO = 0.3;
const float LOW_SHADOW_LOW = -0.03;
const float LOW_SHADOW_HIGH = 0.12;
const float LOW_SHADOW_COS = 0.7;
//The band also scatters this share of the dome's light (the sky lights the haze too: the earth's shadow is blue-grey, not black)
const float LOW_DOME = 0.5;
//How the band's depth grows along part of a sky path (lowBand): (share of the path)^LOW_PATH_POWER
const float LOW_PATH_POWER = 1.5;
//Vanilla's sunrise/sunset fan is suppressed on the side of the sky facing the sun: fully where the horizontal cosine to the sun's
// azimuth is above FAN_SIDE_HIGH, not at all below FAN_SIDE_LOW (the fan spans the sun's half of the horizon)
const float FAN_SIDE_LOW = -0.3;
const float FAN_SIDE_HIGH = 0.1;
//Overcast light (rain): the sky's colour is replaced by its luminance times this tint (luminance 1)
const float OVERCAST_RED = 0.96;
const float OVERCAST_GREEN = 1.0;
const float OVERCAST_BLUE = 1.08;
//Numerical guards: the smallest sine of elevation the sky's ground-fog integral divides by, the height difference (in fog
// heights) below which the fog integral uses its series, the longest haze path in blocks, the smallest optical depth divided by
const float SKY_FOG_MIN_SLOPE = 0.001;
const float FOG_SERIES = 0.001;
const float HAZE_MAX_DISTANCE = 1000000.0;
//The haze starts gently. Its optical depths use the distance d^3 / (d^2 + HAZE_ONSET^2): d^3/ONSET^2 right by the camera, about
// d - small beyond a few onsets (unchanged at the horizon and in the sky). Never decreases with d. 2026-11-09 (session 1b, the user: "the
// aerial perspective is too weak"): 400 kept everything within 150 blocks clear and the haze only began at the render distance's edge,
// where the border fog took over. 32 lets it start a few blocks out, as in the references (Luxium: fog start 8 blocks, a near fade of
// about 40)
const float HAZE_ONSET = 32.0;
//2026-11-09 (session 1b): the aerial veil. Seen from a hill at noon, Luxium and Complementary Reimagined veil terrain 100 to 250 blocks
// away in blue-grey and keep it there (Luxium: a maximum opacity of 0.64), while the far distance fades on into the sky. The aerial
// haze's air mass along a view is therefore not just the distance over AIR_MASS_BLOCKS: near the camera it builds VEIL_GAIN times faster,
// leveling off at VEIL_GAIN x VEIL_RANGE blocks' worth (about 3.5 air masses at fog strength 100) after a few VEIL_RANGE blocks of air:
//   mass = ambient.w (d + VEIL_GAIN VEIL_RANGE (1 - exp(-p / VEIL_RANGE)))
// with p the path's air weighted by exp(-height / VEIL_HEIGHT) above sea level (the boundary layer's haze thins upward: a high view, a
// mountain top or a cloud has less of it). This compresses the distance like the "aerial perspective view distance scale" of Unreal
// Engine's sky atmosphere (after Hillaire 2020, "A scalable and production ready sky and atmosphere rendering technique", EGSR) but only
// near the camera, so Distant Horizons' far terrain keeps some contrast (about 4.5 air masses at 3000 blocks) and only fades into the sky
// beyond it. The colour is the
// sky's own in-scatter over that air mass (airlight: blue by day, warm toward a low sun through the low haze band), so the veil at any
// distance is a sky of that many air masses in the view's direction
const float VEIL_GAIN = 35.0;
const float VEIL_RANGE = 300.0;
const float VEIL_HEIGHT = 200.0;
const float MIN_DEPTH = 0.000001;

struct Atmosphere {
    vec4 sun;
    vec4 sunLight;
    vec4 moonLight;
    vec4 depth;
    vec4 ambient;
    vec4 glow;
    vec4 fog;
};

struct CloudLight {
    vec4 lit;
    vec4 ambient;
    vec4 light;
};

//True when this frame has an atmosphere (any mode but ATMOSPHERE_NONE)
bool atmosphereOn(Atmosphere a) {
    return a.sun.w > 0.5;
}

//True in ATMOSPHERE_SKY (sun, sky gradient, glow, clouds)
bool atmosphereSky(Atmosphere a) {
    return abs(a.sun.w - ATMOSPHERE_SKY) < 0.5;
}

//The haze amount for a pixel (see the header): SKY: 1 for LOD pixels, else its sky light 0..1; FOG: 1 for LOD pixels, else 0
// (vanilla's fog is already in them); NO_SKY: 1; NONE: 0. A NaN sky light counts as 0.
float hazeAmount(Atmosphere a, float skyLight, bool isLod) {
    if (!atmosphereOn(a)) {
        return 0.0;
    }
    if (abs(a.sun.w - ATMOSPHERE_FOG) < 0.5) {
        return isLod ? 1.0 : 0.0;
    }
    if (!atmosphereSky(a) || isLod) {
        return 1.0;
    }
    return skyLight > 0.0 ? min(skyLight, 1.0) : 0.0;
}

//Relative air mass along a view with sine of elevation mu (clamped to 0..1), capped at SKY_MAX_AIR_MASS
float skyAirMass(float mu) {
    float h = clamp(mu, 0.0, 1.0);
    float e = degrees(asin(h));
    return min(1.0/(h + AIR_MASS_A*pow(e + AIR_MASS_B, -AIR_MASS_C)), SKY_MAX_AIR_MASS);
}

//Rayleigh's phase function normalised to a mean of 1 over the sphere: 3/4 (1 + cos^2)
float rayleighPhase(float c) {
    return 0.75*(1.0 + c*c);
}

//Henyey and Greenstein 1941 phase function normalised to a mean of 1 over the sphere
float hgPhase(float c, float g) {
    float d = max(1.0 + g*g - 2.0*g*c, MIN_DEPTH);
    return (1.0 - g*g)/(d*sqrt(d));
}

//Rain's overcast: the colour moves toward its luminance in the overcast tint by the frame's share
vec3 overcast(Atmosphere a, vec3 c) {
    return mix(c, vec3(OVERCAST_RED, OVERCAST_GREEN, OVERCAST_BLUE)*luminance(c), a.sunLight.w);
}

//The low haze band's optical depth along a path of m air masses toward a sky of skyMass air masses (see LOW_DEPTH): by the sun's
// height and the overcast. A sky pixel's whole path (m = skyMass) has LOW_DEPTH m^2 / SKY_MAX_AIR_MASS. 2026-11-09 (session 1b): part of
// that path, m of skyMass, has (m / skyMass)^LOW_PATH_POWER of it. 1a's m^2 (power 2) gave terrain a few hundred blocks away next to
// none of the band's warm light, where Luxium and Complementary fill the land toward a setting sun with orange (F2); 1 (linear: a level
// path is all in the low air) hazed even 50 blocks 17%. The whole path is unchanged, so the sky is too and far terrain still turns into it
float lowBand(Atmosphere a, float m, float skyMass) {
    float w = (1.0 - smoothstep(LOW_SUN_LOW, LOW_SUN_HIGH, a.sun.y))*smoothstep(LOW_NIGHT_LOW, LOW_NIGHT_HIGH, a.sun.y)
            *(1.0 - clamp(a.sunLight.w, 0.0, 1.0));
    float sm = max(skyMass, 0.0);
    float share = sm > 0.0 ? clamp(m/sm, 0.0, 1.0) : 0.0;
    return LOW_DEPTH*w*sm*sm/SKY_MAX_AIR_MASS*pow(share, LOW_PATH_POWER);
}

//The low haze band's light (see LOW_REDDENING): the sky's sunlight reddened further, at its luminance
vec3 lowLight(Atmosphere a) {
    float w = max(a.fog.w, 0.0);
    float mass = min(LOW_REDDENING*w, max(LOW_LIGHT_MASS - w, 0.0));
    vec3 light = a.sunLight.rgb*exp(-SKY_REDDENING*(a.depth.rgb + a.depth.w)*mass);
    float ll = luminance(light);
    return ll > MIN_DEPTH ? light*(luminance(a.sunLight.rgb)/ll) : vec3(0.0);
}

//Single scattering of sun and moon light plus the ambient light along a path of `mass` air masses toward dir, in a homogeneous
// atmosphere (Hoffman and Preetham 2002, "Rendering outdoor light scattering in real time", GDC): in-scatter =
// (sun (tauR PR + tauM PH) + moon (...)) (1 - exp(-tau mass)) / tau, plus the isotropic dome ambient (1 - exp(-AMBIENT_DEPTH
// mass)). Before the overcast. ATMOSPHERE_SKY only. 2026-10-24 (CTE2: "the sunset is only red"): sunLight is the light at the
// ground; a point x air masses along the view has climbed x / M of the air column (M the whole view's air mass), and the sunlight
// there went through that much less of the sun's reddening depth tauS: the sun's term integrates exp(-tau x) exp(tauS x / M), so
// the zenith of a sunset sky keeps its blue while the horizon toward the sun stays red. 2026-11-09 (session 1a): then the low haze band
// (lowBand) in front of it all: what is behind it is dimmed by its transmittance, and it adds its own light (lowLight in its phase, plus
// LOW_DOME of the dome).
vec3 airlight(Atmosphere a, vec3 dir, float mass) {
    vec3 tauR = a.depth.rgb;
    float tauM = a.depth.w;
    vec3 tau = max(tauR + tauM, vec3(MIN_DEPTH));
    float cs = dot(dir, a.sun.xyz);
    vec3 sun = a.sunLight.rgb*(tauR*rayleighPhase(cs) + tauM*hgPhase(cs, HAZE_G));
    vec3 moon = a.moonLight.rgb*(tauR*rayleighPhase(-cs) + tauM*hgPhase(-cs, HAZE_G));
    float m = max(mass, 0.0);
    vec3 km = (tau - SKY_REDDENING*(tauR + tauM)*a.fog.w/skyAirMass(dir.y))*m;
    vec3 sunPath = m*mix(1.0 - 0.5*km, (1.0 - exp(-km))/km, greaterThan(abs(km), vec3(FOG_SERIES)));
    vec3 air = sun*sunPath + moon*(1.0 - exp(-tau*m))/tau + a.ambient.rgb*(1.0 - exp(-AMBIENT_DEPTH*m));
    float band = lowBand(a, m, skyAirMass(dir.y));
    if (band > 0.0) {
        float t = exp(-band);
        float lit = smoothstep(LOW_SHADOW_LOW, LOW_SHADOW_HIGH, a.sun.y);
        float phase = (LOW_ISO + (1.0 - LOW_ISO)*hgPhase(cs, LOW_G))*(lit + (1.0 - lit)*smoothstep(0.0, LOW_SHADOW_COS, cs));
        air = air*t + (LOW_SCATTER*lowLight(a)*phase + LOW_DOME*a.ambient.rgb)*(1.0 - t);
    }
    return air;
}

//The colour the ground fog scatters toward the camera looking along dir: the horizon sky in dir's azimuth (so fogged terrain meets
// the horizon without a seam). NO_SKY and FOG: the fog colour. NONE: black.
vec3 fogColour(Atmosphere a, vec3 dir) {
    if (!atmosphereSky(a)) {
        return max(a.sunLight.rgb, vec3(0.0));
    }
    vec2 h = dir.xz;
    float l = length(h);
    vec3 horizon = l > 0.0001 ? vec3(h.x/l, 0.0, h.y/l) : vec3(0.0, 0.0, 1.0);
    return overcast(a, airlight(a, horizon, skyAirMass(0.0)));
}

//The mean of exp(-height / H) along a straight path of d blocks from a camera camHeight blocks above sea level with world dir.y = dirY,
// invH = 1 / H (heights clamped at sea level, so at most 1): (e^-h0 - e^-h1) / (h1 - h0) in units of H, its series when they are close.
// 1 with invH = 0
float heightMean(float camHeight, float dirY, float d, float invH) {
    float h0 = camHeight*invH;
    float h1 = max(camHeight + dirY*d, 0.0)*invH;
    float dh = h1 - h0;
    float e0 = exp(-h0);
    return abs(dh) > FOG_SERIES ? (e0 - exp(-h1))/dh : e0*(1.0 - 0.5*dh);
}

//Optical depth of the ground fog from the camera along a ray with world dir.y = dirY over dist blocks. The density is
// fog.x exp(-height / fogHeight) (an exponential height fog), heights clamped at sea level (density at most fog.x); the integral
// of an exponential along a straight line is closed-form: dist (e^-h0 - e^-h1) / (h1 - h0) in fog heights. Never decreases with
// dist, never increases with the camera's height. With fog.y = 0 (NO_SKY, FOG) it is fog.x dist.
float groundFogDepth(Atmosphere a, float dirY, float dist) {
    float d = dist > 0.0 ? min(dist, HAZE_MAX_DISTANCE) : 0.0;
    return max(a.fog.x*d*heightMean(a.fog.z, dirY, d, a.fog.y), 0.0);
}

//The aerial haze's air mass over a haze distance d (hazeDistance) along a view with world dir.y = dirY: the far rate ambient.w per block
// plus the near veil (see VEIL_GAIN). Never decreases with d, never increases with the camera's height
float hazeMass(Atmosphere a, float dirY, float d) {
    float p = d*heightMean(a.fog.z, dirY, d, 1.0/VEIL_HEIGHT);
    return max(a.ambient.w, 0.0)*(d + VEIL_GAIN*VEIL_RANGE*(1.0 - exp(-p/VEIL_RANGE)));
}

//The colour of the sky (infinitely far) in world direction dir, scene units: the air's in-scatter over the view's air mass, rain's
// overcast, and the ground fog's band at the horizon (looking below the horizon gives the horizon's fog colour). Without the
// sun-side glow (sunGlow), the sun and moon discs and the stars. NO_SKY: the dimension's fog colour (B17). FOG: vanilla's
// environmental fog colour. NONE: black (keep the game's pixel instead).
vec3 skyColour(Atmosphere a, vec3 dir) {
    if (!atmosphereSky(a)) {
        return fogColour(a, dir);
    }
    vec3 air = overcast(a, airlight(a, dir, skyAirMass(dir.y)));
    //groundFogDepth to infinity upward: fog.x e^-h0 fogHeight / sin(elevation); at and below the horizon the slope is clamped
    float tf = exp(-a.fog.x*exp(-a.fog.z*a.fog.y)/(max(a.fog.y, MIN_DEPTH)*max(dir.y, SKY_FOG_MIN_SLOPE)));
    return air*tf + fogColour(a, dir)*(1.0 - tf);
}

//The glow's lobe toward dir: a Henyey-Greenstein lobe (GLOW_G) around the sun in the glow colour (and around the moon, dimmer)
vec3 glowLobe(Atmosphere a, vec3 dir) {
    float cs = dot(dir, a.sun.xyz);
    return a.glow.rgb*hgPhase(cs, GLOW_G) + a.moonLight.rgb*a.glow.w*hgPhase(-cs, GLOW_G);
}

//How the glow grows along a path of `mass` air masses: like a haze of GLOW_DEPTH per air mass (1 at one air mass)
float glowGain(float mass) {
    return (1.0 - exp(-GLOW_DEPTH*max(mass, 0.0)))/(1.0 - exp(-GLOW_DEPTH));
}

//The glow scattered toward the camera along a path of `mass` air masses (glowLobe times glowGain)
vec3 glowAlong(Atmosphere a, vec3 dir, float mass) {
    return glowLobe(a, dir)*glowGain(mass);
}

//The sun-side glow seen along dir (the whole sky path: stronger toward the horizon), scene units. Add it to skyColour for sky
// pixels. 0 outside ATMOSPHERE_SKY.
vec3 sunGlow(Atmosphere a, vec3 dir) {
    if (!atmosphereSky(a)) {
        return vec3(0.0);
    }
    return glowAlong(a, dir, skyAirMass(dir.y));
}

//The distance the haze acts over for a view distance d (see HAZE_ONSET)
float hazeDistance(float d) {
    float d2 = d*d;
    return d2*d/(d2 + HAZE_ONSET*HAZE_ONSET);
}

//The haze between the camera and a surface dist blocks away along dir: the surface's colour becomes colour * transmittance +
// inscatter. Two media: the aerial haze (the sky's own air over hazeMass air masses, the near veil included, never more than the
// sky's air mass in that direction) and the ground fog (groundFogDepth, coloured fogColour). Both carry their share of the sun-side glow.
// transmittance is per channel in [0, 1], never increases with dist, never decreases with the camera's height, and is 1 with fog
// strength 0 (then inscatter is 0). As dist grows a black surface tends to exactly skyColour + sunGlow (far terrain fades into
// the sky behind it; at and below the horizon the ground fog hides it entirely). amount (0..1) scales both optical depths (see
// hazeAmount). NO_SKY and FOG: a uniform fog of density fog.x in the fog colour. NONE: transmittance 1, inscatter 0.
void haze(Atmosphere a, vec3 dir, float dist, float amount, out vec3 transmittance, out vec3 inscatter) {
    //Comparisons, not clamp: a NaN distance or amount counts as 0 (clamp of NaN is undefined)
    float k = amount > 0.0 ? min(amount, 1.0) : 0.0;
    //Final fix R5: no amount (caves, vanilla pixels under an environmental fog) is no haze, exactly what the full evaluation gives
    if (!atmosphereOn(a) || k <= 0.0) {
        transmittance = vec3(1.0);
        inscatter = vec3(0.0);
        return;
    }
    float d = hazeDistance(dist > 0.0 ? min(dist, HAZE_MAX_DISTANCE) : 0.0);
    float tf = exp(-groundFogDepth(a, dir.y, d)*k);
    if (!atmosphereSky(a)) {
        transmittance = vec3(tf);
        inscatter = fogColour(a, dir)*(1.0 - tf);
        return;
    }
    //Final fix R5: the sky's air mass along dir and the glow's lobe are evaluated once for the path and the fog's sky behind it
    float skyMass = skyAirMass(dir.y);
    float mass = min(hazeMass(a, dir.y, d), skyMass)*k;
    vec3 tau = max(a.depth.rgb + a.depth.w, vec3(MIN_DEPTH));
    vec3 ta = exp(-tau*mass)*exp(-lowBand(a, mass, skyMass));
    transmittance = ta*tf;
    vec3 lobe = glowLobe(a, dir);
    inscatter = (overcast(a, airlight(a, dir, mass)) + lobe*glowGain(mass))*tf;
    if (tf < 1.0) {
        //The ground fog's share: its colour (the horizon sky in dir's azimuth) and the whole sky path's glow (sunGlow)
        inscatter += (fogColour(a, dir) + lobe*glowGain(skyMass))*(1.0 - tf);
    }
}

//colour seen through the haze (see haze)
vec3 applyHaze(Atmosphere a, vec3 colour, vec3 dir, float dist, float amount) {
    vec3 t;
    vec3 i;
    haze(a, dir, dist, amount, t, i);
    return colour*t + i;
}

//Vanilla's stars (and, at night, its moon) for a sky pixel along dir: pixel is the main colour in scene units (vanillaToScene),
// vanillaSky/vanillaFog the composite's slots 26 and 27, gate its slot 28. What vanilla drew above max(sky, fog), its white part
// only (the minimum channel: the fan is coloured, stars are white), dropped on the sun's side by the fan's strength, times the star
// weight. Add it to skyColour + sunGlow. 0 by day and outside ATMOSPHERE_SKY.
vec3 vanillaStars(Atmosphere a, vec3 dir, vec3 pixel, vec4 vanillaSky, vec4 vanillaFog, vec4 gate) {
    if (!atmosphereSky(a) || !(gate.x > 0.0)) {
        return vec3(0.0);
    }
    vec3 residue = max(pixel - max(vanillaSky.rgb, vanillaFog.rgb), vec3(0.0));
    float white = min(residue.r, min(residue.g, residue.b));
    float ld = length(dir.xz);
    float ls = length(a.sun.xz);
    float side = ld > 0.0001 && ls > 0.0001 ? dot(dir.xz/ld, a.sun.xz/ls) : 0.0;
    float fan = gate.y > 0.0 ? min(gate.y, 1.0) : 0.0;
    float keep = 1.0 - fan*smoothstep(FAN_SIDE_LOW, FAN_SIDE_HIGH, side);
    return vec3(white*keep*min(gate.x, 1.0));
}

//The far clouds' premultiplied colour and coverage from clouds.glsl's cloudMarch result m (x light-weighted coverage, y coverage):
// rgb = m.x lit + m.y ambient, a = m.y. Composite over the sky with sky * (1 - a) + rgb.
vec4 cloudColour(CloudLight c, vec3 m) {
    float x = m.x > 0.0 ? m.x : 0.0;
    float y = m.y > 0.0 ? min(m.y, 1.0) : 0.0;
    return vec4(x*c.lit.rgb + y*c.ambient.rgb, y);
}

//cloudColour hazed over the march's mean distance m.z along dir (the in-scatter weighted by the coverage)
vec4 cloudOver(Atmosphere a, CloudLight c, vec3 m, vec3 dir, float amount) {
    vec4 cloud = cloudColour(c, m);
    vec3 t;
    vec3 i;
    haze(a, dir, m.z, amount, t, i);
    return vec4(cloud.rgb*t + i*cloud.a, cloud.a);
}
