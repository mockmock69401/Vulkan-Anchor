package net.vulkanmod.mixin;

import net.vulkanmod.compat.RuntimeOptions;
import net.vulkanmod.visuals.dh.DhPresence;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class MixinPlugin implements IMixinConfigPlugin {
    private static final String CREATE_MIXIN_PACKAGE = "net.vulkanmod.mixin.compatibility.create.";
    private static final String CREATE_MAIN_MENU_SCREEN_MIXIN = CREATE_MIXIN_PACKAGE + "CreateMainMenuScreenMixin";
    private static final String CREATE_MAIN_MENU_SCREEN = "com.simibubi.create.infrastructure.gui.CreateMainMenuScreen";
    private static final String TENSURA_MIXIN_PACKAGE = "net.vulkanmod.mixin.compatibility.tensura.";
    private static final String TENSURA_SIMPLE_BUTTON_MIXIN = TENSURA_MIXIN_PACKAGE + "TensuraSimpleButtonMixin";
    private static final String TENSURA_OVERLAY_HANDLER_MIXIN = TENSURA_MIXIN_PACKAGE + "TensuraOverlayHandlerMixin";
    private static final String TENSURA_SIMPLE_BUTTON = "io.github.manasmods.tensura.client.screen.widgets.SimpleButton";
    private static final String TENSURA_OVERLAY_HANDLER = "io.github.manasmods.tensura.handler.client.OverlayHandler";
    private static final String WITHER_STORM_MIXIN_PACKAGE = "net.vulkanmod.mixin.compatibility.witherstorm.";
    private static final String WITHER_STORM_RENDER_BUFFERER_MIXIN = WITHER_STORM_MIXIN_PACKAGE + "WitherStormRenderBuffererM";
    private static final String WITHER_STORM_RENDER_BUFFERER = "nonamecrackers2.witherstormmod.client.instancing.RenderBufferer";
    private static final String FLYWHEEL_MIXIN_PACKAGE = "net.vulkanmod.mixin.compatibility.flywheel.";
    private static final String FLYWHEEL_BACKEND_MANAGER_MIXIN = FLYWHEEL_MIXIN_PACKAGE + "FlywheelBackendManagerMixin";
    private static final String FLYWHEEL_BACKEND_MANAGER = "dev.engine_room.flywheel.impl.BackendManagerImpl";

    private static final String CHUNK_MIXIN_PACKAGE = "net.vulkanmod.mixin.chunk.";
    private static final String SECTION_LAYER_MIXIN = CHUNK_MIXIN_PACKAGE + "SectionLayerMixin";
    private static final String SECTION_LAYER_GUARD_MIXIN = CHUNK_MIXIN_PACKAGE + "SectionLayerGuardMixin";
    private static final String SECTION_LAYER_LATE_MIXIN = CHUNK_MIXIN_PACKAGE + "SectionLayerLateMixin";

    /**
     * The renderSectionLayer mixins to load. With a Distant Horizons the fork draws, a guard (runs first, skips VulkanMod's
     * draw while DH is active) and a late drawer (runs when the guard did not cancel) replace the single drawer, so DH's own
     * hook in that method keeps its place in between.
     */
    public static Set<String> sectionLayerMixins(DhPresence.State dh) {
        return DhPresence.drawsLods(dh)
                ? Set.of(SECTION_LAYER_GUARD_MIXIN, SECTION_LAYER_LATE_MIXIN)
                : Set.of(SECTION_LAYER_MIXIN);
    }

    private static final String LEGACY_DH_MIXIN_PACKAGE = "net.vulkanmod.mixin.visuals.legacydh.";
    private static final String LEGACY_DH_RENDER_DEPTH_MIXIN = LEGACY_DH_MIXIN_PACKAGE + "GlRenderDefinitionRenderDepthMixin";
    private static final String LEGACY_DH_DEPTH_DIRECTION_MIXIN = LEGACY_DH_MIXIN_PACKAGE + "GlRenderDefinitionDepthDirectionMixin";

    /**
     * The mixins that take DH 3.3.0 to 3.3.4's GL render definition over: only with that DH, and of its two depth-direction
     * methods only the one it has (getRenderDepth in 3.3.0 and 3.3.1, getDepthDirection from 3.3.2).
     */
    public static boolean legacyDhMixin(String mixinClassName, DhPresence.State dh, boolean legacyRenderDepth) {
        if (dh != DhPresence.State.LEGACY_RENDER_DEF) return false;
        if (LEGACY_DH_RENDER_DEPTH_MIXIN.equals(mixinClassName)) return legacyRenderDepth;
        if (LEGACY_DH_DEPTH_DIRECTION_MIXIN.equals(mixinClassName)) return !legacyRenderDepth;
        return true;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith(CREATE_MIXIN_PACKAGE)) {
            return CREATE_MAIN_MENU_SCREEN_MIXIN.equals(mixinClassName)
                    && CREATE_MAIN_MENU_SCREEN.equals(targetClassName);
        }

        if (mixinClassName.startsWith(TENSURA_MIXIN_PACKAGE)) {
            if (TENSURA_SIMPLE_BUTTON_MIXIN.equals(mixinClassName)) {
                return TENSURA_SIMPLE_BUTTON.equals(targetClassName);
            }

            if (TENSURA_OVERLAY_HANDLER_MIXIN.equals(mixinClassName)) {
                return TENSURA_OVERLAY_HANDLER.equals(targetClassName);
            }

            return false;
        }

        if (mixinClassName.startsWith(WITHER_STORM_MIXIN_PACKAGE)) {
            return WITHER_STORM_RENDER_BUFFERER_MIXIN.equals(mixinClassName)
                    && WITHER_STORM_RENDER_BUFFERER.equals(targetClassName);
        }

        if (mixinClassName.startsWith(FLYWHEEL_MIXIN_PACKAGE)) {
            return FLYWHEEL_BACKEND_MANAGER_MIXIN.equals(mixinClassName)
                    && FLYWHEEL_BACKEND_MANAGER.equals(targetClassName);
        }

        if (mixinClassName.startsWith(LEGACY_DH_MIXIN_PACKAGE)) {
            return legacyDhMixin(mixinClassName, DhPresence.current(), DhPresence.legacyRenderDepth());
        }

        if (SECTION_LAYER_MIXIN.equals(mixinClassName)
                || SECTION_LAYER_GUARD_MIXIN.equals(mixinClassName)
                || SECTION_LAYER_LATE_MIXIN.equals(mixinClassName)) {
            return sectionLayerMixins(DhPresence.current()).contains(mixinClassName);
        }

        if (mixinClassName.startsWith("net.vulkanmod.mixin.profiling.")) {
            return RuntimeOptions.profilingMixinsEnabled();
        }

        if (isOldF3Mixin(mixinClassName)) {
            return true;
        }

        if (mixinClassName.startsWith("net.vulkanmod.mixin.debug.")) {
            return RuntimeOptions.debugMixinsEnabled();
        }

        return true;
    }

    private static boolean isOldF3Mixin(String mixinClassName) {
        return mixinClassName.equals("net.vulkanmod.mixin.debug.DebugScreenOverlayM")
                || mixinClassName.equals("net.vulkanmod.mixin.debug.GlDebugInfoM");
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

    }

    @Override
    public List<String> getMixins() {
                                                                                                    
                                                                                                       
                                                                                                         
                                                                                                      
        return new ArrayList<>();
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }
}

