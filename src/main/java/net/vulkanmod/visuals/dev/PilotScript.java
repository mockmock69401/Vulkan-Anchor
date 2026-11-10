package net.vulkanmod.visuals.dev;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;

/** The pilot's script format without the game: line parsing, the known command names and config-key assignment. */
final class PilotScript {
    private PilotScript() {
    }

    /** The commands VisualsPilot runs; a script line with another name is logged and skipped. */
    private static final Set<String> COMMANDS = Set.of("world", "wait", "cmd", "ccmd", "rd", "fov", "zoom", "key", "hold", "set", "gui",
            "shot", "fps", "log", "vm", "spin", "burst", "glide", "gpu", "screen", "quit", "size", "f3", "chunks", "leave", "frames", "fgburst",
            "fsr", "window", "preset", "colourpreset");

    record Line(String command, String arg) {
    }

    /** Null for a blank line or a '#' comment. */
    static Line parse(String raw) {
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) return null;
        String[] w = line.split("\\s+", 2);
        return new Line(w[0], w.length > 1 ? w[1] : "");
    }

    /** The most presents one fgburst captures (host memory: the pacer's readback buffers are the burst's size, FrameBurst). */
    static final int MAX_FG_BURST = 32;

    record FgBurst(String name, int count) {
    }

    /** "NAME N" of the fgburst line; IllegalArgumentException for a missing or unsafe name, or a count outside 1..{@link #MAX_FG_BURST}. */
    static FgBurst parseFgBurst(String arg) {
        String[] w = arg.trim().split("\\s+");
        if (w.length != 2 || !w[0].matches("[A-Za-z0-9_.-]+") || w[0].startsWith(".")) throw new IllegalArgumentException("fgburst NAME N: " + arg);
        int n;
        try {
            n = Integer.parseInt(w[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("fgburst count: " + w[1], e);
        }
        if (n < 1 || n > MAX_FG_BURST) throw new IllegalArgumentException("fgburst count " + n + " is outside 1.." + MAX_FG_BURST);
        return new FgBurst(w[0], n);
    }

    /** FSR's debug drawings (FfxStructs.FG_DRAW_DEBUG_* flags) and whether its swapchain presents only generated frames. */
    record FsrDebug(int flags, boolean onlyPresentGenerated) {
    }

    /**
     * The words of an "fsr debug" line, combined: view (FSR's debug view in the generated frames), tear (tear lines), pacing (pacing
     * lines), generated (present only the generated frames); off alone clears everything. IllegalArgumentException for another word.
     */
    static FsrDebug parseFsrDebug(String words) {
        String[] w = words.trim().split("\\s+");
        if (w.length == 1 && w[0].equals("off")) return new FsrDebug(0, false);
        int flags = 0;
        boolean generated = false;
        for (String word : w) {
            switch (word) {
                case "view" -> flags |= net.vulkanmod.visuals.fsr.FfxStructs.FG_DRAW_DEBUG_VIEW;
                case "tear" -> flags |= net.vulkanmod.visuals.fsr.FfxStructs.FG_DRAW_DEBUG_TEAR_LINES;
                case "pacing" -> flags |= net.vulkanmod.visuals.fsr.FfxStructs.FG_DRAW_DEBUG_PACING_LINES;
                case "generated" -> generated = true;
                default -> throw new IllegalArgumentException("fsr debug view|tear|pacing|generated ... | off: " + words);
            }
        }
        return new FsrDebug(flags, generated);
    }

    /**
     * The "fsr status" line: FSR's support verdict, whether its swapchain is live, its present count and the last frame id FSR was
     * told, with what both did since the previous status line (prevPresents/prevFrame below 0: no previous line, or FSR's swapchain was
     * remade since, which restarts its count) over that many seconds.
     */
    static String fsrStatus(String support, boolean live, long presents, long frame, long prevPresents, long prevFrame, double seconds) {
        StringBuilder s = new StringBuilder("support ").append(support).append(", live ").append(live)
                .append(", presents ").append(presents).append(", frame ").append(frame);
        if (live && presents >= 0 && prevPresents >= 0 && presents >= prevPresents && prevFrame >= 0 && frame > prevFrame && seconds > 0) {
            long dp = presents - prevPresents, df = frame - prevFrame;
            s.append(String.format(java.util.Locale.ROOT, "; since the last status (%.1f s): %d presents in %d frames, %.2f presents per frame, %.1f fps, %.1f presents/s",
                    seconds, dp, df, (double) dp / df, df / seconds, dp / seconds));
        }
        return s.toString();
    }

    static boolean isKnown(String command) {
        return COMMANDS.contains(command);
    }

    /** Sets a public field of target by name (dots walk into nested objects), parsed by the field's type. */
    static void setField(Object target, String path, String value) throws ReflectiveOperationException {
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length - 1; i++) {
            target = target.getClass().getField(parts[i]).get(target);
        }
        Field f = target.getClass().getField(parts[parts.length - 1]);
        Class<?> t = f.getType();
        //2026-11-10 (session 8): "default" is the field's value in a fresh object of the target's class (a colour's default, say)
        if (value.equals("default") && !Modifier.isStatic(f.getModifiers())) {
            f.set(target, f.get(target.getClass().getConstructor().newInstance()));
            return;
        }
        //2026-11-10 (session 8): an int may be given as #RRGGBB (the shader colours)
        if (t == int.class) f.setInt(target, value.startsWith("#") ? Integer.parseInt(value.substring(1), 16) : Integer.parseInt(value));
        else if (t == float.class) f.setFloat(target, Float.parseFloat(value));
        else if (t == boolean.class) f.setBoolean(target, Boolean.parseBoolean(value));
        else if (t == double.class) f.setDouble(target, Double.parseDouble(value));
        else f.set(target, value);
    }
}
