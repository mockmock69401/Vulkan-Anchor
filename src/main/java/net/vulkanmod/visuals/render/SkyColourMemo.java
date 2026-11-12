package net.vulkanmod.visuals.render;

/**
 * 2026-11-12 (session 14, second audit): ClientLevel.getSkyColor samples 216 biomes (CubicSampler, a Vec3 per sample) and runs three
 * times a frame with the same arguments: FogRenderer.setupColor, LevelRenderer.renderSky and the atmosphere's capture. Within one game
 * tick its result depends only on the camera position, the partial tick and the level's time, weather and lightning flash (all set by
 * the tick), so the last result is kept with those inputs and given back while they are all equal. The render thread only.
 */
public final class SkyColourMemo {
    private Object level;
    private long gameTime = Long.MIN_VALUE, dayTime;
    private double x, y, z;
    private float partialTick;
    private int flash;
    private Object value;

    /** The kept result for these inputs, or null. */
    public Object get(Object level, long gameTime, long dayTime, int flash, double x, double y, double z, float partialTick) {
        if (this.value == null || level != this.level || gameTime != this.gameTime || dayTime != this.dayTime || flash != this.flash
                || Double.compare(x, this.x) != 0 || Double.compare(y, this.y) != 0 || Double.compare(z, this.z) != 0
                || Float.compare(partialTick, this.partialTick) != 0) return null;
        return this.value;
    }

    public void put(Object level, long gameTime, long dayTime, int flash, double x, double y, double z, float partialTick, Object value) {
        this.level = level;
        this.gameTime = gameTime;
        this.dayTime = dayTime;
        this.flash = flash;
        this.x = x;
        this.y = y;
        this.z = z;
        this.partialTick = partialTick;
        this.value = value;
    }

    /** A level unloads: nothing kept refers to it. */
    public void clear() {
        this.level = null;
        this.value = null;
    }
}
