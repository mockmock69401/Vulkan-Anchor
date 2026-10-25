package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.dev.FrameRecorder;
import net.vulkanmod.visuals.vk.DeferredFree;
import net.vulkanmod.visuals.vk.VkContext;
import net.vulkanmod.visuals.vk.VkFrames;
import net.vulkanmod.visuals.vk.VkUploadStream;
import net.vulkanmod.visuals.VisualsLog;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

/** P7l design 3.1, 3.7: keeps the light volume around the camera filled from the client's light engine (render thread). */
public final class LightVolumeSystem {
    private final DeferredFree deferred;
    private final LightSections sections = new LightSections();
    private final ByteBuffer bytes = MemoryUtil.memAlloc(LightVolumeMath.SECTION_BYTES);
    private final int[] column = new int[256];
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private VkLightVolume volume;
    private LightVolumeMath.Layout layout;
    private ClientLevel level;
    private int frames, uploaded;

    public LightVolumeSystem(DeferredFree deferred) {
        this.deferred = deferred;
    }

    /**
     * postProcess, before the post-process prepares (CPU only): the volume for this level and render distance, made when
     * wanted (once per layout if it fails), with up to SECTION_BUDGET sections queued for upload. null when off or failed.
     */
    public VkLightVolume update(VkUploadStream upload, ClientLevel level, double x, double y, double z, int renderDistance, boolean wanted) {
        if (!wanted || level == null) {
            this.drop();
            return null;
        }
        LightVolumeMath.Layout want = LightVolumeMath.layout(renderDistance, level.getMinSection(), level.getSectionsCount());
        if (level != this.level || !want.equals(this.layout)) {
            this.drop();
            this.level = level;
            this.layout = want;
            //P7l design 3.7: the data buffer is bound whole, so past maxStorageBufferRange it counts as an allocation failure
            if (!LightVolumeMath.fitsStorageRange(want, VkContext.maxStorageBufferRange())) {
                VisualsLog.warn("Visuals light volume (" + (want.bytes() >> 20) + " MiB) exceeds the device's maxStorageBufferRange ("
                        + (VkContext.maxStorageBufferRange() >> 20) + " MiB): the render distance is too large for per-pixel light, which stays off until the render distance or the world changes");
                return null;
            }
            try {
                this.volume = new VkLightVolume(want);
            } catch (RuntimeException e) {
                VisualsLog.warn("Visuals light volume (" + (want.bytes() >> 20) + " MiB) could not be made; per-pixel light stays off until the render distance or the world changes", e);
                return null;
            }
            this.sections.reset(want);
            LightUpdates.collect(true);
            LightUpdates.holdForMeshes(true);//2026-10-15: Reforged reports its mesh uploads (the TaskDispatcher source hook)
        }
        if (this.volume == null) return null;
        LightUpdates.drainInto(this.sections);
        ClientChunkCache chunks = level.getChunkSource();
        this.sections.centre(SectionPos.blockToSectionCoord(Mth.floor(x)), SectionPos.blockToSectionCoord(Mth.floor(y)),
                SectionPos.blockToSectionCoord(Mth.floor(z)), ++this.frames % LightVolumeMath.RESCAN_FRAMES == 0, chunks::hasChunk);
        LevelLightEngine engine = level.getLightEngine();
        LayerLightEventListener skyLayer = engine.getLayerListener(LightLayer.SKY), blockLayer = engine.getLayerListener(LightLayer.BLOCK);
        for (int[] s : this.sections.next(LightVolumeMath.SECTION_BUDGET)) {
            if (!chunks.hasChunk(s[0], s[2])) continue;
            SectionPos pos = SectionPos.of(s[0], s[1], s[2]);
            DataLayer sky = skyLayer.getDataLayerData(pos), block = blockLayer.getDataLayerData(pos);
            if (sky == null) this.fillColumn(skyLayer, pos);
            LightPacking.pack(sky == null ? null : sky.getData(), block == null ? null : block.getData(), (cx, cz) -> this.column[cz << 4 | cx], this.bytes);
            this.volume.upload(upload, s[0], s[1], s[2], this.bytes);
            this.sections.uploaded(s[0], s[1], s[2]);
            this.uploaded++;
            if (FrameRecorder.ON) FrameRecorder.add(FrameRecorder.LIGHT_SECTIONS, 1);
        }
        return this.volume;
    }

    /** A section without sky data has one value per column: the light engine's at its top (it looks up to the section above). */
    private void fillColumn(LayerLightEventListener skyLayer, SectionPos pos) {
        for (int cz = 0; cz < 16; cz++) {
            for (int cx = 0; cx < 16; cx++) {
                this.column[cz << 4 | cx] = skyLayer.getLightValue(this.cursor.set(pos.minBlockX() + cx, pos.maxBlockY(), pos.minBlockZ() + cz));
            }
        }
    }

    /** Outside a render pass, before the upload commit. */
    public void recordClear(VkCommandBuffer cmd) {
        if (this.volume != null && this.volume.clearIfNeeded(cmd)) this.sections.forgetAll();//the cleared keys are uploaded again
    }

    /** The 5-second log line, null without a volume. */
    public String stats() {
        if (this.volume == null) return null;
        String line = "Visuals light volume: ready " + this.sections.ready() + "/" + this.sections.slots() + " sections, uploaded "
                + this.uploaded + ", queued " + this.sections.queued();
        this.uploaded = 0;
        return line;
    }

    private void drop() {
        if (this.volume != null) {
            VkLightVolume old = this.volume;
            this.deferred.retire(old::free, VkFrames.serial());
        }
        this.volume = null;
        this.level = null;
        this.layout = null;
        LightUpdates.collect(false);
    }

    /** Only once no submitted frame can still use it. */
    public void free() {
        if (this.volume != null) this.volume.free();
        this.volume = null;
        LightUpdates.collect(false);
        MemoryUtil.memFree(this.bytes);
    }
}
