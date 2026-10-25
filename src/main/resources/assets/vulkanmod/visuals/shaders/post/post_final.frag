#version 450 core
//The visuals' full-screen post-process (design 2026-10-10 P7b 3.3), drawn once in Reforged's resumed main pass before the
// hand and the GUI. Reforged's viewport is flipped, so the images are read at gl_FragCoord (row 0 at the top, as written).
// The scene arrives linear HDR before exposure, with the atmosphere applied (P7c).
#import <vulkanmod:visuals/post/postfinal.glsl>

out vec4 colour;

void main() {
    colour = finalColour(gl_FragCoord.xy);
}
