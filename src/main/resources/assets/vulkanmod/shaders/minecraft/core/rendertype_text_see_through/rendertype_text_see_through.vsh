#version 450

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;

layout(binding = 0) uniform UniformBufferObject {
   mat4 MVP;
};

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;

//2026-10-30: 1 for world text (a perspective matrix), 0 for GUI text: an orthographic MVP's last row is (0, 0, 0, w)
layout(location = 3) flat out float textPerspective;

float textIsPerspective(mat4 m) {
    return abs(m[0][3]) + abs(m[1][3]) + abs(m[2][3]) > 1e-6 ? 1.0 : 0.0;
}

void main() {
    textPerspective = textIsPerspective(MVP);
    gl_Position = MVP * vec4(Position, 1.0);

    vertexColor = Color;
    texCoord0 = UV0;
}

