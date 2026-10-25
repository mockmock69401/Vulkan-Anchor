package net.vulkanmod.visuals.render;

/** P7h design 3.3: vanilla's cloud layer this frame: the dimension's cloud height, the camera on the cloud map (CloudMath.offset), getCloudColor. */
public record CloudFrame(float height, float offsetX, float offsetZ, float[] colour) {
}
