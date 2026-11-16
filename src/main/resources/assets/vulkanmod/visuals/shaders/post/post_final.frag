#version 450 core
//The visuals' full-screen post-process (design 2026-10-10 P7b 3.3), drawn once in Reforged's resumed main pass before the
// hand and the GUI. Reforged's viewport is flipped, so the images are read at gl_FragCoord (row 0 at the top, as written).
// The scene arrives linear HDR before exposure, with the atmosphere applied (P7c).
#import <vulkanmod:visuals/post/postfinal.glsl>

//2026-11-09 (session 6): the shader weather overlay (weather.comp: premultiplied display colour, coverage), read while screenParams.w is 1
layout(binding = 3) uniform sampler2D weatherTex;
//2026-11-16 (shader roadmap session 26): water running down the lens (lenswater.glsl; LensWater; postfinal.glsl's lensParams), drawn
// while lensParams.y > 0
#import <vulkanmod:visuals/post/lenswater.glsl>

out vec4 colour;

void main() {
    vec2 at = gl_FragCoord.xy;
    float veil = 0.0;
    if (lensParams.y > 0.0) {
        vec2 size = 1.0/screenParams.xy;
        vec3 lens = lensWater(at, size, lensParams);
        at = clamp(at + lens.xy, vec2(0.5), size - 0.5);
        veil = lens.z;
    }
    colour = finalColour(at);
    if (screenParams.w > 0.5) {
        vec4 w = texelFetch(weatherTex, ivec2(at), 0);
        colour.rgb = colour.rgb*(1.0 - w.a) + w.rgb;
    }
    colour.rgb = clamp(veil > 0.0 ? lensVeil(colour.rgb, veil) : colour.rgb, 0.0, 1.0);
}
