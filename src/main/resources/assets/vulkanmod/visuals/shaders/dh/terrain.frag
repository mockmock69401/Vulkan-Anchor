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
// core/core/src/main/resources/assets/distanthorizons/shaders/terrain/gl/frag.frag (DH core d7a62ce): the colour is the
// vertex's, and a fragment nearer to the camera than the discard distance (DH's near clip distance plus 16 blocks, where vanilla
// terrain is drawn) is discarded. Left out: the texture tiles, the noise texture and the dithered fade (DH options; the boundary
// treatment is the fork's, later). The second output is the fragment's light and face for the LOD light attachment (LodTarget, R16_UINT).

layout(location = 0) in vec4 vertexColour;
layout(location = 1) in vec3 cameraOffset;
layout(location = 2) flat in uint vertexLight;
layout(location = 3) flat in float discardDistance;

layout(location = 0) out vec4 fragColour;
layout(location = 1) out uint fragLight;

void main() {
    if (discardDistance > 0.0 && length(cameraOffset) < discardDistance) {
        discard;
    }
    fragColour = vertexColour;
    fragLight = vertexLight;
}
