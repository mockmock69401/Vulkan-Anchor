package net.vulkanmod.render.chunk.build;

import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Session 23: which models go through vanilla's block renderer (a FRAPI renderer's own emitter) instead of getQuads. */
class FrapiModelsTest {
    /** A sub-interface, as a wrapper model's own type would be: FrapiModels finds the interface above it. */
    interface Wrapped extends FabricBakedModel {
    }

    /** Stands for BakedModel (a proxy of the real one would load Minecraft's block classes, which need the game's bootstrap). */
    interface Model {
    }

    private static Object model(Class<?>[] types, boolean vanillaAdapter) {
        return Proxy.newProxyInstance(FrapiModelsTest.class.getClassLoader(), types, (proxy, method, args) -> {
            if (method.getName().equals("isVanillaAdapter")) return vanillaAdapter;
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return null;
        });
    }

    @Test
    void onlyAFabricModelThatIsNoVanillaAdapterNeedsTheRenderer() {
        Class<?>[] fabric = {Model.class, FabricBakedModel.class};
        assertTrue(FrapiModels.needsRenderer(model(fabric, false)), "Continuity's kind: emits its own quads");
        assertFalse(FrapiModels.needsRenderer(model(fabric, true)), "a vanilla model seen through FRAPI keeps getQuads");
        assertFalse(FrapiModels.needsRenderer(model(new Class<?>[]{Model.class}, false)), "no FRAPI at all");
    }

    @Test
    void theInterfaceIsFoundAboveTheModelsOwnTypes() {
        assertTrue(FrapiModels.needsRenderer(model(new Class<?>[]{Model.class, Wrapped.class}, false)));
    }
}
