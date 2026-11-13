//2026-11-13 (shader roadmap session 19, the user: "portal light, as AstraLex draws it"): nether portals' violet light. PortalLights (Java)
// finds the boxes of portal blocks near the camera; the composite colours the block light around them violet and makes the portal itself
// glow (bloom then spreads it). No uniforms or bindings: any shader can import it. PortalMath.java mirrors every const and function here:
// change both together (DimensionEffectsTest compares them).
//
// THE MODEL. Vanilla's block light already spreads the portal's light (level 11 at the portal, one less per block, blocked by walls); it
// only lacks the colour. A pixel's block light is the portal's where its level is no more than the portal's would be there: 11 minus the
// taxicab distance (blocks) from the box (vanilla's light steps one level per block, along the axes). Where another light is brighter
// (a torch nearby) the share falls to 0 over PORTAL_SHARE_SPAN levels. That share tints the block light's colour violet and lifts it
// by PORTAL_LIGHT_GAIN; a pixel on the portal's surface (within PORTAL_SURFACE of the box) glows PORTAL_GLOW times brighter, pulsing slowly.

//Vanilla's nether portal block light level
const float PORTAL_LEVEL = 11.0;
//Levels over which a brighter other light takes the share away, and the levels at the edge of the light over which it fades in
const float PORTAL_SHARE_SPAN = 2.0;
const float PORTAL_EDGE = 2.0;
//The light's colour (linear; its luminance about 0.4: blockLightFactor rescales it to the light's luminance) and its lift
const float PORTAL_R = 0.75;
const float PORTAL_G = 0.2;
const float PORTAL_B = 1.0;
const float PORTAL_LIGHT_GAIN = 0.5;
//The portal's own surface: within this many blocks of the box, its glow gain, the pulse's depth and speed (radians per second)
const float PORTAL_SURFACE = 0.05;
const float PORTAL_GLOW = 4.0;
const float PORTAL_PULSE = 0.15;
const float PORTAL_PULSE_SPEED = 2.4;

//The taxicab distance (blocks) from pos to the box lo..hi (0 inside)
float portalDistance(vec3 pos, vec3 lo, vec3 hi) {
    vec3 d = max(max(lo - pos, pos - hi), vec3(0.0));
    return d.x + d.y + d.z;
}

//The share (0..1) of the block light at level block that is the portal's, at pos with the box lo..hi
float portalShare(vec3 pos, float block, vec3 lo, vec3 hi) {
    float expected = PORTAL_LEVEL - portalDistance(pos, lo, hi);
    if (!(expected > 0.0) || !(block > 0.0)) {
        return 0.0;
    }
    return clamp((expected + PORTAL_SHARE_SPAN - block)/PORTAL_SHARE_SPAN, 0.0, 1.0)*clamp(expected/PORTAL_EDGE, 0.0, 1.0);
}

//The glow gain of a pixel on the portal's surface at time t (seconds), the strength applied, at pos (its pulse's phase)
float portalGlowGain(vec3 worldPos, float t, float strength) {
    float phase = dot(floor(worldPos), vec3(0.37, 0.61, 0.23));
    return 1.0 + PORTAL_GLOW*strength*(1.0 - PORTAL_PULSE + PORTAL_PULSE*sin(t*PORTAL_PULSE_SPEED + phase));
}
