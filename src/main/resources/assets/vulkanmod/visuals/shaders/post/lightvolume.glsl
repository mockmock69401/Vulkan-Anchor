//Visuals P7l design 3.3-3.5: the light volume (LightVolumeMath, VkLightVolume) read per pixel; RelightMath mirrors the maths,
// change both together. The includer declares, before importing this after viewpos.glsl: the storage buffers lightData and
// lightReady, usampler2D lodLight (GL layout), uimage2D terrainLight (r32ui, TerrainLightTarget), mainDepth, lodDepth, and vec4
// uniforms lightCamera (the camera's block, 1 = on, 2 = on with this frame's terrain light), lightFrac (the camera inside that block, lift strength), lightSize (slots x, y, z, minimum section y), lightMode
// (debug, max gain, 1 = sky light dimension, 1 = sun visibility valid)
const float LIGHT_MAX_GAIN = 4.0;
const float LIGHT_WATER_ALBEDO = 0.08;
const int LIGHT_SIDE_NEAR = 16;
const int LIGHT_SIDE_FAR = 32;
const int LIGHT_SIDE_DIAGONAL_NEAR = 11;
const int LIGHT_SIDE_DIAGONAL_FAR = 23;

//a mod n for negative a too (GLSL's % is undefined for negatives)
int lightMod(int a, int n) {
    return a - n*int(floor(float(a)/float(n)));
}

//LightVolumeMath.Layout.key: x and z 12 low bits, y 7 bits above the minimum, the held bit
uint lightKey(ivec3 section) {
    return uint(section.x & 0xFFF) | (uint(section.z & 0xFFF) << 12) | (uint((section.y - int(lightSize.w)) & 0x7F) << 24) | 0x80000000u;
}

//World block b's byte (sky low nibble, block high), -1 when its section is not in the volume (yet)
int lightByte(ivec3 b) {
    ivec3 section = b >> 4;
    ivec3 n = ivec3(lightSize.xyz);
    int sy = section.y - int(lightSize.w);
    if (sy < 0 || sy >= n.y) {
        return -1;
    }
    int slot = lightMod(section.x, n.x) + n.x*(lightMod(section.z, n.z) + n.z*sy);
    if (lightReady[slot] != lightKey(section)) {
        return -1;
    }
    ivec3 l = b & 15;
    uint index = uint(slot)*4096u + uint(l.y*256 + l.z*16 + l.x);
    return int((lightData[index >> 2u] >> ((index & 3u)*8u)) & 0xFFu);
}

//Gate G7l: sky and block light of the face at rel (blocks from the camera block's corner) with world normal n: the layer of
// cells half a block in front of it along n's dominant axis, bilinear across the layer. Vanilla lights a face by the air in
// front of it; mixing in the block itself (water's own light is a level lower, 1.75x in linear light) made far water flicker
// between lifted and not with depth noise. Solid cells (0) are skipped while another is lit. z: 1 known, 0 not
// (RelightMath.faceAxis, frontLayer and faceBlend mirror this)
vec3 faceLightAt(vec3 rel, vec3 n) {
    vec3 a = abs(n);
    int axis = a.x >= a.y && a.x >= a.z ? 0 : (a.y >= a.z ? 1 : 2);
    int u = axis == 0 ? 1 : 0;
    int v = axis == 2 ? 1 : 2;
    int layer = int(floor(rel[axis] + (n[axis] >= 0.0 ? 0.5 : -0.5)));
    vec2 q = vec2(rel[u], rel[v]) - 0.5;
    vec2 f = floor(q);
    vec2 t = q - f;
    ivec3 cam = ivec3(lightCamera.xyz);
    int cells[4];
    bool anyLit = false;
    for (int i = 0; i < 4; i++) {
        ivec3 c = ivec3(0);
        c[axis] = layer;
        c[u] = int(f.x) + (i & 1);
        c[v] = int(f.y) + ((i >> 1) & 1);
        cells[i] = lightByte(cam + c);
        if (cells[i] < 0) {
            return vec3(0.0);
        }
        anyLit = anyLit || cells[i] != 0;
    }
    vec2 sum = vec2(0.0);
    vec2 plain = vec2(0.0);
    float weights = 0.0;
    float count = 0.0;
    for (int i = 0; i < 4; i++) {
        if (anyLit && cells[i] == 0) {
            continue;
        }
        float w = ((i & 1) != 0 ? t.x : 1.0 - t.x)*(((i >> 1) & 1) != 0 ? t.y : 1.0 - t.y);
        vec2 val = vec2(float(cells[i] & 15), float(cells[i] >> 4));
        sum += val*w;
        plain += val;
        weights += w;
        count += 1.0;
    }
    return vec3(weights > 1e-6 ? sum/weights : plain/max(count, 1.0), 1.0);
}

//2026-10-23, the terrain light (TerrainLightMath mirrors this, terrain_light.glsl packs it): each pixel's nearest terrain fragment,
// its window depth on top of its sky and block levels in quarter steps, all ones where no terrain was drawn
const uint TERRAIN_LIGHT_NONE = 4294967295u;
const float TERRAIN_LIGHT_DEPTH_STEPS = 1048576.0;
const uint TERRAIN_LIGHT_DEPTH_MAX = 1048575u;
const float TERRAIN_LIGHT_LEVEL_STEPS = 4.0;
//The stored depth may differ from the main depth's by this many steps (the depth buffer's own rounding) and still be the same surface
const uint TERRAIN_LIGHT_DEPTH_SLACK = 1u;

//(sky, block, 2) from the terrain light where the pixel's front surface (depth: the main depth there) is its nearest terrain
// fragment, else (0, 0, 0)
vec3 terrainLightAt(ivec2 p, float depth) {
    uint v = imageLoad(terrainLight, p).r;
    if (v == TERRAIN_LIGHT_NONE) {
        return vec3(0.0);
    }
    uint d = min(uint(clamp(depth, 0.0, 1.0)*TERRAIN_LIGHT_DEPTH_STEPS), TERRAIN_LIGHT_DEPTH_MAX);
    uint stored = v >> 12;
    if (max(d, stored) - min(d, stored) > TERRAIN_LIGHT_DEPTH_SLACK) {
        return vec3(0.0);
    }
    return vec3(float((v >> 6) & 63u)/TERRAIN_LIGHT_LEVEL_STEPS, float(v & 63u)/TERRAIN_LIGHT_LEVEL_STEPS, 2.0);
}

//This pixel's light (z: 0 unknown, 1 the LOD pass's or the volume's, 2 the terrain light's): the LOD pass's for LOD pixels, the
// terrain's own where its nearest terrain fragment is in front, else the volume in front of the face (world: the surface relative to
// the camera in world axes; n: its world normal, toward the camera when unknown)
vec3 pixelLight(ivec2 p, ivec2 size, vec3 world, vec3 n) {
    if (clampedToLod(texelFetch(mainDepth, p, 0).r)) {
        ivec2 lp = lodTexel(p, size);
        if (texelFetch(lodDepth, lp, 0).r != FAR) {
            uint v = texelFetch(lodLight, lp, 0).r & 0xFFu;//the high byte is the face (2026-10-19)
            return vec3(float(v & 15u), float(v >> 4u), 1.0);
        }
    }
    if (lightCamera.w < 0.5) {
        return vec3(0.0);
    }
    if (lightCamera.w > 1.5) {
        vec3 t = terrainLightAt(p, texelFetch(mainDepth, p, 0).r);
        if (t.z > 0.5) {
            return t;
        }
    }
    return faceLightAt(lightFrac.xyz + world, dot(n, n) > 0.25 ? n : -world);
}

//Design 3.5: the brightest known sky light in a ring 16 and 32 blocks out, one block above the water surface, /15.
// 2026-11-19 (the user: cave water and its floor hazy and washed out, a torch shrank the haze): a ring cell counts only where the air
// toward it is open. The ring went through the rock of a cave near the surface to the open air outside, so the cave's pool was lit as
// under the open sky and, too dark in vanilla's light to scale, got its scattering floor (gate G7l): a milky veil that only a torch's
// light switched off. From a cell b one block above the water, each of the 8 directions is walked out from the water's own sky level
// in LIGHT_SIDE_STEPS cells to the far ring (the near ring is the middle one); the walk stops at an unknown cell or where the sky level
// jumps by more than the Manhattan distance m between two cells: vanilla's sky light changes by at most 1 per block of open air, so a
// jump is a wall (rock, dark air beside open air). A ring cell counts only when the walk reaches it. Each direction gives the brightest
// ring cell it reached; the side sky is the brightest of them, faded in by the share of the horizon seen open (their mean /15) from
// LIGHT_SIDE_OPEN_LOW to LIGHT_SIDE_OPEN_HIGH: a doorway's one direction lifted a cave pool's cells one by one to full sky light, in
// blocky patches, while an island's shade on the sea or a roof open on two sides sees enough of it to stay as lit as before (the mean
// alone darkened those). RelightMath.sideSeen and side mirror this
const int LIGHT_SIDE_STEPS = 4;
const float LIGHT_SIDE_OPEN_LOW = 0.125;
const float LIGHT_SIDE_OPEN_HIGH = 0.375;
float sideSkyCell(ivec3 b, float sky) {
    int d = LIGHT_SIDE_FAR;
    int k = LIGHT_SIDE_DIAGONAL_FAR;
    ivec2 o[8] = ivec2[8](ivec2(d, 0), ivec2(-d, 0), ivec2(0, d), ivec2(0, -d), ivec2(k, k), ivec2(k, -k), ivec2(-k, k), ivec2(-k, -k));
    int sum = 0;
    int best = 0;
    for (int i = 0; i < 8; i++) {
        int seen = 0;
        float prev = sky;
        ivec2 last = ivec2(0);
        for (int j = 0; j < LIGHT_SIDE_STEPS; j++) {
            ivec2 at = o[i]*(j + 1)/LIGHT_SIDE_STEPS;
            int v = lightByte(b + ivec3(at.x, 0, at.y));
            if (v < 0) {
                break;
            }
            float level = float(v & 15);
            ivec2 m = abs(at - last);
            if (abs(level - prev) > float(m.x + m.y)) {
                break;
            }
            if (j == LIGHT_SIDE_STEPS/2 - 1 || j == LIGHT_SIDE_STEPS - 1) {
                seen = max(seen, v & 15);
            }
            prev = level;
            last = at;
        }
        sum += seen;
        best = max(best, seen);
    }
    return float(best)/15.0*smoothstep(LIGHT_SIDE_OPEN_LOW, LIGHT_SIDE_OPEN_HIGH, float(sum)/(15.0*8.0));
}

//The side sky at the water surface (world): sideSkyCell at the 4 corners of the LIGHT_SIDE_GRID-block world grid cell around it,
// bilinear between them. Under a roof open on one side the share of the horizon seen open changes from block to block (a diagonal
// reaches out or meets a wall), so one cell's value, and even a blend of the 4 nearest blocks', lay on the water in patches; over the
// wider grid it is a gradient. Each corner walks from the water's own sky level, so a corner beyond a thin wall still breaks at the
// wall's jump. Water that sees the sky overhead (sky light 15) gets nothing from the ring and reads no cells
const int LIGHT_SIDE_GRID = 4;
float sideSky(vec3 world, float sky) {
    if (sky >= 15.0) {
        return 0.0;
    }
    vec3 q = lightFrac.xyz + world;
    vec2 f = (q.xz - 0.5)/float(LIGHT_SIDE_GRID);
    ivec2 c = ivec2(floor(f));
    vec2 t = f - vec2(c);
    ivec3 b = ivec3(lightCamera.xyz) + ivec3(c.x*LIGHT_SIDE_GRID, int(floor(q.y)) + 1, c.y*LIGHT_SIDE_GRID);
    float s00 = sideSkyCell(b, sky);
    float s10 = sideSkyCell(b + ivec3(LIGHT_SIDE_GRID, 0, 0), sky);
    float s01 = sideSkyCell(b + ivec3(0, 0, LIGHT_SIDE_GRID), sky);
    float s11 = sideSkyCell(b + ivec3(LIGHT_SIDE_GRID, 0, LIGHT_SIDE_GRID), sky);
    return mix(mix(s00, s10, t.x), mix(s01, s11, t.x), t.y);
}

float skyVisibility(float sky) {
    float v = clamp(sky/15.0, 0.0, 1.0);
    return v*v;
}

float waterSky(float sky, float side) {
    return max(skyVisibility(sky), clamp(side, 0.0, 1.0));
}

//Sky Light Ambient: the sky light lifted toward 15 by lift (where the sun reaches). 2026-11-21 (the user, Tensura pack: thin bright
// lines along ledges and pillar edges deep in a cave, no light source or water there): none where vanilla's sky light is 0, faded in
// to its first level. Sky light 0 means no open air reaches the sky, so the sun cannot; there the sun visibility is the shadow map's
// error alone (a face the rays run along, its blocker within the bias), and the lift took those faces from 0 to 15, lit as open sky
// and, at that sky light, past the direct sun's sky gate too. RelightMath.liftedSky mirrors this
float liftedSky(float sky, float lift) {
    return sky + (15.0 - sky)*clamp(lift, 0.0, 1.0)*clamp(sky, 0.0, 1.0);
}

float skyShare(float sky) {
    return clamp(sky/15.0, 0.0, 1.0);
}

//Gate G7l: the gain hit the cap in some channel (vanilla too dark to scale): only then does water get its scattering floor
bool relightCapped(vec3 vanillaLinear, vec3 targetLinear) {
    return any(greaterThan(targetLinear/max(vanillaLinear, vec3(1e-4)), vec3(LIGHT_MAX_GAIN)));
}

//Linear lightmap colours: vanilla's to the target, per channel, at most LIGHT_MAX_GAIN
vec3 relightGain(vec3 vanillaLinear, vec3 targetLinear) {
    return min(targetLinear/max(vanillaLinear, vec3(1e-4)), vec3(LIGHT_MAX_GAIN));
}
