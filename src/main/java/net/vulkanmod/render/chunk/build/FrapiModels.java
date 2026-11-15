package net.vulkanmod.render.chunk.build;

import net.minecraft.client.resources.model.BakedModel;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

/**
 * 2026-11-15 (session 23, Conquest the Kingdom's Throne: grass drawn as solid dark quads): models of the Fabric Rendering API (FRAPI,
 * here through Forgified Fabric API) that are no plain vanilla model draw their quads through a renderer's own emitter, not through
 * getQuads. Continuity's connected and random textures are such models: Conquest Reforged's grass block carries grass tufts whose model
 * texture is an opaque placeholder that Continuity swaps at render time for the transparent tufts. Our chunk builder took getQuads and
 * drew the placeholder. Such a model now goes through vanilla's block renderer (BlockRenderer.renderFrapi), where the FRAPI renderer
 * draws it. Found by the interface's name, so nothing here needs Fabric's classes; without them no model takes this way.
 */
final class FrapiModels {
    private static final String FABRIC_BAKED_MODEL = "net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel";

    /** Per model class: its isVanillaAdapter (as (Object)boolean), or null for a class that is no FabricBakedModel. */
    private static final ClassValue<MethodHandle> VANILLA_ADAPTER = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            Class<?> api = find(type);
            if (api == null) return null;
            try {
                Method m = api.getMethod("isVanillaAdapter");
                return MethodHandles.publicLookup().unreflect(m).asType(MethodType.methodType(boolean.class, Object.class));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    };

    private FrapiModels() {
    }

    private static Class<?> find(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                Class<?> hit = i.getName().equals(FABRIC_BAKED_MODEL) ? i : find(i);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** Whether the model draws through a FRAPI renderer (a FabricBakedModel that is no vanilla adapter). */
    static boolean needsRenderer(BakedModel model) {
        return needsRenderer((Object) model);
    }

    /** As needsRenderer(BakedModel), for any object (the tests: no Minecraft classes loaded). */
    static boolean needsRenderer(Object model) {
        MethodHandle h = VANILLA_ADAPTER.get(model.getClass());
        if (h == null) return false;
        try {
            return !(boolean) h.invokeExact((Object) model);
        } catch (Throwable t) {
            return false;
        }
    }
}
