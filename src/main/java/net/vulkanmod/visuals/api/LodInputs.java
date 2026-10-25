package net.vulkanmod.visuals.api;

import net.vulkanmod.visuals.vk.VkImage2D;
import org.joml.Matrix4fc;

/**
 * The LOD inputs of one frame for post: the LOD depth and its view, the opaque band view, the light view, and the projection.
 * The light view has VkPostProcess.LOD_LIGHT_FORMAT (R16_UINT): the light in the low byte (sky in the low nibble, block in the high)
 * and the pixel's face in the high byte (0 for none, else DH's face index plus one: down, up, north, south, west, east), which the
 * lighting uses as the LOD pixel's normal.
 * {@code lodDistanceSections} is the extension's LOD render distance in LOD sections of 512 blocks (the unit the far clouds'
 * reach and the light shafts' spread are tuned in); the visuals use their default reach when no extension supplies inputs.
 */
public record LodInputs(VkImage2D depth, long depthView, long opaqueBandView, long lightView, Matrix4fc projection, boolean sizeMatches,
                        float lodDistanceSections) {
}
