package net.fabricmc.fabric.api.renderer.v1.model;

/**
 * Test-only stand-in with the name and the one method FrapiModels looks for (the real interface belongs to the Fabric API; nothing
 * here is taken from it).
 */
public interface FabricBakedModel {
    default boolean isVanillaAdapter() {
        return true;
    }
}
