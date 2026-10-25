package net.vulkanmod.visuals.fsr;

/**
 * FSR design 4.4: when the FSR swapchain starts, stops or remakes its frame-generation context. Pure: the decision is
 * {@link #next}, and {@link #run} carries one action out through a {@link Bridge}, always after a wait-idle so no frame in
 * flight still uses the swapchain being replaced.
 */
public final class FsrLifecycle {
    public enum State {OFF, LIVE, FAILED}

    public enum Action {NONE, START, STOP, RECREATE_FG_CONTEXT}

    /** What {@link #run} drives; implemented by FsrSwapchain, and by a recording fake in tests. */
    public interface Bridge {
        void waitIdle();

        /** Creates the swapchain and frame-generation contexts; throws on any failure. */
        void start();

        /** Tears down whatever start made, in order; best effort, also after a partial start. */
        void stop();

        /** Replaces the frame-generation context with one at the swapchain's current size; throws on failure. */
        void recreateFrameGenerationContext();

        /** Records the failure (FsrSupport.markFailed). */
        void failed(String what, Throwable t);
    }

    private FsrLifecycle() {
    }

    /**
     * The action for this swapchain recreation. A failed FSR stays failed. Without a swapchain (a minimised window, 0x0) FSR
     * neither starts nor remakes its context: it keeps what it has until the window has a size again.
     */
    public static Action next(State s, boolean wanted, int width, int height, boolean sizeChanged) {
        boolean hasSize = width > 0 && height > 0;
        return switch (s) {
            case FAILED -> Action.NONE;
            case OFF -> wanted && hasSize ? Action.START : Action.NONE;
            case LIVE -> {
                if (!wanted) yield Action.STOP;
                yield hasSize && sizeChanged ? Action.RECREATE_FG_CONTEXT : Action.NONE;
            }
        };
    }

    /**
     * Carries out a, waiting idle first, and returns the new state. Nothing escapes: a wait, start or context recreation that
     * throws is reported, torn down with {@link Bridge#stop} (unless stop itself was what threw) and leaves FSR
     * {@link State#FAILED}.
     */
    public static State run(State s, Action a, Bridge b) {
        if (a == Action.NONE) return s;
        String what = a == Action.START ? "start" : a == Action.STOP ? "stop" : "frame generation context recreation";
        boolean stopCalled = false;
        try {
            b.waitIdle();
            switch (a) {
                case START -> b.start();
                case STOP -> {
                    stopCalled = true;
                    b.stop();
                    return State.OFF;
                }
                case RECREATE_FG_CONTEXT -> b.recreateFrameGenerationContext();
                default -> throw new IllegalStateException(a.name());
            }
            return State.LIVE;
        } catch (RuntimeException | LinkageError e) {
            b.failed(what, e);
            if (!stopCalled) {
                try {
                    b.stop();
                } catch (RuntimeException | LinkageError ignored) {
                    //the failure is already reported; what is left cannot be torn down further
                }
            }
            return State.FAILED;
        }
    }
}
