#version 460
//2026-11-07: the sections of Sable's sub-levels (physics structures) this frame drew, into a shadow map (VkSubLevelShadows). Their
// buffers are vanilla's block format; the position and offset are as Sable draws them (Position + ChunkOffset in its model-view).

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;

layout(location = 0) uniform mat4 lightViewProjection;//the draw's vertex space to the map's clip (Vulkan depth)
layout(location = 1) uniform vec4 chunkOffset;//xyz the section's offset, w the layer's alpha cutout

layout(location = 0) out vec2 uv;
layout(location = 1) flat out float cutout;

void main() {
    gl_Position = lightViewProjection*vec4(Position + chunkOffset.xyz, 1.0);
    uv = UV0;
    cutout = chunkOffset.w;
}
