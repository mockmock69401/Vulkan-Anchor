package net.vulkanmod.mixin.render;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    // Shadowed under their SRG names, not Mojang names with an SRG alias: Mixin refuses to alias a non-private
    // field, and mods whose access transformer makes one of these public (EpicFight Nightfall, VIX, Fancy Trail
    // publish f_172581_) then crash startup in this mixin.
    @Shadow(remap = false) @Final private Map<String, ShaderInstance> f_172578_;

    @Shadow(remap = false) private @Nullable static ShaderInstance f_172579_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172580_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172581_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172582_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172583_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172586_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172589_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172591_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172608_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172609_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172610_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172611_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172613_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172614_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172615_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172616_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172617_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172618_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172619_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172620_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_234217_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172621_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172622_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172623_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172624_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172625_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172626_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172627_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172628_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172629_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172630_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172631_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172632_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172633_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172593_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172594_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172595_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172596_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172597_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172598_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172599_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172600_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172601_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172602_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172603_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172604_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172605_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172606_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172607_;

    @Shadow(remap = false) private static @Nullable ShaderInstance f_268423_;
    @Shadow(remap = false) private static @Nullable ShaderInstance f_268525_;
    @Shadow(remap = false) private static @Nullable ShaderInstance f_285653_;
    @Shadow(remap = false) private static @Nullable ShaderInstance f_285598_;
    @Shadow(remap = false) private static @Nullable ShaderInstance f_285623_;
    @Shadow(remap = false) private static @Nullable ShaderInstance f_285569_;

    @Shadow(remap = false) private @Nullable static ShaderInstance f_172587_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172588_;
    @Shadow(remap = false) private @Nullable static ShaderInstance f_172590_;

    @Shadow(remap = false) public ShaderInstance f_172635_;

    @Shadow(remap = false)
    private ShaderInstance m_172724_(ResourceProvider resourceProvider, String string, VertexFormat vertexFormat) {
        throw new AssertionError();
    }

    @Inject(method = "m_172767_", at = @At("HEAD"), cancellable = true, remap = false)
    public void reloadShaders(ResourceProvider provider, CallbackInfo ci) throws IOException {
        RenderSystem.assertOnRenderThread();

        List<Pair<ShaderInstance, Consumer<ShaderInstance>>> shaders = Lists.newArrayListWithCapacity(this.f_172578_.size());

        try {
            shaders.add(Pair.of(new ShaderInstance(provider, "particle", DefaultVertexFormat.PARTICLE), (shaderInstance) -> {
                f_172586_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "position", DefaultVertexFormat.POSITION), (shaderInstance) -> {
                f_172579_ = shaderInstance;
            }));

            ShaderInstance positionColor = new ShaderInstance(provider, "position_color", DefaultVertexFormat.POSITION_COLOR);
            shaders.add(Pair.of(positionColor, (shaderInstance) -> f_172580_ = shaderInstance));
                                                                                                                                                             
                                                              
                  
            shaders.add(Pair.of(new ShaderInstance(provider, "position_color_tex", DefaultVertexFormat.POSITION_COLOR_TEX), (shaderInstance) -> {
                f_172581_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "position_color_tex_lightmap", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP), (shaderInstance) -> {
               f_172588_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "position_tex", DefaultVertexFormat.POSITION_TEX), (shaderInstance) -> {
                f_172582_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "position_tex_color", DefaultVertexFormat.POSITION_TEX_COLOR), (shaderInstance) -> {
                f_172583_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "position_tex_color_normal", DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL), (shaderInstance) -> {
                f_172589_ = shaderInstance;
            }));
                                                                                                                                                                     
                                                                 
                  
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_solid", DefaultVertexFormat.BLOCK), (shaderInstance) -> {
                f_172591_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_cutout_mipped", DefaultVertexFormat.BLOCK), (shaderInstance) -> {
                f_172608_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_cutout", DefaultVertexFormat.BLOCK), (shaderInstance) -> {
                f_172609_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_translucent", DefaultVertexFormat.BLOCK), (shaderInstance) -> {
                f_172610_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_translucent_moving_block", DefaultVertexFormat.BLOCK), (shaderInstance) -> {
                f_172611_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_armor_cutout_no_cull", DefaultVertexFormat.NEW_ENTITY), (shaderInstance) -> {
                f_172613_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_entity_solid", DefaultVertexFormat.NEW_ENTITY), (shaderInstance) -> {
                f_172614_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_entity_cutout", DefaultVertexFormat.NEW_ENTITY), (shaderInstance) -> {
                f_172615_ = shaderInstance;
            }));

                                      
            ShaderInstance entity_no_cull = new ShaderInstance(provider, "rendertype_entity_cutout_no_cull", DefaultVertexFormat.NEW_ENTITY);
            shaders.add(Pair.of(entity_no_cull, (shaderInstance) -> {
                f_172616_ = shaderInstance;
            }));
                                                                                                                                                                                      
                                                                       
                  
            shaders.add(Pair.of(entity_no_cull, (shaderInstance) -> {
                f_172617_ = shaderInstance;
            }));

            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_item_entity_translucent_cull", DefaultVertexFormat.NEW_ENTITY), (shaderInstance) -> {
                f_172618_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_entity_translucent_cull", DefaultVertexFormat.NEW_ENTITY), (shaderInstance) -> {
                f_172619_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_entity_translucent", DefaultVertexFormat.NEW_ENTITY), (shaderInstance) -> {
                f_172620_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_entity_translucent_emissive", DefaultVertexFormat.NEW_ENTITY), shader -> {
                f_234217_ = shader;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_entity_smooth_cutout", DefaultVertexFormat.NEW_ENTITY), (shaderInstance) -> {
                f_172621_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_beacon_beam", DefaultVertexFormat.BLOCK), (shaderInstance) -> {
                f_172622_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_entity_decal", DefaultVertexFormat.NEW_ENTITY), (shaderInstance) -> {
                f_172623_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_entity_no_outline", DefaultVertexFormat.NEW_ENTITY), (shaderInstance) -> {
                f_172624_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_entity_shadow", DefaultVertexFormat.NEW_ENTITY), (shaderInstance) -> {
                f_172625_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_entity_alpha", DefaultVertexFormat.NEW_ENTITY), (shaderInstance) -> {
                f_172626_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_eyes", DefaultVertexFormat.NEW_ENTITY), (shaderInstance) -> {
                f_172627_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_energy_swirl", DefaultVertexFormat.NEW_ENTITY), (shaderInstance) -> {
                f_172628_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_leash", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP), (shaderInstance) -> {
                f_172629_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_water_mask", DefaultVertexFormat.POSITION), (shaderInstance) -> {
                f_172630_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_outline", DefaultVertexFormat.POSITION_COLOR_TEX), (shaderInstance) -> {
                f_172631_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_armor_glint", DefaultVertexFormat.POSITION_TEX), (shaderInstance) -> {
                f_172632_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_armor_entity_glint", DefaultVertexFormat.POSITION_TEX), (shaderInstance) -> {
                f_172633_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_glint_translucent", DefaultVertexFormat.POSITION_TEX), (shaderInstance) -> {
                f_172593_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_glint", DefaultVertexFormat.POSITION_TEX), (shaderInstance) -> {
                f_172594_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_glint_direct", DefaultVertexFormat.POSITION_TEX), (shaderInstance) -> {
                f_172595_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_entity_glint", DefaultVertexFormat.POSITION_TEX), (shaderInstance) -> {
                f_172596_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_entity_glint_direct", DefaultVertexFormat.POSITION_TEX), (shaderInstance) -> {
                f_172597_ = shaderInstance;
            }));

                  
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_text", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP), (shaderInstance) -> {
                f_172598_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_text_background", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP), (shaderInstance) -> {
                f_268423_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_text_intensity", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP), (shaderInstance) -> {
                f_172599_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_text_see_through", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP), (shaderInstance) -> {
                f_172600_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_text_background_see_through", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP), (shaderInstance) -> {
                f_268525_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_text_intensity_see_through", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP), (shaderInstance) -> {
                f_172601_ = shaderInstance;
            }));

            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_lightning", DefaultVertexFormat.POSITION_COLOR), (shaderInstance) -> {
                f_172602_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_tripwire", DefaultVertexFormat.BLOCK), (shaderInstance) -> {
                f_172603_ = shaderInstance;
            }));
            ShaderInstance endPortalShader = new ShaderInstance(provider, "rendertype_end_portal", DefaultVertexFormat.POSITION);
            shaders.add(Pair.of(endPortalShader, (shaderInstance) -> {
                f_172604_ = shaderInstance;
            }));
            shaders.add(Pair.of(endPortalShader, (shaderInstance) -> {
                f_172605_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL), (shaderInstance) -> {
                f_172606_ = shaderInstance;
            }));
            shaders.add(Pair.of(new ShaderInstance(provider, "rendertype_crumbling", DefaultVertexFormat.BLOCK), (shaderInstance) -> {
                f_172607_ = shaderInstance;
            }));

            shaders.add(Pair.of(positionColor, (shaderInstance) -> {
                f_285653_ = shaderInstance;
            }));
            shaders.add(Pair.of(positionColor, (shaderInstance) -> {
                f_285598_ = shaderInstance;
            }));
            shaders.add(Pair.of(positionColor, (shaderInstance) -> {
                f_285623_ = shaderInstance;
            }));
            shaders.add(Pair.of(positionColor, (shaderInstance) -> {
                f_285569_ = shaderInstance;
            }));

            net.minecraftforge.fml.ModLoader.get().postEvent(new net.minecraftforge.client.event.RegisterShadersEvent(provider, shaders));
        } catch (IOException ioexception) {
            shaders.forEach((pair) -> pair.getFirst().close());
            throw new RuntimeException("could not reload shaders", ioexception);
        }

        this.m_172759_();
        shaders.forEach((pair) -> {
            ShaderInstance shaderinstance = pair.getFirst();
            this.f_172578_.put(shaderinstance.getName(), shaderinstance);
            pair.getSecond().accept(shaderinstance);
        });

        ci.cancel();
    }

       
              
              
       
    @Overwrite(remap = false)
    private void m_172759_() {
        RenderSystem.assertOnRenderThread();

        final var clearList = ImmutableList.copyOf(this.f_172578_.values());
        MemoryManager.getInstance().addFrameOp(() -> clearList.forEach((ShaderInstance::close)));

        this.f_172578_.clear();
    }

       
              
              
       
    @Overwrite(remap = false)
    public void m_172722_(ResourceProvider resourceProvider) {
        if (this.f_172635_ != null) {
            throw new RuntimeException("Blit shader already preloaded");
        } else {
            try {
                this.f_172635_ = new ShaderInstance(resourceProvider, "blit_screen", DefaultVertexFormat.POSITION_TEX);
            } catch (IOException var3) {
                throw new RuntimeException("could not preload blit shader", var3);
            }

            f_172579_ = this.m_172724_(resourceProvider, "position", DefaultVertexFormat.POSITION);
            f_172580_ = this.m_172724_(resourceProvider, "position_color", DefaultVertexFormat.POSITION_COLOR);
            f_172581_ = this.m_172724_(resourceProvider, "position_color_tex", DefaultVertexFormat.POSITION_COLOR_TEX);
            f_172582_ = this.m_172724_(resourceProvider, "position_tex", DefaultVertexFormat.POSITION_TEX);
            f_172583_ = this.m_172724_(resourceProvider, "position_tex_color", DefaultVertexFormat.POSITION_TEX_COLOR);
            f_172598_ = this.m_172724_(resourceProvider, "rendertype_text", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);

            f_285653_ = f_172580_;
            f_285598_ = f_172580_;
        }
    }

       
              
              
       
    @Overwrite(remap = false)
    public float m_172790_() {
        return Float.POSITIVE_INFINITY;
    }

}

