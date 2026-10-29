#version 460
//2026-10-29 (the user: Complementary Reimagined's High profile has entity shadows): the mobs, players and items this frame drew,
// captured as camera-relative world triangles (EntityShadowCapture), into the entity shadow map (VkEntityShadows)

layout(location = 0) in vec3 Position;

layout(location = 0) uniform mat4 lightViewProjection;//camera-relative world to the entity map's clip (Vulkan depth 0..1)

void main() {
    gl_Position = lightViewProjection*vec4(Position, 1.0);
}
