package net.vulkanmod.visuals.api;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** The registry of visuals extensions. Registration is on the render thread; a duplicate registration is ignored. */
public final class VisualsExtensions {
    private static final CopyOnWriteArrayList<VisualsExtension> EXTENSIONS = new CopyOnWriteArrayList<>();

    private VisualsExtensions() {
    }

    public static void register(VisualsExtension e) {
        EXTENSIONS.addIfAbsent(Objects.requireNonNull(e));
    }

    public static void unregister(VisualsExtension e) {
        EXTENSIONS.remove(e);
    }

    /** The extensions in registration order: an unmodifiable view; iterating it is a safe snapshot (copy-on-write). */
    public static List<VisualsExtension> all() {
        return Collections.unmodifiableList(EXTENSIONS);
    }
}
