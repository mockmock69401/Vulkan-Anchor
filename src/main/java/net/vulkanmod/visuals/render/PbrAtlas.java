package net.vulkanmod.visuals.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.lwjgl.system.MemoryUtil;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UINT;

/**
 * 2026-11-10 (shader roadmap session 9, PBR): the resource packs' LabPBR normal (_n) and specular (_s) maps of the block atlas' sprites,
 * stitched into two more atlases laid out exactly as the block atlas (each sprite's map at its sprite's place, scaled to the sprite's
 * size; an animated map's first frame), with the block atlas' mip levels. The terrain's PBR build samples them at its own texture
 * coordinate (pbr_terrain.glsl). Where a sprite has no map the normal atlas is flat (128, 128, 255, 255) and the specular one 0: such
 * a texel is lit as before. A third, small texture maps each atlas cell to its sprite (the parallax keeps its march inside the sprite).
 * <p>
 * Built at a frame's start (outside its recording) while PBR is on and the visuals run, rebuilt after a resource reload changes the
 * atlas, freed when PBR goes off: with PBR off nothing is loaded or kept.
 */
public final class PbrAtlas {
    /** A sprite's place in the block atlas (pixels at level 0). */
    public record Sprite(ResourceLocation name, int x, int y, int width, int height) {
    }

    /** The flat normal texel (r, g 128: no tilt; b the AO 255: none; a the height 255: the top), as a little-endian RGBA int. */
    public static final int FLAT_NORMAL = 0xFFFF8080;
    /** The specular texel without a map: no smoothness, F0, porosity or emission. */
    public static final int NO_SPECULAR = 0;
    /** The parallax lookup's cell must be at least this many pixels and the lookup at most this many cells on a side. */
    static final int CELL_MIN = 4, CELLS_MAX = 1024;
    /** Rows per upload (bounded so a large atlas does not need a staging buffer of its whole size). */
    private static final int UPLOAD_BYTES = 4 << 20;

    private static List<Sprite> sprites = List.of();
    private static int atlasWidth, atlasHeight, levels = 1;
    private static long generation, builtGeneration = -1;
    private static VulkanImage normal, specular, spriteMap;
    private static int normalCount, specularCount;
    private static boolean parallaxMap;
    private static final MappedBuffer PARAMS = new MappedBuffer(4 * 4);

    private PbrAtlas() {
    }

    /** TextureAtlas.upload's end (MSpriteAtlasTexture): the block atlas' sprites, for the next build. */
    public static void onUpload(TextureAtlas atlas, SpriteLoader.Preparations data) {
        if (!TextureAtlas.LOCATION_BLOCKS.equals(atlas.location())) return;
        List<Sprite> list = new ArrayList<>();
        for (Map.Entry<ResourceLocation, TextureAtlasSprite> e : data.regions().entrySet()) {
            TextureAtlasSprite s = e.getValue();
            list.add(new Sprite(s.contents().name(), s.getX(), s.getY(), s.contents().width(), s.contents().height()));
        }
        sprites = List.copyOf(list);
        atlasWidth = data.width();
        atlasHeight = data.height();
        levels = data.mipLevel() + 1;
        generation++;
    }

    /** Whether PBR is wanted this frame: the setting, the visuals and their terrain light on. */
    public static boolean wanted() {
        VisualsConfig c = VisualsConfig.get();
        return c.enabled && c.pbr && c.perPixelLight && VisualsConfig.terrainLight;
    }

    /** At a frame's start, outside its recording (MinecraftMixin): builds, rebuilds or frees the atlases. */
    public static void update() {
        boolean want = wanted() && !sprites.isEmpty();
        if (!want) {
            if (normal != null) free();
            //turned on again later: built again
            builtGeneration = -1;
            return;
        }
        //2026-11-12 (shader roadmap session 14): built for this atlas, or found no LabPBR maps in it (or could not be built). Before, a
        // build that found no maps left normal null and ran again every frame: two atlas-sized buffers filled and every sprite's _n and
        // _s looked up, about 30 ms of CPU a frame with PBR on (the default) and no LabPBR pack (fps 640 -> 32 at 2560x1440)
        if (builtGeneration == generation) return;
        free();
        try {
            build();
        } catch (RuntimeException | OutOfMemoryError e) {
            VisualsLog.warn("Visuals PBR atlas could not be built, PBR stays off", e);
            free();
            builtGeneration = generation;
        }
    }

    /** Whether the atlases are built and hold at least one map (without any, PBR draws nothing different and stays off). */
    public static boolean active() {
        return normal != null && normalCount + specularCount > 0;
    }

    public static int normalMaps() {
        return normalCount;
    }

    public static int specularMaps() {
        return specularCount;
    }

    /** Binds the three textures to the terrain's PBR samplers (Sampler3, 4, 5: terrain_pbr.json). */
    public static void bind() {
        VTextureSelector.bindTexture(3, normal);
        VTextureSelector.bindTexture(4, specular);
        VTextureSelector.bindTexture(5, spriteMap);
    }

    /** The terrain's PbrParams: x the normal strength, y the parallax depth (0: off), z 1 when the parallax lookup holds the sprites. */
    public static MappedBuffer params() {
        VisualsConfig c = VisualsConfig.get();
        PARAMS.putFloat(0, PbrMath.normalStrength(c.pbrNormalStrength));
        PARAMS.putFloat(4, c.pbrParallax ? PbrMath.PARALLAX_DEPTH : 0f);
        PARAMS.putFloat(8, parallaxMap ? 1f : 0f);
        PARAMS.putFloat(12, 0f);
        return PARAMS;
    }

    private static void free() {
        for (VulkanImage i : new VulkanImage[]{normal, specular, spriteMap}) {
            if (i != null) i.free();
        }
        normal = specular = spriteMap = null;
        normalCount = specularCount = 0;
        parallaxMap = false;
    }

    private static void build() {
        long start = System.nanoTime();
        int w = atlasWidth, h = atlasHeight;
        ResourceManager resources = Minecraft.getInstance().getResourceManager();
        ByteBuffer n = MemoryUtil.memAlloc(w * h * 4);
        ByteBuffer s = MemoryUtil.memAlloc(w * h * 4);
        try {
            for (int i = 0; i < w * h; i++) {
                n.putInt(i * 4, FLAT_NORMAL);
                s.putInt(i * 4, NO_SPECULAR);
            }
            int foundN = 0, foundS = 0;
            for (Sprite sprite : sprites) {
                if (stitch(resources, sprite, "_n", n, w)) foundN++;
                if (stitch(resources, sprite, "_s", s, w)) foundS++;
            }
            normalCount = foundN;
            specularCount = foundS;
            builtGeneration = generation;
            if (foundN + foundS == 0) {
                VisualsLog.info("Visuals PBR: no LabPBR maps in the resource packs (" + sprites.size() + " sprites), PBR draws nothing");
                return;
            }
            //2026-11-12 (session 14): an atlas without a single map is one value everywhere (flat, or no specular), which a 1x1 image
            // gives the same at any coordinate and level: a pack (or a mod) with a few _n maps and no _s no longer keeps an atlas-sized
            // specular image (8192x4096 with its levels: about 180 MB in CTE2), nor the other way round
            normal = foundN > 0 ? upload(n, w, h) : constant(FLAT_NORMAL);
            specular = foundS > 0 ? upload(s, w, h) : constant(NO_SPECULAR);
            spriteMap = spriteMap(w, h);
            VisualsLog.info(String.format("Visuals PBR atlas: %d normal and %d specular maps of %d sprites, %dx%d, %d levels, parallax %s, %.0f ms",
                    foundN, foundS, sprites.size(), w, h, levels, parallaxMap ? "on" : "off (sprites not on a cell grid)", (System.nanoTime() - start) / 1e6));
        } finally {
            MemoryUtil.memFree(n);
            MemoryUtil.memFree(s);
        }
    }

    /** Copies sprite's map (name + suffix) into atlas at the sprite's place, scaled to its size; false when there is none. */
    private static boolean stitch(ResourceManager resources, Sprite sprite, String suffix, ByteBuffer atlas, int atlasW) {
        ResourceLocation name = sprite.name();
        ResourceLocation file = new ResourceLocation(name.getNamespace(), "textures/" + name.getPath() + suffix + ".png");
        Optional<Resource> resource = resources.getResource(file);
        if (resource.isEmpty()) return false;
        try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
            int iw = image.getWidth();
            //An animated map's first frame: as tall as the sprite is for the map's width (the whole image when shorter)
            int ih = Math.min(image.getHeight(), Math.max(1, iw * sprite.height() / Math.max(sprite.width(), 1)));
            for (int y = 0; y < sprite.height(); y++) {
                int sy = Math.min(y * ih / sprite.height(), ih - 1);
                int row = (sprite.y() + y) * atlasW + sprite.x();
                for (int x = 0; x < sprite.width(); x++) {
                    int sx = Math.min(x * iw / sprite.width(), iw - 1);
                    //NativeImage's pixel is ABGR: red in the low byte, as the atlas' RGBA bytes read little-endian
                    atlas.putInt((row + x) * 4, image.getPixelRGBA(sx, sy));
                }
            }
            return true;
        } catch (Exception e) {
            VisualsLog.warn("Visuals PBR: " + file + " could not be read (" + e + ")");
            return false;
        }
    }

    private static VulkanImage upload(ByteBuffer level0, int w, int h) {
        VulkanImage image = new VulkanImage.Builder(w, h).setMipLevels(levels).createVulkanImage();
        ByteBuffer level = level0;
        int lw = w, lh = h;
        for (int l = 0; l < levels; l++) {
            if (l > 0) {
                ByteBuffer next = PbrMath.downsample(level, lw, lh);
                if (level != level0) MemoryUtil.memFree(level);
                level = next;
                lw = Math.max(1, lw >> 1);
                lh = Math.max(1, lh >> 1);
            }
            uploadLevel(image, l, level, lw, lh);
        }
        if (level != level0) MemoryUtil.memFree(level);
        image.readOnlyLayout();
        return image;
    }

    /** Session 14: a 1x1 image of texel (little-endian RGBA, as the atlases), for an atlas without maps. */
    private static VulkanImage constant(int texel) {
        ByteBuffer one = MemoryUtil.memAlloc(4);
        try {
            one.putInt(0, texel);
            VulkanImage image = new VulkanImage.Builder(1, 1).createVulkanImage();
            image.uploadSubTextureAsync(0, 1, 1, 0, 0, 0, 0, 1, one);
            image.readOnlyLayout();
            return image;
        } finally {
            MemoryUtil.memFree(one);
        }
    }

    private static void uploadLevel(VulkanImage image, int l, ByteBuffer pixels, int w, int h) {
        int rows = Math.max(1, UPLOAD_BYTES / (w * 4));
        for (int y = 0; y < h; y += rows) {
            int n = Math.min(rows, h - y);
            ByteBuffer slice = MemoryUtil.memSlice(pixels, y * w * 4, n * w * 4);
            image.uploadSubTextureAsync(l, w, n, 0, y, 0, 0, w, slice);
        }
    }

    /**
     * The parallax lookup: per cell of the atlas (the largest power of two every sprite's place and size are multiples of) the cell's
     * place in its sprite (x, y) and the sprite's size (width, height), all in cells (PbrMath.spriteCells). 1x1 and empty when the
     * sprites are not on such a grid of at least CELL_MIN pixels: the parallax then stays off.
     */
    private static VulkanImage spriteMap(int w, int h) {
        int[] grid = PbrMath.spriteCells(sprites, w, h, CELL_MIN, CELLS_MAX);
        parallaxMap = grid != null;
        int cw = parallaxMap ? grid[0] : 1, ch = parallaxMap ? grid[1] : 1;
        ByteBuffer cells = MemoryUtil.memCalloc(cw * ch * 4);
        try {
            if (parallaxMap) {
                for (int i = 0; i < cw * ch; i++) cells.putInt(i * 4, grid[2 + i]);
            }
            VulkanImage image = new VulkanImage.Builder(cw, ch).setFormat(VK_FORMAT_R8G8B8A8_UINT).createVulkanImage();
            image.uploadSubTextureAsync(0, cw, ch, 0, 0, 0, 0, cw, cells);
            image.readOnlyLayout();
            return image;
        } finally {
            MemoryUtil.memFree(cells);
        }
    }
}
