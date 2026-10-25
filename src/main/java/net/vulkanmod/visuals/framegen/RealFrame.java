package net.vulkanmod.visuals.framegen;

import net.vulkanmod.visuals.framegen.CameraPrediction.CameraSample;
import net.vulkanmod.visuals.render.TaaMath;

/**
 * A real frame the render thread hands to the pacer (FG design 3.1 point 1): renderValue is the render timeline value its submit
 * signals (the pacer's reads wait on it), slot the capture slot holding its world and UI images, camera its unjittered camera (null when
 * none was recorded), sample the camera sample for prediction (null likewise), handNanos when it was handed, inputNanos when its input
 * was sampled, and generate false in menus and paused frames (the pacer then presents it alone).
 */
public record RealFrame(long renderValue, int slot, TaaMath.Camera camera, CameraSample sample, long handNanos, long inputNanos,
                        boolean generate) {
}
