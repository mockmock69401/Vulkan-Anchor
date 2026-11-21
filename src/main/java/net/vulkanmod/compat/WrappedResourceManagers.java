package net.vulkanmod.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 2026-11-21 (Patrix 32x in Sunlit: "ENABLE CONNECT TEXTURE" on stone and grass with Continuity installed): Continuity finds its
 * resource redirects (how its OptiFine-path CTM tiles reach the block atlas) only on the game's own resource managers. Blueprint
 * (Team Abnormals' library) hands the resource reload its RemoldedResourceManager, a wrapper around the real manager; Continuity then
 * finds no redirects, loads none of the pack's tiles (all its tiles become missingno) and draws every block with the base texture,
 * which Patrix makes a placeholder. This walks such wrappers down to the manager they hold (any instance field of the manager type)
 * and asks again there. Not tied to Blueprint: any wrapper that keeps its inner manager in a field is seen through.
 */
public final class WrappedResourceManagers {
    private static final int MAX_DEPTH = 6;

    /** Per class: its instance fields of an object type, superclasses included, opened for reading. */
    private static final ClassValue<Field[]> FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> type) {
            List<Field> out = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        out.add(f);
                    } catch (RuntimeException ignored) {
                        //a field we may not open is no wrapper we can see through
                    }
                }
            }
            return out.toArray(new Field[0]);
        }
    };

    private WrappedResourceManagers() {
    }

    /**
     * The first non-null answer of {@code lookup} for a manager held inside {@code manager}, walking fields of type
     * {@code managerType} depth first, or null. {@code manager} itself is not asked (the caller already did).
     */
    public static Object findThroughWrappers(Object manager, Class<?> managerType, Function<Object, Object> lookup) {
        return find(manager, managerType, lookup, 0, new ArrayList<>());
    }

    private static Object find(Object manager, Class<?> managerType, Function<Object, Object> lookup, int depth, List<Object> seen) {
        if (manager == null || depth >= MAX_DEPTH) return null;
        for (Object o : seen) {
            if (o == manager) return null;
        }
        seen.add(manager);
        for (Field f : FIELDS.get(manager.getClass())) {
            if (!managerType.isAssignableFrom(f.getType())) continue;
            Object inner;
            try {
                inner = f.get(manager);
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue;
            }
            if (inner == null || inner == manager) continue;
            Object found = lookup.apply(inner);
            if (found == null) found = find(inner, managerType, lookup, depth + 1, seen);
            if (found != null) return found;
        }
        return null;
    }
}
