#version 460
//    This file is part of the Distant Horizons mod
//    licensed under the GNU LGPL v3 License.
//
//    Copyright (C) 2020 James Seibel
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU Lesser General Public License as published by
//    the Free Software Foundation, version 3.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU Lesser General Public License for more details.
//
//    You should have received a copy of the GNU Lesser General Public License
//    along with this program.  If not, see <https://www.gnu.org/licenses/>.
//
//
// Translated for VulkanMod-Reforged from Distant Horizons
// core/core/src/main/resources/assets/distanthorizons/shaders/terrain/gl/vert.vert (DH core d7a62ce; authors James Seibel,
// TomTheFurry, stduhpf, coolGi): the vertex decoding only (u16 position relative to the container corner, the micro offset along
// x and z), for the sun's shadow cascades: depth only, no colour and no light. The cascade's matrix and the corner relative to the
// cascade's camera come from DhExtension.drawCasters; the cutoff (shadow.frag) is the fork's.

layout(location = 0) in uvec4 vPosition;//u16 x, y, z, meta

layout(location = 0) uniform mat4 lightViewProjection;//the cascade: camera-relative position to its clip space (depth 0..1)
layout(location = 1) uniform vec4 modelOffset;//xyz: the container's corner minus the cascade's camera (blocks)
layout(location = 2) uniform vec4 params;//x: the micro offset (blocks), y: the caster cutoff (blocks, 0 for none)

layout(location = 0) out vec2 horizontal;//the vertex's x and z relative to the cascade's camera
layout(location = 1) flat out float cutoff;

void main() {
    uint meta = vPosition.w;
    vec3 pos = vec3(vPosition.xyz) + modelOffset.xyz;

    //The micro offset, 2 bits an axis (0b00zzyyxx; 01 positive, 11 negative), closes the seams between neighbouring quads.
    // DH applies x and z only.
    uint micro = (meta >> 8u) & 0x3Fu;
    float mx = (micro & 1u) != 0u ? params.x : 0.0;
    mx = (micro & 2u) != 0u ? -mx : mx;
    float mz = (micro & 16u) != 0u ? params.x : 0.0;
    mz = (micro & 32u) != 0u ? -mz : mz;
    pos.x += mx;
    pos.z += mz;

    horizontal = pos.xz;
    cutoff = params.y;
    gl_Position = lightViewProjection*vec4(pos, 1.0);
}
