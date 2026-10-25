package net.vulkanmod.render.chunk.build.task;

import com.google.common.collect.Lists;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.vulkanmod.render.vertex.QuadSorter;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;

import org.jetbrains.annotations.Nullable;
import java.util.List;

public class CompiledSection {
    public static final CompiledSection UNCOMPILED = new CompiledSection();

    boolean isCompletelyEmpty = false;
    final List<BlockEntity> blockEntities = Lists.newArrayList();
    @Nullable QuadSorter.SortState transparencyState;
    /** The animated sprites this section's mesh uses (SpriteActivity marks them while the section is visible). */
    public net.vulkanmod.render.texture.SpriteActivity.Holder[] animatedSprites = net.vulkanmod.render.texture.SpriteActivity.NONE;

    public boolean hasTransparencyState() {
        return this.transparencyState != null;
    }

    public List<BlockEntity> getBlockEntities() {
        return this.blockEntities;
    }
}



