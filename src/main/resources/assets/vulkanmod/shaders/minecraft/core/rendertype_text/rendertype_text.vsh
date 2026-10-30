#version 450

#include "fog.glsl"

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;

layout(binding = 0) uniform UniformBufferObject {
   mat4 MVP;
};

layout(binding = 3) uniform sampler2D Sampler2;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out float vertexDistance;

//2026-10-30: 1 for world text (a perspective matrix), 0 for GUI text: an orthographic MVP's last row is (0, 0, 0, w)
layout(location = 3) flat out float textPerspective;

float textIsPerspective(mat4 m) {
    return abs(m[0][3]) + abs(m[1][3]) + abs(m[2][3]) > 1e-6 ? 1.0 : 0.0;
}

void main() {
    textPerspective = textIsPerspective(MVP);
    gl_Position = MVP * vec4(Position, 1.0);

    vertexDistance = fog_distance(Position.xyz, 0);
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
}
