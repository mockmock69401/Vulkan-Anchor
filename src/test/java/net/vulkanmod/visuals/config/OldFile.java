package net.vulkanmod.visuals.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** For the tests: turns a settings file written now into one an older revision saved. */
public final class OldFile {
    private static final Pattern PALETTE = Pattern.compile("\"colourPreset\": ([0-9]+)");

    private OldFile() {
    }

    /**
     * Rewrites file's revision to revision. Before revision 28 the palettes had other numbers (ColourPresets.fromOldId's inverse), so a
     * palette saved now is written under its old number.
     */
    public static void age(Path file, int revision) throws IOException {
        String s = Files.readString(file).replace("\"revision\": " + VisualsConfig.currentRevision(), "\"revision\": " + revision);
        if (revision < VisualsConfig.THREE_PALETTES_REVISION) {
            Matcher m = PALETTE.matcher(s);
            s = m.replaceAll(r -> "\"colourPreset\": " + oldId(Integer.parseInt(r.group(1))));
        }
        Files.writeString(file, s);
    }

    /** A palette's number before revision 28. */
    public static int oldId(int palette) {
        return switch (palette) {
            case ColourPresets.DEFAULT -> ColourPresets.DEFAULT;
            case ColourPresets.ANIME -> ColourPresets.OLD_ANIME;
            case ColourPresets.HORROR -> ColourPresets.OLD_HORROR;
            default -> ColourPresets.OLD_CUSTOM;
        };
    }
}
