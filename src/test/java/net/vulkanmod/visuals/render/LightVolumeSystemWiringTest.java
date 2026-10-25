package net.vulkanmod.visuals.render;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** P7l design 3.1, 3.7: how the light volume is filled. */
class LightVolumeSystemWiringTest {
    private static List<String> calls(String className, String method) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = LightVolumeSystemWiringTest.class.getClassLoader().getResourceAsStream(className + ".class")) {
            assertNotNull(in, className);
            new ClassReader(in).accept(node, 0);
        }
        List<String> calls = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (!m.name.equals(method)) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
            }
        }
        return calls;
    }

    @Test
    void updateDrainsCentresReadsBothLayersAndUploads() throws Exception {
        List<String> c = calls("net/vulkanmod/visuals/render/LightVolumeSystem", "update");
        int drain = c.indexOf("LightUpdates.drainInto"), centre = c.indexOf("LightSections.centre"), next = c.indexOf("LightSections.next");
        int pack = c.indexOf("LightPacking.pack"), upload = c.indexOf("VkLightVolume.upload"), uploaded = c.indexOf("LightSections.uploaded");
        assertTrue(drain >= 0 && drain < centre && centre < next && next < pack && pack < upload && upload < uploaded, c.toString());
        assertTrue(c.contains("LayerLightEventListener.getDataLayerData") && c.contains("ClientChunkCache.hasChunk"), c.toString());
        assertTrue(c.contains("LightVolumeMath.layout") && c.contains("LightUpdates.collect"), c.toString());
    }

    /** Final review: a layout over maxStorageBufferRange is refused before the buffer is allocated. */
    @Test
    void updateChecksTheStorageBufferRangeBeforeAllocating() throws Exception {
        List<String> c = calls("net/vulkanmod/visuals/render/LightVolumeSystem", "update");
        int range = c.indexOf("VkContext.maxStorageBufferRange"), fits = c.indexOf("LightVolumeMath.fitsStorageRange");
        assertTrue(range >= 0 && range < fits && fits < c.indexOf("VisualsLog.warn"), c.toString());
    }

    @Test
    void aMissingSkyLayerIsReadPerColumn() throws Exception {
        assertTrue(calls("net/vulkanmod/visuals/render/LightVolumeSystem", "fillColumn").contains("LayerLightEventListener.getLightValue"));
    }

    @Test
    void theVolumeClearsItsReadyTableAndUploadsKeyAfterData() throws Exception {
        List<String> clear = calls("net/vulkanmod/visuals/render/VkLightVolume", "clearIfNeeded");
        assertTrue(clear.contains("VK10.vkCmdFillBuffer") && clear.contains("VkFrames.isDropped") && clear.contains("VK10.vkCmdPipelineBarrier"), clear.toString());
        List<String> upload = calls("net/vulkanmod/visuals/render/VkLightVolume", "upload");
        assertEquals(2, upload.stream().filter("VkUploadStream.upload"::equals).count(), upload.toString());
    }

    /** A clear after a dropped frame wipes keys the CPU counts as held: they are forgotten and uploaded again. */
    @Test
    void aReclearAfterADropForgetsTheHeldSections() throws Exception {
        List<String> c = calls("net/vulkanmod/visuals/render/LightVolumeSystem", "recordClear");
        int clear = c.indexOf("VkLightVolume.clearIfNeeded"), forget = c.indexOf("LightSections.forgetAll");
        assertTrue(clear >= 0 && clear < forget, c.toString());
    }
}
