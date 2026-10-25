package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.api.DhApi;
import net.vulkanmod.visuals.VisualsLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the fork reads of DH that DH's API does not give: a terrain buffer container's position, its public final
 * {@code minCornerBlockPos} field (an int block position with getX/getY/getZ). The first failure switches DH drawing off
 * with one warning; it stays off until the game restarts, and DH's buffers are freed then (DhVertexBuffer.freeAll). A proposal to expose the
 * position is drafted for the DH team. Also DH's vanilla-graphics override, held off in memory (ruling R22); its failure
 * only warns.
 */
public final class DhInternals {
    static final String CORNER_FIELD = "minCornerBlockPos";
    static final String SECTION_FIELD = "pos";
    /** DH's terrain buffer container class, as the activation line names it. */
    static final String CONTAINER_CLASS = "LodBufferContainer";
    /** DH core's graphics config class and its vanilla-settings entry (ruling R22). */
    static final String GRAPHICS_CONFIG = "com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics";
    static final String VANILLA_OVERRIDE_FIELD = "overrideVanillaGraphicsSettings";
    /** Dev switch (-D...=true): the container's position reads as failed, to check the fail-off path in the game (Task 6). */
    static final String FORCE_REFLECT_FAIL = "vulkanmod.visuals.dh.forceReflectFail";

    private static volatile boolean ok = true;
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static final AtomicBoolean CONFIG_WARNED = new AtomicBoolean();
    /** The container and position classes and their accessors, looked up once (DH has one container class). */
    private static volatile Accessors cached;

    private record Accessors(Class<?> container, Field corner, Class<?> pos, Method x, Method y, Method z) {}

    private DhInternals() {
    }

    public static boolean ok() {
        return ok;
    }

    /** The container's minimum corner in blocks {x, y, z}, or null (DH drawing switched off) when it cannot be read. */
    public static long[] minCornerBlockPos(Object container) {
        if (!ok || container == null) return null;
        if (Boolean.getBoolean(FORCE_REFLECT_FAIL)) {
            disable("cannot read " + CORNER_FIELD + " (forced by -D" + FORCE_REFLECT_FAIL + "=true)");
            return null;
        }
        try {
            Accessors a = cached;
            Class<?> type = container.getClass();
            if (a == null || a.container() != type) a = new Accessors(type, type.getField(CORNER_FIELD), null, null, null, null);
            Object pos = a.corner().get(container);
            if (pos == null) {
                disable(CORNER_FIELD + " is null");
                return null;
            }
            if (a.pos() != pos.getClass()) {
                Class<?> p = pos.getClass();
                a = new Accessors(a.container(), a.corner(), p, p.getMethod("getX"), p.getMethod("getY"), p.getMethod("getZ"));
            }
            cached = a;
            return new long[]{((Number) a.x().invoke(pos)).longValue(), ((Number) a.y().invoke(pos)).longValue(),
                    ((Number) a.z().invoke(pos)).longValue()};
        } catch (ReflectiveOperationException | RuntimeException e) {
            disable("cannot read " + CORNER_FIELD + " of " + container.getClass().getName() + " (" + e + ")");
            return null;
        }
    }

    /** DH's section key of a container (detail level and position); never switches drawing off. */
    static Long sectionKey(Object container) {
        try {
            Object v = container.getClass().getField(SECTION_FIELD).get(container);
            return v instanceof Long l ? l : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The detail level of a container's section (DH's DhSectionPos keeps it in the low seven bits of the key), or -1 when the key
     * cannot be read; the culling frustum's answers are found by it (ruling R26a). Never switches drawing off.
     */
    static int detailLevel(Object container) {
        Long key = sectionKey(container);
        return key == null ? -1 : (int) (key & 0x7F);
    }

    /** holdOffVanillaGraphicsOverride on DH's own config. */
    static boolean holdOffVanillaGraphicsOverride() {
        return holdOffVanillaGraphicsOverride(GRAPHICS_CONFIG);
    }

    /**
     * Ruling R22: DH's overrideVanillaGraphicsSettings turns vanilla clouds off at DH's first frame (right for DH's GL renderer); under
     * the fork the visuals' far clouds and their cloud reflections on water need them. The entry's API value (ConfigEntry.setApiValue,
     * which get() answers while it is set and which DH never saves) holds it off in memory; the toml keeps the player's value. Not in
     * DH's public API, so a failure warns once and changes nothing else: DH keeps drawing, with DH's default.
     */
    static boolean holdOffVanillaGraphicsOverride(String configClass) {
        try {
            Class<?> holder = Class.forName(configClass, true, DhInternals.class.getClassLoader());
            Object entry = holder.getField(VANILLA_OVERRIDE_FIELD).get(null);
            if (entry == null) throw new IllegalStateException(VANILLA_OVERRIDE_FIELD + " is null");
            //DH 3.3.3 names the API user; DH 3.3.0 to 3.3.2 take the value alone
            try {
                entry.getClass().getMethod("setApiValue", Object.class, String.class).invoke(entry, Boolean.FALSE, DhRenderDefinition.NAME);
            } catch (NoSuchMethodException e) {
                entry.getClass().getMethod("setApiValue", Object.class).invoke(entry, Boolean.FALSE);
            }
            Object now = entry.getClass().getMethod("get").invoke(entry);
            if (!Boolean.FALSE.equals(now)) throw new IllegalStateException(VANILLA_OVERRIDE_FIELD + " still reads " + now);
            VisualsLog.info("Distant Horizons: " + VANILLA_OVERRIDE_FIELD + " held off in memory (vanilla clouds stay for the visuals' far clouds; "
                    + "DistantHorizons.toml is unchanged)");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (CONFIG_WARNED.compareAndSet(false, true)) {
                VisualsLog.warn("Distant Horizons: could not hold off " + VANILLA_OVERRIDE_FIELD + " (" + e + "); DH may turn vanilla clouds off, "
                        + "which removes the visuals' far clouds. Set " + VANILLA_OVERRIDE_FIELD + " = false in config/DistantHorizons.toml to keep them");
            }
            return false;
        }
    }

    /** Spec 4.5: the activation's line naming what is read of DH outside its API, with DH's version. */
    static String readsLine(String dhVersion) {
        return "Distant Horizons " + dhVersion + ": read outside DH's API: " + CONTAINER_CLASS + "." + CORNER_FIELD + " (a container's corner), "
                + CONTAINER_CLASS + "." + SECTION_FIELD + " (its section key, for the culling's detail level) and "
                + "Config.Client.Advanced.Graphics." + VANILLA_OVERRIDE_FIELD + " (held off in memory)";
    }

    /** readsLine for the running DH, logged once at activation. */
    static void logReads() {
        String version;
        try {
            version = DhApi.getModVersion();
        } catch (RuntimeException | LinkageError e) {
            version = "(version unknown)";
        }
        VisualsLog.info(readsLine(version));
    }

    /**
     * Switches DH drawing off; the first call warns. DH's buffers are freed (on the render thread) and no upload allocates again, so
     * DH's LOD memory is not held until the game restarts. The dhLods setting does not come here: switched off, it keeps them.
     */
    static void disable(String reason) {
        ok = false;
        if (WARNED.compareAndSet(false, true)) {
            VisualsLog.warn("Distant Horizons: " + reason + "; DH LODs are not drawn until the game restarts");
        }
        DhVertexBuffer.freeAll();
    }

    /** The same, with the failure's stack trace in the one warning. */
    static void disable(String reason, Throwable cause) {
        ok = false;
        if (WARNED.compareAndSet(false, true)) {
            VisualsLog.warn("Distant Horizons: " + reason + "; DH LODs are not drawn until the game restarts", cause);
        }
        DhVertexBuffer.freeAll();
    }

    static void resetForTest() {
        ok = true;
        WARNED.set(false);
        CONFIG_WARNED.set(false);
        cached = null;
    }
}
