package net.vulkanmod.visuals.particle;

/** Test access to ParticleLook's package-private Anime Nether colours. */
public final class ParticleLookAccess {
    private ParticleLookAccess() {
    }

    public static int[] netherAnimeColours() {
        return ParticleLook.NETHER_ANIME.values().stream().mapToInt(Integer::intValue).toArray();
    }

    /** The Anime Nether's colour of a group (by its key: "flames", "smoke" ...), or -1 when it keeps the palette's. */
    public static int netherAnimeColour(String group) {
        for (var e : ParticleLook.NETHER_ANIME.entrySet()) {
            if (e.getKey().key().equals(group)) {
                return e.getValue();
            }
        }
        return -1;
    }
}
