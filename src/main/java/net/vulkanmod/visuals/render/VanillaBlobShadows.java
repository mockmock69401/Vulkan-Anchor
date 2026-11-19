package net.vulkanmod.visuals.render;

/**
 * 2026-11-19 (the user, Tensura: "플레이어를 중심으로 원형 그림자가 있으며 사라지지 않음", a round shadow around the player that never went
 * away, besides the sun's shadows): vanilla's entity shadow, the grey disc it draws on the blocks under every mob and player when its
 * Entity Shadows option is on, stayed under the sun and moon shadow maps, darkening the ground the maps already lit or shaded (seen in
 * third person, which Tensura's Shoulder Surfing makes the usual view). In a frame whose maps are drawn (ShadowFrame: the sun or moon
 * up, no rain, out in the open, the setting on) the disc is left out (EntityRenderDispatcherShadowMixin); the entities then cast by
 * the entity shadow map when that setting is on. Elsewhere (caves, rain, a new moon, shadows off) vanilla's disc stays as it was.
 */
public final class VanillaBlobShadows {
    private static long hiddenSerial = Long.MIN_VALUE;

    private VanillaBlobShadows() {
    }

    /** frame(), before the entities: whether this frame (serial) draws the sun or moon shadow maps. */
    public static void frame(long serial, boolean shadowMaps) {
        hiddenSerial = shadowMaps ? serial : Long.MIN_VALUE;
    }

    /** Whether vanilla's disc is left out in the frame being recorded (serial). */
    public static boolean hidden(long serial) {
        return serial == hiddenSerial;
    }
}
