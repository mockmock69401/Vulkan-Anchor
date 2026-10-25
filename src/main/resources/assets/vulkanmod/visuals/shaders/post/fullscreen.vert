#version 450 core
//A full-screen quad for the final draws (postFinal, postBlit), drawn as a four-vertex triangle strip over the viewport. The fragment
// shaders read their images at gl_FragCoord, so this passes no varyings.
void main() {
    vec2 corner = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    gl_Position = vec4(corner*2.0 - 1.0, 0.0, 1.0);
}
