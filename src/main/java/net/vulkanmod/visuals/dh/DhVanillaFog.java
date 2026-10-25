package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import net.vulkanmod.visuals.VisualsLog;

/**
 * 2026-10-21: DH moves vanilla's terrain fog out of the way while its rendering is on and its enableVanillaFog is off (the LODs carry
 * the distance instead). With the visuals' LOD setting off nothing is drawn past the render distance, and the vanilla terrain ended
 * against the sky with no fog. While the LODs are not drawn, enableVanillaFog's API value (never saved; DistantHorizons.toml keeps the
 * player's value) holds vanilla's fog on; once they are drawn again the API value is cleared and DH's own value applies.
 */
final class DhVanillaFog {
    /** null: not set by us yet; else the last state set (true: held on). */
    private Boolean held;
    private boolean warned;

    /** The API value to set: TRUE (vanilla fog on) while the LODs are not drawn, null (DH's own value) while they are. */
    static Boolean apiValue(boolean lodsDrawn) {
        return lodsDrawn ? null : Boolean.TRUE;
    }

    /** DH 3.3.3 names the API user; DH 3.3.0 to 3.3.2's config value takes the value alone. */
    private static boolean hold(IDhApiConfigValue<Boolean> v) {
        try {
            return v.setValue(Boolean.TRUE, DhRenderDefinition.NAME);
        } catch (IncompatibleClassChangeError e) {
            return v.setValue(Boolean.TRUE);
        }
    }

    /** Once a frame; changes DH's config only when the answer changes. */
    void update(boolean lodsDrawn) {
        boolean hold = apiValue(lodsDrawn) != null;
        if (this.held != null && this.held == hold) return;
        //DH's API is filled in after its own setup: until then, try again next frame
        if (DhApi.Delayed.configs == null) return;
        try {
            IDhApiConfigValue<Boolean> v = DhApi.Delayed.configs.graphics().fog().enableVanillaFog();
            boolean accepted = hold ? hold(v) : v.clearValue();
            this.held = hold;
            VisualsLog.info("Distant Horizons: vanilla fog " + (hold ? "held on in memory (LODs not drawn)" : "back to DistantHorizons.toml's value")
                    + (accepted ? "" : ", not accepted by DH") + "; enableVanillaFog reads " + v.getValue());
        } catch (RuntimeException | LinkageError e) {
            this.held = hold;
            if (!this.warned) {
                this.warned = true;
                VisualsLog.warn("Distant Horizons: could not set enableVanillaFog (" + e + "); with the LODs off, set enableVanillaFog = true in "
                        + "config/DistantHorizons.toml for vanilla's fog at the render distance");
            }
        }
    }
}
