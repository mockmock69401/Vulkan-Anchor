package net.vulkanmod.mixin.compatibility.continuity;

import net.minecraft.server.packs.resources.ResourceManager;
import net.vulkanmod.compat.WrappedResourceManagers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Continuity's ResourceRedirectHandler.get(ResourceManager) answers only for the game's own resource managers; behind a wrapper
 * (Blueprint's RemoldedResourceManager) it answered null and Continuity loaded none of a pack's OptiFine CTM tiles. When it answers
 * null, the managers held inside the wrapper are asked (WrappedResourceManagers).
 */
@Pseudo
@Mixin(targets = "me.pepperbell.continuity.client.resource.ResourceRedirectHandler", remap = false)
public class ContinuityRedirectHandlerM {
    @org.spongepowered.asm.mixin.Unique
    private static final ThreadLocal<Boolean> vulkanmod$walking = ThreadLocal.withInitial(() -> false);
    @org.spongepowered.asm.mixin.Unique
    private static MethodHandle vulkanmod$get;

    @Inject(method = "get", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void vulkanmod$throughWrappers(ResourceManager manager, CallbackInfoReturnable<Object> cir) {
        if (cir.getReturnValue() != null || manager == null || vulkanmod$walking.get()) return;
        vulkanmod$walking.set(true);
        try {
            MethodHandle get = vulkanmod$get;
            if (get == null) {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                Class<?> self = lookup.lookupClass();
                get = lookup.findStatic(self, "get", MethodType.methodType(self, ResourceManager.class))
                        .asType(MethodType.methodType(Object.class, Object.class));
                vulkanmod$get = get;
            }
            MethodHandle h = get;
            Object found = WrappedResourceManagers.findThroughWrappers(manager, ResourceManager.class, inner -> {
                try {
                    return (Object) h.invokeExact(inner);
                } catch (Throwable t) {
                    return null;
                }
            });
            if (found != null) cir.setReturnValue(found);
        } catch (ReflectiveOperationException | RuntimeException e) {
            //Continuity changed shape: leave its answer as it was
        } finally {
            vulkanmod$walking.set(false);
        }
    }
}
