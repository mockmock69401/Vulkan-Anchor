package net.vulkanmod.visuals.fsr;

import net.vulkanmod.visuals.render.TaaMath;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.system.MemoryUtil.*;

/** FSR design 4.5 steps 4 and 5: what FSR is told every frame (configure, prepare, generation) and the camera it gets. */
class FsrFrameTest {
    //ffx descriptor types (ffx_framegeneration.h, ffx_api_vk.h)
    private static final long CONFIGURE = 0x20002L, GENERATE = 0x20003L, PREPARE = 0x20004L, CAMERA_INFO = 0x2000aL;
    private static final long QUERY_COMMAND_LIST = 0x40003L, QUERY_TEXTURE = 0x40004L;
    private static final long FG_CONTEXT = 0x1000L, SC_CONTEXT = 0x2000L, INTERPOLATION_CMD = 0xC0DEL, INTERPOLATION_IMAGE = 0x1EAFL;

    /** One recorded ffx call: the descriptor type and the fields the tests look at. */
    record Call(String fn, long context, long type, long frameId, boolean enabled, boolean reset, long chainedType, float frameTimeMs) {
        Call(String fn, long context, long type, long frameId, boolean enabled, boolean reset, long chainedType) {
            this(fn, context, type, frameId, enabled, reset, chainedType, 0f);
        }
    }

    /** An FfxApi that never reaches a DLL: it records what it is given and answers FSR's two queries with fake handles. */
    private static class Recorder extends FfxApi {
        final List<Call> calls = new ArrayList<>();

        Recorder() {
            super(0L, 0L, 0L, 0L, 0L);
        }

        @Override
        public int configure(long pContext, long desc) {
            calls.add(new Call("configure", pContext, memGetLong(desc),
                    memGetLong(desc + FfxStructs.OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_FRAME_ID),
                    memGetByte(desc + FfxStructs.OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_FRAME_GENERATION_ENABLED) != 0, false, 0L));
            return FfxApi.RETURN_OK;
        }

        @Override
        public int query(long pContext, long desc) {
            long type = memGetLong(desc);
            long out = memGetAddress(desc + 16);
            if (type == QUERY_COMMAND_LIST) memPutAddress(out, INTERPOLATION_CMD);
            if (type == QUERY_TEXTURE) memPutAddress(out + FfxStructs.OFF_FFX_API_RESOURCE_RESOURCE, INTERPOLATION_IMAGE);
            calls.add(new Call("query", pContext, type, -1, false, false, 0L));
            return FfxApi.RETURN_OK;
        }

        @Override
        public int dispatch(long pContext, long desc) {
            long type = memGetLong(desc);
            if (type == PREPARE) {
                long next = memGetAddress(desc + 8);
                calls.add(new Call("dispatch", pContext, type, memGetLong(desc + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_FRAME_ID),
                        false, false, next == NULL ? 0L : memGetLong(next),
                        memGetFloat(desc + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_FRAME_TIME_DELTA)));
            } else if (type == GENERATE) {
                assertEquals(INTERPOLATION_CMD, memGetAddress(desc + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_COMMAND_LIST));
                assertEquals(INTERPOLATION_IMAGE, memGetAddress(desc + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_OUTPUTS));
                calls.add(new Call("dispatch", pContext, type, memGetLong(desc + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_FRAME_ID),
                        false, memGetByte(desc + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_RESET) != 0, 0L));
            } else {
                calls.add(new Call("dispatch", pContext, type, -1, false, false, 0L));
            }
            return FfxApi.RETURN_OK;
        }

        List<Call> of(long type) {
            return calls.stream().filter(c -> c.type() == type).toList();
        }
    }

    private static final TaaMath.Camera CAMERA = new TaaMath.Camera(new Matrix4f().perspective((float) Math.toRadians(70), 16 / 9f, 0.05f, 1000f, true),
            new Matrix4f().rotateX(0.3f).rotateY(2.0f), 10, 70, -20);

    /** A frame of a 1920x1080 display: captured or not, with a pause reason (null = generate) and a camera cut. */
    private static FsrFrame.Frame frame(boolean captured, String pauseReason, boolean cut, long nowNs) {
        return frame(captured, pauseReason, cut, nowNs, 1L);
    }

    private static FsrFrame.Frame frame(boolean captured, String pauseReason, boolean cut, long nowNs, long contextSerial) {
        return new FsrFrame.Frame(0x5CL, FG_CONTEXT, SC_CONTEXT, contextSerial, 1920, 1080, 0x1A6EL, FfxStructs.FORMAT_R8G8B8A8_UNORM,
                captured, pauseReason, cut, captured ? 0x40DL : 0L, 1920, 1080, 0xDE97L, 0x307L, 1920, 1080,
                captured ? CAMERA : null, nowNs);
    }

    private static void run(FsrFrame.Sequencer s, Recorder api, FsrFrame.Frame f) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            s.run(stack, api, 0xCB1L, f);
        }
    }

    @Test void frameIdIncreasesByOneEveryFrame() {
        FsrFrame.Sequencer s = new FsrFrame.Sequencer(1);
        Recorder api = new Recorder();
        FsrFrame.Frame[] frames = {
                frame(true, null, false, 0), frame(true, "screen", false, 16_000_000), frame(false, FsrCapture.NOT_CAPTURED, false, 32_000_000),
                frame(true, null, false, 48_000_000), frame(true, "paused", false, 64_000_000), frame(true, null, true, 80_000_000)
        };
        long first = -1;
        for (int i = 0; i < frames.length; i++) {
            run(s, api, frames[i]);
            if (i == 0) first = s.frameId();
            assertEquals(first + i, s.frameId(), "frame " + i);
        }
        //every frame configures once with its own id, paused or not captured included; prepare and generation use the same id
        List<Call> configures = api.of(CONFIGURE);
        assertEquals(frames.length, configures.size());
        for (int i = 0; i < configures.size(); i++) assertEquals(first + i, configures.get(i).frameId());
        for (Call c : api.of(PREPARE)) assertTrue(configures.stream().anyMatch(k -> k.frameId() == c.frameId()));
        for (Call c : api.of(GENERATE)) assertTrue(configures.stream().anyMatch(k -> k.frameId() == c.frameId()));
        //configure, prepare and generation go to the frame-generation context, the queries to the swapchain context
        for (Call c : api.calls) assertEquals(c.fn().equals("query") ? SC_CONTEXT : FG_CONTEXT, c.context(), c.toString());
    }

    @Test void pausedFramesDisableGeneration() {
        FsrFrame.Sequencer s = new FsrFrame.Sequencer(1);
        Recorder api = new Recorder();
        run(s, api, frame(true, "screen", false, 0));
        assertFalse(api.of(CONFIGURE).get(0).enabled());
        assertTrue(api.of(GENERATE).isEmpty(), api.calls.toString());
        assertTrue(api.of(QUERY_COMMAND_LIST).isEmpty(), "no interpolation command list is taken on a paused frame");
        //a captured paused frame still prepares (FSR keeps this frame's depth and motion as history), chained with the camera
        assertEquals(1, api.of(PREPARE).size());
        assertEquals(CAMERA_INFO, api.of(PREPARE).get(0).chainedType());

        api.calls.clear();
        run(s, api, frame(false, FsrCapture.NOT_CAPTURED, false, 16_000_000));
        assertFalse(api.of(CONFIGURE).get(0).enabled());
        assertTrue(api.of(PREPARE).isEmpty(), "nothing to prepare from without a capture");
        assertTrue(api.of(GENERATE).isEmpty());

        api.calls.clear();
        run(s, api, frame(true, null, false, 32_000_000));
        assertTrue(api.of(CONFIGURE).get(0).enabled());
        assertEquals(1, api.of(GENERATE).size());
        //configure, prepare, then the two queries and the generation
        assertEquals(List.of(CONFIGURE, PREPARE, QUERY_COMMAND_LIST, QUERY_TEXTURE, GENERATE), api.calls.stream().map(Call::type).toList());
    }

    @Test void generatedTellsWhetherThisFrameDispatchedGeneration() {
        //FsrSwapchain.beforePresent gates FSR's present only on frames whose interpolation FSR will submit
        FsrFrame.Sequencer s = new FsrFrame.Sequencer(1);
        Recorder api = new Recorder();
        assertFalse(s.generated(), "no frame yet");
        run(s, api, frame(true, null, false, 0));
        assertTrue(s.generated());
        run(s, api, frame(true, "screen", false, 16_000_000));
        assertFalse(s.generated());
        run(s, api, frame(false, FsrCapture.NOT_CAPTURED, false, 32_000_000));
        assertFalse(s.generated());
        run(s, api, frame(true, null, false, 48_000_000));
        assertTrue(s.generated());
    }

    @Test void cameraCutSetsResetOnce() {
        FsrFrame.Sequencer s = new FsrFrame.Sequencer(1);
        Recorder api = new Recorder();
        boolean[] cuts = {false, false, true, false, false};
        long now = 0;
        for (boolean cut : cuts) {
            run(s, api, frame(true, null, cut, now));
            now += 16_000_000;
        }
        List<Boolean> resets = api.of(GENERATE).stream().map(Call::reset).toList();
        //the first generated frame has no past to interpolate from; after it, only the cut frame resets
        assertEquals(List.of(true, false, true, false, false), resets);

        //generation that resumes after a frame without it starts over too (FSR's previous frame is older than the last frame)
        api.calls.clear();
        run(s, api, frame(true, "screen", false, now));
        run(s, api, frame(true, null, false, now + 16_000_000));
        run(s, api, frame(true, null, false, now + 32_000_000));
        assertEquals(List.of(true, false), api.of(GENERATE).stream().map(Call::reset).toList());

        //a remade frame-generation context (a resize, FSR off and on) has no history either
        api.calls.clear();
        run(s, api, frame(true, null, false, now + 48_000_000, 2L));
        run(s, api, frame(true, null, false, now + 64_000_000, 2L));
        assertEquals(List.of(true, false), api.of(GENERATE).stream().map(Call::reset).toList());
    }

    @Test void frameTimeIsThePreviousFrameDuration() {
        assertEquals(1000f / 60f, FsrFrame.Sequencer.frameTimeMs(-1L, 5_000_000L), 1e-4f);
        assertEquals(8.5f, FsrFrame.Sequencer.frameTimeMs(10_000_000L, 18_500_000L), 1e-4f);

        //prepare gets the previous frame's duration; a new context (FSR off and on) starts over instead of passing the time it was off
        FsrFrame.Sequencer s = new FsrFrame.Sequencer(1);
        Recorder api = new Recorder();
        run(s, api, frame(true, null, false, 0L, 1L));
        run(s, api, frame(true, null, false, 12_000_000L, 1L));
        run(s, api, frame(true, null, false, 5_012_000_000L, 2L));
        run(s, api, frame(true, null, false, 5_022_000_000L, 2L));
        List<Float> times = api.of(PREPARE).stream().map(Call::frameTimeMs).toList();
        assertEquals(4, times.size());
        assertEquals(1000f / 60f, times.get(0), 1e-3f);
        assertEquals(12f, times.get(1), 1e-3f);
        assertEquals(1000f / 60f, times.get(2), 1e-3f);
        assertEquals(10f, times.get(3), 1e-3f);
    }

    @Test void cameraVectorsAreOrthonormal() {
        float[][] still = FsrFrame.cameraVectors(new Matrix4f());
        assertArrayEquals(new float[]{0, 1, 0}, still[0], 1e-6f);
        assertArrayEquals(new float[]{1, 0, 0}, still[1], 1e-6f);
        assertArrayEquals(new float[]{0, 0, -1}, still[2], 1e-6f);

        for (float yaw = -3f; yaw <= 3f; yaw += 0.7f) {
            for (float pitch = -1.5f; pitch <= 1.5f; pitch += 0.5f) {
                //as Minecraft's view: pitch about x, then yaw (plus half a turn) about y
                float[][] v = FsrFrame.cameraVectors(new Matrix4f().rotateX(pitch).rotateY(yaw + (float) Math.PI));
                float[] up = v[0], right = v[1], forward = v[2];
                assertEquals(0f, dot(up, forward), 1e-5f);
                assertEquals(0f, dot(up, right), 1e-5f);
                assertEquals(0f, dot(right, forward), 1e-5f);
                assertEquals(1f, dot(up, up), 1e-5f);
                assertEquals(1f, dot(right, right), 1e-5f);
                assertEquals(1f, dot(forward, forward), 1e-5f);
                //looking down (positive pitch in Minecraft) points forward below the horizon
                assertEquals(-Math.sin(pitch), forward[1], 1e-5f);
            }
        }
    }

    @Test void pilotDebugFlagsReachConfigureAndPrepare() {
        FsrFrame.Sequencer s = new FsrFrame.Sequencer(1);
        List<int[]> seen = new ArrayList<>();//{type, flags, onlyPresentGenerated}
        Recorder api = new Recorder() {
            @Override
            public int configure(long pContext, long desc) {
                seen.add(new int[]{(int) memGetLong(desc), memGetInt(desc + FfxStructs.OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_FLAGS),
                        memGetByte(desc + FfxStructs.OFF_FFX_CONFIGURE_DESC_FRAME_GENERATION_ONLY_PRESENT_GENERATED)});
                return super.configure(pContext, desc);
            }

            @Override
            public int dispatch(long pContext, long desc) {
                if (memGetLong(desc) == PREPARE) seen.add(new int[]{(int) PREPARE, memGetInt(desc + FfxStructs.OFF_FFX_DISPATCH_DESC_FRAME_GENERATION_PREPARE_FLAGS), 0});
                return super.dispatch(pContext, desc);
            }
        };
        run(s, api, frame(true, null, false, 0));
        s.debugFlags = FfxStructs.FG_DRAW_DEBUG_VIEW | FfxStructs.FG_DRAW_DEBUG_PACING_LINES;
        s.onlyPresentGenerated = true;
        run(s, api, frame(true, null, false, 16_000_000));
        s.debugFlags = 0;
        s.onlyPresentGenerated = false;
        run(s, api, frame(true, null, false, 32_000_000));
        int debug = (1 << 2) | (1 << 4);
        int[][] expected = {{(int) CONFIGURE, 0, 0}, {(int) PREPARE, 0, 0}, {(int) CONFIGURE, debug, 1}, {(int) PREPARE, debug, 0},
                {(int) CONFIGURE, 0, 0}, {(int) PREPARE, 0, 0}};
        assertEquals(expected.length, seen.size());
        for (int i = 0; i < expected.length; i++) assertArrayEquals(expected[i], seen.get(i), "call " + i);
    }

    @Test void generationTurnsOnOnlyAfterEightWantingFramesAndOffAtOnce() {
        FsrFrame.Sequencer s = new FsrFrame.Sequencer();
        Recorder api = new Recorder();
        int[] reconfigures = {0};
        s.beforeReconfigure = () -> reconfigures[0]++;
        long now = 0;
        //the first seven frames that want generation present without it, the eighth turns it on
        for (int i = 0; i < 7; i++) run(s, api, frame(true, null, false, now += 16_000_000));
        assertTrue(api.of(CONFIGURE).stream().noneMatch(Call::enabled), api.calls.toString());
        assertTrue(api.of(GENERATE).isEmpty());
        assertTrue(api.of(QUERY_COMMAND_LIST).isEmpty());
        assertEquals(0, reconfigures[0]);
        run(s, api, frame(true, null, false, now += 16_000_000));
        assertTrue(api.of(CONFIGURE).get(7).enabled());
        assertEquals(1, api.of(GENERATE).size());
        assertEquals(0, reconfigures[0], "with generation off FSR has no presenter thread to quiesce");

        //a paused frame turns it off at once (no generation dispatch), and back on only after eight wanting frames again
        api.calls.clear();
        run(s, api, frame(true, "screen", false, now += 16_000_000));
        assertFalse(api.of(CONFIGURE).get(0).enabled());
        assertTrue(api.of(GENERATE).isEmpty());
        assertEquals(1, reconfigures[0], "FSR's presents are quiesced before the configure that turns generation off");
        api.calls.clear();
        //a reason that flickers every other frame never turns it back on, and costs no reconfiguration
        for (int i = 0; i < 20; i++) run(s, api, frame(true, i % 2 == 0 ? null : "screen", false, now += 16_000_000));
        assertTrue(api.of(CONFIGURE).stream().noneMatch(Call::enabled));
        assertEquals(1, reconfigures[0]);
        for (int i = 0; i < 8; i++) run(s, api, frame(true, null, false, now += 16_000_000));
        assertEquals(1, api.of(GENERATE).size());
        assertEquals(1, reconfigures[0]);

        //a remade context starts with generation off (FsrSwapchain configured it off before the old context went)
        api.calls.clear();
        run(s, api, frame(true, null, false, now += 16_000_000, 2L));
        assertFalse(api.of(CONFIGURE).get(0).enabled());
        assertTrue(api.of(GENERATE).isEmpty());
        assertEquals(1, reconfigures[0]);
    }

    @Test void flagChangesWhileGeneratingQuiesceFirst() {
        FsrFrame.Sequencer s = new FsrFrame.Sequencer(1);
        Recorder api = new Recorder();
        int[] reconfigures = {0};
        s.beforeReconfigure = () -> reconfigures[0]++;
        run(s, api, frame(true, null, false, 0));
        s.debugFlags = FfxStructs.FG_DRAW_DEBUG_VIEW;
        run(s, api, frame(true, null, false, 16_000_000));
        run(s, api, frame(true, null, false, 32_000_000));
        assertEquals(1, reconfigures[0]);
    }

    @Test void aMissingImageFailsBeforeFsrIsTold() {
        FsrFrame.Sequencer s = new FsrFrame.Sequencer(1);
        Recorder api = new Recorder();
        //a 0 handle in an FfxApiResource crashes inside FSR: the frame fails here instead (FSR is then turned off)
        FsrFrame.Frame noDepth = new FsrFrame.Frame(0x5CL, FG_CONTEXT, SC_CONTEXT, 1L, 1920, 1080, 0x1A6EL, FfxStructs.FORMAT_R8G8B8A8_UNORM,
                true, null, false, 0x40DL, 1920, 1080, 0L, 0x307L, 1920, 1080, CAMERA, 0L);
        assertThrows(FsrException.class, () -> run(s, api, noDepth));
        FsrFrame.Frame noPresent = new FsrFrame.Frame(0x5CL, FG_CONTEXT, SC_CONTEXT, 1L, 1920, 1080, 0L, FfxStructs.FORMAT_R8G8B8A8_UNORM,
                true, null, false, 0x40DL, 1920, 1080, 0xDE97L, 0x307L, 1920, 1080, CAMERA, 0L);
        assertThrows(FsrException.class, () -> run(s, api, noPresent));
        assertTrue(api.calls.isEmpty(), api.calls.toString());
        //FSR's interpolation texture must come back with an image too
        Recorder noOutput = new Recorder() {
            @Override
            public int query(long pContext, long desc) {
                int r = super.query(pContext, desc);
                if (memGetLong(desc) == QUERY_TEXTURE) memPutAddress(memGetAddress(desc + 16) + FfxStructs.OFF_FFX_API_RESOURCE_RESOURCE, 0L);
                return r;
            }
        };
        assertThrows(FsrException.class, () -> run(s, noOutput, frame(true, null, false, 16_000_000)));
        assertTrue(noOutput.of(GENERATE).isEmpty());
    }

    @Test void frameIntervalIsTheLastRealFrameDuration() {
        FsrFrame.Sequencer s = new FsrFrame.Sequencer(1);
        Recorder api = new Recorder();
        assertEquals(-1L, s.frameIntervalNs());
        run(s, api, frame(true, null, false, 1_000_000L));
        assertEquals(-1L, s.frameIntervalNs());
        run(s, api, frame(true, null, false, 7_500_000L));
        assertEquals(6_500_000L, s.frameIntervalNs());
        //a new context forgets it
        run(s, api, frame(true, null, false, 9_000_000_000L, 2L));
        assertEquals(-1L, s.frameIntervalNs());
    }

    @Test void verticalFovComesFromTheProjection() {
        assertEquals((float) Math.toRadians(70), FsrFrame.fovY(CAMERA.projection()), 1e-5f);
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
