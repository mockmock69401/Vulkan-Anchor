#version 450 core
//The visuals' full-screen post-process (design 2026-10-10 P7b 3.3), drawn once in Reforged's resumed main pass before the
// hand and the GUI. Reforged's viewport is flipped, so the images are read at gl_FragCoord (row 0 at the top, as written).
// The scene arrives linear HDR before exposure, with the atmosphere applied (P7c).
#import <vulkanmod:visuals/post/postfinal.glsl>

//2026-11-09 (session 6): the shader weather overlay (weather.comp: premultiplied display colour, coverage), read while screenParams.w is 1
layout(binding = 3) uniform sampler2D weatherTex;

out vec4 colour;

void main() {
    colour = finalColour(gl_FragCoord.xy);
    if (screenParams.w > 0.5) {
        vec4 w = texelFetch(weatherTex, ivec2(gl_FragCoord.xy), 0);
        colour.rgb = colour.rgb*(1.0 - w.a) + w.rgb;
    }
}
