//2026-11-16 (shader roadmap session 26; the user: "세번째 사진 처럼 이동할 때 물결치는 효과도 구현해줘", Experimental > Interaction): the wake of
// whatever swims, wades or floats through the water. Each entity at the surface leaves a trail of sources (WakeMath, Wakes: one every
// WakeMath.STEP seconds, newest first); every source sends out a ring at its own speed, and the water takes two waves from them:
//  - The wake: each moment of the trail sends out a short wave packet (WAKE_PACKET_WAVE crests apart, WAKE_PACKET_WIDTH wide) with no
//    phase of its own. The packets the trail sent cancel out where their rings pass a pixel one after the other, and add up where the rings
//    bunch up: along the arms of a V behind what moves faster than its rings (a Kelvin wake's arms, crests running along them), and in a
//    bow wave just around it. The trail is taken as continuous: between two sources the packet is integrated exactly (the ring's distance
//    to the pixel runs linearly between theirs), so there is no ring per source to see.
//  - The rings: the sources also carry a wave's phase (WakeMath.Trail, WAKE_WAVELENGTH apart), and a pixel takes the ring passing over it
//    with the phase it left with (where two neighbouring sources' rings lie on either side of the pixel, linearly between them): a body at
//    rest, bobbing, sends out concentric rings (at IDLE amplitude).
// WakeMath sets the ring speed from the entity's speed so that the V keeps about a Kelvin wake's opening.
//
// The data (binding WakeMath.BINDING, vec4s, camera relative): each trail is cut into chunks of WakeMath.CHUNK sources (each chunk also
// holding the next chunk's first source, so every neighbouring pair is in one chunk); chunk c's header at 2c (x, z of the circle its
// sources' rings and packets stay in, its radius, the water surface's y) and 2c + 1 (the index of its first source, its source count): a
// pixel outside a chunk's circle skips its sources. Source i at first + 2i (x, z, age in seconds, ring
// speed in blocks a second) and first + 2i + 1 (the wake's amplitude, the rings' phase in cycles 0..1, 1 when joined to the older source
// after it, the rings' amplitude). wakeParams (the pass's loose uniform slot WakeMath.SLOT): x the setting (ratio, 0 = off), y the chunk
// count, z the sources' lifetime (seconds), w unused. WakeMath mirrors the consts and the wave (WakeTest).

//At most this many chunks, this many sources each (WakeMath.MAX_CHUNKS, CHUNK + 1)
const int WAKE_MAX_CHUNKS = 96;
const int WAKE_MAX_SOURCES = 9;
//The rings: their crests this far apart (blocks), their slope at amplitude 1, and their spread (the amplitude falls as
// 1 / sqrt(1 + radius / WAKE_SPREAD) as a ring grows)
const float WAKE_WAVELENGTH = 0.4;
const float WAKE_SLOPE = 0.5;
const float WAKE_SPREAD = 1.0;
//The wake's packet: crests this far apart, its half width (blocks); its height (blocks) where a wake of amplitude 1 adds up fully
// (WAKE_PACKET_GAIN: the integral over the trail's time there, about 1 / its seconds)
const float WAKE_PACKET_WAVE = 0.3;
const float WAKE_PACKET_WIDTH = 0.35;
const float WAKE_PACKET_HEIGHT = 0.04;
const float WAKE_PACKET_GAIN = 8.0;
//A trail draws on water within this many blocks above or below its surface (not on a pond under a bridge it crosses)
const float WAKE_Y_RANGE = 0.6;
//A crest catches this share of the light the water mirrors there (at amplitude 1)
const float WAKE_SHINE = 0.25;
//Foam on young crests: at most WAKE_FOAM, from crests this young (seconds)
const float WAKE_FOAM = 0.45;
const float WAKE_FOAM_AGE = 0.45;
//The foam's brightness: this share of the brighter of the mirrored light and twice the water's own
const float WAKE_FOAM_WHITE = 0.85;
//The wave fades out as half a wavelength covers fewer pixels: none at WAKE_FADE_NONE, full from WAKE_FADE_FULL
const float WAKE_FADE_NONE = 0.5;
const float WAKE_FADE_FULL = 2.0;

//The wake's packet at e (blocks outside the ring): its integral H, its height h = H' (the packet: a Gaussian times a cosine, less a small
// sine term so that H is a Gaussian times a sine), and h'
void wakePacket(float e, out float H, out float h, out float dh) {
    float k = 6.2831853/WAKE_PACKET_WAVE;
    float w2 = WAKE_PACKET_WIDTH*WAKE_PACKET_WIDTH;
    float g = exp(-e*e/w2);
    float s = sin(k*e);
    float c = cos(k*e);
    float b = 2.0*e/w2;
    H = g*s/k;
    h = g*(c - b*s/k);
    dh = g*(-2.0*b*c - k*s + (b*b - 2.0/w2)*s/k);
}

//The wake at a water pixel at world (camera relative), footprint the blocks one pixel covers: xy its slope (d height / d x, d height / d z),
// z the crests' shine (a share of the mirrored light), w its foam (0..1)
vec4 wakeAt(vec3 world, float footprint) {
    float pixels = footprint > 0.000001 ? min(WAKE_WAVELENGTH, WAKE_PACKET_WAVE)*0.5/footprint : 1000000.0;
    float fade = smoothstep(WAKE_FADE_NONE, WAKE_FADE_FULL, pixels);
    if (!(wakeParams.x > 0.0) || fade <= 0.0) {
        return vec4(0.0);
    }
    float life = max(wakeParams.z, 0.001);
    int chunks = min(int(wakeParams.y + 0.5), WAKE_MAX_CHUNKS);
    float reach = 3.0*WAKE_PACKET_WIDTH;
    vec2 slope = vec2(0.0);
    vec2 wake = vec2(0.0);
    float height = 0.0;
    float shine = 0.0;
    float foam = 0.0;
    for (int t = 0; t < chunks; t++) {
        vec4 head = wakeData[2*t];
        vec2 dc = world.xz - head.xy;
        if (dot(dc, dc) > head.z*head.z || abs(world.y - head.w) > WAKE_Y_RANGE) {
            continue;
        }
        vec4 range = wakeData[2*t + 1];
        int first = int(range.x + 0.5);
        int count = min(int(range.y + 0.5), WAKE_MAX_SOURCES);
        vec4 newer = vec4(0.0);
        vec4 newerWave = vec4(0.0);
        vec2 newerDir = vec2(0.0);
        float newerE = 0.0;
        //The packet at the newer source (its integral and height; 0 beyond reach, where the Gaussian is under 1e-4)
        float newerH = 0.0;
        float newerHeight = 0.0;
        for (int i = 0; i < count; i++) {
            vec4 s = wakeData[first + 2*i];
            vec4 wave = wakeData[first + 2*i + 1];
            //How far the pixel lies outside this source's ring, and the way out from the source
            vec2 d = world.xz - s.xy;
            float dist = length(d);
            float e = dist - s.w*s.z;
            vec2 dir = dist > 0.0001 ? d/dist : vec2(0.0);
            float H = 0.0;
            float hh = 0.0;
            float dhh = 0.0;
            if (abs(e) < reach) {
                wakePacket(e, H, hh, dhh);
            }
            if (i > 0 && newerWave.z > 0.5) {
                float age = 0.5*(newer.z + s.z);
                //The wake and the rings fade out linearly with age
                float live = 1.0 - clamp(age/life, 0.0, 1.0);
                //The wake: the packets of the trail between the two, while the pixel lies within reach of their rings
                if (min(newerE, e) < reach && max(newerE, e) > -reach) {
                    float a = 0.5*(newerWave.x + wave.x)*live*inversesqrt(1.0 + max(0.5*(newer.w*newer.z + s.w*s.z), 0.0)/WAKE_SPREAD);
                    float span = s.z - newer.z;
                    float de = e - newerE;
                    float part;
                    vec2 grad;
                    if (abs(de) > 0.02*WAKE_PACKET_WIDTH) {
                        part = span*(H - newerH)/de;
                        grad = span*((hh*dir - newerHeight*newerDir)/de - (H - newerH)*(dir - newerDir)/(de*de));
                    } else {
                        float i0, h0, dh0;
                        wakePacket(0.5*(newerE + e), i0, h0, dh0);
                        part = span*h0;
                        grad = span*dh0*0.5*(dir + newerDir);
                    }
                    wake += a*grad;
                    height += a*part;
                    foam += a*max(part, 0.0)*exp(-age/WAKE_FOAM_AGE);
                }
                //The rings: the ring between this source and the newer one passes over the pixel
                if ((newerE > 0.0) != (e > 0.0)) {
                    float k = newerE/(newerE - e);
                    vec2 centre = mix(newer.xy, s.xy, k);
                    vec2 dd = world.xz - centre;
                    float r = length(dd);
                    float ringAge = mix(newer.z, s.z, k);
                    //The phase between the two (their difference taken the short way round the cycle)
                    float dp = wave.y - newerWave.y;
                    float phase = newerWave.y + k*(dp - floor(dp + 0.5));
                    float ringLive = 1.0 - clamp(ringAge/life, 0.0, 1.0);
                    float a = mix(newerWave.w, wave.w, k)*ringLive*inversesqrt(1.0 + r/WAKE_SPREAD);
                    if (r > 0.0001) {
                        //Height a sin(2 pi phase); the phase falls by a cycle a wavelength outward
                        slope -= dd/r*(a*cos(6.2831853*phase));
                    }
                    shine += a*max(sin(6.2831853*phase), 0.0);
                }
            }
            newer = s;
            newerWave = wave;
            newerE = e;
            newerDir = dir;
            newerH = H;
            newerHeight = hh;
        }
    }
    float strength = wakeParams.x*fade;
    //The wake's height in blocks is WAKE_PACKET_HEIGHT x GAIN x the integral; the rings' slope WAKE_SLOPE x their sum
    slope = slope*WAKE_SLOPE + wake*(WAKE_PACKET_GAIN*WAKE_PACKET_HEIGHT);
    shine += max(height*WAKE_PACKET_GAIN, 0.0);
    foam *= WAKE_PACKET_GAIN;
    return vec4(slope*strength, min(shine, 1.5)*WAKE_SHINE*strength, clamp(foam*WAKE_FOAM*strength, 0.0, 1.0));
}
