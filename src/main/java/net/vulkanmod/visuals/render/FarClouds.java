package net.vulkanmod.visuals.render;

/**
 * P7h design 3.5: whether the visuals drew the clouds in the last post-processed frame, so LevelRendererCloudsMixin skips vanilla's.
 * The render thread sets and reads it; the post-process sets it every frame and the render system clears it when it shuts down.
 */
public final class FarClouds {
    private static boolean replacing;

    private FarClouds() {
    }

    public static boolean replacesVanilla() {
        return replacing;
    }

    public static void set(boolean on) {
        replacing = on;
    }

    /** Whether type overrides owner's public method name(params) (P7h task 4 review); false when there is no such method. */
    public static boolean overrides(Class<?> type, Class<?> owner, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params).getDeclaringClass() != owner;
        } catch (NoSuchMethodException | SecurityException e) {
            return false;
        }
    }
}
