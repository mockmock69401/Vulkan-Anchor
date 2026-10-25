#version 450 core
//A full-screen quad for the LOD composite, a four-vertex triangle strip. uv is the position on the screen in GL's orientation (row 0 at
// the bottom), which is how the LOD target's images are laid out.
layout(location = 0) out vec2 uv;

void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    uv = corner;
    gl_Position = vec4(corner*2.0 - 1.0, 0.0, 1.0);
}
