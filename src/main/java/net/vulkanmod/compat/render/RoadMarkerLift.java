package net.vulkanmod.compat.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.vulkanmod.Initializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2026-10-29 (the user: DragN's road markings break up farther away): the road markers of "DragN's VV: Refueled" (refueled, its
 * RoadMarkerBlock) are one flat quad 0.01 pixels (0.000625 blocks) above the block below. A few dozen blocks away the depth of
 * that quad and the road's top face no longer differ, and the road wins in block-long patches: the lines turn into dashes (with
 * Embeddium as well). Their baked models are wrapped so every quad lying that low sits LIFT higher instead.
 */
public final class RoadMarkerLift {
    public static final String NAMESPACE = "refueled";
    /** The marker block's class (no compile-time dependency on the mod). */
    public static final String MARKER_CLASS = "RoadMarkerBlock";
    /** Blocks the quads move up: 50 times the model's own offset, still flat to the eye. */
    public static final float LIFT = 1f / 32f;
    /** Only quads whose every corner is below this (blocks) move: the markers' floor plane, nothing taller. */
    public static final float FLOOR_MAX = 1f / 64f;

    private RoadMarkerLift() {
    }

    /** Mod event bus: wraps the block-state models of Refueled's road markers. */
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        int wrapped = 0;
        for (Map.Entry<ModelResourceLocation, BakedModel> e : event.getModels().entrySet()) {
            ModelResourceLocation key = e.getKey();
            if (!NAMESPACE.equals(key.id().getNamespace()) || "inventory".equals(key.variant())) continue;
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(key.id().getNamespace(), key.id().getPath()));
            if (block == null || !MARKER_CLASS.equals(block.getClass().getSimpleName())) continue;
            e.setValue(new Lifted(e.getValue()));
            wrapped++;
        }
        if (wrapped > 0) Initializer.LOGGER.info("Road markers (refueled): {} models lifted {} blocks off the road", wrapped, LIFT);
    }

    /** The quad LIFT higher when all its corners lie below FLOOR_MAX, else the quad itself. */
    static BakedQuad lift(BakedQuad quad) {
        int[] v = quad.getVertices();
        int stride = DefaultVertexFormat.BLOCK.getVertexSize() / 4;
        for (int i = 0; i < 4; i++) {
            if (!(Float.intBitsToFloat(v[i * stride + 1]) < FLOOR_MAX)) return quad;
        }
        int[] out = v.clone();
        for (int i = 0; i < 4; i++) {
            out[i * stride + 1] = Float.floatToRawIntBits(Float.intBitsToFloat(v[i * stride + 1]) + LIFT);
        }
        return new BakedQuad(out, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
    }

    static final class Lifted extends BakedModelWrapper<BakedModel> {
        /** The original quad to its lifted copy (BakedQuad compares by identity); filled as the chunk builders ask. */
        private final Map<BakedQuad, BakedQuad> lifted = new ConcurrentHashMap<>();

        Lifted(BakedModel original) {
            super(original);
        }

        private List<BakedQuad> lifted(List<BakedQuad> quads) {
            if (quads.isEmpty()) return quads;
            List<BakedQuad> out = new ArrayList<>(quads.size());
            for (BakedQuad q : quads) out.add(this.lifted.computeIfAbsent(q, RoadMarkerLift::lift));
            return out;
        }

        @Override
        public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand) {
            return this.lifted(super.getQuads(state, side, rand));
        }

        @Override
        public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand,
                                                 @NotNull ModelData data, @Nullable RenderType renderType) {
            return this.lifted(super.getQuads(state, side, rand, data, renderType));
        }
    }
}
