package dev.vulkanfix.compat;

import org.joml.Matrix4f;

import java.lang.reflect.Method;
import java.util.List;

/**
 * The emit matrices Pufferfish's Skills hangs on a RenderedBuffer: one per item icon of the skill tree. The mod is not
 * a compile dependency, so its accessor is reached by name.
 */
public final class PufferfishItemEmits {
    private static final Method GET_EMITS = findGetEmits();

    private PufferfishItemEmits() {
    }

    private static Method findGetEmits() {
        try {
            return Class.forName("net.puffish.skillsmod.access.BuiltBufferAccess").getMethod("getEmits");
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** The buffer's emit matrices, or null for an ordinary draw. */
    @SuppressWarnings("unchecked")
    public static List<Matrix4f> of(Object renderedBuffer) {
        if (GET_EMITS == null || !GET_EMITS.getDeclaringClass().isInstance(renderedBuffer)) {
            return null;
        }
        try {
            return (List<Matrix4f>) GET_EMITS.invoke(renderedBuffer);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read Pufferfish's Skills item emits", e);
        }
    }

    /** The model view one emit draws with, as Pufferfish's own inject composes it. */
    public static Matrix4f modelView(Matrix4f modelView, Matrix4f emit) {
        return new Matrix4f(modelView).mul(emit);
    }
}
