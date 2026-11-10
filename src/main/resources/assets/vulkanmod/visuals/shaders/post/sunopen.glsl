//2026-11-10 (the user: at sunrise and sunset the sun shone through ice spikes and hills): the share of the sun's disc the screen shows
// open (water.glsl's "the sun's open share" says why and how; WaterMath mirrors the maths, WaterTest compares). Import after
// viewpos.glsl (clampedToLod, lodTexel and the includer's lodDepth) and water.glsl (SUN_HALF_SIZE, SUN_OPEN_TAP).

//Whether the tap at pixel q shows the sky (the sun open there). Any surface hides the sun: terrain, and a translucent one too, since the
// sea and ice sheets at the horizon are what a sun half below the horizon sets behind
bool sunTapOpen(sampler2D depthTex, ivec2 q, ivec2 size) {
    float depth = texelFetch(depthTex, q, 0).r;
    return depth == FAR || (clampedToLod(depth) && texelFetch(lodDepth, lodTexel(q, size), 0).r == FAR);
}

//The open share 0..1 of the sun (world direction sun) on a screen of size pixels, viewToClip the frame's projection and toView the world
// to view rotation; 1 behind the camera
float sunOpenShare(sampler2D depthTex, mat4 viewToClip, mat3 toView, vec3 sun, ivec2 size) {
    vec4 c = viewToClip*vec4(toView*sun, 0.0);
    if (!(c.w > 0.000001)) {
        return 1.0;
    }
    vec2 uv = c.xy/c.w*0.5 + 0.5;
    vec2 h = SUN_HALF_SIZE*0.5*vec2(abs(viewToClip[0][0]), abs(viewToClip[1][1]));
    float open = 0.0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 t = uv + vec2(float(i), float(j))*SUN_OPEN_TAP*h;
            if (any(lessThan(t, vec2(0.0))) || any(greaterThanEqual(t, vec2(1.0)))) {
                open += 1.0;
                continue;
            }
            ivec2 q = clamp(ivec2(floor(vec2(t.x, 1.0 - t.y)*vec2(size))), ivec2(0), size - 1);
            open += sunTapOpen(depthTex, q, size) ? 1.0 : 0.0;
        }
    }
    return open/9.0;
}
