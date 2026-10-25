#version 460
//Distant Horizons LODs into the sun's shadow cascades (DhExtension.drawCasters): depth only. Inside the square around the
// cascade's camera where VulkanMod's vanilla terrain casts (DhShadowCasters.cutoff: half its side, in blocks; 0 for none) the LOD
// casts nothing, so the vanilla casters keep their cutout shapes there (DH's colour-only LODs would cast leaves as solid blocks).

layout(location = 0) in vec2 horizontal;
layout(location = 1) flat in float cutoff;

void main() {
    if (cutoff > 0.0 && max(abs(horizontal.x), abs(horizontal.y)) < cutoff) {
        discard;
    }
}
