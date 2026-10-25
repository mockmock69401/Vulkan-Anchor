package net.vulkanmod.render.texture;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.Arrays;
import java.util.List;

/**
 * Which animated textures were drawn lately, so that only those tick (interpolate and upload their next frame). The client tick
 * otherwise interpolates and uploads every animated sprite of every atlas, seen or not; in CTE2 that was most of the worst
 * client-tick spikes (2026-10-24, the tree-walk stutter).
 *
 * <p>A frame counter advances once per rendered frame (Renderer.beginFrame). Drawing a sprite marks it with the current frame:
 * terrain through the animated sprites each visible section recorded at build time, items and block models through their quads,
 * and everything else through the sprite's UV getters. A sprite ticks when it was marked in this frame or the one before it, so the
 * ticks before a frame count the last frame's draws and the ticks a slow frame catches up all run. A sprite that comes into view
 * shows its frame from when it stopped until the next tick.
 *
 * <p>The setting (Config.animateOnlyVisibleTextures) switches it off: every sprite then ticks, as vanilla does.
 */
public final class SpriteActivity {
    /** The frame a never-drawn sprite holds. */
    public static final long NEVER = Long.MIN_VALUE / 2;
    public static final Holder[] NONE = new Holder[0];

    /** A SpriteContents' activity record (added by the SpriteContents mixin). */
    public interface Holder {
        void vulkanmod$setAnimated();

        boolean vulkanmod$isAnimated();

        long vulkanmod$activeFrame();

        void vulkanmod$markFrame(long frame);
    }

    /** A TextureAtlasSprite's link to its animated contents' record, null for a still sprite (added by the TextureAtlasSprite mixin). */
    public interface AtlasSprite {
        Holder vulkanmod$animated();
    }

    //written by the render thread, read by chunk builders through the UV getters
    private static volatile long frame;
    private static volatile boolean enabled = true;

    private SpriteActivity() {
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static long frame() {
        return frame;
    }

    /** Render thread, once per rendered frame before anything is drawn. */
    public static void nextFrame() {
        frame++;
    }

    public static boolean active(long markedFrame, long currentFrame) {
        return markedFrame >= currentFrame - 1;
    }

    public static boolean shouldTick(Holder sprite) {
        return !enabled || active(sprite.vulkanmod$activeFrame(), frame);
    }

    public static void mark(Holder sprite) {
        sprite.vulkanmod$markFrame(frame);
    }

    public static void markAll(Holder[] sprites) {
        long f = frame;
        for (Holder s : sprites) s.vulkanmod$markFrame(f);
    }

    /** Marks the animated sprites of a quad list (items, block models drawn outside the terrain). */
    public static void markQuads(List<BakedQuad> quads) {
        for (int i = 0, n = quads.size(); i < n; i++) markSprite(quads.get(i).getSprite());
    }

    public static void markSprite(TextureAtlasSprite sprite) {
        Holder h = animatedOf(sprite);
        if (h != null) h.vulkanmod$markFrame(frame);
    }

    /** The animated contents of a sprite, or null for a still sprite (or none). */
    public static Holder animatedOf(TextureAtlasSprite sprite) {
        return sprite == null ? null : ((AtlasSprite) sprite).vulkanmod$animated();
    }

    static void resetForTest() {
        frame = 0;
        enabled = true;
    }

    /** The animated sprites one section build meets, each once (a builder thread's own). */
    public static final class Collector {
        private Holder[] sprites = new Holder[8];
        private int count;

        public void add(Holder sprite) {
            if (!sprite.vulkanmod$isAnimated()) return;
            for (int i = 0; i < this.count; i++) {
                if (this.sprites[i] == sprite) return;
            }
            if (this.count == this.sprites.length) this.sprites = Arrays.copyOf(this.sprites, this.count * 2);
            this.sprites[this.count++] = sprite;
        }

        public void clear() {
            Arrays.fill(this.sprites, 0, this.count, null);
            this.count = 0;
        }

        public Holder[] toArray() {
            return this.count == 0 ? NONE : Arrays.copyOf(this.sprites, this.count);
        }
    }
}
