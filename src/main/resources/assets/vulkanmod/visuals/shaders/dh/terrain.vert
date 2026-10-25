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
// Translated for VulkanMod-Reforged from Distant Horizons
// core/core/src/main/resources/assets/distanthorizons/shaders/terrain/gl/vert.vert (DH core d7a62ce; authors James Seibel,
// TomTheFurry, stduhpf, coolGi): the vertex decoding (u16 position relative to the container corner, the micro offset along x
// and z, sky and block light from the meta word) and the colour (the vertex colour times the lightmap at the vertex's light).
// Left out: the earth curvature (DH's option, off by default), the TAA jitter, the white-world debug mode and the texture tile
// (the fork draws DH's colour-only mode). The matrix and the camera-relative corner come from DhExtension; the projection has
// Vulkan's 0..1 depth (DhDepth.toVulkan).

layout(location = 0) in uvec4 vPosition;//u16 x, y, z, meta
layout(location = 1) in vec4 colour;//u8 r, g, b, a as unorm
layout(location = 2) in uvec2 vFace;//u8 material, u8 normal (DH's face index: down, up, north, south, west, east)

layout(binding = 0) uniform sampler2D lightMap;//vanilla's 16x16 lightmap: block light along u, sky light along v

layout(location = 0) uniform mat4 dhViewProjection;//DH's projection (depth 0..1) times DH's model view
layout(location = 1) uniform vec4 modelOffset;//xyz: the container's corner minus the camera (blocks)
layout(location = 2) uniform vec4 params;//x: the micro offset (blocks), y: the discard distance (blocks, 0 for none)

layout(location = 0) out vec4 vertexColour;
layout(location = 1) out vec3 cameraOffset;//the vertex relative to the camera
layout(location = 2) flat out uint vertexLight;//sky light in the low nibble, block light in the high one; the face index plus one above (0: none)
layout(location = 3) flat out float discardDistance;

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

    uint lights = meta & 0xFFu;
    vertexColour = vec4(texelFetch(lightMap, ivec2(lights >> 4u, lights & 15u), 0).rgb, 1.0)*colour;
    //2026-10-19: the face above the light, for the lighting pass's LOD normal (DhVertexFormat.lightAttachment)
    vertexLight = lights | ((vFace.y < 6u ? vFace.y + 1u : 0u) << 8u);
    cameraOffset = pos;
    discardDistance = params.y;
    gl_Position = dhViewProjection*vec4(pos, 1.0);
}
