package net.puffish.skillsmod.access;

import org.joml.Matrix4f;

import java.util.List;

/** Test stand-in for the interface Pufferfish's Skills mixes into BufferBuilder.RenderedBuffer. */
public interface BuiltBufferAccess {
    void setEmits(List<Matrix4f> emits);

    List<Matrix4f> getEmits();
}
