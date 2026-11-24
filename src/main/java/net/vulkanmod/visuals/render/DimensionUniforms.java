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
    /**
     * 2026-11-23 (the user: the End's background like outer space): the composite's END_SKY slot (81, scene_copy.comp endSkyParams): x the
     * End's sky style (END_SKY_*, endsky.glsl; 0 outside the End: vanilla's sky as before). 2026-11-24 (the user: a golden light pillar
     * at the End island's very centre): yz the camera's world x and z from the pillar's axis (PILLAR_X, PILLAR_Z), w the pillar (pillar:
     * its column's top and its depth; PILLAR_OFF: none).
     */
    public static final int COPY_END_SKY = 81;
    /** The End sky setting's styles (VisualsConfig.endSky; endsky.glsl END_SKY_SPACE, END_SKY_ANIME). */
    public static final int END_SKY_VANILLA = 0, END_SKY_SPACE = 1, END_SKY_ANIME = 2, END_SKY_MAX = 2;
    /** The light pillar's axis (the middle of block 0, 0: the exit portal's bedrock column). */
    public static final double PILLAR_X = 0.5, PILLAR_Z = 0.5;
    /** The pillar value meaning no pillar (scene_copy.comp: below -1e8). */
    public static final float PILLAR_OFF = -1e9f;
    /** The pillar's depth below its column's top travels as 1024ths of a block in its value's fraction (at most PILLAR_DEPTH_MAX). */
    public static final int PILLAR_DEPTH_STEPS = 1024, PILLAR_DEPTH_MAX = 1023;
    /** The three settings' range (percent; 100 = the default look). */
    public static final int STRENGTH_MAX = 200;

    /** The dimension's kind for these effects: the End (galaxy), a hot one (the Nether: heat haze), or another. */
    public enum Kind {
        END, HOT, OTHER
    }

    /** pillarX, pillarZ: the camera from the light pillar's axis (blocks), pillarBase the pillar (pillar; PILLAR_OFF: none). */
    public record Values(float galaxy, float heat, float cameraY, float seconds, int endSky, float pillarX, float pillarZ, float pillarBase) {
        public Values(float galaxy, float heat, float cameraY, float seconds, int endSky) {
            this(galaxy, heat, cameraY, seconds, endSky, 0, 0, PILLAR_OFF);
        }

        public void write(LooseUniformWriter w) {
            if (w.has(COPY_DIMENSION)) w.putVec4(COPY_DIMENSION, this.galaxy, this.heat, this.cameraY, this.seconds);
            if (w.has(COPY_END_SKY)) w.putVec4(COPY_END_SKY, this.endSky, this.pillarX, this.pillarZ, this.pillarBase);
        }

        /** These values without the galaxy (its map not uploaded yet). */
        public Values withGalaxy(float g) {
            return new Values(g, this.heat, this.cameraY, this.seconds, this.endSky, this.pillarX, this.pillarZ, this.pillarBase);
        }

        /** The heat haze pass runs (and the final colour reads its offsets). */
        public boolean heatOn() {
            return this.heat > 0;
        }
    }

    private DimensionUniforms() {
    }

    public static Values off() {
        return new Values(0, 0, 0, 0, END_SKY_VANILLA);
    }

    /** s the settings, kind the dimension's, cameraY the camera's world y, seconds the real time (FlowClock). */
    public static Values compute(PostProcessSettings s, Kind kind, double cameraY, double seconds) {
        return compute(s, kind, PILLAR_X, cameraY, PILLAR_Z, PILLAR_OFF, seconds);
    }

    /**
     * 2026-11-24 (the user: "빛기둥의 시작지점을 포탈 y좌표만큼 아래로 이동해줘. 빛기둥은 엔더드래곤 처치가 완료된 후 그 위치의 드래곤 알이
     * 없어졌을 때만 나타나게 해줘"): the pillar from the exit portal's column: columnTop the top face of its bedrock (world y, the egg's
     * floor), egg whether the dragon egg sits there, litPortalY the y of the lit exit portal beside it (the dragon dead), or
     * Integer.MIN_VALUE without one. Its shape stands on the column's top (the whole part); its light starts the portal's y lower (the
     * depth, in the fraction: pillarDepth). PILLAR_OFF while the dragon lives or its egg is there.
     */
    public static float pillar(int columnTop, boolean egg, int litPortalY) {
        if (egg || litPortalY == Integer.MIN_VALUE) return PILLAR_OFF;
        int depth = Math.max(0, Math.min(PILLAR_DEPTH_MAX, litPortalY));
        return columnTop + depth / (float) PILLAR_DEPTH_STEPS;
    }

    /** The pillar's column top (world y) and its depth (blocks) from pillar's value (as scene_copy.comp reads them). */
    public static float pillarTop(float pillar) {
        return (float) Math.floor(pillar);
    }

    public static float pillarDepth(float pillar) {
        return (pillar - pillarTop(pillar)) * PILLAR_DEPTH_STEPS;
    }

    /** As above with the camera's world x and z and the light pillar (pillar; PILLAR_OFF: none). */
    public static Values compute(PostProcessSettings s, Kind kind, double cameraX, double cameraY, double cameraZ, double pillarBase,
                                 double seconds) {
        if (s == null || !s.enabled()) return off();
        float galaxy = kind == Kind.END ? Math.max(0, s.endGalaxy()) : 0;
        float heat = kind == Kind.HOT ? Math.max(0, s.netherHeatHaze()) : 0;
        double t = seconds - Math.floor(seconds / HeatMath.HEAT_TIME_WRAP) * HeatMath.HEAT_TIME_WRAP;
        int endSky = kind == Kind.END ? Math.max(END_SKY_VANILLA, Math.min(END_SKY_MAX, s.endSky())) : END_SKY_VANILLA;
        float px = Double.isFinite(cameraX) ? (float) (cameraX - PILLAR_X) : 0;
        float pz = Double.isFinite(cameraZ) ? (float) (cameraZ - PILLAR_Z) : 0;
        float base = kind == Kind.END && Double.isFinite(pillarBase) ? (float) pillarBase : PILLAR_OFF;
        return new Values(galaxy, heat, Double.isFinite(cameraY) ? (float) cameraY : 0, Double.isFinite(t) ? (float) t : 0, endSky, px, pz, base);
    }
}
