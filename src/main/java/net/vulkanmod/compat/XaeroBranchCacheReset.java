package net.vulkanmod.compat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Before glGetTexImage honoured format and type (GlPixelPack), Xaero's World Map saved its zoomed-out tiles with their colour
 * channels shifted by one, and a tile rebuilt from such tiles copied the shift on: each session added another shift (olive,
 * magenta, yellow tiles; blue, green or red bars along the explored edge). Those tiles live in each dimension's "cache" folder
 * (one sub-folder per zoom level, *.xwmc). Xaero rebuilds them from the full-size tiles ("cache_N" folders, never read back, so
 * never damaged) when they are missing, so the folders are removed once, before any world loads.
 */
public final class XaeroBranchCacheReset {
    static final String MARKER = "vulkanmod_xaero_branch_cache_reset.txt";
    private static final int MAX_DEPTH = 6;
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod");

    private XaeroBranchCacheReset() {
    }

    public static void runOnce(Path gameDir, Path configDir, boolean worldMapLoaded) {
        if (!worldMapLoaded) return;
        Path marker = configDir.resolve(MARKER);
        if (Files.exists(marker)) return;
        try {
            List<Path> removed = reset(gameDir.resolve("xaero").resolve("world-map"));
            Files.createDirectories(configDir);
            Files.writeString(marker, "Xaero World Map zoomed-out tile caches removed once (colours saved wrong by older builds): "
                    + removed.size() + " folder(s)\n");
            if (!removed.isEmpty())
                LOGGER.info("Removed {} Xaero World Map zoomed-out tile cache folder(s); Xaero rebuilds them", removed.size());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not reset Xaero World Map zoomed-out tile caches", e);
        }
    }

    /** Deletes every branch-tile cache folder under {@code worldMapDir}; returns the folders removed. */
    static List<Path> reset(Path worldMapDir) throws IOException {
        List<Path> removed = new ArrayList<>();
        if (!Files.isDirectory(worldMapDir)) return removed;
        List<Path> folders;
        try (Stream<Path> walk = Files.walk(worldMapDir, MAX_DEPTH)) {
            folders = walk.filter(XaeroBranchCacheReset::isBranchCache).toList();
        }
        for (Path folder : folders) {
            deleteTree(folder);
            removed.add(folder);
        }
        return removed;
    }

    /** A folder named "cache" holding only zoom-level folders (digits) of *.xwmc files. */
    static boolean isBranchCache(Path dir) {
        if (!Files.isDirectory(dir) || !dir.getFileName().toString().equals("cache")) return false;
        try (Stream<Path> levels = Files.list(dir)) {
            for (Path level : (Iterable<Path>) levels::iterator) {
                if (!Files.isDirectory(level) || !level.getFileName().toString().matches("\\d+")) return false;
                try (Stream<Path> files = Files.list(level)) {
                    if (!files.allMatch(f -> Files.isRegularFile(f) && f.getFileName().toString().contains(".xwmc"))) return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
