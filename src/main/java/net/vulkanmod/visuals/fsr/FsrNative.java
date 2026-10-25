package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.VisualsLog;
import org.lwjgl.system.Library;
import org.lwjgl.system.SharedLibrary;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/** FSR design 4.1: the bundled AMD FidelityFX Vulkan DLL, its extraction into the cache and its entry points. */
public final class FsrNative {
    /** SHA-256 of the vendored amd_fidelityfx_vk.dll (FidelityFX SDK v1.1.4). */
    public static final String SHA256 = "a1624cc4238fef046f30c4d80ce3f47be63fc5f5373f49e3ee9edb9960f54c78";

    static final String DLL_NAME = "amd_fidelityfx_vk.dll";
    static final String RESOURCE = "/natives/windows-x64/" + DLL_NAME;

    private static volatile FsrNative instance;

    private final SharedLibrary library;

    private FsrNative(SharedLibrary library) {
        this.library = library;
    }

    /** The DLL loaded by {@link FsrSupport#probe}, empty when it is not loaded. */
    public static Optional<FsrNative> instance() {
        return Optional.ofNullable(instance);
    }

    /** Extracts the bundled DLL into the cache directory and loads it as the process-wide instance. */
    static FsrNative loadBundled(Path cacheDir) throws IOException {
        Path dll = extract(cacheDir, () -> FsrNative.class.getResourceAsStream(RESOURCE));
        FsrNative loaded = load(dll);
        instance = loaded;
        return loaded;
    }

    /** Writes amd_fidelityfx_vk.dll into cacheDir when it is missing or its SHA-256 differs; returns its path. */
    static Path extract(Path cacheDir, Supplier<InputStream> dll) throws IOException {
        Files.createDirectories(cacheDir);
        Path target = cacheDir.resolve(DLL_NAME);
        if (Files.isRegularFile(target) && SHA256.equals(sha256(Files.readAllBytes(target)))) {
            return target;
        }
        Path tmp = Files.createTempFile(cacheDir, DLL_NAME, ".tmp");
        try (InputStream in = dll.get()) {
            if (in == null) {
                throw new IOException("bundled " + DLL_NAME + " is missing");
            }
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            if (!SHA256.equals(sha256(Files.readAllBytes(tmp)))) {
                throw new IOException("bundled " + DLL_NAME + " does not match its recorded SHA-256");
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        VisualsLog.info("Visuals FSR: extracted " + target);
        return target;
    }

    /** Loads the DLL at an absolute path; throws when it cannot be loaded or lacks one of the five entry points. */
    static FsrNative load(Path dll) {
        FsrNative n = new FsrNative(Library.loadNative(FsrNative.class, "org.lwjgl", dll.toAbsolutePath().toString()));
        for (String export : new String[]{"ffxCreateContext", "ffxDestroyContext", "ffxConfigure", "ffxQuery", "ffxDispatch"}) {
            n.fn(export);
        }
        return n;
    }

    /** The loaded module's address (its HMODULE, the image base on Windows). */
    public long imageBase() {
        return library.address();
    }

    /** Address of an exported function; throws when the DLL has no such export. */
    public long fn(String export) {
        long address = library.getFunctionAddress(export);
        if (address == 0L) {
            throw new IllegalStateException(DLL_NAME + " has no export " + export);
        }
        return address;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
