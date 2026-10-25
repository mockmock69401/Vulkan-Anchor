#version 460
//2026-10-16 (CTE2 report, edge cover design 2026-10-16): EdgeCover's quads over a built section's side where
// VulkanMod has not built the neighbour yet. A quad is three vec4 (EdgeCover.FLOATS_PER_QUAD): its lower corner relative to the
// quads' origin and its height; the sprite; sky, block, shade, side. Six vertices an instance (GL names: GlslForVulkan maps
// gl_VertexID and gl_InstanceID to Vulkan's indices; first vertex and instance are 0)

layout(binding = 2, std430) readonly restrict buffer Quads { vec4 quads[]; };

layout(location = 0) uniform mat4 mvp;//camera-relative world to Reforged's clip (0..1 depth)
layout(location = 1) uniform vec4 cameraFrac;//xyz: the camera minus the quads' origin (VkEdgeCover: moves only with the camera far away); w: 1 to compare against the LOD depth, 0 without LODs

layout(location = 0) out vec2 local;//blocks across and up the quad (the sprite repeats per block)
layout(location = 1) out vec3 rel;
layout(location = 2) flat out vec4 sprite;
layout(location = 3) flat out vec2 light;
layout(location = 4) flat out float shade;
layout(location = 5) flat out int side;
layout(location = 7) flat out float compareLod;//cameraFrac.w, for the fragment stage
layout(location = 6) out vec4 clip;//for the LOD depth at this pixel (as the composite reads it: NDC*0.5 + 0.5)

//Two triangles: (across, up) corners
const vec2 CORNERS[6] = vec2[6](vec2(0.0, 0.0), vec2(0.0, 1.0), vec2(1.0, 1.0), vec2(0.0, 0.0), vec2(1.0, 1.0), vec2(1.0, 0.0));

void main() {
    vec4 a = quads[3*gl_InstanceID], b = quads[3*gl_InstanceID + 1], c = quads[3*gl_InstanceID + 2];
    side = int(c.w + 0.5);
    vec2 k = CORNERS[gl_VertexID];
    local = vec2(k.x, (1.0 - k.y)*a.w);//v grows downward in the atlas
    //Across the face: z for the X sides, x for the Z sides, one block wide
    vec3 across = side < 2 ? vec3(0.0, 0.0, 1.0) : vec3(1.0, 0.0, 0.0);
    vec3 pos = a.xyz + across*k.x + vec3(0.0, a.w*k.y, 0.0);
    rel = pos - cameraFrac.xyz;
    gl_Position = mvp*vec4(rel, 1.0);
    clip = gl_Position;
    compareLod = cameraFrac.w;
    sprite = b;
    light = c.xy;
    shade = c.z;
}
