package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.fsr.FsrLifecycle.Action;
import net.vulkanmod.visuals.fsr.FsrLifecycle.State;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** FSR design 4.4: when the FSR swapchain starts, stops or remakes its frame-generation context, and that it waits idle first. */
class FsrLifecycleTest {
    /** Records the bridge calls in order; start or recreate throw when asked to. */
    private static final class Recorder implements FsrLifecycle.Bridge {
        final List<String> calls = new ArrayList<>();
        RuntimeException startFails;
        RuntimeException waitFails;

        @Override public void waitIdle() {
            calls.add("waitIdle");
            if (waitFails != null) throw waitFails;
        }
        @Override public void start() {
            calls.add("start");
            if (startFails != null) throw startFails;
        }
        @Override public void stop() { calls.add("stop"); }
        @Override public void recreateFrameGenerationContext() { calls.add("recreateFg"); }
        @Override public void failed(String what, Throwable t) { calls.add("failed " + what); }
    }

    @Test void togglesWithFramesInFlightWaitBeforeSwapping() {
        assertEquals(Action.START, FsrLifecycle.next(State.OFF, true, 2560, 1440, false));
        assertEquals(Action.STOP, FsrLifecycle.next(State.LIVE, false, 2560, 1440, false));
        assertEquals(Action.NONE, FsrLifecycle.next(State.OFF, false, 2560, 1440, false));
        assertEquals(Action.NONE, FsrLifecycle.next(State.LIVE, true, 2560, 1440, false));

        Recorder on = new Recorder();
        assertEquals(State.LIVE, FsrLifecycle.run(State.OFF, Action.START, on));
        assertEquals(List.of("waitIdle", "start"), on.calls);

        Recorder off = new Recorder();
        assertEquals(State.OFF, FsrLifecycle.run(State.LIVE, Action.STOP, off));
        assertEquals(List.of("waitIdle", "stop"), off.calls);

        Recorder none = new Recorder();
        assertEquals(State.LIVE, FsrLifecycle.run(State.LIVE, Action.NONE, none));
        assertEquals(List.of(), none.calls);
    }

    @Test void minimisedWindowKeepsContextsUntilItHasASize() {
        assertEquals(Action.NONE, FsrLifecycle.next(State.LIVE, true, 0, 0, true));
        assertEquals(Action.RECREATE_FG_CONTEXT, FsrLifecycle.next(State.LIVE, true, 1920, 1080, true));
        //nothing to replace without a swapchain: FSR starts once the window has a size again
        assertEquals(Action.NONE, FsrLifecycle.next(State.OFF, true, 0, 0, false));

        Recorder r = new Recorder();
        assertEquals(State.LIVE, FsrLifecycle.run(State.LIVE, Action.RECREATE_FG_CONTEXT, r));
        assertEquals(List.of("waitIdle", "recreateFg"), r.calls);
    }

    @Test void failedStaysFailed() {
        assertEquals(Action.NONE, FsrLifecycle.next(State.FAILED, true, 2560, 1440, false));
        assertEquals(Action.NONE, FsrLifecycle.next(State.FAILED, true, 2560, 1440, true));
        assertEquals(Action.NONE, FsrLifecycle.next(State.FAILED, false, 2560, 1440, false));

        //a failed start reports, tears down what it made and leaves FSR failed
        Recorder r = new Recorder();
        r.startFails = new FsrException("ffxCreateContext failed");
        assertEquals(State.FAILED, FsrLifecycle.run(State.OFF, Action.START, r));
        assertEquals(List.of("waitIdle", "start", "failed start", "stop"), r.calls);
    }

    @Test void failedWaitStillTearsDownAndNeverEscapes() {
        //FSR's wait for presents failing must not leave the game without a swapchain or throw out of the recreation
        Recorder off = new Recorder();
        off.waitFails = new FsrException("ffxDispatch (wait for presents) failed");
        assertEquals(State.FAILED, FsrLifecycle.run(State.LIVE, Action.STOP, off));
        assertEquals(List.of("waitIdle", "failed stop", "stop"), off.calls);

        Recorder on = new Recorder();
        on.waitFails = new IllegalStateException("no export");
        assertEquals(State.FAILED, FsrLifecycle.run(State.OFF, Action.START, on));
        assertEquals(List.of("waitIdle", "failed start", "stop"), on.calls);
    }
}
