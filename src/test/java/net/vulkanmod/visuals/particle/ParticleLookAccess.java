package net.vulkanmod.visuals.particle;

/** Test access to ParticleLook's package-private Anime Nether colours. */
public final class ParticleLookAccess {
    private ParticleLookAccess() {
    }

    public static int[] netherAnimeColours() {
        return ParticleLook.NETHER_ANIME.values().stream().mapToInt(Integer::intValue).toArray();
    }
}
