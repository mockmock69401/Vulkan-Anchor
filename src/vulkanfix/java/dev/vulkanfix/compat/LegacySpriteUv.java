package dev.vulkanfix.compat;

/** 1.20.1's TextureAtlasSprite.getU/getV take 0..16; code ported from 1.20.5+ passes 0..1. */
public final class LegacySpriteUv {
    private LegacySpriteUv() {
    }

    public static double toLegacy(double unitUv) {
        return unitUv * 16.0;
    }
}
