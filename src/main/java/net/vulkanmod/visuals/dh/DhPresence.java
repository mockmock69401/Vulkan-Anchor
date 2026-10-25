package net.vulkanmod.visuals.dh;

import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;

import java.nio.file.Files;

/**
 * Whether Distant Horizons is installed, and how the fork draws its LODs: through DH's render API (DH 3.3.5 and later), through
 * DH's internal render definition (DH 3.3.0 to 3.3.4, the legacydh mixins), or not at all (older DH). Decided from the mod file
 * alone, before any DH class loads, so this class and everything that calls it stay loadable when DH is missing: it names no DH
 * type.
 */
public final class DhPresence {
    public enum State { ABSENT, NO_RENDER_API, LEGACY_RENDER_DEF, RENDER_API }

    private static final String MOD_ID = "distanthorizons";
    private static final String[] RENDER_API_CLASS = {
            "com", "seibel", "distanthorizons", "api", "interfaces", "render", "renderDef", "AbstractDhApiRenderDefinition.class"
    };
    /** DH 3.3.0 to 3.3.4's internal render definition, gone from DH once the render API replaced it. */
    private static final String[] LEGACY_RENDER_DEF_CLASS = {
            "com", "seibel", "distanthorizons", "core", "wrapperInterfaces", "render", "AbstractDhRenderApiDefinition.class"
    };
    /** DH 3.3.0 and 3.3.1's depth direction enum; DH 3.3.2 moved it to the API (EDhApiDepthDirection). */
    private static final String[] LEGACY_RENDER_DEPTH_CLASS = {
            "com", "seibel", "distanthorizons", "core", "render", "EDhRenderDepth.class"
    };

    private static volatile State cached;
    private static volatile boolean legacyRenderDepth;
    private static volatile boolean active;

    private DhPresence() {
    }

    public static State classify(boolean modLoaded, boolean renderApiClassPresent) {
        return classify(modLoaded, renderApiClassPresent, false);
    }

    public static State classify(boolean modLoaded, boolean renderApiClassPresent, boolean legacyRenderDefPresent) {
        if (!modLoaded) return State.ABSENT;
        if (renderApiClassPresent) return State.RENDER_API;
        return legacyRenderDefPresent ? State.LEGACY_RENDER_DEF : State.NO_RENDER_API;
    }

    /** Whether the fork draws DH's LODs with this DH. */
    public static boolean drawsLods(State state) {
        return state == State.RENDER_API || state == State.LEGACY_RENDER_DEF;
    }

    /** drawsLods for the running game. */
    public static boolean drawsLods() {
        return drawsLods(current());
    }

    /** With the legacy render definition: whether DH names its depth direction getRenderDepth (DH 3.3.0, 3.3.1). */
    public static boolean legacyRenderDepth() {
        current();
        return legacyRenderDepth;
    }

    /** Looks at the loading mod list; logs one line when DH is there. Only valid once FML has the mod list. */
    public static State detect() {
        LoadingModList list = LoadingModList.get();
        if (list == null) {
            Log.info("Distant Horizons: mod list not available yet, treating DH as absent");
            return State.ABSENT;
        }
        ModFileInfo file = list.getModFileById(MOD_ID);
        if (file == null) return classify(false, false);

        boolean renderApi = Files.exists(file.getFile().findResource(RENDER_API_CLASS));
        boolean legacy = Files.exists(file.getFile().findResource(LEGACY_RENDER_DEF_CLASS));
        legacyRenderDepth = Files.exists(file.getFile().findResource(LEGACY_RENDER_DEPTH_CLASS));
        State state = classify(true, renderApi, legacy);
        String version = file.getMods().isEmpty() ? "?" : String.valueOf(file.getMods().get(0).getVersion());
        switch (state) {
            case RENDER_API -> Log.info("Distant Horizons " + version + ": render API found, DH LODs are drawn by VulkanMod");
            case LEGACY_RENDER_DEF -> Log.info("Distant Horizons " + version + ": no render API, DH's render definition is taken over, "
                    + "DH LODs are drawn by VulkanMod");
            default -> Log.info("Distant Horizons " + version + ": render API missing (DH 3.3.0 or later needed), DH LODs are not drawn under VulkanMod");
        }
        return state;
    }

    /** {@link #detect()} once, cached. */
    public static State current() {
        State state = cached;
        if (state == null) {
            state = detect();
            cached = state;
        }
        return state;
    }

    /**
     * Set once a render definition is registered with DH. It stays set when DH drops the definition or the world
     * is left, and after drawing was switched off for a failure: the definition is still registered then and draws
     * nothing, so VulkanMod's own terrain pass must keep standing aside for DH's hook.
     */
    public static void markActive(boolean value) {
        active = value;
    }

    public static boolean isActive() {
        return active;
    }

    /** Kept apart so the logger class loads only when a line is written. */
    private static final class Log {
        static void info(String line) {
            org.apache.logging.log4j.LogManager.getLogger("VulkanMod").info(line);
        }
    }
}
