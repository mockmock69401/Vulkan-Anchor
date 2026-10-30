#version 450
#include "text_aa.glsl"

layout(binding = 2) uniform sampler2D Sampler0;

layout(binding = 1) uniform UBO {
    vec4 ColorModulator;
    float TextAntialias;
};

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;

layout(location = 3) flat in float textPerspective;

layout(location = 0) out vec4 fragColor;

void main() {
    float r;
    if (TextAntialias > 0.5) {
        float footprint;
        r = textCoverageAt(textSample(Sampler0, texCoord0, footprint).r, footprint, textPerspective);
    } else {
        r = texture(Sampler0, texCoord0).r;
    }
    vec4 color = vec4(r) * vertexColor;
    if (textDiscard(color.a, TextAntialias)) {
        discard;
    }
    fragColor = color * ColorModulator;
}
