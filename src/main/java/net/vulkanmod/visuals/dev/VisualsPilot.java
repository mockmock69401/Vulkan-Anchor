package net.vulkanmod.visuals.dev;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.ClientCommandHandler;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.vulkanmod.visuals.VisualsLog;
import net.vulkanmod.visuals.config.VisualsConfig;
import net.vulkanmod.visuals.vk.VkGpuTimer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Gates without a person at the keyboard: with -Dvulkanmod.visuals.pilot=script.txt the client runs the script's lines, one per
 * tick unless told to wait. Off (and never constructed) without the property. Lines ('#' starts a comment):
 * <pre>
 * world NAME SEED      load the save NAME from the title screen, creating it (creative, commands on) when missing
 * wait SECONDS         do nothing for that long
 * cmd COMMAND          run a server command as the server, its output logged (tp, time, weather, gamemode, locate ...)
 * ccmd COMMAND         run a client command as the player would type it
 * rd CHUNKS            set the render distance as the options screen does
 * fov DEGREES          set the field of view
 * zoom DEGREES         force the rendered field of view, below the options' 30 (a spyglass); 0 stops
 * key NAME             press a key mapping once, as a tap (key.attack, key.use, a mod's key name)
 * hold NAME on|off     hold a key mapping down or let it go
 * set KEY VALUE        set a VisualsConfig field (taa false, exposure 130), int, float, boolean or string
 * gui on|off           show or hide the HUD (off also clears the chat)
 * shot NAME            save NAME.png in the game directory
 * fps                  log the frame rate
 * chunks               log the section and client chunk counts (the F3 "Chunks:" and chunk cache lines)
 * log TEXT             write TEXT to the log
 * vm ...               drive VulkanMod's option screen (VisualsOptionScreenPilot)
 * spin DEG_PER_SECOND  turn the camera every frame at that rate (0 stops), as a steady mouse would
 * burst NAME N         save NAME_i.png on each of the next N rendered frames (one-frame effects)
 * glide X Y Z SECONDS  move the camera on a straight line to (X, Y, Z), every frame
 * gpu                  log the GPU ms per interval and render-thread CPU ms since the last gpu line
 * screen select|title|pause|close  open the singleplayer world list, the title screen (menu checks) or the pause menu (kept
 *                      open until the next screen line), or close the open screen (a mod's notice on joining)
 * leave                save and quit to the title screen, as the pause menu does (a later world line loads again)
 * size WIDTH HEIGHT    resize the game window (the swapchain and the main target follow on the next frames)
 * window fullscreen    switch between fullscreen and the window, as F11 does
 * window vsync on|off  turn vsync on or off, as the video settings do
 * f3                   log the visuals' F3 lines (the renderer's and each extension's)
 * frames start|stop NAME   record every frame's time, phases and work counts (FrameRecorder); stop logs the spike figures, writes NAME.csv
 * fgburst NAME N      the pacer captures its next N presents (N up to 32) to NAME_i_real|gen.png in the game directory; in
 *                      fgView 1 each real present gives the warp (gen) and the real frame (real)
 * fsr status           log FSR's support verdict, whether its swapchain is live, its present count and the last frame FSR was told,
 *                      with the presents per real frame since the last status line
 * fsr debug WORDS      FSR's debug drawings in its generated frames, combined: view (FSR's debug view), tear (tear lines), pacing
 *                      (pacing lines), generated (present only generated frames); "fsr debug off" clears them. shot reads the main
 *                      target, so its pictures are real frames without them: the drawings are seen on screen only
 * quit                 close the game
 * </pre>
 * A line with another command name is logged as unknown and skipped.
 */
public final class VisualsPilot {
    public static final String PROPERTY = "vulkanmod.visuals.pilot";

    private static VisualsPilot instance;
    private static String burstName;
    private static int burstLeft, burstIndex;

    private static double[] glideFrom, glideTo;
    private static double glideSeconds;
    private static long glideStart;
    private static volatile double spinRate;
    private static volatile long spinStart;
    private static volatile double zoomFov;
    //the previous "fsr status" line's present count, frame id, time and frame-generation context (-1: none yet)
    private static long fsrStatusPresents = -1, fsrStatusFrame = -1, fsrStatusNs, fsrStatusContext = -1;

    /** Registers the pilot's hooks when the property names a script; does nothing otherwise. */
    public static void install() {
        if (System.getProperty(PROPERTY) == null) return;
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        NeoForge.EVENT_BUS.addListener((ViewportEvent.ComputeCameraAngles event) -> cameraAngles(event));
        NeoForge.EVENT_BUS.addListener((ViewportEvent.ComputeFov event) -> computeFov(event));
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> glideFrame());
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> renderTick());
    }

    /** Every frame before the level renders: the camera's place on the glide's line. */
    static void glideFrame() {
        if (glideFrom == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double elapsed = (System.nanoTime() - glideStart) / 1e9;
        double[] p = Glide.at(glideFrom, glideTo, glideSeconds, elapsed);
        mc.player.setPos(p[0], p[1], p[2]);
        mc.player.xo = mc.player.xOld = p[0];
        mc.player.yo = mc.player.yOld = p[1];
        mc.player.zo = mc.player.zOld = p[2];
        if (elapsed >= glideSeconds) glideFrom = null;
    }

    /** After every rendered frame: the burst's next screenshot. */
    static void renderTick() {
        if (burstLeft <= 0) return;
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, String.format("%s_%03d.png", burstName, burstIndex++), mc.getMainRenderTarget(), m -> {
        });
        burstLeft--;
    }

    /** Every frame: the spin's turn so far added to the camera's yaw. */
    static void cameraAngles(ViewportEvent.ComputeCameraAngles event) {
        double rate = spinRate;
        if (rate == 0) return;
        event.setYaw((float) (event.getYaw() + rate * (System.nanoTime() - spinStart) / 1e9));
    }

    /** Every frame: the zoom's field of view in place of the game's, when set. */
    static void computeFov(ViewportEvent.ComputeFov event) {
        double fov = zoomFov;
        if (fov > 0 && event.usedConfiguredFov()) event.setFOV(fov);
    }

    private final List<String> lines;
    private int next;
    private long resumeAt;
    private boolean loading, finished, pauseOnLostFocus = true, pausedByScript;
    private long waitingSince;//0 before the first line, 1 when no line waits, else when the waiting line began
    private static final long WAIT_LIMIT_MS = 120_000;

    private VisualsPilot(List<String> lines) {
        this.lines = lines;
    }

    private static void tick() {
        if (instance == null) {
            String script = System.getProperty(PROPERTY);
            if (script == null) return;
            try {
                instance = new VisualsPilot(Files.readAllLines(Path.of(script)));
            } catch (IOException e) {
                VisualsLog.error("Visuals pilot: cannot read " + script, e);
                System.clearProperty(PROPERTY);
                return;
            }
            VisualsLog.info("Visuals pilot: running " + script + " (" + instance.lines.size() + " lines)");
        }
        instance.step(Minecraft.getInstance());
    }

    private void step(Minecraft mc) {
        if (this.next >= this.lines.size()) {
            if (!this.finished) {
                this.finished = true;
                mc.options.pauseOnLostFocus = this.pauseOnLostFocus;//the game is the player's again
                VisualsLog.info("Visuals pilot: script finished");
            }
            return;
        }
        if (this.waitingSince == 0) this.pauseOnLostFocus = mc.options.pauseOnLostFocus;
        mc.options.pauseOnLostFocus = false;
        if (mc.screen instanceof PauseScreen && !this.pausedByScript) mc.setScreen(null);
        if (System.currentTimeMillis() < this.resumeAt) return;
        while (this.next < this.lines.size()) {
            PilotScript.Line parsed = PilotScript.parse(this.lines.get(this.next));
            if (parsed == null) {
                this.next++;
                continue;
            }
            int done = this.run(mc, parsed);
            if (done < 0) {
                //not yet: try the same line next tick, for at most WAIT_LIMIT_MS
                if (this.waitingSince == 0 || this.waitingSince == 1) this.waitingSince = System.currentTimeMillis();
                if (System.currentTimeMillis() - this.waitingSince < WAIT_LIMIT_MS) return;
                VisualsLog.warn("Visuals pilot: gave up waiting on: " + this.lines.get(this.next).trim());
            }
            this.waitingSince = 1;
            this.next++;
            if (done > 0) return;//one acting line per tick
        }
    }

    /** -1 to retry next tick, 0 for an instant line (run the next now), 1 when done for this tick. */
    private int run(Minecraft mc, PilotScript.Line line) {
        String arg = line.arg();
        try {
            switch (line.command()) {
                case "world" -> {
                    //joined: in the level with no loading screen up (a pack may open its own screen on the first join)
                    if (mc.level != null && mc.player != null && !(mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen
                            || mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen
                            || mc.screen instanceof net.minecraft.client.gui.screens.GenericMessageScreen
                            || mc.screen instanceof net.minecraft.client.gui.screens.ProgressScreen)) {
                        this.loading = false;//joined: a later world line (after leave) may load again
                        return 0;
                    }
                    //loading, or the game is not loaded yet; any screen will do once it is (a pack's welcome screen may stay up)
                    if (this.loading || mc.getOverlay() != null || mc.screen == null) return -1;
                    String[] a = arg.split("\\s+");
                    boolean exists = mc.getLevelSource().levelExists(a[0]);
                    long seed = exists ? 0 : Long.parseLong(a[1]);//arguments first, so a bad line fails before loading starts
                    this.loading = true;
                    if (exists) {
                        mc.createWorldOpenFlows().openWorld(a[0], () -> mc.setScreen(new TitleScreen()));
                    } else {
                        LevelSettings settings = new LevelSettings(a[0], GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                        mc.createWorldOpenFlows().createFreshLevel(a[0], settings, new WorldOptions(seed, true, false), WorldPresets::createNormalWorldDimensions, mc.screen);
                    }
                    return -1;
                }
                case "wait" -> {
                    this.resumeAt = System.currentTimeMillis() + (long) (Double.parseDouble(arg) * 1000);
                    return 1;
                }
                case "cmd" -> {
                    IntegratedServer server = mc.getSingleplayerServer();
                    if (server == null) return -1;
                    server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(LOG_SOURCE).withPermission(4), arg));
                    return 1;
                }
                case "ccmd" -> {
                    if (mc.player == null) return -1;
                    if (!ClientCommandHandler.runCommand(arg)) VisualsLog.warn("Visuals pilot: not a client command: " + arg);
                    return 1;
                }
                case "rd" -> {
                    mc.options.renderDistance().set(Integer.parseInt(arg));
                    mc.options.broadcastOptions();
                    mc.levelRenderer.allChanged();
                    return 1;
                }
                case "fov" -> {
                    mc.options.fov().set(Integer.parseInt(arg));
                    return 1;
                }
                case "key", "hold" -> {
                    String[] a = arg.split("\\s+");
                    KeyMapping mapping = null;
                    for (KeyMapping k : mc.options.keyMappings) {
                        if (k.getName().equals(a[0])) mapping = k;
                    }
                    if (mapping == null) {
                        VisualsLog.warn("Visuals pilot: no key mapping " + a[0]);
                        return 0;
                    }
                    if (line.command().equals("key")) {
                        KeyMapping.click(mapping.getKey());
                    } else {
                        mapping.setDown(a.length > 1 && a[1].equals("on"));
                    }
                    VisualsLog.info("Visuals pilot: " + line.command() + " " + arg);
                    return 1;
                }
                case "zoom" -> {
                    zoomFov = Double.parseDouble(arg);
                    return 1;
                }
                case "set" -> {
                    String[] a = arg.split("\\s+", 2);
                    PilotScript.setField(VisualsConfig.get(), a[0], a[1]);
                    VisualsLog.info("Visuals pilot: " + a[0] + " = " + a[1]);
                    return 0;
                }
                case "colourpreset" -> {
                    //colourpreset N: session 8's colour palette (ColourPresets.apply), as choosing it on the colours page and applying
                    net.vulkanmod.visuals.config.ColourPresets.apply(VisualsConfig.get(), Integer.parseInt(arg.trim()));
                    VisualsLog.info("Visuals pilot: colour preset " + arg);
                    return 0;
                }
                case "preset" -> {
                    //preset N: session 4's performance preset (VisualsPresets.apply), as choosing it on the page and applying
                    net.vulkanmod.visuals.config.VisualsPresets.apply(VisualsConfig.get(), Integer.parseInt(arg.trim()));
                    VisualsLog.info("Visuals pilot: preset " + arg);
                    return 0;
                }
                case "gui" -> {
                    mc.options.hideGui = arg.equals("off");
                    if (mc.options.hideGui) mc.gui.getChat().clearMessages(false);
                    return 0;
                }
                case "shot" -> {
                    Screenshot.grab(mc.gameDirectory, arg + ".png", mc.getMainRenderTarget(), m -> VisualsLog.info("Visuals pilot: " + m.getString()));
                    return 1;
                }
                case "fps" -> {
                    VisualsLog.info("Visuals pilot: fps " + mc.getFps());
                    return 0;
                }
                case "chunks" -> {
                    if (mc.level == null) return -1;
                    VisualsLog.info("Visuals pilot: chunks " + mc.levelRenderer.getSectionStatistics() + " | " + mc.level.getChunkSource().gatherStats()
                            + " | " + (net.vulkanmod.render.chunk.ChunkStatusMap.INSTANCE == null ? "no status map" : net.vulkanmod.render.chunk.ChunkStatusMap.INSTANCE.describe()));
                    return 0;
                }
                case "gpu" -> {
                    //GPU ms per interval and render-thread CPU ms since the last gpu line
                    VisualsLog.info("Visuals pilot: gpu " + VkGpuTimer.PILOT.line());
                    VkGpuTimer.PILOT.clear();
                    return 0;
                }
                case "burst" -> {
                    String[] b = arg.split("\\s+");
                    burstName = b[0];
                    burstIndex = 0;
                    burstLeft = Integer.parseInt(b[1]);
                    return 0;
                }
                case "fgburst" -> {
                    PilotScript.FgBurst b = PilotScript.parseFgBurst(arg);
                    net.vulkanmod.visuals.framegen.FrameGen.requestBurst(b.name(), b.count());
                    return 0;
                }
                case "glide" -> {
                    //glide X Y Z SECONDS: the camera on a straight line to (X, Y, Z), moved every frame
                    String[] g = arg.split("\\s+");
                    if (mc.player == null) return -1;
                    glideFrom = new double[]{mc.player.getX(), mc.player.getY(), mc.player.getZ()};
                    glideTo = new double[]{Double.parseDouble(g[0]), Double.parseDouble(g[1]), Double.parseDouble(g[2])};
                    glideSeconds = Double.parseDouble(g[3]);
                    glideStart = System.nanoTime();
                    return 0;
                }
                case "spin" -> {
                    spinRate = Double.parseDouble(arg);
                    spinStart = System.nanoTime();
                    return 0;
                }
                case "vm" -> {
                    VisualsOptionScreenPilot.run(mc, arg);
                    return 1;
                }
                case "log" -> {
                    VisualsLog.info("Visuals pilot: " + arg);
                    return 0;
                }
                case "loaded" -> {
                    //waits for the game to finish loading, then shows the title screen (a pack's first-launch screen is skipped)
                    if (mc.getOverlay() != null || mc.screen == null) return -1;
                    if (!(mc.screen instanceof TitleScreen)) mc.setScreen(new TitleScreen());
                    return 0;
                }
                case "screen" -> {
                    this.pausedByScript = arg.equals("pause");
                    switch (arg) {
                        case "select" -> mc.setScreen(new SelectWorldScreen(new TitleScreen()));
                        case "title" -> mc.setScreen(new TitleScreen());
                        case "pause" -> mc.pauseGame(false);
                        case "close" -> mc.setScreen(null);
                        default -> VisualsLog.warn("Visuals pilot: unknown screen " + arg);
                    }
                    return 1;
                }
                case "size" -> {
                    String[] a = arg.split("\\s+");
                    org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), Integer.parseInt(a[0]), Integer.parseInt(a[1]));
                    VisualsLog.info("Visuals pilot: window size " + a[0] + "x" + a[1]);
                    return 1;
                }
                case "window" -> {
                    String[] a = arg.split(" ");
                    if (a[0].equals("fullscreen")) {
                        mc.getWindow().toggleFullScreen();
                        mc.options.fullscreen().set(mc.getWindow().isFullscreen());
                    } else if (a[0].equals("vsync") && a.length == 2 && (a[1].equals("on") || a[1].equals("off"))) {
                        boolean on = a[1].equals("on");
                        mc.options.enableVsync().set(on);
                        mc.getWindow().updateVsync(on);
                    } else {
                        throw new IllegalArgumentException("window fullscreen | window vsync on|off: " + arg);
                    }
                    VisualsLog.info("Visuals pilot: window " + arg);
                    return 1;
                }
                case "frames" -> {
                    String[] a = arg.split("\\s+");
                    if (a[0].equals("start")) {
                        FrameRecorder.start();
                    } else {
                        net.vulkanmod.visuals.framegen.FrameGen.flushPresentLog();
                        FrameRecorder.stop(mc.gameDirectory.toPath(), a[1]);
                    }
                    return 0;
                }
                case "fsr" -> {
                    fsr(arg);
                    return 0;
                }
                case "f3" -> {
                    List<String> lines = new java.util.ArrayList<>();
                    net.vulkanmod.visuals.Visuals.addDebugInfo(lines);
                    for (String l : lines) VisualsLog.info("Visuals pilot: f3 " + l);
                    return 0;
                }
                case "leave" -> {
                    if (mc.level == null) return 0;
                    VisualsLog.info("Visuals pilot: leaving the world");
                    mc.level.disconnect();
                    mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
                    mc.setScreen(new TitleScreen());
                    return 1;
                }
                case "quit" -> {
                    VisualsLog.info("Visuals pilot: done");
                    mc.stop();
                    return 1;
                }
                default -> {
                    VisualsLog.warn("Visuals pilot: unknown command, skipped: " + line.command() + " " + arg);
                    return 0;
                }
            }
        } catch (RuntimeException | ReflectiveOperationException e) {
            VisualsLog.error("Visuals pilot: line failed: " + line.command() + " " + arg, e);
            return 0;
        }
    }

    /** The "fsr status" and "fsr debug ..." lines. */
    private static void fsr(String arg) {
        String[] a = arg.trim().split("\\s+", 2);
        switch (a[0]) {
            case "status" -> {
                boolean live = net.vulkanmod.visuals.fsr.FsrSwapchain.live();
                long presents = live ? net.vulkanmod.visuals.fsr.FsrSwapchain.lastPresentCount() : -1;
                long frame = net.vulkanmod.visuals.fsr.FsrFrame.frameId();
                long now = System.nanoTime();
                //a context remade since the last line (resize, fullscreen, vsync, FSR off and on): its counts are not comparable
                long context = net.vulkanmod.visuals.fsr.FsrSwapchain.frameGenerationContextSerial();
                boolean same = context == fsrStatusContext;
                VisualsLog.info("Visuals pilot: fsr status " + PilotScript.fsrStatus(net.vulkanmod.visuals.fsr.FsrSupport.current().name(), live,
                        presents, frame, same ? fsrStatusPresents : -1, same ? fsrStatusFrame : -1, (now - fsrStatusNs) / 1e9));
                fsrStatusContext = context;
                fsrStatusPresents = presents;
                fsrStatusFrame = frame;
                fsrStatusNs = now;
            }
            case "debug" -> {
                PilotScript.FsrDebug d = PilotScript.parseFsrDebug(a.length > 1 ? a[1] : "");
                net.vulkanmod.visuals.fsr.FsrFrame.pilotDebug(d.flags(), d.onlyPresentGenerated());
                VisualsLog.info("Visuals pilot: fsr debug flags " + d.flags() + ", only generated " + d.onlyPresentGenerated());
            }
            default -> throw new IllegalArgumentException("fsr status | fsr debug ...: " + arg);
        }
    }

    private static final CommandSource LOG_SOURCE = new CommandSource() {
        @Override
        public void sendSystemMessage(Component message) {
            VisualsLog.info("Visuals pilot: " + message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    };
}
