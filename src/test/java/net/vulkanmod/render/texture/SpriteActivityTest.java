package net.vulkanmod.render.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Animated textures tick only while something drew them (2026-10-24, the tree-walk stutter: the client tick spent most of its worst
 * spikes interpolating and uploading animated textures nobody saw).
 */
class SpriteActivityTest {
    /** A sprite's activity record, as the SpriteContents mixin keeps it. */
    private static final class Sprite implements SpriteActivity.Holder {
        boolean animated;
        long frame = SpriteActivity.NEVER;

        @Override public void vulkanmod$setAnimated() { this.animated = true; }
        @Override public boolean vulkanmod$isAnimated() { return this.animated; }
        @Override public long vulkanmod$activeFrame() { return this.frame; }
        @Override public void vulkanmod$markFrame(long frame) { this.frame = frame; }
    }

    private static final class CountingTicker implements TextureAtlasSprite.Ticker {
        int ticks, closes;
        @Override public void tickAndUpload() { this.ticks++; }
        @Override public void close() { this.closes++; }
    }

    @AfterEach
    void reset() {
        SpriteActivity.resetForTest();
    }

    @Test void markedThisFrameOrTheLastIsActive() {
        assertTrue(SpriteActivity.active(10, 10));
        assertTrue(SpriteActivity.active(9, 10));
        assertFalse(SpriteActivity.active(8, 10));
        assertFalse(SpriteActivity.active(SpriteActivity.NEVER, 10));
    }

    @Test void anUnseenSpriteDoesNotTick() {
        Sprite s = new Sprite();
        CountingTicker inner = new CountingTicker();
        TextureAtlasSprite.Ticker t = new ActiveSpriteTicker(inner, s);
        SpriteActivity.nextFrame();
        t.tickAndUpload();
        assertEquals(0, inner.ticks);
    }

    @Test void aSpriteDrawnSinceTheLastFrameTicks() {
        Sprite s = new Sprite();
        s.animated = true;
        CountingTicker inner = new CountingTicker();
        TextureAtlasSprite.Ticker t = new ActiveSpriteTicker(inner, s);
        SpriteActivity.nextFrame();
        SpriteActivity.mark(s);//drawn in this frame
        t.tickAndUpload();
        SpriteActivity.nextFrame();
        t.tickAndUpload();//the next frame's ticks still count the last frame's draw
        SpriteActivity.nextFrame();
        t.tickAndUpload();//two frames without a draw: stops
        assertEquals(2, inner.ticks);
    }

    @Test void caughtUpTicksInOneFrameAllTick() {
        Sprite s = new Sprite();
        s.animated = true;
        CountingTicker inner = new CountingTicker();
        TextureAtlasSprite.Ticker t = new ActiveSpriteTicker(inner, s);
        SpriteActivity.nextFrame();
        SpriteActivity.mark(s);
        for (int i = 0; i < 4; i++) t.tickAndUpload();//a slow frame: the client runs several ticks before the next frame
        assertEquals(4, inner.ticks);
    }

    @Test void switchedOffEverySpriteTicks() {
        SpriteActivity.setEnabled(false);
        Sprite s = new Sprite();
        CountingTicker inner = new CountingTicker();
        TextureAtlasSprite.Ticker t = new ActiveSpriteTicker(inner, s);
        SpriteActivity.nextFrame();
        t.tickAndUpload();
        assertEquals(1, inner.ticks);
    }

    @Test void closeAlwaysReachesTheTicker() {
        CountingTicker inner = new CountingTicker();
        new ActiveSpriteTicker(inner, new Sprite()).close();
        assertEquals(1, inner.closes);
    }

    @Test void theSectionCollectorKeepsEachAnimatedSpriteOnce() {
        Sprite a = new Sprite(), b = new Sprite(), still = new Sprite();
        a.animated = true;
        b.animated = true;
        SpriteActivity.Collector c = new SpriteActivity.Collector();
        c.add(a);
        c.add(still);
        c.add(a);
        c.add(b);
        assertEquals(2, c.toArray().length);
        c.clear();
        assertEquals(0, c.toArray().length);
        assertTrue(c.toArray() == SpriteActivity.NONE, "an empty section shares the empty array");
    }

    @Test void markingAllMarksEach() {
        Sprite a = new Sprite(), b = new Sprite();
        SpriteActivity.nextFrame();
        SpriteActivity.markAll(new SpriteActivity.Holder[]{a, b});
        assertTrue(SpriteActivity.active(a.frame, SpriteActivity.frame()));
        assertTrue(SpriteActivity.active(b.frame, SpriteActivity.frame()));
    }

    /** 2026-10-30 (audit): a sprite tesselated into a mesh drawn for many frames (a contraption) keeps ticking unseen. */
    @Test void aTesselatedSpriteKeepsTicking() {
        Sprite kept = new Sprite(), seen = new Sprite();
        SpriteActivity.keep(kept);
        SpriteActivity.mark(seen);
        for (int i = 0; i < 5; i++) SpriteActivity.nextFrame();
        assertTrue(SpriteActivity.shouldTick(kept));
        assertFalse(SpriteActivity.shouldTick(seen));
    }
}
