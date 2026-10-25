#version 460
//Visuals P7o design 4.2: VulkanMod's compiled vanilla terrain into the near sun shadow cascade. The position decoding mirrors
// VulkanMod-Reforged's terrain.vsh (its compressed vertex: short xyz in 1/2048 blocks; the area origin + 4 - camera pushed
// per area; the section's block offset in the instance index); change both together if VulkanMod changes its format

layout(location = 0) in ivec4 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in uvec2 UV0;

layout(location = 0) uniform mat4 lightViewProjection;//camera-relative world to the cascade's clip (Vulkan depth 0..1)
layout(location = 1) uniform vec4 areaOffset;//the chunk area's origin + 4 - the cascade's camera, the layer's alpha cutout

layout(location = 0) out vec2 uv;
layout(location = 1) flat out float cutout;

void main() {
    //gl_InstanceID + gl_BaseInstance: the instance index with VulkanMod's base instance (GlslForVulkan turns it into gl_InstanceIndex)
    ivec3 baseOffset = bitfieldExtract(ivec3(gl_InstanceID + gl_BaseInstance) >> ivec3(0, 16, 8), 0, 8);
    vec3 pos = fma(vec3(Position.xyz), vec3(1.0/2048.0), areaOffset.xyz + vec3(baseOffset));
    gl_Position = lightViewProjection*vec4(pos, 1.0);
    uv = vec2(UV0)*(1.0/32768.0);
    cutout = areaOffset.w;
}
