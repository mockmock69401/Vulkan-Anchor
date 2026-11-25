package net.vulkanmod.render.chunk.build;

/**
 * 2026-11-25 (Conquest the Kingdom's Throne: warped wart blocks drew wrong): a model quad may carry its own light in its vertex data, as
 * a model element's "forge_data": {"block_light": 12} bakes it (Jaden's Nether Expansion's glowing spots on the warped and nether wart
 * blocks). Forge's block renderer draws such a vertex with the brighter of the baked and the world light, block and sky apart; ours
 * took the world light only, so the glow layer stayed as dark as the block. Packed light: block in the low 16 bits, sky in the high.
 */
final class BakedLight {
    private BakedLight() {
    }

    /** The vertex light: per channel (block, sky) the brighter of the world's light and the quad's baked light (0 = none). */
    static int merge(int light, int baked) {
        if (baked == 0) return light;
        int block = Math.max(light & 0xFFFF, baked & 0xFFFF);
        int sky = Math.max(light >>> 16, baked >>> 16);
        return block | sky << 16;
    }
}
