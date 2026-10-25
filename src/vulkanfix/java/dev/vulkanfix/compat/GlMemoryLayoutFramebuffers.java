package dev.vulkanfix.compat;

import java.util.HashSet;
import java.util.Set;

/**
 * GL framebuffer names whose contents must keep GL's memory layout (row 0 = bottom), because the mod samples them
 * with the same UVs as textures it uploads from the CPU. Render thread only; Reforged never reuses framebuffer names.
 */
public final class GlMemoryLayoutFramebuffers {
    private static final Set<Integer> IDS = new HashSet<>();
    private static int scopes;

    private GlMemoryLayoutFramebuffers() {
    }

    public static void register(int framebufferId) {
        if (framebufferId > 0) {
            IDS.add(framebufferId);
        }
    }

    /**
     * Every framebuffer, the main target included, keeps GL's memory layout until the matching exitScope (VIX's post effects, which are
     * all GL passes: each one then copies its input's rows as they are).
     */
    public static void enterScope() {
        scopes++;
    }

    public static boolean inScope() {
        return scopes > 0;
    }

    public static void exitScope() {
        scopes = Math.max(0, scopes - 1);
    }

    public static boolean uses(int framebufferId) {
        //In a scope the main target (Reforged's id 0) too: VIX's last pass copies into it
        return scopes > 0 ? framebufferId >= 0 : framebufferId > 0 && IDS.contains(framebufferId);
    }

    static void resetForTest() {
        IDS.clear();
        scopes = 0;
    }
}
