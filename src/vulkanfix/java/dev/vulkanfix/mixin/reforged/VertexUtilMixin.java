package dev.vulkanfix.mixin.reforged;

import dev.vulkanfix.compat.NormalPacking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Reforged's VertexUtil.packNormal(float, float, float) packs (int)(n * 127) & 0xFF without vanilla's [-1, 1]
 * clamp. GUI items reach it with normals around 1e25, which saturate and wrap to -1/127, so flat items in the
 * hotbar and inventory were lit at the 0.4 ambient floor. Use the vanilla-equivalent clamped packing.
 */
@Mixin(targets = "net.vulkanmod.render.vertex.VertexUtil", remap = false)
public abstract class VertexUtilMixin {
    /**
     * @author vulkanfix
     * @reason clamp normal components like vanilla BufferBuilder.normalIntValue
     */
    @Overwrite(remap = false)
    public static int packNormal(float x, float y, float z) {
        return NormalPacking.pack(x, y, z);
    }
}
