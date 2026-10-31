package net.vulkanmod.vulkan.shader;

import net.vulkanmod.visuals.Bytecode;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.ShadercIncludeResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10-31 (the user: Tensura (Vulkan) crashed on a language change): shaderc's release callback read the include result's name
 * and content with LWJGL's getters, which scan for a terminating 0 (memByteBufferNT1); the content is not terminated, so the scan ran
 * past it and now and then into an unmapped page (EXCEPTION_ACCESS_VIOLATION in strlen64NT1 while shaders were rebuilt).
 */
class IncludeReleaseTest {
    @Test
    void theReleaseFreesWhatTheResultHoldsWithoutReadingIt() {
        byte[] content = new byte[64];
        java.util.Arrays.fill(content, (byte) 'a');
        long result = SPIRVUtils.includeResult("light.glsl", content, 7);
        long name = MemoryUtil.memGetAddress(result + ShadercIncludeResult.SOURCE_NAME);
        long text = MemoryUtil.memGetAddress(result + ShadercIncludeResult.CONTENT);
        assertEquals(content.length, MemoryUtil.memGetAddress(result + ShadercIncludeResult.CONTENT_LENGTH));
        List<Long> freed = new ArrayList<>();
        SPIRVUtils.releaseIncludeResult(result, freed::add);
        assertEquals(List.of(name, text, result), freed);
        for (long address : freed) MemoryUtil.nmemFree(address);
    }

    @Test
    void aNullResultFreesNothing() {
        List<Long> freed = new ArrayList<>();
        SPIRVUtils.releaseIncludeResult(MemoryUtil.NULL, freed::add);
        assertTrue(freed.isEmpty());
    }

    /** No getter that scans for a terminator (source_name(), content() and their static forms) in the release callback. */
    @Test
    void theCallbackUsesNoTerminatorScanningGetter() throws Exception {
        List<String> calls = Bytecode.calls("net/vulkanmod/vulkan/shader/SPIRVUtils$ShaderReleaser", "invoke");
        for (String getter : List.of("ShadercIncludeResult.source_name", "ShadercIncludeResult.content",
                "ShadercIncludeResult.nsource_name", "ShadercIncludeResult.ncontent")) {
            assertFalse(calls.contains(getter), getter + " in " + calls);
        }
        assertTrue(calls.contains("SPIRVUtils.releaseIncludeResult"), calls.toString());
    }
}
