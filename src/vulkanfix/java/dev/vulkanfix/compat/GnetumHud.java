package dev.vulkanfix.compat;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Gnetum caches the HUD in its own framebuffer: while it draws the HUD (Gnetum.rendering), it turns every bind of Minecraft's main
 * render target into a bind of that framebuffer (its RenderTargetMixin, at the head of RenderTarget._bindWrite). VulkanMod's
 * MainTarget overrides bindWrite and never reaches _bindWrite, so those binds went to the window instead; a mod that rebinds the
 * main target in the middle of the HUD (Xaero's Minimap 26.6.0 after its radar icons) sent the rest of the HUD past Gnetum's cache,
 * and the cached HUD and the direct one alternated (BMC4: hotbar and minimap flickering). Gnetum is called by reflection: this build
 * has no compile dependency on it. Gnetum 2.4.6 and 2.5.1 have the same field and methods.
 */
public final class GnetumHud {
    private static final MethodHandle RENDERING, INSTANCE, BIND;

    static {
        MethodHandle rendering = null, instance = null, bind = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> gnetum = Class.forName("me.decce.gnetum.Gnetum");
            Class<?> manager = Class.forName("me.decce.gnetum.FramebufferManager");
            rendering = lookup.findStaticGetter(gnetum, "rendering", boolean.class);
            instance = lookup.findStatic(manager, "getInstance", MethodType.methodType(manager));
            bind = lookup.findVirtual(manager, "bind", MethodType.methodType(void.class, boolean.class));
        } catch (ReflectiveOperationException | LinkageError absent) {
            rendering = instance = bind = null;
        }
        RENDERING = rendering;
        INSTANCE = instance;
        BIND = bind;
    }

    private GnetumHud() {
    }

    /** Whether Gnetum is drawing the HUD into its framebuffer. */
    public static boolean rendering() {
        if (RENDERING == null) return false;
        try {
            return (boolean) RENDERING.invokeExact();
        } catch (Throwable failure) {
            throw new IllegalStateException("Gnetum state read failed", failure);
        }
    }

    /** Whether Gnetum took the bind of {@code target} (it is the main render target and Gnetum is drawing the HUD). */
    public static boolean bindInstead(RenderTarget target) {
        if (RENDERING == null) return false;
        try {
            if (!rendering() || target != Minecraft.getInstance().getMainRenderTarget()) return false;
            Object manager = INSTANCE.invoke();
            if (manager == null) return false;
            BIND.invoke(manager, true);
            return true;
        } catch (Throwable failure) {
            throw new IllegalStateException("Gnetum HUD framebuffer bind failed", failure);
        }
    }
}
