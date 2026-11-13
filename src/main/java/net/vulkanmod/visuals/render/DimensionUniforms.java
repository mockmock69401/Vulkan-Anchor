package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.LooseUniformWriter;

/**
 * 2026-11-13 (shader roadmap session 19, the user: AstraLex's Nether heat, portal light and End galaxy sky): the composite's
 * DIMENSION slot (62, scene_copy.comp dimensionParams): x the End's galaxy strength (galaxy.glsl; 0 outside the End), y the Nether's heat
 * haze strength (heat.glsl, the composite's .HEAT build; 0 outside an ultra-warm dimension), z the camera's world y, w the time in seconds
 * wrapped at HeatMath.HEAT_TIME_WRAP. Strengths are the settings' ratios (1 = 100%); the post-process off gives off().
 */
public final class DimensionUniforms {
    public static final int COPY_DIMENSION = 62;
    /** The three settings' range (percent; 100 = the default look). */
    public static final int STRENGTH_MAX = 200;

    /** The dimension's kind for these effects: the End (galaxy), a hot one (the Nether: heat haze), or another. */
    public enum Kind {
        END, HOT, OTHER
    }

    public record Values(float galaxy, float heat, float cameraY, float seconds) {
        public void write(LooseUniformWriter w) {
            if (w.has(COPY_DIMENSION)) w.putVec4(COPY_DIMENSION, this.galaxy, this.heat, this.cameraY, this.seconds);
        }

        /** These values without the galaxy (its map not uploaded yet). */
        public Values withGalaxy(float g) {
            return new Values(g, this.heat, this.cameraY, this.seconds);
        }

        /** The heat haze pass runs (and the final colour reads its offsets). */
        public boolean heatOn() {
            return this.heat > 0;
        }
    }

    private DimensionUniforms() {
    }

    public static Values off() {
        return new Values(0, 0, 0, 0);
    }

    /** s the settings, kind the dimension's, cameraY the camera's world y, seconds the real time (FlowClock). */
    public static Values compute(PostProcessSettings s, Kind kind, double cameraY, double seconds) {
        if (s == null || !s.enabled()) return off();
        float galaxy = kind == Kind.END ? Math.max(0, s.endGalaxy()) : 0;
        float heat = kind == Kind.HOT ? Math.max(0, s.netherHeatHaze()) : 0;
        double t = seconds - Math.floor(seconds / HeatMath.HEAT_TIME_WRAP) * HeatMath.HEAT_TIME_WRAP;
        return new Values(galaxy, heat, Double.isFinite(cameraY) ? (float) cameraY : 0, Double.isFinite(t) ? (float) t : 0);
    }
}
