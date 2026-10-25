package net.vulkanmod.visuals.dev;

import net.vulkanmod.visuals.VisualsLog;

import java.io.IOException;
import java.io.Writer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Stutter (2026-10-21): a per-frame record of the render thread's frame time, its phases and counts of the work that could
 * make a frame long (chunk uploads, buffer growth, DH uploads, shadow redraws, light sections, pipeline and shader creation,
 * GC). Off, and its hooks folded away by the JIT, unless the pilot runs or -Dvulkanmod.visuals.frames=true. The pilot's
 * {@code frames start} clears and starts a recording, {@code frames stop NAME} logs its figures and writes NAME.csv.
 * <p>
 * A frame runs from one Minecraft.runTick to the next. Its phases: pre (Reforged's preInitFrame: fence waits, chunk uploads),
 * tasks (queued client tasks and client ticks), begin (beginFrame: frame fence, swapchain image), render (the level and the
 * GUI), end (submit and present), display (window update, frame limit). Hooks add from any thread; frames close on the render thread.
 */
public final class FrameRecorder {
    public static final boolean ON = System.getProperty(VisualsPilot.PROPERTY) != null || Boolean.getBoolean("vulkanmod.visuals.frames");

    //Nanoseconds a frame
    public static final int TICK = 0, FENCE = 1, ACQUIRE = 2, PRESENT = 3, XFER_WAIT = 4, UPLOAD = 5, GRAPH = 6, LIGHT = 7,
            VIS_FRAME = 8, VIS_POST = 9, DH_UPLOAD = 10, PIPELINE = 11, SPIRV = 12,
    //Counts a frame
            TICKS = 13, SECTIONS = 14, AREA_GROW = 15, STAGING_GROW = 16, DH_UPLOADS = 17, DH_KB = 18, DH_ALLOC = 19, SHADOW_WHOLE = 20,
            SHADOW_SLICE = 21, LIGHT_SECTIONS = 22, PIPELINES = 23, SHADERS = 24, TEX_KB = 25, GPU_US = 26, ALLOC_KB = 27,
            GFX_SUBMITS = 28, XFER_SUBMITS = 29, XFER_FENCES = 30, GPU_END = 31,
            DAY_TIME = 32, SERVER_DAY_TIME = 33, SHADOW_TIME = 34, WIND_PHASE = 35;
    static final int HOOKS = 36;
    private static final String[] HOOK_NAMES = {"tick", "fence", "acquire", "present", "xferWait", "upload", "graph", "light",
            "visFrame", "visPost", "dhUpload", "pipeline", "spirv", "ticks", "sections", "areaGrow", "stagingGrow", "dhUploads", "dhKB",
            "dhAlloc", "shadowWhole", "shadowSlice", "lightSections", "pipelines", "shaders", "texKB", "gpuUs",
            "allocKB", "gfxSubmits", "xferSubmits", "xferFences", "gpuEndUs",
            "dayTimeMilliTicks", "serverDayTime", "shadowTimeE9", "windPhaseE6"};
    private static final int NS_HOOKS = 13;
    //Per record: the frame and its phases (ns), GC count and ms, then the hooks
    private static final String[] PHASES = {"frame", "pre", "tasks", "begin", "render", "end", "display"};
    static final int GC_COUNT = PHASES.length, GC_MS = GC_COUNT + 1, FIRST_HOOK = GC_MS + 1, FIELDS = FIRST_HOOK + HOOKS;

    //Phase marks of the open frame
    public static final int M_PRE_END = 0, M_BEGIN_START = 1, M_BEGIN_END = 2, M_END_START = 3, M_END_END = 4;

    private static final AtomicLongArray ACC = new AtomicLongArray(HOOKS);
    private static final long[] MARKS = new long[5];
    private static final int CAPACITY = 1 << 16;
    private static long[] data;
    private static int count;
    private static boolean recording;
    private static long frameStart, gcCount, gcMs, gcCountNow, gcMsNow;
    private static List<GarbageCollectorMXBean> gcs;
    private static java.lang.management.ThreadMXBean threads;
    private static long lastAllocated;
    /** The present log's records of the recording (PresentLog layout), written as NAME_presents.csv; render thread. */
    private static final int PRESENT_CAPACITY = 1 << 18;
    private static long[][] presents;
    private static int presentCount;

    private FrameRecorder() {
    }

    public static void add(int hook, long n) {
        ACC.addAndGet(hook, n);
    }

    /** Sets hook to n (the last value of the frame wins). */
    public static void set(int hook, long n) {
        ACC.set(hook, n);
    }

    /** Adds the nanoseconds since start to hook. */
    public static void time(int hook, long start) {
        ACC.addAndGet(hook, System.nanoTime() - start);
    }

    public static void mark(int mark) {
        MARKS[mark] = System.nanoTime();
    }

    /** Render thread, the top of Minecraft.runTick: closes the last frame. */
    public static void frameStart() {
        long now = System.nanoTime();
        gcNow();
        long allocated = allocatedNow();
        if (lastAllocated > 0 && allocated >= lastAllocated) ACC.addAndGet(ALLOC_KB, (allocated - lastAllocated) >> 10);
        lastAllocated = allocated;
        if (recording && frameStart != 0 && count < CAPACITY) {
            int o = count++ * FIELDS;
            data[o] = now - frameStart;
            long[] m = MARKS;
            if (m[M_PRE_END] != 0 && m[M_BEGIN_START] != 0 && m[M_BEGIN_END] != 0 && m[M_END_START] != 0 && m[M_END_END] != 0) {
                data[o + 1] = m[M_PRE_END] - frameStart;
                data[o + 2] = m[M_BEGIN_START] - m[M_PRE_END];
                data[o + 3] = m[M_BEGIN_END] - m[M_BEGIN_START];
                data[o + 4] = m[M_END_START] - m[M_BEGIN_END];
                data[o + 5] = m[M_END_END] - m[M_END_START];
                data[o + 6] = now - m[M_END_END];
            }
            data[o + GC_COUNT] = gcCountNow - gcCount;
            data[o + GC_MS] = gcMsNow - gcMs;
            for (int h = 0; h < HOOKS; h++) data[o + FIRST_HOOK + h] = ACC.getAndSet(h, 0);
        } else {
            for (int h = 0; h < HOOKS; h++) ACC.set(h, 0);
        }
        java.util.Arrays.fill(MARKS, 0);
        gcCount = gcCountNow;
        gcMs = gcMsNow;
        frameStart = now;
    }

    /** The render thread's allocated bytes so far (0 where the JVM does not tell). */
    private static long allocatedNow() {
        if (threads == null) threads = ManagementFactory.getThreadMXBean();
        try {
            return threads instanceof com.sun.management.ThreadMXBean t ? t.getCurrentThreadAllocatedBytes() : 0;
        } catch (UnsupportedOperationException e) {
            return 0;
        }
    }

    /** The collectors' summed counts and ms into gcCountNow and gcMsNow (no garbage of its own). */
    private static void gcNow() {
        if (gcs == null) gcs = ManagementFactory.getGarbageCollectorMXBeans();
        long c = 0, t = 0;
        for (int i = 0; i < gcs.size(); i++) {
            GarbageCollectorMXBean b = gcs.get(i);
            c += Math.max(0, b.getCollectionCount());
            t += Math.max(0, b.getCollectionTime());
        }
        gcCountNow = c;
        gcMsNow = t;
    }

    /** The pilot's frames start: a new recording from the next frame. */
    static void start() {
        if (data == null) data = new long[CAPACITY * FIELDS];
        count = 0;
        presentCount = 0;
        if (presents == null) presents = new long[PRESENT_CAPACITY][];
        frameStart = 0;
        recording = true;
        VisualsLog.info("Visuals frames: recording");
    }

    /**
     * One present log record (PresentLog layout) for the recording: the paced path's drained records (FrameGen, the single consumer of the
     * log) and the direct path's own present. Render thread; nothing outside a recording or past its capacity.
     */
    public static void present(long[] record) {
        if (recording && presentCount < PRESENT_CAPACITY) presents[presentCount++] = record.clone();
    }

    /** The pilot's frames stop NAME: logs the recording's figures and its slowest frames, writes dir/NAME.csv. */
    static void stop(Path dir, String name) {
        recording = false;
        int n = count;
        double[] ms = new double[n];
        for (int i = 0; i < n; i++) ms[i] = data[i * FIELDS] / 1e6;
        FrameStats stats = FrameStats.of(ms, n);
        VisualsLog.info("Visuals frames " + name + ": " + stats.line());
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(ms[b], ms[a]));
        for (int k = 0; k < Math.min(8, n); k++) VisualsLog.info("Visuals frames " + name + " slow #" + (k + 1) + ": frame " + order[k] + " " + describe(order[k]));
        Path out = dir.resolve(name + ".csv");
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            for (String p : PHASES) sb.append(p).append("Us,");
            sb.append("gcCount,gcMs");
            for (int h = 0; h < HOOKS; h++) sb.append(',').append(HOOK_NAMES[h]).append(h < NS_HOOKS ? "Us" : "");
            w.write(sb.append('\n').toString());
            for (int i = 0; i < n; i++) {
                sb.setLength(0);
                int o = i * FIELDS;
                for (int f = 0; f < FIELDS; f++) {
                    if (f > 0) sb.append(',');
                    boolean ns = f < PHASES.length || (f >= FIRST_HOOK && f - FIRST_HOOK < NS_HOOKS);
                    sb.append(ns ? data[o + f] / 1000 : data[o + f]);
                }
                w.write(sb.append('\n').toString());
            }
        } catch (IOException e) {
            VisualsLog.warn("Visuals frames: cannot write " + out, e);
        }
        writePresents(dir.resolve(name + "_presents.csv"));
    }

    /** The recording's present records as CSV (PresentCsv); nothing when frame generation and the direct path logged none. */
    private static void writePresents(Path out) {
        int n = presentCount;
        presentCount = 0;
        if (n == 0) return;
        try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write(PresentCsv.HEADER + '\n');
            for (int i = 0; i < n; i++) {
                w.write(PresentCsv.line(presents[i]) + '\n');
                presents[i] = null;
            }
        } catch (IOException e) {
            VisualsLog.warn("Visuals frames: cannot write " + out, e);
        }
        VisualsLog.info("Visuals frames: " + n + " present records in " + out.getFileName());
    }

    /** One frame's non-zero fields, times in ms. */
    private static String describe(int i) {
        int o = i * FIELDS;
        StringBuilder sb = new StringBuilder();
        for (int f = 0; f < FIELDS; f++) {
            long v = data[o + f];
            if (v == 0) continue;
            String key = f < PHASES.length ? PHASES[f] : f == GC_COUNT ? "gcCount" : f == GC_MS ? "gcMs" : HOOK_NAMES[f - FIRST_HOOK];
            boolean ns = f < PHASES.length || (f >= FIRST_HOOK && f - FIRST_HOOK < NS_HOOKS);
            sb.append(key).append('=').append(ns ? String.format(java.util.Locale.ROOT, "%.2f", v / 1e6) : Long.toString(v)).append(' ');
        }
        return sb.toString().trim();
    }
}
