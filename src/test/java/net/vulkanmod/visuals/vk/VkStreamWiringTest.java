package net.vulkanmod.visuals.vk;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Copies must be ordered against shader access with barriers on both sides; this pins the calls in commit(). */
class VkStreamWiringTest {
    private static List<String> callsIn(String clazz, String method) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = VkStreamWiringTest.class.getClassLoader().getResourceAsStream(clazz + ".class")) {
            assertNotNull(in, clazz);
            new ClassReader(in).accept(node, 0);
        }
        List<String> calls = new ArrayList<>();
        for (MethodNode m : node.methods) {
            if (!m.name.equals(method)) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.name);
            }
        }
        return calls;
    }

    @Test
    void uploadCommitCopiesThenBarriers() throws Exception {
        List<String> calls = callsIn("net/vulkanmod/visuals/vk/VkUploadStream", "commit");
        assertTrue(calls.contains("vkCmdCopyBuffer"), calls.toString());
        assertTrue(calls.lastIndexOf("vkCmdPipelineBarrier") > calls.indexOf("vkCmdCopyBuffer"), calls.toString());
    }

    /** Review I3: an upload into a live buffer must wait for earlier reads/writes of it (write-after-read/write). */
    @Test
    void uploadCommitBarriersBeforeTheCopyToo() throws Exception {
        List<String> calls = callsIn("net/vulkanmod/visuals/vk/VkUploadStream", "commit");
        int copy = calls.indexOf("vkCmdCopyBuffer");
        assertTrue(copy > 0 && calls.indexOf("vkCmdPipelineBarrier") < copy, calls.toString());
    }

    @Test
    void imageUploadsTransitionAroundTheCopies() throws Exception {
        List<String> calls = callsIn("net/vulkanmod/visuals/vk/VkUploadStream", "commit");
        int copy = calls.indexOf("vkCmdCopyBufferToImage");
        assertTrue(copy > 0 && calls.indexOf("transition") < copy && calls.lastIndexOf("transition") > copy, calls.toString());
    }

    @Test
    void downloadCommitBarriersAroundTheCopy() throws Exception {
        List<String> calls = callsIn("net/vulkanmod/visuals/vk/VkDownloadStream", "commit");
        int copy = calls.indexOf("vkCmdCopyBuffer");
        assertTrue(copy > 0 && calls.indexOf("vkCmdPipelineBarrier") < copy && calls.lastIndexOf("vkCmdPipelineBarrier") > copy, calls.toString());
    }
}
