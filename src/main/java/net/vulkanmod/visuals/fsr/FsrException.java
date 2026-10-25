package net.vulkanmod.visuals.fsr;

/** FSR design 4.3: a failed ffx call or FSR-replaced Vulkan call; whoever catches it turns FSR off for the session. */
public class FsrException extends RuntimeException {
    public FsrException(String message) {
        super(message);
    }
}
