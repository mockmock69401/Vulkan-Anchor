//Depth conventions shared by the post passes. The main and level depth buffers hold Vulkan's 0..1 depth (near 0, far 1: no reversed
// depth), and every projection the passes unproject with has been remapped to 0..1 as well (ReforgedProjection), so a depth value is
// already the NDC z. Without USE_ZERO_ONE_DEPTH the depth is read as GL's, whose NDC z is the depth scaled to -1..1.
const float NEAR = 0.0;
const float FAR = 1.0;

//A screen position (uv in GL's orientation, row 0 at the bottom, and depth) to normalised device coordinates.
#ifdef USE_ZERO_ONE_DEPTH
#define SCREEN2NDC(s) vec3((s).xy*2.0 - 1.0, (s).z)
#else
#define SCREEN2NDC(s) ((s)*2.0 - 1.0)
#endif
