package dev.vulkanfix.compat;

import java.util.Map;
import java.util.function.Predicate;

/** Decides which vulkanfix mixins apply, based only on which mods are loaded. */
public final class CompatGate {
    public static final String VULKANMOD = "vulkanmod";

    /** Mixin sub-package prefix -> modId that must be loaded for it to apply. */
    private static final Map<String, String> REQUIRED_MOD_BY_PREFIX = Map.of(
            "vivecraft.", "vivecraft",
            "immediatelyfast.", "immediatelyfast",
            "iceberg.", "iceberg",
            "xaero.minimap.", "xaerominimap",
            "xaero.worldmap.", "xaeroworldmap",
            "aaaparticles.", "aaa_particles",
            "gnetum.", "gnetum",
            "fancymenu.", "fancymenu",
            "vix.", "vix",
            "puffish.", "puffish_skills"
    );

    private CompatGate() {
    }

    public static boolean shouldApply(String relativeMixinName, Predicate<String> isModLoaded) {
        if (!isModLoaded.test(VULKANMOD)) {
            return false;
        }
        for (Map.Entry<String, String> entry : REQUIRED_MOD_BY_PREFIX.entrySet()) {
            if (relativeMixinName.startsWith(entry.getKey())) {
                return isModLoaded.test(entry.getValue());
            }
        }
        return true;
    }
}
