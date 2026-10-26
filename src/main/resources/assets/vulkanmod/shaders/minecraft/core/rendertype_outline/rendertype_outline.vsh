#version 450

layout(location = 0) in vec3 Position;
//1.20.1's outline format is POSITION_COLOR_TEX (1.21 made it POSITION_TEX_COLOR, which this shader had): with UV0 at 1 the
// outline pass read the UVs as the colour and the colour as UVs, and Glowing drew coloured, dotted silhouettes (2026-10-27)
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;

layout(binding = 0) uniform UniformBufferObject {
   mat4 MVP;
};

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;

void main() {
    gl_Position = MVP * vec4(Position, 1.0);

    vertexColor = Color;
    texCoord0 = UV0;
}

