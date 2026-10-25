package mod.chloeprime.aaaparticles.client.installer;

/** Test fixture shaped like AAA Particles 2.2.3's NativePlatform: records what marked the platform unsupported. */
public final class NativePlatform {
    public static Throwable unsupportedBecause;

    public static void setCurrentPlatformUnsupported(Throwable reason) {
        unsupportedBecause = reason;
    }
}
