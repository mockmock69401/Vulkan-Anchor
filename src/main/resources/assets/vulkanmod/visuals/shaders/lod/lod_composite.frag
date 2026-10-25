#version 450 core
//The LOD composite: the colour and depth the LOD target holds, laid into Reforged's resumed main pass. Written for the fork from this
// description: wherever the LOD target has a surface, the pixel takes the LOD's colour (blended over the frame, tinted towards the
// fog colour with distance) and the LOD's depth re-expressed in the main projection, so the depth test and the depth bounds [1, 1] of
// the pipeline (LodComposite) keep vanilla geometry, and the depth the post-process reads at LOD pixels is the main one.
//
// It reads no vanilla distance map, band blend, chunk fade grid or LOD distance fade. The rule "no LOD where vanilla is present" is the pipeline's depth bounds test [1, 1] on the main
// depth (the LOD lands only on pixels still at the clear value): this pass cannot sample the depth it draws into. The LOD target is cleared to 1.0, which is how an empty pixel is told.
layout(binding = 0) uniform sampler2D lodDepth;
layout(binding = 1) uniform sampler2D lodColour;
layout(location = 0) uniform mat4 lodClipToView;//the inverse of the LOD projection (depth 0..1)
layout(location = 1) uniform mat4 viewToMainClip;//the main projection (depth 0..1)
layout(location = 2) uniform vec4 fogColour;//rgb, strength; strength 0 switches the fog off
layout(location = 3) uniform vec4 fogRange;//the distance fog starts and ends at (blocks)

layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 colour;

//Main depth 1.0 is what the post-process takes for sky, so a LOD pixel stays this many 24-bit steps short of it
const float FAR_LIMIT = 1.0 - 3.0/16777215.0;

void main() {
    float depth = texture(lodDepth, uv).r;
    if (depth >= 1.0) {
        discard;
    }
    vec4 lod = texture(lodColour, uv);
    if (lod.a <= 0.0) {
        discard;
    }

    vec4 atView = lodClipToView*vec4(uv*2.0 - 1.0, depth, 1.0);
    vec3 view = atView.xyz/atView.w;
    vec4 atMain = viewToMainClip*vec4(view, 1.0);
    float mainDepth = atMain.z/atMain.w;
    if (mainDepth < 0.0) {
        discard;//in front of the main near plane
    }
    gl_FragDepth = min(mainDepth, FAR_LIMIT);

    float span = fogRange.y - fogRange.x;
    float fog = (fogColour.a > 0.0 && span > 0.0) ? clamp((length(view) - fogRange.x)/span, 0.0, 1.0)*fogColour.a : 0.0;
    colour = vec4(mix(lod.rgb, fogColour.rgb, fog), lod.a);
}
