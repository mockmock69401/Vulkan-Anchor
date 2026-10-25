#version 460
//Visuals P7o design 4.2: cutouts (grass, leaves, flowers) cast their own shape: the block atlas's transparent texels cast nothing,
// by the layer's own cutout as VulkanMod draws it (final review I1: solid 0, cutout_mipped 0.5, cutout and tripwire 0.1)

layout(binding = 0) uniform sampler2D atlas;

layout(location = 0) in vec2 uv;
layout(location = 1) flat in float cutout;

void main() {
    if (textureLod(atlas, uv, 0.0).a < cutout) {
        discard;
    }
}
