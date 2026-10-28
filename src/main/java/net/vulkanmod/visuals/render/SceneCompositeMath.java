package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.render.AtmosphereMath.Atmosphere;
import net.vulkanmod.visuals.render.AtmosphereMath.CloudLight;

/**
 * W7: the Java mirror of the scene composite (post/scene_copy.comp). Every shader const is a field of the same name here
 * (SceneCompositeTest compares them both ways), and {@link #composite} is the shader's per-pixel combination, line for line, given what the
 * shader fetches (the pixel's position, W5's lightingAt, the light levels, the held light, the far clouds' march). It calls the other
 * packages' mirrors (AtmosphereMath, LightingMath, WaterMath, PostMath, RelightMath) where the shader calls their GLSL.
 */
public final class SceneCompositeMath {
    // ---- the shader's consts (one tuning knob each) ----
    public static final float FACE_SHADE_DOWN = 0.5f, FACE_SHADE_UP = 1.0f, FACE_SHADE_NORTH_SOUTH = 0.8f, FACE_SHADE_WEST_EAST = 0.6f;
    /** The share of vanilla's face shade divided out of the sky-lit light at full sun weight. */
    public static final float FACE_UNSHADE = 1.0f;
    public static final float FACE_AXIS_MIN = 0.999f;
    /** The share of vanilla's face shade baked into Distant Horizons' LOD colours. */
    public static final float LOD_FACE_SHADE_SHARE = 1.0f;
    /** B14: sky-lit surfaces by day with the camera underground; block light by day under the open sky. */
    public static final float UNDERGROUND_SKY_EXPOSURE = 0.7f, DAY_BLOCK_EXPOSURE = 0.6f;
    /** Tuning round 1: dimensions without sky light. */
    public static final float NO_SKY_EXPOSURE = 1.7f;
    /** Tuning round 2: the view with the camera under water (final fix R2: the camera's fluid, slot 29 .y). */
    public static final float UNDERWATER_EXPOSURE = 2.0f;
    /** B3: vanilla's cloud layer widened by this many blocks plus a share of the distance. */
    public static final float VANILLA_CLOUD_MARGIN = 0.5f, VANILLA_CLOUD_MARGIN_SHARE = 0.01f;
    /** B3: the sky light a cell must hold to count as cloud air. */
    public static final int VANILLA_CLOUD_SKY = 15;
    public static final int HELD_GRID_SIDE = 31;
    public static final float HELD_MAX_GAIN = 256f;
    /** Tuning round 1: the held light loses HELD_FALLOFF levels per level of vanilla's spread. */
    public static final float HELD_FALLOFF = 1.7f;
    /** Tuning round 3: the sky-lit light under an overcast day sky and at night. */
    public static final float OVERCAST_EXPOSURE = 3.6f, NIGHT_SKY_EXPOSURE = 0.85f;
    /** Tuning round 3: the far clouds start this far out with the camera inside vanilla's cloud layer (within the margin). */
    public static final float FAR_CLOUD_NEAR = 16.0f, CLOUD_CAMERA_MARGIN = 1.0f;
    /** 2026-10-28: the block-lit light's steeper falloff at full block light colour strength, and the most a channel is lifted. */
    public static final float BLOCK_LIGHT_FALLOFF = 1.0f, BLOCK_LIGHT_GAIN_MAX = 16.0f;
    public static final float LIGHT_SPLIT_MIN = 0.00001f;
    public static final int DEBUG_SKY_VIEW = 1, DEBUG_BLOCK_VIEW = 2, DEBUG_SHARE_VIEW = 3;
    /** 2026-10-23: where each pixel's light came from. */
    public static final int DEBUG_SOURCE_VIEW = 5;
    /** 2026-10-23: an albedo times vanilla's lightmap at the stored levels must give vanilla's pixel within ABS + REL x it (albedoMatches). */
    public static final float TERRAIN_ALBEDO_MATCH_ABS = 0.03f, TERRAIN_ALBEDO_MATCH_REL = 0.12f;
    public static final float DEBUG_UNKNOWN_RED = 0.25f, DEBUG_UNKNOWN_GREEN = 0.0f, DEBUG_UNKNOWN_BLUE = 0.25f;

    // ---- the composite's slots (boundary 3.2) ----
    public static final int SLOTS = 35;
    public static final int LIGHTING = 10, WATER = 11, CLOUD_SHAPE = 12, CLOUD_LIT = 14, CLOUD_AMBIENT = 15, CLOUD_LIGHT_DIR = 16, LIGHT_CAMERA = 18,
            LIGHT_FRAC = 19, LIGHT_MODE = 21, LIGHT_SUN = 22, LIGHT_SHADE = 23, HELD_ORIGIN = 24, VANILLA_SKY = 26, VANILLA_FOG = 27, STAR_GATE = 28,
            LOOK = 29, BORDER_FOG = 30, BLOCK_LIGHT = 31, MOON_LIGHT = 32;
    /** 2026-10-21: scene_copy.comp's flattenWaterTexture (far water's texture streaks; a neighbourhood filter, shader only). */
    public static final float WATER_FLATTEN = 0.75f, WATER_SAME_SURFACE = 0.03f, WATER_FLATTEN_NEAR = 24.0f, WATER_FLATTEN_FAR = 64.0f;
    public static final int WATER_FLATTEN_RADIUS = 2;

    private SceneCompositeMath() {
    }

    /** Vanilla's lightmap in scene units at levels (block, sky), 0..15 (the shader samples the 16x16 texture, linear filtered). */
    public interface Lightmap {
        float[] linear(float block, float sky);
    }

    static float[] lightmapLinear(Lightmap lm, float block, float sky) {
        return lm.linear(Math.max(0, Math.min(15, block)), Math.max(0, Math.min(15, sky)));
    }

    /** The baked face shade (linear) of a world normal: getShade's value on block faces, 1 for anything else. */
    public static float bakedShade(float[] n) {
        float ax = Math.abs(n[0]), ay = Math.abs(n[1]), az = Math.abs(n[2]);
        if (ay >= FACE_AXIS_MIN) return PostMath.displayToLinear(n[1] > 0 ? FACE_SHADE_UP : FACE_SHADE_DOWN);
        if (az >= FACE_AXIS_MIN) return PostMath.displayToLinear(FACE_SHADE_NORTH_SOUTH);
        if (ax >= FACE_AXIS_MIN) return PostMath.displayToLinear(FACE_SHADE_WEST_EAST);
        return 1;
    }

    public static float faceUnshade(float shade, float sunWeight) {
        return mix(1, 1 / Math.max(shade, 0.01f), clamp01(sunWeight) * FACE_UNSHADE);
    }

    /**
     * The sky-lit share per channel from the lightmap's sky-only and combined colours above its dark floor (0 where both are dark): block
     * light only takes what it adds to the sky's (2026-10-22: a torch in full daylight no longer darkens the sunlit ground).
     */
    public static float[] skyLitShare(float[] skyOnly, float[] lightAll, float[] dark) {
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            float s = Math.max(skyOnly[i] - dark[i], 0), total = Math.max(lightAll[i] - dark[i], s);
            out[i] = total > LIGHT_SPLIT_MIN ? s / Math.max(total, LIGHT_SPLIT_MIN) : 0;
        }
        return out;
    }

    /** B15: the sky-lit light's colour (luminance 1 when both colours are): shade colour to sun colour by W5's sunShare, strength = setting x sun weight. */
    public static float[] lightTint(float share, float strength, float[] sunColour, float[] shadeColour) {
        float s = clamp01(share), k = clamp01(strength);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = mix(1, mix(shadeColour[i], sunColour[i], s), k);
        return out;
    }

    /** 2026-10-28: the moonlight's colour on the sky-lit light (slot 32), white at strength 0. */
    public static float[] moonTint(float[] moon) {
        float k = clamp01(moon[3]);
        return new float[]{mix(1, moon[0], k), mix(1, moon[1], k), mix(1, moon[2], k)};
    }

    /**
     * 2026-10-28: the factor per channel on the block-lit light (lightAll x its block-lit share): its colour moved toward warm at its
     * luminance and its falloff steepened by (level luminance / full level's)^(BLOCK_LIGHT_FALLOFF x strength); 1 at strength 0.
     */
    public static float[] blockLightFactor(float[] blockLit, float[] blockOnly, float[] full, float[] dark, float[] warm, float strength) {
        float s = clamp01(strength);
        float l = PostMath.luminance(blockLit[0], blockLit[1], blockLit[2]);
        if (s <= 0 || l <= LIGHT_SPLIT_MIN) return new float[]{1, 1, 1};
        float level = clamp01(PostMath.luminance(Math.max(blockOnly[0] - dark[0], 0), Math.max(blockOnly[1] - dark[1], 0), Math.max(blockOnly[2] - dark[2], 0))
                / Math.max(PostMath.luminance(full[0] - dark[0], full[1] - dark[1], full[2] - dark[2]), LIGHT_SPLIT_MIN));
        float falloff = (float) Math.pow(level, BLOCK_LIGHT_FALLOFF * s);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = Math.min(mix(blockLit[i], l * warm[i], s) * falloff / Math.max(blockLit[i], LIGHT_SPLIT_MIN), BLOCK_LIGHT_GAIN_MAX);
        return out;
    }

    /** Final fix R1: an exposure as the composite applies it, mix(1, e, look) (look: slot 29 .x, the sun-shading setting). */
    public static float looked(float e, float look) {
        return mix(1, e, clamp01(look));
    }

    /** Tuning round 3: the sky-lit light's weather and night exposure (overcast: slot 29 .z, W4's overcast share). */
    public static float skyWeatherExposure(float overcast, float sunUp, float sunUpExposed, float look) {
        float o = clamp01(overcast) * clamp01(sunUpExposed);
        return mix(1, looked(OVERCAST_EXPOSURE, look), o) * mix(1, looked(NIGHT_SKY_EXPOSURE, look), 1 - clamp01(sunUp));
    }

    /** Where the far clouds' march starts along a view (the shader's farCloudNear), from cloudShape. */
    public static float farCloudNear(float[] cloudShape) {
        return cloudShape[0] < CLOUD_CAMERA_MARGIN && cloudShape[0] + cloudShape[1] > -CLOUD_CAMERA_MARGIN ? FAR_CLOUD_NEAR : 0;
    }

    public static float skyExposure(float sunUp, float sunUpExposed, float look) {
        return 1 - clamp01(sunUp - sunUpExposed) * (1 - looked(UNDERGROUND_SKY_EXPOSURE, look));
    }

    /** B14: the block light's exposure from the sun-up factor (slot 11 z). */
    public static float blockExposure(float sunUp, float look) {
        return 1 - clamp01(sunUp) * (1 - looked(DAY_BLOCK_EXPOSURE, look));
    }

    /** The shader's underwaterExposure from slot 29 (look): UNDERWATER_EXPOSURE x the look weight with the camera in water, else 1. */
    public static float underwaterExposure(float[] look) {
        return look[1] > 0.5f ? looked(UNDERWATER_EXPOSURE, look[0]) : 1;
    }

    private static boolean exposureMode(float[] look, float mode) {
        return Math.abs(look[3] - mode) < 0.5f;
    }

    /** The shader's heldFalloff: the held level at a surface whose spread level is level (eye: the eye cell's level). */
    public static float heldFalloff(float level, float eye) {
        return level > 0 ? Math.max(eye - HELD_FALLOFF * (eye - level), 0) : 0;
    }

    /** The shader's heldRoom: HeldLight.whiteSurfaceScale. */
    public static float heldRoom(float litMax, float gainMax, float heldMax) {
        float room = Math.max(heldMax / Math.max(litMax, 1e-4f), 1);
        return Math.min(1, room / gainMax);
    }

    /** The light volume's horizontal reach from the camera in blocks, for its slot count along x (lightSize.x). */
    public static float volumeReach(float slotsX) {
        return (float) Math.floor(slotsX * 0.5f) * 16;
    }

    /**
     * B3: whether a vanilla pixel at world (camera relative) and distance dist is vanilla's own cloud: only while the far clouds are off
     * (cloudShape.w 0), W4 gives the layer (cloudShape x bottom, y thickness, 0 for flat clouds; all 0: none) and the light volume is on;
     * within the layer's height; behind is the light byte of the cell half a block behind the surface (-1 unknown): cloud air has sky
     * light VANILLA_CLOUD_SKY; an unknown cell counts only beyond the volume's reach.
     */
    public static boolean vanillaCloud(float[] world, float dist, float[] cloudShape, boolean volumeOn, int behind, float reach) {
        if (cloudShape[3] > 0.5f || !(cloudShape[1] > 0 || cloudShape[0] != 0) || !volumeOn) return false;
        float margin = VANILLA_CLOUD_MARGIN + VANILLA_CLOUD_MARGIN_SHARE * dist;
        if (world[1] < cloudShape[0] - margin || world[1] > cloudShape[0] + Math.max(cloudShape[1], 0) + margin) return false;
        if (behind < 0) return Math.max(Math.abs(world[0]), Math.abs(world[2])) > reach;
        return (behind & 15) >= VANILLA_CLOUD_SKY;
    }

    /**
     * What the shader fetches for one pixel. colour: the main colour (display rgba); sky: nothing wrote the depth; dir: the world view
     * direction (unit); dist: the view distance; fromLod: viewpos.glsl's fromLod; normal: the pixel's world normal (0 for plants, light
     * sources and clouds); lt: lightingAt's result (read only when the pass ran); plantSun: plantSunTex (-1 elsewhere); light: pixelLight's
     * (sky, block, known); sideSky: lightvolume.glsl's sideSky at the surface; held: heldFalloff(heldLightAt, the eye level) (0 off); cloud: vanillaCloud;
     * cloudMarch: clouds.glsl's cloudMarch along dir from farCloudNear to dist (sky: to HAZE_MAX_DISTANCE), its z counted from the camera,
     * (0, 0, 0) for none; ao: W10's aoAt(aoTex, p, dist) (read only when the pass ran and slot 10 .x > 0; 1 = no occlusion); albedo:
     * terrainAlbedoAt's colour before the lightmap (display rgb) where it holds, else null (read only with the terrain light's source 2).
     */
    public record Pixel(float[] colour, boolean sky, float[] dir, float dist, boolean fromLod, float[] normal, float[] lt, float plantSun,
                        float[] light, float sideSky, float held, boolean cloud, float[] cloudMarch, float ao, float[] albedo) {
        /** A pixel without an albedo. */
        public Pixel(float[] colour, boolean sky, float[] dir, float dist, boolean fromLod, float[] normal, float[] lt, float plantSun,
                     float[] light, float sideSky, float held, boolean cloud, float[] cloudMarch, float ao) {
            this(colour, sky, dir, dist, fromLod, normal, lt, plantSun, light, sideSky, held, cloud, cloudMarch, ao, null);
        }

        /** A pixel without ambient occlusion (aoAt 1) or an albedo. */
        public Pixel(float[] colour, boolean sky, float[] dir, float dist, boolean fromLod, float[] normal, float[] lt, float plantSun,
                     float[] light, float sideSky, float held, boolean cloud, float[] cloudMarch) {
            this(colour, sky, dir, dist, fromLod, normal, lt, plantSun, light, sideSky, held, cloud, cloudMarch, 1, null);
        }
    }

    /** scene_copy.comp albedoMatches: albedo (display) times vanilla's lightmap at (block, sky) gives vanilla's pixel (display rgb). */
    public static boolean albedoMatches(float[] albedo, float[] vanilla, float block, float sky, Lightmap lm) {
        float[] lmd = lightmapDisplay(lm, block, sky);
        for (int i = 0; i < 3; i++) {
            if (Math.abs(albedo[i] * lmd[i] - vanilla[i]) > TERRAIN_ALBEDO_MATCH_ABS + TERRAIN_ALBEDO_MATCH_REL * vanilla[i]) return false;
        }
        return true;
    }

    /** scene_copy.comp lightmapDisplay: vanilla's lightmap colour as vanilla multiplies it (display values). */
    static float[] lightmapDisplay(Lightmap lm, float block, float sky) {
        float[] l = lightmapLinear(lm, block, sky);
        return new float[]{PostMath.linearToDisplay(l[0] / PostMath.SCENE_WHITE), PostMath.linearToDisplay(l[1] / PostMath.SCENE_WHITE),
                PostMath.linearToDisplay(l[2] / PostMath.SCENE_WHITE)};
    }

    /** The struct Atmosphere from the composite's slots 3..7, 9, 25. */
    public static Atmosphere atmosphere(float[][] slots) {
        return new Atmosphere(slots[3], slots[4], slots[5], slots[6], slots[7], slots[9], slots[25]);
    }

    private static float[] farCloudsOver(Atmosphere a, float[][] slots, float[] colour, float[] dir, float[] march) {
        if (slots[CLOUD_SHAPE][3] < 0.5f || !AtmosphereMath.atmosphereSky(a) || !(march[1] > 0)) return colour;
        float[] r = AtmosphereMath.cloudOver(a, new CloudLight(slots[CLOUD_LIT], slots[CLOUD_AMBIENT], slots[CLOUD_LIGHT_DIR]), march, dir, 1);
        return new float[]{colour[0] * (1 - r[3]) + r[0], colour[1] * (1 - r[3]) + r[1], colour[2] * (1 - r[3]) + r[2]};
    }

    /** The shader's main for one pixel: rgba written to sceneOut. slots: SLOTS vec4 (the matrix slots unused here). */
    public static float[] composite(Pixel px, float[][] slots, Lightmap lm) {
        float[] scene = {PostMath.displayToLinear(px.colour[0]), PostMath.displayToLinear(px.colour[1]), PostMath.displayToLinear(px.colour[2])};
        Atmosphere a = atmosphere(slots);
        if (px.sky) {
            if (!AtmosphereMath.atmosphereOn(a) || a.mode() == AtmosphereMath.ATMOSPHERE_NO_SKY) return rgba(scale(scene, underwaterExposure(slots[LOOK])), 0);
            float[] sky = add(add(AtmosphereMath.skyColour(a, px.dir), AtmosphereMath.sunGlow(a, px.dir)),
                    add(WaterMath.sunDisc(a, px.dir), AtmosphereMath.vanillaStars(a, px.dir, scene, slots[VANILLA_SKY], slots[VANILLA_FOG], slots[STAR_GATE])));
            return rgba(scale(farCloudsOver(a, slots, sky, px.dir, px.cloudMarch), underwaterExposure(slots[LOOK])), 0);
        }
        float[] lighting = slots[LIGHTING], water = slots[WATER], lightFrac = slots[LIGHT_FRAC], lightMode = slots[LIGHT_MODE], look = slots[LOOK];
        boolean passRan = lighting[2] > 0.5f;
        float weight = clamp01(look[0]);
        int code = LightingMath.alphaCode(px.colour[3]);
        boolean plant = code == LightingMath.PLANT_ALPHA_CODE, emissive = code == LightingMath.EMISSIVE_ALPHA_CODE;
        boolean cloud = !px.fromLod && px.cloud;
        float[] lt = passRan ? px.lt : new float[]{1, 1, 0, 1};
        float plantSun = passRan && plant ? px.plantSun : -1;
        float kind = passRan && !cloud ? lt[2] : 0;
        boolean isWater = kind > 0;
        float[] lightNormal = isWater ? new float[]{0, 1, 0} : px.normal;
        boolean known = px.light[2] > 0.5f;
        boolean hasAlbedo = px.light[2] > 1.5f && px.albedo != null && albedoMatches(px.albedo, px.colour, px.light[1], px.light[0], lm);
        float sky = known ? px.light[0] : 15, block = known ? px.light[1] : 0;
        float skyT = sky, blockT = block;
        float[] lit = scene;
        float[] share = {1, 1, 1};
        float sunPart = 0;
        if (!cloud && !emissive) {
            boolean ownSun = plant && plantSun >= 0;
            float direct = ownSun ? plantSun : lt[1], visibility = ownSun ? plantSun : lt[3];
            if (known) {
                if (passRan) skyT = RelightMath.liftedSky(sky, RelightMath.lift(visibility, lightFrac[3]));
                if (isWater && !px.fromLod && lightMode[2] > 0.5f) skyT = Math.max(skyT, 15 * RelightMath.waterSky(sky, px.sideSky));
                if (slots[HELD_ORIGIN][3] > 0.5f && slots[LIGHT_CAMERA][3] > 0.5f && !px.fromLod) blockT = Math.max(blockT, px.held);
            }
            //Final fix R5: each lightmap colour sampled once, equal levels reuse a sample (as the shader)
            float[] lightAll = lightmapLinear(lm, blockT, skyT);
            float[] lightSky = blockT == block ? lightAll : lightmapLinear(lm, block, skyT);
            float[] lightNow = skyT == sky ? lightSky : lightmapLinear(lm, block, sky);
            float[] dark = new float[3], skyOnly = new float[3], blockOnly = new float[3];
            float[] gain = {1, 1, 1};
            boolean capped = false;
            float[] relit = scene;
            if (known) {
                if (hasAlbedo) {
                    //2026-10-23: vanilla's own product at the new levels (the held light's too)
                    float[] lmd = lightmapDisplay(lm, blockT, skyT);
                    relit = new float[3];
                    for (int i = 0; i < 3; i++) relit[i] = PostMath.displayToLinear(px.albedo[i] * lmd[i]) * PostMath.SCENE_WHITE;
                } else {
                    for (int i = 0; i < 3; i++) {
                        gain[i] = RelightMath.gain(lightNow[i], lightSky[i]);
                        capped |= RelightMath.capped(lightNow[i], lightSky[i]);
                    }
                    if (blockT > block) {
                        float[] held = new float[3];
                        for (int i = 0; i < 3; i++) held[i] = Math.min(lightAll[i] / Math.max(lightSky[i], 1e-4f), HELD_MAX_GAIN);
                        float scale = heldRoom(max3(mul(scene, gain)), Math.max(max3(held), 1), max3(lightAll));
                        for (int i = 0; i < 3; i++) gain[i] *= Math.max(held[i] * scale, 1);
                    }
                    relit = mul(scene, gain);
                }
                dark = lightmapLinear(lm, 0, 0);
                skyOnly = skyT == 0 ? dark : (blockT == 0 ? lightAll : lightmapLinear(lm, 0, skyT));
                blockOnly = blockT == 0 ? dark : (skyT == 0 ? lightAll : lightmapLinear(lm, blockT, 0));
                share = skyLitShare(skyOnly, lightAll, dark);
            }
            float skyLight = clamp01(skyT / 15);
            float sunWeight = passRan ? lighting[1] : 0;
            float shade = 1;
            if (!plant && kind == 0) shade = px.fromLod ? mix(1, bakedShade(lightNormal), LOD_FACE_SHADE_SHARE) : bakedShade(lightNormal);
            float skyFactor = LightingMath.sunShadingAt(direct, skyLight, sunWeight, lighting[3]) * faceUnshade(shade, sunWeight);
            float directShare = direct * LightingMath.skyGate(skyLight);
            float shadeL = LightingMath.shadeLevel(lighting[3]), sunL = LightingMath.sunLevel(lighting[3]);
            sunPart = (sunL - shadeL) * directShare / (shadeL + (sunL - shadeL) * directShare);
            float[] tint = mul(passRan ? lightTint(directShare, slots[LIGHT_SUN][3] * slots[LIGHT_SHADE][3], slots[LIGHT_SUN], slots[LIGHT_SHADE]) : new float[]{1, 1, 1},
                    moonTint(slots[MOON_LIGHT]));
            //Final fix R1: none of the exposures gated by the lighting pass; the sky-light ones not on light an active volume does not
            // know yet (follow-up 1: with the volume off every pixel takes them)
            boolean exposed = known || slots[LIGHT_CAMERA][3] < 0.5f;
            float se = exposed ? skyExposure(water[2], water[3], weight) : 1, be = blockExposure(water[2], weight);
            if (exposed && exposureMode(look, AtmosphereMath.ATMOSPHERE_SKY)) se *= skyWeatherExposure(look[2], water[2], water[3], weight);
            //2026-10-28: the block-lit light's colour and falloff
            float[] blockShare = new float[3];
            for (int i = 0; i < 3; i++) blockShare[i] = (1 - share[i]) * be;
            if (known && slots[BLOCK_LIGHT][3] > 0) {
                float[] blockLit = new float[3];
                for (int i = 0; i < 3; i++) blockLit[i] = (1 - share[i]) * lightAll[i];
                blockShare = mul(blockShare, blockLightFactor(blockLit, blockOnly, lightmapLinear(lm, 15, 0), dark, slots[BLOCK_LIGHT], slots[BLOCK_LIGHT][3]));
            }
            float[] perLight = new float[3];
            for (int i = 0; i < 3; i++) perLight[i] = share[i] * skyFactor * tint[i] * se + blockShare[i];
            //W10: the ambient occlusion takes the sky light's ambient part and the lightmap's floor (AmbientOcclusion.occludedLight)
            float[] ao = {1, 1, 1};
            if (passRan && lighting[0] > 0) {
                float occlusion = AmbientOcclusion.strengthApplied(px.ao, lighting[0]);
                if (occlusion < 1) {
                    float[] floorShare = known ? AmbientOcclusion.floorShare(skyOnly, blockOnly, dark) : new float[3];
                    float ambient = LightingMath.sunShadingAt(0, skyLight, sunWeight, lighting[3]) * faceUnshade(shade, sunWeight);
                    float[] occludable = new float[3];
                    for (int i = 0; i < 3; i++) occludable[i] = share[i] * ambient * tint[i] * se + blockShare[i] * floorShare[i];
                    ao = AmbientOcclusion.occludedLight(perLight, occludable, occlusion);
                }
            }
            lit = new float[3];
            for (int i = 0; i < 3; i++) lit[i] = relit[i] * perLight[i] * ao[i];
            if (exposureMode(look, AtmosphereMath.ATMOSPHERE_NO_SKY)) lit = scale(lit, looked(NO_SKY_EXPOSURE, weight));
            if (isWater && capped) {
                for (int i = 0; i < 3; i++) lit[i] = Math.max(lit[i], lightAll[i] * perLight[i] * RelightMath.WATER_ALBEDO);
            }
            if (isWater) lit = WaterMath.waterColour(lit, kind * water[0], scale(mul(mul(lightAll, perLight), ao), water[1]));
        }
        int debug = (int) Math.floor(lightMode[0] + 0.5f);
        if (debug >= DEBUG_SKY_VIEW && debug <= DEBUG_SHARE_VIEW) return rgba(debugView(debug, known, skyT, blockT, share, sunPart), 0);
        if (debug == DEBUG_SOURCE_VIEW) return rgba(sourceView(px.light[2], px.fromLod, hasAlbedo), 0);
        float hazeSky = isWater ? RelightMath.skyShare(sky) : clamp01(skyT / 15);
        float amount = AtmosphereMath.hazeAmount(a, known && !cloud ? hazeSky : 1, px.fromLod);
        float[] hazed = AtmosphereMath.applyHaze(a, lit, px.dir, px.dist, amount);
        float border = a.mode() == AtmosphereMath.ATMOSPHERE_SKY ? borderAmount(slots[BORDER_FOG], px.dir, px.dist, px.fromLod) : 0;
        if (border > 0) {
            float[] behind = add(AtmosphereMath.skyColour(a, px.dir), AtmosphereMath.sunGlow(a, px.dir));
            for (int i = 0; i < 3; i++) hazed[i] += (behind[i] - hazed[i]) * border;
        }
        return rgba(scale(farCloudsOver(a, slots, hazed, px.dir, px.cloudMarch), underwaterExposure(look)), passRan && !cloud ? WaterMath.reflectionMark(kind) : 0);
    }

    /** B18: the light debug views. */
    public static float[] debugView(int view, boolean known, float sky, float block, float[] share, float sunPart) {
        if (!known) return new float[]{DEBUG_UNKNOWN_RED, DEBUG_UNKNOWN_GREEN, DEBUG_UNKNOWN_BLUE};
        if (view == DEBUG_SKY_VIEW) {
            float v = PostMath.displayToLinear(clamp01(sky / 15));
            return new float[]{v, v, v};
        }
        if (view == DEBUG_BLOCK_VIEW) {
            float v = PostMath.displayToLinear(clamp01(block / 15));
            return new float[]{v, v, v};
        }
        float s = PostMath.luminance(share[0], share[1], share[2]);
        float sun = s * clamp01(sunPart);
        return new float[]{sun, 1 - s, s - sun};
    }

    /**
     * 2026-10-23, debug view 5: green the terrain light with an albedo, cyan without (source 2), blue the light volume, yellow the
     * LOD pass (1), else the unknown colour.
     */
    public static float[] sourceView(float source, boolean fromLod, boolean albedo) {
        if (source < 0.5f) return new float[]{DEBUG_UNKNOWN_RED, DEBUG_UNKNOWN_GREEN, DEBUG_UNKNOWN_BLUE};
        if (source > 1.5f) return albedo ? new float[]{0, 1, 0} : new float[]{0, 1, 1};
        return fromLod ? new float[]{1, 1, 0} : new float[]{0, 0, 1};
    }

    /** scene_copy.comp borderAmount: the border fog's share at a vanilla surface (dir, dist from the camera), 0 for LOD pixels or off. */
    public static float borderAmount(float[] borderFog, float[] dir, float dist, boolean fromLod) {
        if (borderFog[2] < 0.5f || fromLod) return 0;
        float d = (float) Math.hypot(dir[0] * dist, dir[2] * dist);
        float t = Math.max(0, Math.min(1, (d - borderFog[0]) / (borderFog[1] - borderFog[0])));
        return t * t * (3 - 2 * t);
    }

    private static float[] rgba(float[] c, float a) {
        return new float[]{c[0], c[1], c[2], a};
    }

    private static float[] add(float[] a, float[] b) {
        return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static float[] mul(float[] a, float[] b) {
        return new float[]{a[0] * b[0], a[1] * b[1], a[2] * b[2]};
    }

    private static float[] scale(float[] a, float s) {
        return new float[]{a[0] * s, a[1] * s, a[2] * s};
    }

    private static float max3(float[] a) {
        return Math.max(a[0], Math.max(a[1], a[2]));
    }

    static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float clamp01(float v) {
        return v > 0 ? Math.min(v, 1) : 0;
    }
}
