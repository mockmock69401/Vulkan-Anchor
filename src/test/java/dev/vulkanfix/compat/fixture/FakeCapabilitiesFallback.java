package dev.vulkanfix.compat.fixture;

/** Stands in for net.vulkanmod.compat.opengl.GlCapabilitiesFallback (public static void install()). */
public final class FakeCapabilitiesFallback {
    public static int installs;

    private FakeCapabilitiesFallback() {
    }

    public static void install() {
        installs++;
    }
}
