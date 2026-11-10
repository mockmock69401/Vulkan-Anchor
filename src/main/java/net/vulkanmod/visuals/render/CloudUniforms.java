package net.vulkanmod.visuals.render;

import org.joml.Vector3f;
import net.vulkanmod.visuals.vk.LooseUniformWriter;

/**
 * W4: the far clouds' block, five vec4 from COPY_FIRST in the composite and from REFLECTION_FIRST in the reflection:
 * <ol start="0">
 * <li>cloudShape (clouds.glsl): x the layer's bottom relative to the camera, y its thickness (x and y whenever vanilla has a cloud
 * layer, also with the far clouds off; session 3: while the realistic clouds are drawn, their cumulus layer, CloudMath.REAL_LIFT higher
 * and REAL_THICKNESS thick), z the LOD distance in blocks, w 1 when
 * the far clouds are drawn (0: cloudMarch returns nothing)</li>
 * <li>cloudOffset (clouds.glsl): xy the camera's x, z on the cloud map in blocks; z (session 3) the realistic style's quality 1..3 plus
 * the frame's jitter phase as its fraction (0: vanilla's cell shape), w the share of the sky the cumulus may cover</li>
 * <li>CloudLight.lit (atmosphere.glsl): rgb a fully sunlit cloud's colour, scene units, vanilla's cloud colour and CLOUD_ALBEDO applied</li>
 * <li>CloudLight.ambient: rgb the sky light every cloud sample gets</li>
 * <li>CloudLight.light: xyz the direction the clouds are lit from (world, unit: the sun, the moon while the sun is down)</li>
 * </ol>
 * The composite reads the last three as {@code CloudLight(slot14, slot15, slot16)}, the reflection as {@code CloudLight(slot16, slot17, slot18)}.
 */
public final class CloudUniforms {
    public static final int COPY_FIRST = 12, REFLECTION_FIRST = 14;
    public static final int SLOTS = 5;
    /** A fully sunlit cloud is CLOUD_LIGHT x vanilla's cloud colour x CLOUD_ALBEDO; every sample also gets CLOUD_AMBIENT of it in the sky's shade tint. */
    public static final float CLOUD_LIGHT = 0.9f, CLOUD_AMBIENT = 0.15f;
    /**
     * Session 3: the realistic style's light. A fully sunlit cloud is REAL_LIGHT x the sky's sunlight (Atmosphere.sunLight: session 1a's
     * time-of-day colour, gold to orange at sunset, dimmed in rain; the moon's light while the sun is down) per unit of phase; the
     * multiple-scattering octaves add up to 1.75 of it where nothing shades the cloud. The sky light is REAL_AMBIENT x the sky's colour
     * straight up (AtmosphereMath.skyColour: blue by day, violet at dusk, grey in rain, dark at night).
     */
    public static final float REAL_LIGHT = 0.6f, REAL_AMBIENT = 0.85f;
    /** Session 11: the anime style's light, as REAL_LIGHT and REAL_AMBIENT (the cel bands give the sunlit weight 0.2 in shadow to 1.3 at the top). */
    public static final float ANIME_LIGHT = 0.8f, ANIME_AMBIENT = 1.0f, ANIME_BOOST_LUMA = 0.8f, ANIME_BOOST_MAX = 3;
    /** Session 3: rain and thunder raise the cumulus' share of the sky by these fractions of itself (a rainy sky is overcast). */
    public static final float REAL_RAIN = 0.8f, REAL_THUNDER = 0.3f;
    /** Session 3: the jitter phase advances by the golden ratio's fraction a frame (TAA spreads the samples); kept under REAL_PHASE_MAX so the quality's whole part stays. */
    public static final float REAL_PHASE_STEP = 0.618034f, REAL_PHASE_MAX = 0.999f;

    /** slots: SLOTS vec4 (4 * SLOTS floats) in block order. */
    public record Values(boolean on, float[] slots) {
        @Override
        public float[] slots() {
            return this.slots.clone();
        }

        /** Session 7: the realistic style (cloudOffset.z at least 1, clouds.glsl's cloudRealistic); session 11: also the anime style (cloudAnime), which the lean reflection build leaves out as well. */
        public boolean realistic() {
            return this.slots.length > 6 && this.slots[6] >= 1;
        }

        /** The shader's CloudLight, for the Java mirror. */
        public AtmosphereMath.CloudLight light() {
            return new AtmosphereMath.CloudLight(java.util.Arrays.copyOfRange(this.slots, 8, 12), java.util.Arrays.copyOfRange(this.slots, 12, 16),
                    java.util.Arrays.copyOfRange(this.slots, 16, 20));
        }

        public void write(LooseUniformWriter w, int first) {
            for (int i = 0; i < SLOTS; i++) {
                if (w.has(first + i)) w.putVec4(first + i, this.slots[i * 4], this.slots[i * 4 + 1], this.slots[i * 4 + 2], this.slots[i * 4 + 3]);
            }
        }
    }

    private CloudUniforms() {
    }

    /**
     * On when the far clouds setting is on, the post-process enabled, the frame current with sky light and vanilla's cloud layer, the
     * atmosphere in ATMOSPHERE_SKY (not under an environmental fog), the cloud map uploaded and the LOD distance positive. The colours
     * are filled whenever the atmosphere is ATMOSPHERE_SKY, also when off.
     * <p>Tuning round 1 (W7's B3 request): cloudShape x (the layer's bottom relative to the camera) and y (CLOUD_THICKNESS) are filled
     * whenever the frame has sky light and vanilla's cloud layer, also while the far clouds are off (w 0), so the composite can find
     * vanilla's own clouds. The frame does not tell vanilla's flat ("fast") clouds from its boxes, so y is always the box thickness, which
     * is positive and contains a flat layer at the bottom; z (LOD distance) and the offsets are filled only when on.
     */
    public static Values compute(PostProcessSettings settings, AtmosphereFrame frame, AtmosphereUniforms.Values atmosphere, long serial,
                                 boolean hasSky, boolean cloudMapReady, float lodSections) {
        float[] v = new float[4 * SLOTS];
        if (settings == null || frame == null || frame.serial() != serial || !hasSky) return new Values(false, v);
        CloudFrame clouds = frame.clouds();
        boolean layer = clouds != null && Float.isFinite(clouds.height()) && Float.isFinite(frame.cameraY());
        if (layer) {
            v[0] = clouds.height() + CloudMath.CLOUD_LIFT - frame.cameraY();
            v[1] = CloudMath.CLOUD_THICKNESS;
        }
        if (atmosphere == null || atmosphere.mode() != AtmosphereMath.ATMOSPHERE_SKY) return new Values(false, v);
        boolean realistic = layer && settings.cloudStyle() == CloudMath.STYLE_REALISTIC && settings.farClouds();
        //Session 11: the anime style takes the realistic style's light (the sky's sunlight, the sky's colour above) and its layer's coverage
        boolean anime = layer && settings.cloudStyle() == CloudMath.STYLE_ANIME && settings.farClouds();
        boolean styled = realistic || anime;
        float[] base = {1, 1, 1};
        if (clouds != null && clouds.colour() != null && clouds.colour().length >= 3) {
            for (int i = 0; i < 3; i++) base[i] = AtmosphereUniforms.vanillaToScene(clouds.colour()[i]);
        }
        Vector3f sun = AtmosphereUniforms.sunDirection(frame);
        boolean day = sun.y >= 0;
        float[] tint = day ? AtmosphereUniforms.sunTint(sun.y, settings.sunsetLight()) : new float[]{1, 1, 1};
        AtmosphereMath.Atmosphere a = styled ? atmosphere.atmosphere() : null;
        float[] zenith = styled ? AtmosphereMath.skyColour(a, new float[]{0, 1, 0}) : null;
        float[] sunlight = styled ? (day ? a.sunLight() : a.moonLight()) : null;
        float litGain = anime ? ANIME_LIGHT * animeBoost(sunlight, frame.rain()) : REAL_LIGHT, ambientGain = anime ? ANIME_AMBIENT : REAL_AMBIENT;
        //2026-11-10 (session 8): the picked colours (exactly 1 at the defaults): the cloud colour on both styles' light; the vanilla shape's
        // sunlit tint also takes the sky's (day, sunset, sunrise; the night sky's by night), which the realistic style's sunlight has already
        ShaderColours colours = settings.colours();
        float[] cloud = colours.tint(ShaderColours.Colour.CLOUDS);
        float[] sky = day ? AtmosphereUniforms.sunTint(colours, sun.y, AtmosphereUniforms.morning(sun.x)) : colours.tint(ShaderColours.Colour.NIGHT_SKY);
        for (int i = 0; i < 3; i++) {
            v[8 + i] = (styled ? litGain * Math.max(sunlight[i], 0) : base[i] * CloudMath.CLOUD_ALBEDO * CLOUD_LIGHT * tint[i] * sky[i]) * cloud[i];
            v[12 + i] = (styled ? ambientGain * Math.max(zenith[i], 0) : base[i] * CloudMath.CLOUD_ALBEDO * CLOUD_AMBIENT * ColourMath.SKY_SHADE[i]) * cloud[i];
        }
        v[16] = day ? sun.x : -sun.x;
        v[17] = day ? sun.y : -sun.y;
        v[18] = day ? sun.z : -sun.z;
        float lodDistance = lodSections * CloudMath.LOD_BLOCKS_PER_SECTION;
        boolean on = settings.enabled() && settings.farClouds() && layer && cloudMapReady && lodDistance > 0 && Float.isFinite(lodDistance);
        if (on) {
            v[2] = lodDistance;
            v[3] = 1;
            v[4] = Float.isFinite(clouds.offsetX()) ? clouds.offsetX() : 0;
            v[5] = Float.isFinite(clouds.offsetZ()) ? clouds.offsetZ() : 0;
            if (realistic) {
                //Only while drawn: otherwise vanilla's own clouds may be up and the composite finds them by vanilla's layer
                v[0] += CloudMath.REAL_LIFT;
                //After 3b: a bigger cloud size also thickens the layer (CloudMath.realThickness)
                v[1] = CloudMath.realThickness(settings.cloudSize());
                float phase = settings.taa() ? (float) ((serial * (double) REAL_PHASE_STEP) % 1.0) : 0;
                v[6] = 1 + Math.max(0, Math.min(2, settings.cloudQuality())) + Math.max(0, Math.min(REAL_PHASE_MAX, phase));
                v[7] = realCover(settings.cloudCoverage(), frame.rain(), frame.thunder());
            } else if (anime) {
                //Session 11: the anime layer, its quality after CloudMath.ANIME_Z, the same jitter phase and coverage
                v[0] += CloudMath.ANIME_LIFT;
                v[1] = CloudMath.animeThickness(settings.cloudSize());
                float phase = settings.taa() ? (float) ((serial * (double) REAL_PHASE_STEP) % 1.0) : 0;
                v[6] = CloudMath.ANIME_Z + Math.max(0, Math.min(2, settings.cloudQuality())) + Math.max(0, Math.min(REAL_PHASE_MAX, phase));
                v[7] = realCover(settings.cloudCoverage(), frame.rain(), frame.thunder());
            }
        }
        return new Values(on, v);
    }

    /**
     * Session 11: the anime style keeps a low sun's light bright (Fantasy's sunset clouds are lit peach, not dimmed as a real low sun's):
     * its light is raised toward ANIME_BOOST_LUMA of luminance, at most ANIME_BOOST_MAX times, keeping its hue (the moon's stays dark).
     * Rain (0..1) takes the boost away (boost^(1 - rain)): the light rain dims stays dim, so rain clouds are grey.
     */
    public static float animeBoost(float[] light, float rain) {
        float r = Float.isFinite(rain) ? Math.max(0, Math.min(1, rain)) : 0;
        return (float) Math.pow(animeBoost(light), 1 - r);
    }

    /** animeBoost in clear weather. */
    public static float animeBoost(float[] light) {
        float luma = 0.2126f * Math.max(light[0], 0) + 0.7152f * Math.max(light[1], 0) + 0.0722f * Math.max(light[2], 0);
        if (!(luma > 0)) return 1;
        return Math.max(1, Math.min(ANIME_BOOST_MAX, ANIME_BOOST_LUMA / luma));
    }

    /** Session 3: the cumulus' share of the sky (cloudOffset.w): REAL_COVER x the coverage setting (1 = 100%), raised by rain and thunder (0..1 each). */
    public static float realCover(float coverage, float rain, float thunder) {
        float c = Float.isFinite(coverage) ? Math.max(coverage, 0) : 1;
        float r = Float.isFinite(rain) ? Math.max(0, Math.min(1, rain)) : 0, t = Float.isFinite(thunder) ? Math.max(0, Math.min(1, thunder)) : 0;
        return CloudMath.REAL_COVER * c * (1 + REAL_RAIN * r + REAL_THUNDER * t);
    }
}
