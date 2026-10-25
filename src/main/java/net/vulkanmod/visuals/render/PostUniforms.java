package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.LooseUniformWriter;

/**
 * W2: the final pass's uniforms (postfinal.glsl, push constants, 5 vec4 = 80 bytes):
 * <ul>
 * <li>0: x exposure, y tone knee, z bloom mix weight, w 1 / bloom levels</li>
 * <li>1: rgb light-shaft colour (scaled by the setting), w 1 when the shafts are read</li>
 * <li>2: rgb warm tint (bright areas), w saturation</li>
 * <li>3: rgb cool tint (dark areas), w contrast - 1 (Schlick gain k)</li>
 * <li>4: x 1 / width, y 1 / height, z vignette strength, w unused</li>
 * </ul>
 */
public final class PostUniforms {
    /** boundary 3.4: the bloom passes' (1/srcWidth, 1/srcHeight) slot. */
    public static final int BLOOM_PARAMS = 0;
    /** The final pass's locations. */
    static final int EXPOSURE = 0, SHAFTS = 1, WARM = 2, COOL = 3, SCREEN = 4;

    public record Values(int width, int height, float exposure, float toneKnee, float bloomMix, float bloomNorm, float[] shaft,
                         boolean shaftsOn, float[] warm, float[] cool, float saturation, float contrast, float vignette) {
        public void write(LooseUniformWriter w) {
            w.putVec4(EXPOSURE, this.exposure, this.toneKnee, this.bloomMix, this.bloomNorm);
            w.putVec4(SHAFTS, this.shaft[0], this.shaft[1], this.shaft[2], this.shaftsOn ? 1 : 0);
            w.putVec4(WARM, this.warm[0], this.warm[1], this.warm[2], this.saturation);
            w.putVec4(COOL, this.cool[0], this.cool[1], this.cool[2], this.contrast - 1);
            w.putVec4(SCREEN, 1f / Math.max(1, this.width), 1f / Math.max(1, this.height), this.vignette, 0);
        }
    }

    private PostUniforms() {
    }

    /**
     * Once per frame. The grade follows the sun height from timeOfDay (0 = noon) and is neutral without sky; the shafts are read
     * only with sky, a non-zero setting and a non-zero, finite colour (shaftLight: the shaft colour at full strength in scene
     * units per unit of the shaft image, zero when no shaft pass ran this frame).
     */
    public static Values compute(PostProcessSettings settings, int width, int height, float timeOfDay, boolean hasSky, int bloomLevels,
                                 float[] shaftLight) {
        float[] warm = {1, 1, 1}, cool = {1, 1, 1};
        float grade = settings.gradeStrength();
        if (hasSky && grade > 0) {
            float sunHeight = SunMath.sunDirection(timeOfDay).y;
            float g = grade * PostMath.gradeFade(sunHeight);
            if (g > 0) {
                float[] w = PostMath.warmTint(sunHeight), c = PostMath.COOL_TINT;
                for (int i = 0; i < 3; i++) {
                    warm[i] = 1 + (w[i] - 1) * g;
                    cool[i] = 1 + (c[i] - 1) * g;
                }
            }
        }
        float[] shaft = {0, 0, 0};
        boolean shaftsOn = false;
        float shaftStrength = settings.lightShafts() * PostMath.SHAFT_SCALE;
        if (hasSky && shaftStrength > 0 && shaftLight != null && shaftLight.length >= 3) {
            for (int i = 0; i < 3; i++) {
                float s = shaftLight[i];
                shaft[i] = Float.isFinite(s) ? Math.max(0, s) * shaftStrength : 0;
                shaftsOn |= shaft[i] > 0;
            }
        }
        return new Values(width, height, settings.exposure() * PostMath.EXPOSURE_SCALE, PostMath.toneKnee(settings.toneStrength()),
                settings.bloomStrength() * PostMath.BLOOM_MIX, 1f / Math.max(1, bloomLevels), shaft, shaftsOn, warm, cool,
                settings.saturation(), settings.contrast(), settings.vignetteStrength());
    }
}
