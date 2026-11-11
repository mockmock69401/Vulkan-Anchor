package net.vulkanmod.mixin.render.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.interfaces.ExtendedVertexBuilder;
import net.vulkanmod.mixin.visuals.SpriteContentsAccessor;
import net.vulkanmod.mixin.visuals.TextureSheetParticleAccessor;
import net.vulkanmod.visuals.particle.ParticleEffectTag;
import net.vulkanmod.visuals.particle.ParticleLook;
import net.vulkanmod.visuals.particle.ParticleLookMath;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@SuppressWarnings("target")
@Mixin(SingleQuadParticle.class)
public abstract class SingleQuadParticleM extends Particle {

    @Shadow(remap = false) protected float f_107663_;

    @Shadow(remap = false) protected abstract float m_5970_();
    @Shadow(remap = false) protected abstract float m_5952_();
    @Shadow(remap = false) protected abstract float m_5951_();
    @Shadow(remap = false) protected abstract float m_5950_();

    @Shadow(remap = false) public abstract float m_5902_(float f);

    protected SingleQuadParticleM(ClientLevel clientLevel, double d, double e, double f, double g, double h, double i) {
        super(clientLevel, d, e, f, g, h, i);
        this.f_107663_ = 0.1F * (this.random.nextFloat() * 0.5F + 0.5F) * 2.0F;
    }

       
              
              
       
    @Overwrite(remap = false)
    public void m_5744_(VertexConsumer vertexConsumer, Camera camera, float f) {
        double lx = (Mth.lerp(f, this.xo, this.x));
        double ly = (Mth.lerp(f, this.yo, this.y));
        double lz = (Mth.lerp(f, this.zo, this.z));

        if(cull(WorldRenderer.getInstance(), (float) lx, (float) ly, (float) lz))
            return;

        Vec3 vec3 = camera.getPosition();
        float offsetX = (float) (lx - vec3.x());
        float offsetY = (float) (ly - vec3.y());
        float offsetZ = (float) (lz - vec3.z());

        Quaternionf quaternionf;
        if (this.roll != 0.0F) {
            quaternionf = new Quaternionf(camera.rotation());
            quaternionf.rotateZ(Mth.lerp(f, this.oRoll, this.roll));
        } else {
            quaternionf = camera.rotation();
        }

        Vector3f[] vector3fs = new Vector3f[]{new Vector3f(-1.0F, -1.0F, 0.0F), new Vector3f(-1.0F, 1.0F, 0.0F), new Vector3f(1.0F, 1.0F, 0.0F), new Vector3f(1.0F, -1.0F, 0.0F)};
        float j = this.m_5902_(f);

        //2026-11-11 (shader roadmap session 13): the particle look options (ParticleLook); nothing changes while they are all at their defaults
        int lookTag = ParticleLook.active() ? ((ParticleEffectTag) this).vulkanmod$effectTag() : 0;
        int shape = 0, tintGroup = 0;
        float[] stats = null;
        if (lookTag != 0) {
            j *= ParticleLook.sizeScale(lookTag);
            tintGroup = ParticleLook.tintGroup(lookTag);
            shape = ParticleLook.realisticShape(lookTag);
            if (shape != 0 && (Object) this instanceof TextureSheetParticle) {
                TextureAtlasSprite sprite = ((TextureSheetParticleAccessor) this).vulkanmod$sprite();
                if (sprite != null) {
                    SpriteContents contents = sprite.contents();
                    stats = ParticleLook.spriteStats(contents, ((SpriteContentsAccessor) contents).vulkanmod$originalImage());
                    j = ParticleLookMath.realisticSize(j, stats[3], shape);
                }
            }
            if (stats == null) shape = 0;
        }

        for(int k = 0; k < 4; ++k) {
            Vector3f vector3f = vector3fs[k];
            vector3f.rotate(quaternionf);
            vector3f.mul(j);
            vector3f.add(offsetX, offsetY, offsetZ);
        }

        float u0 = this.m_5970_();
        float u1 = this.m_5952_();
        float v0 = this.m_5951_();
        float v1 = this.m_5950_();
        int light = this.getLightColor(f);
        float r = this.rCol, g = this.gCol, b = this.bCol;
        if (shape != 0) {
            //A realistic shape: no texels; u carries the seed, the colour is the sprite's mean (ParticleLookMath, particle.vsh)
            int seed = System.identityHashCode(this);
            u0 = ParticleLookMath.seedU(seed, 0);
            u1 = ParticleLookMath.seedU(seed, 1);
            v0 = 0f;
            v1 = 1f;
            r *= stats[0];
            g *= stats[1];
            b *= stats[2];
        }
        if (shape != 0 || tintGroup != 0) light = ParticleLookMath.light(light, shape, tintGroup);

        ExtendedVertexBuilder vertexBuilder = (ExtendedVertexBuilder)vertexConsumer;
        int packedColor = ColorUtil.RGBA.pack(r, g, b, this.alpha);

        vertexBuilder.vertex(vector3fs[0].x(), vector3fs[0].y(), vector3fs[0].z(), u1, v1, packedColor, light);
        vertexBuilder.vertex(vector3fs[1].x(), vector3fs[1].y(), vector3fs[1].z(), u1, v0, packedColor, light);
        vertexBuilder.vertex(vector3fs[2].x(), vector3fs[2].y(), vector3fs[2].z(), u0, v0, packedColor, light);
        vertexBuilder.vertex(vector3fs[3].x(), vector3fs[3].y(), vector3fs[3].z(), u0, v1, packedColor, light);
    }

    protected int getLightColor(float f) {
        BlockPos blockPos = BlockPos.containing(this.x, this.y, this.z);
        return this.level.hasChunkAt(blockPos) ? LevelRenderer.getLightColor(this.level, blockPos) : 0;
    }

    private boolean cull(WorldRenderer worldRenderer, float x, float y, float z) {
        RenderSection section = worldRenderer.getSectionGrid().getSectionAtBlockPos((int) x, (int) y, (int) z);
        return section != null && section.getLastFrame() != worldRenderer.getLastFrame();
    }

    @Override
    public ParticleRenderType getRenderType() {
        return null;
    }
}

