package net.vulkanmod.visuals.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.vulkanmod.visuals.vk.GlslForVulkan;
import net.vulkanmod.visuals.vk.VkComputeProgram;
import net.vulkanmod.visuals.vk.VkGraphicsProgram;
import net.vulkanmod.visuals.vk.shader.ShaderSource;
import net.vulkanmod.vulkan.Vulkan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 2026-11-25 (the user: "셰이더를 수정할때마다 게임을 켜서 확인하는 게 너무 오래걸려"): with -Dvulkanmod.visuals.shaderDir=<the project's
 * src/main/resources>, the game reads the visuals' shader files from that folder and, when one is saved, compiles every program again
 * between two ticks and swaps it in. A program that does not compile, or whose bindings or loose uniforms changed, keeps its old
 * shaders; the chat and the log say which and why. The pilot's "shaders reload" line does the same at once.
 */
public final class ShaderHotReload {
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanMod Visuals");
    private static final long POLL_NS = 500_000_000L;

    private static long lastPoll, lastStamp = -1;
    private static volatile boolean requested;

    private ShaderHotReload() {
    }

    /** Registers the watcher when a dev shader folder is set; does nothing otherwise. */
    public static void install() {
        Path dir = ShaderSource.devDir();
        if (dir == null) return;
        LOGGER.info("Shader hot reload: reading shader files from {}", dir.toAbsolutePath());
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.END) tick(dir);
        });
    }

    /** Asks for a reload at the next tick's end (the pilot). */
    public static void request() {
        requested = true;
    }

    static void tick(Path dir) {
        long now = System.nanoTime();
        if (!requested && now - lastPoll < POLL_NS) return;
        lastPoll = now;
        long stamp = newestChange(dir.resolve("assets"));
        boolean changed = lastStamp >= 0 && stamp != lastStamp;
        lastStamp = stamp;
        if (changed || requested) {
            requested = false;
            report(reloadAll());
        }
    }

    /** The newest modification time of any file under root (0 when there is none). */
    static long newestChange(Path root) {
        if (!Files.isDirectory(root)) return 0;
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).mapToLong(f -> {
                try {
                    return Files.getLastModifiedTime(f).toMillis();
                } catch (IOException e) {
                    return 0;
                }
            }).max().orElse(0);
        } catch (IOException e) {
            return 0;
        }
    }

    /** What one reload did: how many programs took the new shaders, and the failures ("name: reason"). */
    public record Result(int reloaded, List<String> failed, long millis) {
    }

    /** Waits until no frame uses a program, then reloads every live program; each failure leaves that program as it was. */
    public static Result reloadAll() {
        long start = System.nanoTime();
        Vulkan.waitIdle();
        GlslForVulkan.clearConverted();
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (VkGraphicsProgram p : VkGraphicsProgram.live()) {
            try {
                p.reload();
                ok++;
            } catch (RuntimeException e) {
                failed.add(p.name() + ": " + e.getMessage());
            }
        }
        for (VkComputeProgram p : VkComputeProgram.live()) {
            try {
                p.reload();
                ok++;
            } catch (RuntimeException e) {
                failed.add(p.name() + ": " + e.getMessage());
            }
        }
        return new Result(ok, failed, (System.nanoTime() - start) / 1_000_000);
    }

    private static void report(Result r) {
        LOGGER.info("Shader hot reload: {} programs reloaded, {} kept their old shaders, {} ms", r.reloaded(), r.failed().size(), r.millis());
        for (String f : r.failed()) LOGGER.warn("Shader hot reload: {}", f);
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        if (r.failed().isEmpty()) {
            mc.gui.getChat().addMessage(Component.literal("§a셰이더 다시 불러옴§r: " + r.reloaded() + "개, " + r.millis() + " ms"));
        } else {
            String first = r.failed().get(0);
            if (first.length() > 300) first = first.substring(0, 300) + "…";
            mc.gui.getChat().addMessage(Component.literal("§c셰이더 " + r.failed().size() + "개 실패, 이전 셰이더 유지§r (로그 참고): " + first));
        }
    }
}
