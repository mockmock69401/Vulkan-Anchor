package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.vulkanmod.visuals.VisualsLog;

/**
 * 2026-10-26 (DeceasedCraft with DH: "There was an error generating a chunk" in chat once the player left the start area): DH's
 * FEATURES and SURFACE generators run world-gen features on a partial region, and Lost Cities' LostCityFeature reads neighbouring
 * chunks that region does not hold (NullPointerException, "No chunk exists", ArrayIndexOutOfBounds in Lost Cities; LOD holes). With
 * Lost Cities installed, DH's generator mode is held at INTERNAL_SERVER, which has the server generate each chunk, through the API:
 * an in-memory value DistantHorizons.toml never sees, so the player's file and a DH config reset do not matter.
 */
public final class DhWorldGenGuard {
    private static final String LOST_CITIES = "lostcities";
    /** 2026-10-30 (audit): how often the player's mode is looked at again (ticks). */
    static final int CHECK_TICKS = 20;
    private static boolean gaveUp, warned, holding;
    private static int ticks;

    private DhWorldGenGuard() {
    }

    /** Call only with DH loaded (this class names DH's API). */
    public static void install() {
        if (!ModList.get().isLoaded(LOST_CITIES)) return;
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
    }

    /** 2026-11-02: a dedicated server with DH generates its players' LODs itself, so its DH config is held the same way. */
    public static void installServer() {
        if (!ModList.get().isLoaded(LOST_CITIES)) return;
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> tick());
    }

    private static void tick() {
        if (!gaveUp && ticks++ % CHECK_TICKS == 0) apply();
    }

    /** Whether a mode runs features on a partial region (what Lost Cities cannot generate in). */
    static boolean partialRegion(EDhApiDistantGeneratorMode mode) {
        return mode == EDhApiDistantGeneratorMode.FEATURES || mode == EDhApiDistantGeneratorMode.SURFACE;
    }

    /**
     * 2026-10-30 (audit): held while the player's own mode (DH's true value: DistantHorizons.toml, DH's screen) runs features on a
     * partial region, and let go once the player picks one that does not (it was held for the session, over the player's choice).
     */
    private static void apply() {
        try {
            //DH's API is filled in after its own setup: until then, try again later
            if (DhApi.Delayed.configs == null) return;
            IDhApiConfigValue<EDhApiDistantGeneratorMode> mode = generatorMode();
            EDhApiDistantGeneratorMode chosen = mode.getTrueValue();
            boolean hold = partialRegion(chosen);
            if (hold == holding) return;
            holding = hold;
            if (hold) {
                boolean accepted = hold(mode);
                VisualsLog.info("Distant Horizons: Lost Cities is installed, so the distant generator mode is held at INTERNAL_SERVER in memory"
                        + " (DistantHorizons.toml says " + chosen + (accepted ? "" : "; not accepted by DH") + ")");
            } else {
                mode.clearValue();
                VisualsLog.info("Distant Horizons: the distant generator mode " + chosen + " is safe with Lost Cities; no longer held");
            }
        } catch (RuntimeException | LinkageError e) {
            //Also a DH whose API classes are renamed (DH 3.3.0 for NeoForge 1.21.1): give up after the first try
            gaveUp = true;
            if (!warned) {
                warned = true;
                VisualsLog.warn("Distant Horizons: could not set the distant generator mode (" + e + "); with Lost Cities, set "
                        + "chunkGeneratorMode = \"INTERNAL_SERVER\" in config/DistantHorizons.toml");
            }
        }
    }

    /** DH 3.3.3 calls it chunkGeneratorMode; earlier APIs only have distantGeneratorMode. */
    private static IDhApiConfigValue<EDhApiDistantGeneratorMode> generatorMode() {
        try {
            return DhApi.Delayed.configs.worldGenerator().chunkGeneratorMode();
        } catch (AbstractMethodError | NoSuchMethodError e) {
            return DhApi.Delayed.configs.worldGenerator().distantGeneratorMode();
        }
    }

    /** DH 3.3.3 names the API user; DH 3.3.0 to 3.3.2's config value takes the value alone (as DhVanillaFog.hold). */
    private static boolean hold(IDhApiConfigValue<EDhApiDistantGeneratorMode> v) {
        try {
            return v.setValue(EDhApiDistantGeneratorMode.INTERNAL_SERVER, DhRenderDefinition.NAME);
        } catch (IncompatibleClassChangeError e) {
            return v.setValue(EDhApiDistantGeneratorMode.INTERNAL_SERVER);
        }
    }
}
