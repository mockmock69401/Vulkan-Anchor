package dev.vulkanfix.mixin.reforged;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.vulkanfix.compat.ShaderStagePaths;
import dev.vulkanfix.compat.VrGuard;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.minecraft.util.GsonHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.Reader;
import java.lang.reflect.Field;

/**
 * VulkanMod-Reforged converts mod shaders in createLegacyShader(), reading the fields vsPath/fsName that its
 * ShaderInstanceM merges into ShaderInstance. They are supposed to be set by a @Redirect on getOrCreate that does
 * not take effect on Forge 47.4.23, so every mod shader failed with "minecraft:null.vsh" and used a fallback.
 * Priority 800 puts this RETURN callback ahead of Reforged's (900), so the paths are there when it converts.
 */
@Mixin(value = ShaderInstance.class, priority = 800, remap = false)
public abstract class ShaderInstancePathsMixin {
    @Inject(
            method = "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V",
            at = @At("RETURN"),
            remap = false
    )
    private void vulkanfix$fillLegacyStagePaths(ResourceProvider provider, ResourceLocation location, VertexFormat format, CallbackInfo ci) {
        try {
            Field vsPath = ShaderInstance.class.getDeclaredField("vsPath");
            Field fsName = ShaderInstance.class.getDeclaredField("fsName");
            vsPath.setAccessible(true);
            fsName.setAccessible(true);
            if (vsPath.get(this) != null && fsName.get(this) != null) {
                return;
            }
            ResourceLocation json = new ResourceLocation(ShaderStagePaths.jsonFor(location.getNamespace(), location.getPath()));
            try (Reader reader = provider.openAsReader(json)) {
                JsonObject program = GsonHelper.parse(reader);
                if (vsPath.get(this) == null) {
                    vsPath.set(this, ShaderStagePaths.forStage(GsonHelper.getAsString(program, "vertex")));
                }
                if (fsName.get(this) == null) {
                    fsName.set(this, ShaderStagePaths.forStage(GsonHelper.getAsString(program, "fragment")));
                }
            }
        } catch (NoSuchFieldException e) {
            // Reforged layout changed; nothing to fill.
        } catch (Exception e) {
            VrGuard.LOGGER.warn("[vulkanfix] Could not resolve shader stage paths for {}", location, e);
        }
    }
}
