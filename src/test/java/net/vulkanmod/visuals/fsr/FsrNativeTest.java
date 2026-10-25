package net.vulkanmod.visuals.fsr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** FSR design 4.1: the bundled DLL, its extraction and its recorded hash. */
class FsrNativeTest {
    private static final String RESOURCE = "/natives/windows-x64/amd_fidelityfx_vk.dll";

    @TempDir Path dir;

    private static InputStream bundled() {
        return FsrNativeTest.class.getResourceAsStream(RESOURCE);
    }

    private static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    @Test void extractWritesTheDllOnceAndKeepsAMatchingCopy() throws Exception {
        Path cache = dir.resolve("natives");
        Path first = FsrNative.extract(cache, FsrNativeTest::bundled);
        assertEquals(cache.resolve("amd_fidelityfx_vk.dll"), first);
        assertEquals(FsrNative.SHA256, sha256(first));
        FileTime stamp = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(first, stamp);
        FsrNative.extract(cache, FsrNativeTest::bundled);
        assertEquals(stamp, Files.getLastModifiedTime(first));
    }

    @Test void wrongHashIsReplaced() throws Exception {
        Path target = dir.resolve("amd_fidelityfx_vk.dll");
        Files.write(target, new byte[]{1, 2, 3});
        FsrNative.extract(dir, FsrNativeTest::bundled);
        assertEquals(FsrNative.SHA256, sha256(target));
    }

    @Test void unwritableCacheGivesReason() throws Exception {
        Path file = dir.resolve("notADirectory");
        Files.write(file, new byte[]{0});
        assertThrows(IOException.class, () -> FsrNative.extract(file, () -> new ByteArrayInputStream(new byte[]{1})));
    }

    @Test void bundledResourceMatchesTheRecordedHash() throws Exception {
        try (InputStream in = bundled()) {
            assertNotNull(in);
            byte[] bytes = in.readAllBytes();
            assertEquals(9332432, bytes.length);
            assertEquals(FsrNative.SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
    }

    @Test void extractedDllLoadsAndExportsTheFiveEntryPoints() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"));
        //a loaded DLL stays locked by Windows, so it cannot live in the JUnit temp dir that is deleted afterwards
        FsrNative n = FsrNative.load(FsrNative.extract(Path.of("build", "fsr-test-natives"), FsrNativeTest::bundled));
        for (String export : new String[]{"ffxCreateContext", "ffxDestroyContext", "ffxConfigure", "ffxQuery", "ffxDispatch"}) {
            assertNotEquals(0L, n.fn(export), export);
        }
        assertThrows(IllegalStateException.class, () -> n.fn("ffxNoSuchExport"));
    }
}
