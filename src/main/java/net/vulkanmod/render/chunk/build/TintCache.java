package net.vulkanmod.render.chunk.build;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.build.biome.BoxBlur;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiFunction;

public class TintCache {
    private static final int SECTION_WIDTH = 16;

    private final Layer[] layers = new Layer[SECTION_WIDTH];

    private int blendRadius, totalWidth;
    private int secX, secY, secZ;
    private int minX, minZ;
    private int maxX, maxZ;

    private int dataSize;
    private int[] temp;

    public TintCache() {
        Arrays.fill(layers, new Layer());
    }

    public void init(int blendRadius, int secX, int secY, int secZ) {
        this.blendRadius = Minecraft.getInstance().options.biomeBlendRadius().get();
        this.totalWidth = (blendRadius * 2) + 16;

        this.secX = secX;
        this.secY = secY;
        this.secZ = secZ;

        minX = (secX << 4) - blendRadius;
        minZ = (secZ << 4) - blendRadius;
        maxX = (secX << 4) + 16 + blendRadius;
        maxZ = (secZ << 4) + 16 + blendRadius;

        int size = totalWidth * totalWidth;

        if(size != dataSize) {
            this.dataSize = size;
            for (Layer layer : layers) {
                layer.allocate(size);
            }
            temp = new int[size];
        } else {
            for (Layer layer : layers) {
                layer.invalidate();
            }
        }
    }

    public int getColor(BlockPos blockPos, ColorResolver colorResolver) {
        int relY = blockPos.getY() & 15;
        Layer layer = layers[relY];
        if(layer.invalidated)
            calculateLayer(relY);

        int[] values = layer.getValues(colorResolver, this);
        int relX = blockPos.getX() & 15;
        int relZ = blockPos.getZ() & 15;
        int idx = totalWidth * (relZ + blendRadius) + (relX + blendRadius);
        return values[idx];
    }

    public void calculateLayer(int y) {
        Level level = WorldRenderer.getLevel();
        Layer layer = layers[y];

        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        int absY = (secY << 4) + y;

        Biome tB = level.getBiome(blockPos.set(minX, absY, minZ)).value();
        boolean mixed = false;
        for (int absZ = minZ; absZ < maxZ ; absZ++) {
            for (int absX = minX; absX < maxX ; absX++) {
                blockPos.set(absX, absY, absZ);
                Biome biome = level.getBiome(blockPos).value();

                if (biome != tB)
                    mixed = true;

                final int idx = (absX - minX) + (absZ - minZ) * totalWidth;
                layer.biomes[idx] = biome;
                layer.grass[idx] = biome.getGrassColor(absX, absZ);
                layer.foliage[idx] = biome.getFoliageColor();
                layer.water[idx] = biome.getWaterColor();
            }
        }

        if(mixed && blendRadius > 0) {
            BoxBlur.blur(layer.grass, temp, SECTION_WIDTH, blendRadius);
            BoxBlur.blur(layer.foliage, temp, SECTION_WIDTH, blendRadius);
            BoxBlur.blur(layer.water, temp, SECTION_WIDTH, blendRadius);
        }

        layer.mixed = mixed;
        layer.invalidated = false;
    }

    /**
     * A resolver other than vanilla's three (Polytone's colormaps are their own resolvers, for water and plants): its colour per column of
     * the layer's biomes, blurred the same way.
     */
    private int[] resolve(Layer layer, ColorResolver colorResolver) {
        int[] values = new int[dataSize];
        for (int relZ = 0; relZ < totalWidth; relZ++) {
            for (int relX = 0; relX < totalWidth; relX++) {
                int idx = relX + relZ * totalWidth;
                values[idx] = colorResolver.getColor(layer.biomes[idx], minX + relX, minZ + relZ);
            }
        }
        if (layer.mixed && blendRadius > 0)
            BoxBlur.blur(values, temp, SECTION_WIDTH, blendRadius);
        return values;
    }

    static class Layer {
        private boolean invalidated = true;
        private boolean mixed;

        private Biome[] biomes;
        private final Map<ColorResolver, int[]> others = new IdentityHashMap<>();
        private int[] grass;
        private int[] foliage;
        private int[] water;

        void allocate(int size) {
            biomes = new Biome[size];
            grass = new int[size];
            foliage = new int[size];
            water = new int[size];
            invalidate();
        }

        void invalidate() {
            this.invalidated = true;
            others.clear();
        }

        int[] getValues(ColorResolver colorResolver, TintCache cache) {
            if(colorResolver == BiomeColors.GRASS_COLOR_RESOLVER)
                return grass;
            else if(colorResolver == BiomeColors.FOLIAGE_COLOR_RESOLVER)
                return foliage;
            else if(colorResolver == BiomeColors.WATER_COLOR_RESOLVER)
                return water;

            int[] values = others.get(colorResolver);
            if (values == null) {
                values = cache.resolve(this, colorResolver);
                others.put(colorResolver, values);
            }
            return values;
        }
    }
}

