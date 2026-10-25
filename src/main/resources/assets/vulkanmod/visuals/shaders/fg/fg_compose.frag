#version 450 core
//Frame generation (FG design 4, 5.1 step 4): the filled world, the vignette (a multiply the UI layer cannot hold), the UI layer
// (premultiplied: ui.rgb + world*(1 - ui.a)) and the crosshair's inversion blend, into the swapchain image. The world is filtered
// up when the render scale is below 100%. Debug views 1 and 3 (flags.z, on warped frames only: crosshair.y) write the warp's mark (hole 0, edge 0.5, valid 1) as the output alpha;
// view 3 also tints holes red and edge-clamped pixels blue. Every other frame has alpha 1. (A real frame composed in view 1's burst pair
// shows its world image's depth there: the pair's analysis reads the mark from the warped image only.)
layout(binding = 0) uniform sampler2D worldTex;
layout(binding = 1) uniform sampler2D uiTex;
layout(binding = 2) uniform sampler2D vignetteTex;
layout(binding = 3) uniform sampler2D iconsTex;

#import <vulkanmod:visuals/fg/params.glsl>

out vec4 colour;

void main() {
    vec2 frag = gl_FragCoord.xy;
    vec2 uv = frag/size.zw;
    vec3 world = textureLod(worldTex, uv, 0.0).rgb;

    int view = int(flags.z + 0.5);
    float outAlpha = 1.0;
    if ((view == 3 || view == 1) && crosshair.y > 0.5) {
        float mark = texelFetch(worldTex, clamp(ivec2(uv*size.xy), ivec2(0), ivec2(size.xy) - 1), 0).a;
        outAlpha = mark;
        if (view == 3) {
            if (mark < 0.25) {
                world = mix(world, vec3(1.0, 0.0, 0.0), 0.7);
            } else if (mark < 0.75) {
                world = mix(world, vec3(0.0, 0.0, 1.0), 0.7);
            }
        }
    }
    if (vignette.a > 0.5) {
        world *= 1.0 - textureLod(vignetteTex, uv, 0.0).rgb*vignette.rgb;
    }
    vec3 result = world;
    if (flags.w > 0.5) {
        vec4 ui = texelFetch(uiTex, ivec2(frag), 0);
        result = ui.rgb + world*(1.0 - ui.a);
    }
    int quadCount = min(int(crosshair.x + 0.5), 8);
    for (int i = 0; i < quadCount; i++) {
        vec4 rect = quads[2*i];
        vec4 uvRect = quads[2*i + 1];
        vec2 local = (frag - rect.xy)/rect.zw;
        if (all(greaterThanEqual(local, vec2(0.0))) && all(lessThan(local, vec2(1.0)))) {
            float a = textureLod(iconsTex, uvRect.xy + local*uvRect.zw, 0.0).a;
            result = mix(result, 1.0 - result, a);
        }
    }
    colour = vec4(result, outAlpha);
}
