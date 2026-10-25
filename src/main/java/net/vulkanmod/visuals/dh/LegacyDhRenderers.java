package net.vulkanmod.visuals.dh;

import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IDhGenericObjectVertexBufferContainer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhAntiAliasRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhFarFadeRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhFogRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhGenericRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhMetaRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhSsaoRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhTestTriangleRenderer;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.renderPass.IDhVanillaFadeRenderer;

import java.lang.reflect.Proxy;

/**
 * DH 3.3.0 to 3.3.4's passes besides the terrain, as the legacydh mixins hand them out in place of DH's GL ones: none draws, as
 * with DhRenderDefinition (the visuals frame does the fog, the fades and the anti-aliasing). The generic renderer (beacons, clouds,
 * API boxes) and its buffer containers are dynamic proxies, so a method a later 3.3.x adds to them answers a default instead of
 * failing: they hold no data and draw nothing.
 */
public final class LegacyDhRenderers {
    public static final IDhMetaRenderer META = new IDhMetaRenderer() {
        @Override
        public void runRenderPassSetup(com.seibel.distanthorizons.core.render.RenderParams renderParams) {
        }

        @Override
        public void runRenderPassCleanup(com.seibel.distanthorizons.core.render.RenderParams renderParams) {
        }

        @Override
        public void copyToMcTexture(com.seibel.distanthorizons.core.render.RenderParams renderParams) {
        }

        @Override
        public void clearDhDepthAndColorTextures(com.seibel.distanthorizons.core.render.RenderParams renderParams) {
        }
    };
    public static final IDhSsaoRenderer SSAO = p -> { };
    public static final IDhFogRenderer FOG = (p, f) -> { };
    public static final IDhFarFadeRenderer FAR_FADE = p -> { };
    public static final IDhAntiAliasRenderer ANTI_ALIAS = p -> { };
    public static final IDhVanillaFadeRenderer VANILLA_FADE = p -> { };
    public static final IDhTestTriangleRenderer TEST_TRIANGLE = p -> { };

    private LegacyDhRenderers() {
    }

    public static IDhGenericRenderer genericRenderer() {
        return noop(IDhGenericRenderer.class);
    }

    public static IDhGenericObjectVertexBufferContainer genericVboContainer() {
        return noop(IDhGenericObjectVertexBufferContainer.class);
    }

    /**
     * An instance of type whose methods do nothing and answer a default: false, zero, an empty string, the last value a setX(v)
     * stored for getX (the generic containers' state), else an enum's first constant, else null.
     */
    static <T> T noop(Class<T> type) {
        java.util.Map<String, Object> stored = new java.util.concurrent.ConcurrentHashMap<>();
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            String name = method.getName();
            Class<?> r = method.getReturnType();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    default -> "VulkanMod " + type.getSimpleName();
                };
            }
            if (name.startsWith("set") && args != null && args.length == 1 && r == void.class) {
                if (args[0] != null) stored.put(name.substring(3), args[0]);
                return null;
            }
            if (name.startsWith("get") && stored.containsKey(name.substring(3))) return stored.get(name.substring(3));
            return defaultValue(r);
        });
        return type.cast(proxy);
    }

    static Object defaultValue(Class<?> r) {
        if (r == void.class) return null;
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == float.class) return 0f;
        if (r == double.class) return 0d;
        if (r == short.class) return (short) 0;
        if (r == byte.class) return (byte) 0;
        if (r == char.class) return (char) 0;
        if (r == String.class) return "";
        if (r.isEnum()) return r.getEnumConstants()[0];
        return null;
    }
}
