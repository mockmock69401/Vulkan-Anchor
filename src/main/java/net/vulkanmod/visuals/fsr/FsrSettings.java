package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.config.VisualsOptionLayout;

import java.util.Locale;

/** FSR design 7: which frame generation the saved settings ask for, and the two switches' exclusion. */
public final class FsrSettings {
    public enum Active {NONE, OPTICAL_FLOW, FSR}

    private FsrSettings() {
    }

    /** FSR runs when it is saved on and supported (it wins over Optical Flow); otherwise Optical Flow when saved on. */
    public static Active active(boolean opticalFlowSaved, boolean fsrSaved, FsrSupport.Reason fsr) {
        if (fsrSaved && fsr == FsrSupport.Reason.OK) return Active.FSR;
        return opticalFlowSaved ? Active.OPTICAL_FLOW : Active.NONE;
    }

    /** Writes one of the two switches; turning it on turns the other off. Other keys are ignored. */
    public static void onToggle(VisualsConfig c, String key, boolean newValue) {
        if (key.equals(VisualsOptionLayout.FRAME_GENERATION)) {
            c.frameGeneration = newValue;
            if (newValue) c.fsrFrameGeneration = false;
        } else if (key.equals(VisualsOptionLayout.FSR_FRAME_GENERATION)) {
            c.fsrFrameGeneration = newValue;
            if (newValue) c.frameGeneration = false;
        }
    }

    /**
     * 2026-10-25 (the user's request): both generators cost real frames, so turning either one on sets the game's frame limit to this
     * once; the player may raise it afterwards (the block's note under the switches says so).
     */
    public static final int FRAME_LIMIT_ON = 60;

    /** Whether applying this switch from was to now turns a generator on, and so sets the frame limit to FRAME_LIMIT_ON. */
    public static boolean limitsFrameRate(String key, boolean was, boolean now) {
        return now && !was && (key.equals(VisualsOptionLayout.FRAME_GENERATION) || key.equals(VisualsOptionLayout.FSR_FRAME_GENERATION));
    }

    /** The lang key of the text shown when the FSR switch is greyed out for reason. */
    public static String unavailableKey(FsrSupport.Reason reason) {
        return "vulkanmod.visuals." + VisualsOptionLayout.FSR_FRAME_GENERATION + ".unavailable." + reason.name().toLowerCase(Locale.ROOT);
    }
}
