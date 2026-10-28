package net.vulkanmod.visuals.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;
import net.vulkanmod.mixin.texture.SpriteContentsAccessor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 2026-10-28 (the user: ores glow for vanilla's ores but not for Create's and other tech mods'): which blocks are ores, and of which
 * kind, for FoliageMarker's ore marks. An ore is a block in the ores tag, or one whose registry name has "ore" as a word (zombie_extreme,
 * Allthemodium, Chrono Dawn... leave theirs untagged; 53 such blocks across the nine test packs), not a machine or a storage block. A
 * pale ore's specks are whitish metal (zinc, tin, silver, aluminum, osmium): scene_copy.comp tells them by their brightness, which for
 * coloured ores it leaves to netherrack hosts. Decided once per block from its particle sprite (paleSpecks), on the chunk build threads.
 */
public final class OreKinds {
    public static final int NONE = 0, ORE = 1, PALE_ORE = 2;
    /** A word "ore" or "ores" in a registry path ("zinc_ore", "ore_tin", "deepslate_ore_lead"; not "core", "store"). */
    private static final Pattern ORE_WORD = Pattern.compile("(^|[_/])ores?($|[_/])");
    /** Ore-named blocks that are no ore: machines, processed and decorative blocks (industrialforegoing:ore_laser_base...). */
    private static final Pattern NOT_ORE = Pattern.compile("raw_|_raw|storage|processing|washer|crusher|miner|drill|laser|base|sifter|"
            + "smelter|factory|machine|generator|furnace|scanner|finder|chest|crate|_slab|_stairs|_wall|brick|chiseled|polished|_tile|_pillar|"
            + "lamp|cluster|_bud|dust");
    /** paleSpecks: a texel this many times the sprite's median luminance, and below PALE_SAT saturated, is a pale speck... */
    public static final float PALE_BRIGHT = 1.35f, PALE_SAT = 0.25f;
    /**
     * ...a sprite with at least PALE_SHARE of them, below COLOURED_SHARE of texels the coloured rule finds and below DARK_SHARE darker
     * than DARK x its median (dark specks: lead, coal; stone beside them would glow as white crosses) is a pale ore.
     */
    public static final float PALE_SHARE = 0.03f, COLOURED_SHARE = 0.03f, DARK = 0.4f, DARK_SHARE = 0.08f;
    /** The speck rule's neighbours in the sprite are this share of its width away (scene_copy.comp's ORE_HOST_BLOCKS of a block). */
    public static final float HOST_SHARE = 0.3f;

    private static final Map<Block, Integer> KINDS = new ConcurrentHashMap<>();

    private OreKinds() {
    }

    /** The ore kind of a block (cached per block). */
    public static int kind(BlockState state) {
        Block block = state.getBlock();
        Integer k = KINDS.get(block);
        if (k == null) {
            k = classify(state);
            KINDS.put(block, k);
        }
        return k;
    }

    /** Forget the kinds: tags and sprites change on a resource reload or another world. */
    public static void clear() {
        KINDS.clear();
    }

    private static int classify(BlockState state) {
        boolean ore = state.is(Tags.Blocks.ORES);
        if (!ore) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            ore = id != null && oreName(id.getPath());
        }
        if (!ore) return NONE;
        try {
            TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
            NativeImage image = ((SpriteContentsAccessor) sprite.contents()).vulkanmod$originalImage();
            int w = sprite.contents().width(), h = sprite.contents().height();
            int[] abgr = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) abgr[y * w + x] = image.getPixelRGBA(x, y);
            }
            return paleSpecks(abgr, w, h) ? PALE_ORE : ORE;
        } catch (RuntimeException e) {
            return ORE;
        }
    }

    /** Whether a registry path names an ore. */
    public static boolean oreName(String path) {
        return ORE_WORD.matcher(path).find() && !NOT_ORE.matcher(path).find();
    }

    /**
     * Whether a sprite (NativeImage ABGR texels, w x h; an animated strip's first w x w frame) is a pale ore's: its bright, nearly grey
     * texels are at least PALE_SHARE of the opaque ones, the coloured speck rule (SceneCompositeMath.oreSpeck in grey hosts) finds under
     * COLOURED_SHARE, and under DARK_SHARE are dark.
     */
    public static boolean paleSpecks(int[] abgr, int w, int h) {
        int size = Math.min(w, h);
        if (size <= 0) return false;
        float[][] c = new float[size * size][];
        float[] lums = new float[size * size];
        int opaque = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int v = abgr[y * w + x];
                if ((v >>> 24) < 128) continue;
                float[] rgb = {(v & 0xFF) / 255f, (v >> 8 & 0xFF) / 255f, (v >> 16 & 0xFF) / 255f};
                c[y * size + x] = rgb;
                lums[opaque++] = PostMath.luminance(rgb[0], rgb[1], rgb[2]);
            }
        }
        if (opaque == 0) return false;
        float[] sorted = java.util.Arrays.copyOf(lums, opaque);
        java.util.Arrays.sort(sorted);
        float median = sorted[(opaque - 1) / 2];
        int stride = Math.max(1, Math.round(HOST_SHARE * size)), pale = 0, coloured = 0, dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float[] own = c[y * size + x];
                if (own == null) continue;
                float lum = PostMath.luminance(own[0], own[1], own[2]);
                if (lum >= PALE_BRIGHT * median && SceneCompositeMath.saturation(own) < PALE_SAT) pale++;
                if (lum < DARK * median) dark++;
                float[][] around = new float[8][];
                int n = 0;
                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        if (i == 0 && j == 0) continue;
                        float[] o = c[Math.floorMod(y + j * stride, size) * size + Math.floorMod(x + i * stride, size)];
                        if (o != null) around[n++] = o;
                    }
                }
                if (SceneCompositeMath.oreSpeck(own, java.util.Arrays.copyOf(around, n)) > 0.5f) coloured++;
            }
        }
        return pale >= PALE_SHARE * opaque && coloured < COLOURED_SHARE * opaque && dark < DARK_SHARE * opaque;
    }
}
