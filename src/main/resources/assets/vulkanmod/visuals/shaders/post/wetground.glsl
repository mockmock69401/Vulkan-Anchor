//2026-11-13 (shader roadmap session 20, the user after AstraLex: "SSR ... 비 온 뒤 젖은 지면의 반사"): rain-wet ground without a PBR pack.
// Where no LabPBR material takes the rain (pbr.glsl's pbrWet does for a pixel with a specular map), every solid block under the open sky
// gets wet by the same wetness (PbrUniforms: the rain at the camera, eased in over 8 s and dried over 40 s) and the same sky gate
// (pbr.glsl's PBR_WET_SKY_LOW/HIGH): it darkens and deepens its colour (the water in its pores keeps light from leaving: Jensen, Legakis
// and Dorsey 1999, "Rendering of wet materials"), a thin film on top mirrors a little of the sky, and on upward faces puddles gather
// (Lagarde 2013, "Water drop 2b: Dynamic rain and its effects": a puddle is a flat water surface over the darkened ground) in the low
// spots of a world-anchored noise: they spread as the rain goes on and shrink back into their deepest middles as the ground dries. The
// composite (scene_copy.comp) darkens and marks; the reflection pass (reflection.comp) mirrors the scene in the puddles (the screen-space
// march) and the sky in the film, with the rain's rings (rainripple.glsl) on the puddles. Import after water.glsl; no uniforms or
// bindings. WetGroundMath mirrors every constant and function here (WetGroundTest): change both together.
//
// THE MARK. A wet pixel's composite alpha is -(WET_MARK_BASE + its mirror share 0..1): between the metal blocks' -4 and PBR's -7.5, so
// water.glsl's reflectivity and hit tests take it as a solid surface (a reflected ray stops on wet ground), blockreflect.glsl's classes are
// read only after isWetMark said no, and half floats keep the share to 1/256.

//The mark: -(base + share), and the range wet marks fall in
const float WET_MARK_BASE = 5.0;
const float WET_MARK_TOP = -4.5;
const float WET_MARK_BOTTOM = -6.5;
//The shares below this mark nothing (nothing to mirror: the pixel stays as the composite drew it)
const float WET_SHARE_MIN = 0.004;
//The sky light (levels) over which a surface gets the rain, as pbr.glsl's PBR_WET_SKY_LOW/HIGH
const float WET_SKY_LOW = 13.5;
const float WET_SKY_HIGH = 14.75;
//How much darker wet ground gets (vanilla's blocks carry no porosity: between PBR's closed 0 and fully porous 0.55), in a puddle (the
// water over it), and how much deeper its colour (half pbr.glsl's PBR_WET_SATURATION: at 0.25 a dirt path turned orange)
const float WET_DARKEN = 0.3;
const float WET_PUDDLE_DARKEN = 0.45;
const float WET_SATURATION = 0.12;
//The film's mirror share on an upward face, and its share of that on an upright one (pbr.glsl's PBR_WET_SIDE_FILM)
const float WET_FILM = 0.3;
const float WET_SIDE_FILM = 0.35;
//The puddles: noise cells (blocks), the noise's period (blocks: the camera's wrap shows no seam), the share of the ground (in the
// noise's range) they cover at full wetness and default strength, their edges' width (in the noise's range), the upward faces they lie on
const float WET_PUDDLE_CELL = 3.0;
const float WET_PUDDLE_PERIOD = 240.0;
const float WET_PUDDLE_COVER = 0.42;
const float WET_PUDDLE_EDGE = 0.06;
const float WET_PUDDLE_UP = 0.9;
//The second octave's weight (the first's is 1 minus it)
const float WET_PUDDLE_DETAIL = 0.35;
//The reflection pass marches the screen for a share from this on (a puddle); the film mirrors the sky alone (marching every grazing wet
// pixel cost 1.2 ms at 1440p for PBR, pbr.glsl's PBR_REFLECT_MIN_MARCH)
const float WET_MARCH_SHARE = 0.5;
//A film's pixel whose reflectivity (its share times water's Fresnel term) is below this is left as the composite drew it
const float WET_REFLECT_MIN = 0.01;
//The reflective debug view's colour for wet ground
const float DEBUG_WET_RED = 0.2;
const float DEBUG_WET_GREEN = 0.5;
const float DEBUG_WET_BLUE = 1.0;

bool isWetMark(float mark) {
    return mark <= WET_MARK_TOP && mark >= WET_MARK_BOTTOM;
}

float wetShareOf(float mark) {
    return clamp(-mark - WET_MARK_BASE, 0.0, 1.0);
}

//How wet a surface is: the frame's wetness (0..1) where the sky is open above it, times the setting's strength (up to 1)
float wetGroundAt(float wetness, float skyLevel, float strength) {
    return clamp(wetness, 0.0, 1.0)*smoothstep(WET_SKY_LOW, WET_SKY_HIGH, skyLevel)*clamp(strength, 0.0, 1.0);
}

//A lattice value 0..1 at cell c, repeating every period cells
float wetHash(vec2 c, float period) {
    vec2 m = mod(c, period);
    uint h = uint(m.x)*73856093u ^ uint(m.y)*19349663u;
    h = (h ^ (h >> 13u))*1274126177u;
    h ^= h >> 16u;
    return float(h & 65535u)/65535.0;
}

//Smooth value noise 0..1 at x (cells), repeating every period cells
float wetNoise(vec2 x, float period) {
    vec2 c = floor(x);
    vec2 f = x - c;
    f = f*f*(3.0 - 2.0*f);
    float a = wetHash(c, period);
    float b = wetHash(c + vec2(1.0, 0.0), period);
    float d = wetHash(c + vec2(0.0, 1.0), period);
    float e = wetHash(c + vec2(1.0, 1.0), period);
    return mix(mix(a, b, f.x), mix(d, e, f.x), f.y);
}

//The puddle field 0..1 at a world position x, z (wrapped at WET_PUDDLE_PERIOD): two octaves, high where puddles form first
float wetPuddleField(vec2 pos) {
    float cells = WET_PUDDLE_PERIOD/WET_PUDDLE_CELL;
    vec2 x = pos/WET_PUDDLE_CELL;
    return (1.0 - WET_PUDDLE_DETAIL)*wetNoise(x, cells) + WET_PUDDLE_DETAIL*wetNoise(x*2.0 + vec2(17.0, 31.0), 2.0*cells);
}

//The puddle share 0..1 at a world position on a face with world normal n, for a wetness wet (wetGroundAt) and the setting's strength
// (0..2): only on upward faces; the cover grows with the wetness, so drying puddles shrink into their middles
float wetPuddle(vec2 pos, vec3 n, float wet, float strength) {
    if (!(wet > 0.0) || n.y < WET_PUDDLE_UP) {
        return 0.0;
    }
    float t = 1.0 - WET_PUDDLE_COVER*clamp(strength, 0.0, 2.0)*wet;
    return smoothstep(t, t + WET_PUDDLE_EDGE, wetPuddleField(pos));
}

//A wet surface's lit colour: darker and deeper, the more under a puddle
vec3 wetGroundColour(vec3 c, float wet, float puddle) {
    float soak = clamp(wet, 0.0, 1.0);
    float darken = soak*mix(WET_DARKEN, WET_PUDDLE_DARKEN, clamp(puddle, 0.0, 1.0));
    float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
    return max(mix(vec3(l), c, 1.0 + WET_SATURATION*soak), vec3(0.0))*(1.0 - darken);
}

//The mirror share: the film's (less on an upright face) up to a puddle's whole
float wetShare(float wet, float puddle, vec3 n) {
    float film = WET_FILM*clamp(wet, 0.0, 1.0)*mix(WET_SIDE_FILM, 1.0, clamp(n.y, 0.0, 1.0));
    return mix(film, 1.0, clamp(puddle, 0.0, 1.0));
}

//The composite's alpha for a wet pixel (0: nothing to mirror)
float wetGroundMark(float share) {
    return share >= WET_SHARE_MIN ? -(WET_MARK_BASE + clamp(share, 0.0, 1.0)) : 0.0;
}

//A mark, or where it is none the wet ground's
float wetOr(float mark, float wetMark) {
    return mark != 0.0 ? mark : wetMark;
}
