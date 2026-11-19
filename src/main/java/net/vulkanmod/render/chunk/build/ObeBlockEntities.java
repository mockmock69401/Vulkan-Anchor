package net.vulkanmod.render.chunk.build;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.Initializer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * 2026-11-19 (the user, in the Tensura pack: a placed bed was invisible): Optimized Block Entities (OBE, mod id obe) draws beds, signs,
 * chests, banners, skulls, shulker boxes, bells and decorated pots as block models in the chunk mesh, and cancels their block entity
 * renderers while they are drawn that way. It puts them in the mesh through vanilla's SectionCompiler and Sodium's chunk builder only,
 * so with our chunk builder a bed had neither: no model in the mesh, its renderer cancelled. This does in our builder what OBE's Sodium
 * hooks do in Sodium's: the render shape OBE gives a block entity's block (TERRAIN mode: drawn as a model; forced to entity: none), the
 * model it draws, the tasks OBE runs once a section's mesh lands (which switch its block entities' modes), and, at draw time, leaving out
 * the block entities OBE draws in the mesh or skips (its vanilla LevelRenderer hook, which our block entity loop does not pass).
 * Found by name, so nothing here needs OBE's classes; without OBE (or with an OBE whose names changed) nothing changes.
 */
public final class ObeBlockEntities {
    private static final String EXT = "fr.madu59.obe.client.renderer.blockentity.ext.BlockEntityExt";
    private static final String MODES = "fr.madu59.obe.client.renderer.blockentity.misc.RenderModeManager";
    private static final String MODE = MODES + "$RenderMode";
    private static final String RENDERER = "fr.madu59.obe.client.renderer.OBEBlockRenderer";
    private static final String EMPTY_MODEL = "fr.madu59.obe.client.model.BlockEntityStateModel";
    private static final String SPECIAL = "fr.madu59.obe.client.renderer.blockentity.SpecialRenderingManager";
    private static final String TASKS = "fr.madu59.obe.client.chunk.ChunkTaskHolder";

    private static final Hooks HOOKS = Hooks.load();

    private ObeBlockEntities() {
    }

    public static boolean active() {
        return HOOKS != null;
    }

    /**
     * The block's render shape as OBE's chunk builder hooks give it (be: the block's block entity, or null): an OBE block entity forced to
     * draw as an entity has none in the mesh, one OBE draws as terrain is a model; anything else keeps its own.
     */
    public static RenderShape renderShape(BlockState state, BlockEntity be, SectionPos section) {
        RenderShape own = state.getRenderShape();
        if (HOOKS == null || be == null || !state.hasBlockEntity()) return own;
        try {
            if (!HOOKS.ext.isInstance(be) || !(boolean) HOOKS.supported.invoke(be)) return own;
            HOOKS.remesh.invoke(be, section);
            if ((boolean) HOOKS.forceEntity.invoke(be)) return RenderShape.INVISIBLE;
            if ((boolean) HOOKS.enabled.invoke(be) && HOOKS.delayed.invoke(be) == HOOKS.terrain) return RenderShape.MODEL;
        } catch (Throwable t) {
            HOOKS.fail(t);
        }
        return own;
    }

    /** The model OBE draws in the mesh for the block (model: the block's own); the block's own when OBE has none for it. */
    public static BakedModel model(BlockState state, BlockPos pos, BakedModel model, BlockEntity be) {
        if (HOOKS == null || be == null || !state.hasBlockEntity()) return model;
        try {
            if (!HOOKS.ext.isInstance(be) || !(boolean) HOOKS.supported.invoke(be)) return model;
            if ((boolean) HOOKS.enabled.invoke(be) && !(boolean) HOOKS.special.invoke(be) && HOOKS.delayed.invoke(be) != HOOKS.terrain) {
                return (BakedModel) HOOKS.emptyModel.invoke();
            }
            BakedModel obe = (BakedModel) HOOKS.getModel.invoke(HOOKS.renderer, state, pos.immutable(), state.getSeed(pos), model, be);
            return obe != null ? obe : model;
        } catch (Throwable t) {
            HOOKS.fail(t);
            return model;
        }
    }

    /** A section's mesh landed (main thread): OBE's tasks for it switch its block entities to the mode the mesh was built for. */
    public static void sectionCompiled(int blockX, int blockY, int blockZ) {
        if (HOOKS == null) return;
        try {
            HOOKS.executeTasks.invoke(SectionPos.of(blockX >> 4, blockY >> 4, blockZ >> 4));
        } catch (Throwable t) {
            HOOKS.fail(t);
        }
    }

    /** Whether the block entity renderer is left out for be: OBE draws it in the mesh, or it has nothing to show (an empty sign). */
    public static boolean skipEntity(BlockEntity be) {
        if (HOOKS == null) return false;
        try {
            if (!HOOKS.ext.isInstance(be) || !(boolean) HOOKS.enabled.invoke(be)) return false;
            return !(boolean) HOOKS.renderEntityFast.invoke(be) || (boolean) HOOKS.skipBe.invoke(be) || (boolean) HOOKS.skipSpecial.invoke(be);
        } catch (Throwable t) {
            HOOKS.fail(t);
            return false;
        }
    }

    private static final class Hooks {
        final Class<?> ext;
        final MethodHandle supported, forceEntity, enabled, special, delayed, skipBe, remesh, renderEntityFast, getModel, emptyModel,
                skipSpecial, executeTasks;
        final Object terrain, renderer;
        private volatile boolean warned;

        private Hooks(Class<?> ext, MethodHandles.Lookup l, ClassLoader loader) throws ReflectiveOperationException {
            this.ext = ext;
            Class<?> modes = Class.forName(MODES, true, loader);
            Class<?> mode = Class.forName(MODE, true, loader);
            Class<?> renderer = Class.forName(RENDERER, true, loader);
            MethodType object = MethodType.methodType(boolean.class, Object.class);
            this.supported = l.findVirtual(ext, "isSupportedBlockEntity", MethodType.methodType(boolean.class)).asType(object);
            this.forceEntity = l.findVirtual(ext, "forceEntity", MethodType.methodType(boolean.class)).asType(object);
            this.enabled = l.findVirtual(ext, "isEnabled", MethodType.methodType(boolean.class)).asType(object);
            this.special = l.findVirtual(ext, "hasSpecialRenderer", MethodType.methodType(boolean.class)).asType(object);
            this.skipBe = l.findVirtual(ext, "shouldSkipBeRendering", MethodType.methodType(boolean.class)).asType(object);
            this.delayed = l.findVirtual(ext, "renderModeDelayed", MethodType.methodType(mode)).asType(MethodType.methodType(Object.class, Object.class));
            this.remesh = l.findStatic(modes, "updateBlockEntityOnChunkRemesh", MethodType.methodType(void.class, ext, SectionPos.class))
                    .asType(MethodType.methodType(void.class, Object.class, SectionPos.class));
            this.renderEntityFast = l.findStatic(modes, "shouldRenderEntityFast", MethodType.methodType(boolean.class, ext)).asType(object);
            this.getModel = l.findVirtual(renderer, "getModel", MethodType.methodType(BakedModel.class, BlockState.class, BlockPos.class, long.class,
                    BakedModel.class, BlockEntity.class)).asType(MethodType.methodType(Object.class, Object.class, BlockState.class, BlockPos.class,
                    long.class, BakedModel.class, BlockEntity.class));
            this.emptyModel = l.findConstructor(Class.forName(EMPTY_MODEL, true, loader), MethodType.methodType(void.class))
                    .asType(MethodType.methodType(Object.class));
            this.skipSpecial = l.findStatic(Class.forName(SPECIAL, true, loader), "shouldSkipRendering", MethodType.methodType(boolean.class, BlockEntity.class))
                    .asType(object.changeParameterType(0, BlockEntity.class));
            this.executeTasks = l.findStatic(Class.forName(TASKS, true, loader), "executeTasks", MethodType.methodType(void.class, SectionPos.class));
            this.terrain = mode.getField("TERRAIN").get(null);
            this.renderer = renderer.getConstructor().newInstance();
        }

        static Hooks load() {
            ClassLoader loader = BlockEntity.class.getClassLoader();
            Class<?> ext;
            try {
                ext = Class.forName(EXT, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                return null;
            }
            try {
                Hooks hooks = new Hooks(ext, MethodHandles.publicLookup(), loader);
                Initializer.LOGGER.info("Optimized Block Entities found: its block entity models are built into our chunk meshes");
                return hooks;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                Initializer.LOGGER.warn("Optimized Block Entities found but its hooks were not (its block entities may not show): {}", e.toString());
                return null;
            }
        }

        void fail(Throwable t) {
            if (!this.warned) {
                this.warned = true;
                Initializer.LOGGER.warn("Optimized Block Entities hook failed: {}", t.toString());
            }
        }
    }
}
