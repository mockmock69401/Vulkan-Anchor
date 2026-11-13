package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.LooseUniformWriter;

/**
 * 2026-11-13 (shader roadmap session 19, the user: AstraLex's End galaxy sky): the composite's DIMENSION slot (62, scene_copy.comp
 * dimensionParams): x the End's galaxy strength (galaxy.glsl; 0 outside the End); y, z, w unused (0). The strength is the setting's ratio
 * (1 = 100%); the post-process off gives off().
 */
public final class DimensionUniforms {
    public static final int COPY_DIMENSION = 62;
    /** The settings' range (percent; 100 = the default look). */
    public static final int STRENGTH_MAX = 200;

    /** The dimension's kind for these effects: the End (galaxy) or another. */
    public enum Kind {
        END, OTHER
    }

    public record Values(float galaxy) {
        public void write(LooseUniformWriter w) {
            if (w.has(COPY_DIMENSION)) w.putVec4(COPY_DIMENSION, this.galaxy, 0, 0, 0);
        }

        /** These values without the galaxy (its map not uploaded yet). */
        public Values withGalaxy(float g) {
            return new Values(g);
        }
    }

    private DimensionUniforms() {
    }

    public static Values off() {
        return new Values(0);
    }

    /** s the settings, kind the dimension's. */
    public static Values compute(PostProcessSettings s, Kind kind) {
        if (s == null || !s.enabled()) return off();
        return new Values(kind == Kind.END ? Math.max(0, s.endGalaxy()) : 0);
    }
}
