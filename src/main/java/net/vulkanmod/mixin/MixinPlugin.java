package net.vulkanmod.mixin;

import net.vulkanmod.compat.RuntimeOptions;
import net.vulkanmod.compat.sable.SableCompatibilityOptions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import net.vulkanmod.visuals.dh.DhPresence;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

public class MixinPlugin implements IMixinConfigPlugin {
    private static final String SCGUNS_MIXIN = "net.vulkanmod.mixin.compatibility.scguns.GunRenderingHandlerMixin";
    private static final String SCGUNS_HANDLER = "top.ribs.scguns.client.handler.GunRenderingHandler";
    private static final String SCGUNS_DRAW = "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V";
    private static final String SUBSCRIBE_EVENT = "Lnet/neoforged/bus/api/SubscribeEvent;";
    private static final String CREATE_MIXIN_PACKAGE = "net.vulkanmod.mixin.compatibility.create.";
    private static final String CREATE_MAIN_MENU_SCREEN_MIXIN = CREATE_MIXIN_PACKAGE + "CreateMainMenuScreenMixin";
    private static final String CREATE_MAIN_MENU_SCREEN = "com.simibubi.create.infrastructure.gui.CreateMainMenuScreen";
    private static final String TENSURA_MIXIN_PACKAGE = "net.vulkanmod.mixin.compatibility.tensura.";
    private static final String TENSURA_SIMPLE_BUTTON_MIXIN = TENSURA_MIXIN_PACKAGE + "TensuraSimpleButtonMixin";
    private static final String TENSURA_OVERLAY_HANDLER_MIXIN = TENSURA_MIXIN_PACKAGE + "TensuraOverlayHandlerMixin";
    private static final String TENSURA_SIMPLE_BUTTON = "io.github.manasmods.tensura.client.screen.widgets.SimpleButton";
    private static final String TENSURA_OVERLAY_HANDLER = "io.github.manasmods.tensura.handler.client.OverlayHandler";
    private static final String ESSENTIAL_MIXIN_PACKAGE = "net.vulkanmod.mixin.compatibility.essential.";
    private static final String ESSENTIAL_UI_3D_PLAYER_MIXIN = ESSENTIAL_MIXIN_PACKAGE + "EssentialUI3DPlayerMixin";
    private static final String ESSENTIAL_UI_3D_PLAYER = "gg.essential.gui.common.UI3DPlayer";
    private static final String CODECHICKEN_MIXIN_PACKAGE = "net.vulkanmod.mixin.compatibility.codechicken.";
    private static final String CODECHICKEN_UNIFORM_ENTRY = "codechicken.lib.render.shader.CCUniform$UniformEntry";
    private static final String SABLE_MIXIN_PACKAGE = "net.vulkanmod.mixin.compatibility.sable.";
    private static final Set<String> VEIL_CORE_MIXINS = Set.of(
            SABLE_MIXIN_PACKAGE + "VeilVertexAttribDivisorMixin",
            SABLE_MIXIN_PACKAGE + "VeilSamplerObjectMixin",
            SABLE_MIXIN_PACKAGE + "VeilMultiBindLegacySamplerMixin",
            SABLE_MIXIN_PACKAGE + "VeilFirstPersonRendererMixin",
            SABLE_MIXIN_PACKAGE + "VeilRenderSystemMixin");

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
    private static final java.util.Set<String> SUFFIXED_DH_MIXINS = java.util.Set.of(
            LEGACY_DH_MIXIN_PACKAGE + "SuffixedClientApiMixin", LEGACY_DH_MIXIN_PACKAGE + "SuffixedVanillaFogMixin");

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
        if (SCGUNS_MIXIN.equals(mixinClassName)) {
            return SCGUNS_HANDLER.equals(targetClassName) && supportsScgunsCooldownAbi();
        }
        if (mixinClassName.startsWith(CODECHICKEN_MIXIN_PACKAGE)) {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            return (CODECHICKEN_MIXIN_PACKAGE + "CCUniformEntryMixin").equals(mixinClassName)
                    && CODECHICKEN_UNIFORM_ENTRY.equals(targetClassName) && loader != null
                    && loader.getResource("codechicken/lib/render/shader/CCUniform$UniformEntry.class") != null;
        }
        if (VEIL_CORE_MIXINS.contains(mixinClassName)) {
            return isVeilPresent();
        }
        if (mixinClassName.startsWith(SABLE_MIXIN_PACKAGE)) {
            return SableCompatibilityOptions.mode().enabled() && isSablePresent();
        }
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

        if (mixinClassName.startsWith(ESSENTIAL_MIXIN_PACKAGE)) {
            return ESSENTIAL_UI_3D_PLAYER_MIXIN.equals(mixinClassName)
                    && ESSENTIAL_UI_3D_PLAYER.equals(targetClassName);
        }

        if (SUFFIXED_DH_MIXINS.contains(mixinClassName)) {
            return DhPresence.suffixedLegacyRenderDef();
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

    private static boolean supportsScgunsCooldownAbi() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) return false;
        String owner = SCGUNS_HANDLER.replace('.', '/');
        try (InputStream stream = loader.getResourceAsStream(owner + ".class")) {
            if (stream == null) return false;
            ClassNode target = new ClassNode();
            new ClassReader(stream).accept(target, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (!owner.equals(target.name)) return false;

            MethodNode constructor = scgunsMethod(target, "<init>", "()V", -1);
            boolean constructorReturns = false;
            if (constructor != null) {
                for (var instruction : constructor.instructions) {
                    if (instruction.getOpcode() == Opcodes.RETURN) constructorReturns = true;
                }
            }
            return constructorReturns
                    && scgunsMethod(target, "handleRenderTick", "()V", Opcodes.ACC_PRIVATE) != null
                    && scgunsMethod(target, "renderCooldownIndicator", SCGUNS_DRAW, Opcodes.ACC_PRIVATE) != null
                    && subscribedScgunsMethod(target, "(Lnet/neoforged/neoforge/client/event/ClientTickEvent$Post;)V")
                    && subscribedScgunsMethod(target, "(Lnet/neoforged/neoforge/client/event/RenderFrameEvent$Post;)V");
        } catch (IOException | RuntimeException unsupportedClass) {
            return false;
        }
    }

    private static MethodNode scgunsMethod(ClassNode target, String name, String descriptor, int visibility) {
        int visibilityMask = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;
        for (MethodNode method : target.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)
                    && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
                    && (visibility < 0 || (method.access & visibilityMask) == visibility)) {
                return method;
            }
        }
        return null;
    }

    private static boolean subscribedScgunsMethod(ClassNode target, String descriptor) {
        MethodNode method = scgunsMethod(target, "onTick", descriptor, Opcodes.ACC_PUBLIC);
        return method != null && method.visibleAnnotations != null
                && method.visibleAnnotations.stream().anyMatch(annotation -> SUBSCRIBE_EVENT.equals(annotation.desc));
    }

    private static boolean isOldF3Mixin(String mixinClassName) {
        return mixinClassName.equals("net.vulkanmod.mixin.debug.DebugScreenOverlayM")
                || mixinClassName.equals("net.vulkanmod.mixin.debug.GlDebugInfoM");
    }

    private static boolean isSablePresent() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null && loader.getResource(
                "dev/ryanhcode/sable/sublevel/render/dispatcher/SubLevelRenderDispatcher.class") != null;
    }

    private static boolean isVeilPresent() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null && loader.getResource(
                "foundry/veil/api/client/render/VeilRenderSystem.class") != null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

    }
}
