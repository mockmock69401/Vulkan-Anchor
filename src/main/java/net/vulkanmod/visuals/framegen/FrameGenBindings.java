package net.vulkanmod.visuals.framegen;

/**
 * The GL binding numbers of the frame generation shaders (shaders/fg, FG design 5), as VkBindings takes them (the set follows the
 * resource kind). Within one program every resource has its own number; the FgParams block is at one number in all of them.
 */
public final class FrameGenBindings {
    /** The std140 block FgParams (params.glsl, {@link FrameGenParams}), in every fg program. */
    public static final int PARAMS = 8;

    /** fgCapture: the world image (rgba16f), the main colour, the main depth and the LOD depth. */
    public static final int CAPTURE_OUT = 0, CAPTURE_COLOUR = 1, CAPTURE_MAIN_DEPTH = 2, CAPTURE_LOD_DEPTH = 3;
    /** fgSplat: the splat depth image (r32ui) and the world image. */
    public static final int SPLAT_OUT = 0, SPLAT_WORLD = 1;
    /** fgResolve: the resolved image, the splat image (readonly), the world image sampled filtered and sampled nearest. */
    public static final int RESOLVE_OUT = 0, RESOLVE_SPLAT = 1, RESOLVE_WORLD_LINEAR = 2, RESOLVE_WORLD_NEAREST = 3;
    /** fgFill: the filled image, the resolved image (readonly image) and the splat image (readonly). */
    public static final int FILL_OUT = 0, FILL_RESOLVED = 1, FILL_SPLAT = 2;
    /** fgCompose: the filled world, the UI layer, the vignette texture and the crosshair icons texture (all samplers). */
    public static final int COMPOSE_WORLD = 0, COMPOSE_UI = 1, COMPOSE_VIGNETTE = 2, COMPOSE_ICONS = 3;

    private FrameGenBindings() {
    }
}
