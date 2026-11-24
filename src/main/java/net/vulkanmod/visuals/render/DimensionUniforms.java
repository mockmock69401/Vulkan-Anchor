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
    /**
     * 2026-11-24 (the user: "엔더드래곤 처치 후 빛기둥이 생길 때 페이드인으로 구현해줘"; "엔드에서 가장 밝은 별로 인한 빛 그림자도 구현해줘"): the
     * composite's END_LIGHT slot (82, scene_copy.comp endLightParams): x the pillar's fade-in (0 to 1, PillarFade), y the bright star's
     * shadows' strength (0: none), z the portals' look (PORTAL_LOOK_*, portallook.glsl; the user: "네더포탈과 엔드포탈을 일반/애니메에
     * 적절하게 각각 만들어줘"), w unused.
     */
    public static final int COPY_END_LIGHT = 82;
    /** The portals' looks: vanilla's, the Default palette's (and Horror's), Anime's (portallook.glsl PORTAL_LOOK_DEFAULT, _ANIME). */
    public static final int PORTAL_LOOK_VANILLA = 0, PORTAL_LOOK_DEFAULT = 1, PORTAL_LOOK_ANIME = 2;
    /** The End sky setting's styles (VisualsConfig.endSky; endsky.glsl END_SKY_SPACE, END_SKY_ANIME). */
    public static final int END_SKY_VANILLA = 0, END_SKY_SPACE = 1, END_SKY_ANIME = 2, END_SKY_MAX = 2;
    /** The light pillar's axis (the middle of block 0, 0: the exit portal's bedrock column). */
    public static final double PILLAR_X = 0.5, PILLAR_Z = 0.5;
    /** The pillar value meaning no pillar (scene_copy.comp: below -1e8). */
    public static final float PILLAR_OFF = -1e9f;
    /** The pillar's depth below its column's top travels as 1024ths of a block in its value's fraction (at most PILLAR_DEPTH_MAX). */
    public static final int PILLAR_DEPTH_STEPS = 1024, PILLAR_DEPTH_MAX = 1023;
    /** How long the pillar takes to fade in once the exit portal lights (seconds; PillarFade). */
    public static final float PILLAR_FADE_SECONDS = 4;
    /** The three settings' range (percent; 100 = the default look). */
    public static final int STRENGTH_MAX = 200;

    /** The dimension's kind for these effects: the End (galaxy), a hot one (the Nether: heat haze), or another. */
    public enum Kind {
        END, HOT, OTHER
    }

    /**
     * pillarX, pillarZ: the camera from the light pillar's axis (blocks), pillarBase the pillar (pillar; PILLAR_OFF: none), pillarFade its
     * fade-in (0 to 1), starShadow the End star's shadows' strength (0: none).
     */
    public record Values(float galaxy, float heat, float cameraY, float seconds, int endSky, float pillarX, float pillarZ, float pillarBase,
                         float pillarFade, float starShadow, int portalLook, float netherLook) {
        public Values(float galaxy, float heat, float cameraY, float seconds, int endSky) {
            this(galaxy, heat, cameraY, seconds, endSky, 0, 0, PILLAR_OFF, 1, 0, PORTAL_LOOK_VANILLA, 0);
        }

        public Values(float galaxy, float heat, float cameraY, float seconds, int endSky, float pillarX, float pillarZ, float pillarBase,
                      float pillarFade, float starShadow, int portalLook) {
            this(galaxy, heat, cameraY, seconds, endSky, pillarX, pillarZ, pillarBase, pillarFade, starShadow, portalLook, 0);
        }

        /** 2026-11-24: these values with the Anime Nether's look (1: on, netherlook.glsl). */
        public Values withNetherLook(float look) {
            return new Values(this.galaxy, this.heat, this.cameraY, this.seconds, this.endSky, this.pillarX, this.pillarZ, this.pillarBase,
                    this.pillarFade, this.starShadow, this.portalLook, look);
        }

        public void write(LooseUniformWriter w) {
            if (w.has(COPY_DIMENSION)) w.putVec4(COPY_DIMENSION, this.galaxy, this.heat, this.cameraY, this.seconds);
            if (w.has(COPY_END_SKY)) w.putVec4(COPY_END_SKY, this.endSky, this.pillarX, this.pillarZ, this.pillarBase);
            if (w.has(COPY_END_LIGHT)) w.putVec4(COPY_END_LIGHT, this.pillarFade, this.starShadow, this.portalLook, this.netherLook);
        }

        /** These values without the galaxy (its map not uploaded yet). */
        public Values withGalaxy(float g) {
            return new Values(g, this.heat, this.cameraY, this.seconds, this.endSky, this.pillarX, this.pillarZ, this.pillarBase, this.pillarFade,
                    this.starShadow, this.portalLook, this.netherLook);
        }

        /** These values with the End star's shadows at strength (EndStarLight). */
        public Values withStarShadow(float strength) {
            return new Values(this.galaxy, this.heat, this.cameraY, this.seconds, this.endSky, this.pillarX, this.pillarZ, this.pillarBase,
                    this.pillarFade, strength, this.portalLook, this.netherLook);
        }

        /** These values with the portals' look (portalLook: the colour palette's). */
        public Values withPortalLook(int look) {
            return new Values(this.galaxy, this.heat, this.cameraY, this.seconds, this.endSky, this.pillarX, this.pillarZ, this.pillarBase,
                    this.pillarFade, this.starShadow, look, this.netherLook);
        }

        /** The heat haze pass runs (and the final colour reads its offsets). */
        public boolean heatOn() {
            return this.heat > 0;
        }
    }

    private DimensionUniforms() {
    }

    /**
     * 2026-11-24: whether level is the End with its own sky drawn (the effects on, the End sky setting not vanilla's); the dragon's boss
     * fog is left out then (LevelRendererBossFogMixin). A failure (a modded level) counts as no.
     */
    public static boolean endSkyDrawn(net.minecraft.client.multiplayer.ClientLevel level) {
        try {
            if (level == null || level.effects().skyType() != net.minecraft.client.renderer.DimensionSpecialEffects.SkyType.END) return false;
            PostProcessSettings s = PostProcessSettings.from(net.vulkanmod.visuals.config.VisualsConfig.get());
            return s.enabled() && s.endSky() > END_SKY_VANILLA;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 2026-11-24 (the user: "애니메이션 프리셋용 레퍼런스를 보낼게. 네더의 색감을 살리는데 참고해줘"): whether the Anime Nether's look is on: a hot
     * dimension (the Nether) under the Anime palette. Default and Horror keep the Nether as it was.
     */
    public static boolean netherLook(Kind kind, int palette) {
        return kind == Kind.HOT && palette == net.vulkanmod.visuals.config.ColourPresets.ANIME;
    }

    /** The portals' look for a colour palette (ColourPresets): Anime's for Anime, the Default one for the others. */
    public static int portalLook(int palette) {
        return palette == net.vulkanmod.visuals.config.ColourPresets.ANIME ? PORTAL_LOOK_ANIME : PORTAL_LOOK_DEFAULT;
    }

    public static Values off() {
        return new Values(0, 0, 0, 0, END_SKY_VANILLA);
    }

    /** s the settings, kind the dimension's, cameraY the camera's world y, seconds the real time (FlowClock). */
    public static Values compute(PostProcessSettings s, Kind kind, double cameraY, double seconds) {
        return compute(s, kind, PILLAR_X, cameraY, PILLAR_Z, PILLAR_OFF, seconds);
    }

    /**
     * 2026-11-24 (the user: "빛기둥의 시작지점을 포탈 y좌표만큼 아래로 이동해줘"; then "포탈 가운데 빛기둥 출력 조건을 드래곤 알 제거 여부에서
     * 엔더드래곤 처치로 수정해줘"): the pillar from the exit portal's column: columnTop the top face of its bedrock (world y, the egg's
     * floor), litPortalY the y of the lit exit portal beside it (the dragon dead), or Integer.MIN_VALUE without one. Its shape stands on
     * the column's top (the whole part); its light starts the portal's y lower (the depth, in the fraction: pillarDepth). PILLAR_OFF while
     * the dragon lives; the egg no longer matters.
     */
    public static float pillar(int columnTop, int litPortalY) {
        if (litPortalY == Integer.MIN_VALUE) return PILLAR_OFF;
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

    /**
     * 2026-11-24 (the user: "엔더드래곤 처치 후 빛기둥이 생길 때 페이드인으로 구현해줘"): the pillar's fade-in. Once the exit portal lights while
     * its chunk was watched dark (the dragon killed in sight) the pillar fades in over PILLAR_FADE_SECONDS; a portal already lit when its
     * chunk first loads (entering the End after the fight) shows the pillar at once. update: loaded whether the portal's chunk is loaded,
     * lit whether its portal is lit, nanos the time now; it returns the fade (0 to 1).
     */
    public static final class PillarFade {
        private static final int UNKNOWN = 0, DARK = 1, LIT = 2;
        private int state = UNKNOWN;
        private float fade = 1;
        private long lastNanos = Long.MIN_VALUE;

        public float update(boolean loaded, boolean lit, long nanos) {
            float dt = this.lastNanos == Long.MIN_VALUE ? 0 : Math.max(0, Math.min(0.25f, (nanos - this.lastNanos) / 1e9f));
            this.lastNanos = nanos;
            if (!loaded) {
                this.state = UNKNOWN;
                return 0;
            }
            if (!lit) {
                this.state = DARK;
                this.fade = 0;
                return 0;
            }
            if (this.state == UNKNOWN) {
                this.fade = 1;
            } else if (this.state == DARK) {
                this.fade = 0;
            } else {
                this.fade = Math.min(1, this.fade + dt / PILLAR_FADE_SECONDS);
            }
            this.state = LIT;
            return this.fade;
        }

        /** Another level: what was seen there no longer counts. */
        public void reset() {
            this.state = UNKNOWN;
            this.lastNanos = Long.MIN_VALUE;
        }
    }

    /** As above with the camera's world x and z and the light pillar (pillar; PILLAR_OFF: none), shown in full. */
    public static Values compute(PostProcessSettings s, Kind kind, double cameraX, double cameraY, double cameraZ, double pillarBase,
                                 double seconds) {
        return compute(s, kind, cameraX, cameraY, cameraZ, pillarBase, 1, seconds);
    }

    /** As above with the pillar's fade-in (PillarFade). */
    public static Values compute(PostProcessSettings s, Kind kind, double cameraX, double cameraY, double cameraZ, double pillarBase,
                                 float pillarFade, double seconds) {
        if (s == null || !s.enabled()) return off();
        float galaxy = kind == Kind.END ? Math.max(0, s.endGalaxy()) : 0;
        float heat = kind == Kind.HOT ? Math.max(0, s.netherHeatHaze()) : 0;
        double t = seconds - Math.floor(seconds / HeatMath.HEAT_TIME_WRAP) * HeatMath.HEAT_TIME_WRAP;
        int endSky = kind == Kind.END ? Math.max(END_SKY_VANILLA, Math.min(END_SKY_MAX, s.endSky())) : END_SKY_VANILLA;
        float px = Double.isFinite(cameraX) ? (float) (cameraX - PILLAR_X) : 0;
        float pz = Double.isFinite(cameraZ) ? (float) (cameraZ - PILLAR_Z) : 0;
        float base = kind == Kind.END && Double.isFinite(pillarBase) ? (float) pillarBase : PILLAR_OFF;
        float fade = Float.isFinite(pillarFade) ? Math.max(0, Math.min(1, pillarFade)) : 1;
        return new Values(galaxy, heat, Double.isFinite(cameraY) ? (float) cameraY : 0, Double.isFinite(t) ? (float) t : 0, endSky, px, pz, base,
                fade, 0, PORTAL_LOOK_VANILLA);
    }
}
