package net.vulkanmod.render.model.quad;

import net.minecraft.core.Direction;

public interface QuadView {

    int getFlags();

    float getX(int idx);

    float getY(int idx);

    float getZ(int idx);

    int getColor(int idx);

    float getU(int idx);

    float getV(int idx);

    /** The vertex's baked light (a model element's "forge_data" block/sky light), packed as the lightmap; 0 when the quad has none. */
    default int getLight(int idx) {
        return 0;
    }

    int getColorIndex();

    Direction getFacingDirection();

    default boolean isTinted() {
        return this.getColorIndex() != -1;
    }


}
