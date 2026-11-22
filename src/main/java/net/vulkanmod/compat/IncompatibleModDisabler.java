package net.vulkanmod.compat;

import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipFile;

/**
 * Turns off the mods that cannot run next to VulkanMod (the Sodium family and others): each such mod's jar becomes {@code <name>.jar.disabled}, the
 * name every launcher's own disable switch uses. FML has already put those mods in this game's module layer by the time any of
 * VulkanMod's code runs, so the rename only counts from the next start: after one, a dialog says what happened and the game exits
 * before a conflicting mixin can apply.
 */
public final class IncompatibleModDisabler {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod");
    private static final String MOD_ID_LIST = "/assets/vulkanmod/compat/incompatible_mod_ids.txt";
    static final String KEEP_FILE = "vulkanmod_keep_mods.txt";
    private static final String DISABLED_SUFFIX = ".disabled";

    /**
     * One mod file: the ids of the mods in it, the ids those mods cannot load without, and the part of those they need on the
     * client only (a rendering add-on of the mod, like GeckoBetterFPS of Sodium).
     */
    record ModJar(Path path, Set<String> modIds, Set<String> requiredIds, Set<String> clientRequiredIds) {
        ModJar(Path path, Set<String> modIds, Set<String> requiredIds) {
            this(path, modIds, requiredIds, Set.of());
        }
    }

    /**
     * File names by what happened to them. {@code addons} were turned off as well: they need a turned-off mod, on the client only.
     * {@code dependents} are still enabled and require a turned-off mod on both sides (they may add blocks to the world, so they
     * are left to the player).
     */
    record Outcome(List<String> disabled, List<String> addons, List<String> failed, List<String> notRenamable, List<String> dependents) {
        Outcome(List<String> disabled, List<String> failed, List<String> notRenamable, List<String> dependents) {
            this(disabled, List.of(), failed, notRenamable, dependents);
        }

        boolean turnedOff() {
            return !disabled.isEmpty() || !addons.isEmpty();
        }
    }

    private IncompatibleModDisabler() {
    }

    /** Called once FML has the mod list and before any mixin applies. A failure here never stops the game. */
    public static void run() {
        Outcome outcome;
        try {
            if (!FMLLoader.getDist().isClient() || LoadingModList.get() == null) return;

            Set<String> incompatible = lowerCase(CompatMods.loadLines(MOD_ID_LIST));
            incompatible.removeAll(readKeepList(FMLPaths.CONFIGDIR.get().resolve(KEEP_FILE)));
            Path modsDir = FMLPaths.MODSDIR.get();
            outcome = disable(installedJars(), incompatible, new NestedJarIndex(modsDir)::outerJar);
        } catch (Throwable t) {
            LOGGER.error("Could not check for mods that conflict with VulkanMod", t);
            return;
        }

        for (String name : outcome.notRenamable()) {
            LOGGER.warn("{} conflicts with VulkanMod but is not a jar of its own, it cannot be turned off from here", name);
        }
        if (!outcome.turnedOff() && outcome.failed().isEmpty()) return;

        String message = message(outcome, Locale.getDefault().getLanguage().equals("ko"));
        LOGGER.warn(message);
        showDialog(message);
        if (outcome.turnedOff()) {
            System.exit(0);
        }
    }

    static Outcome disable(List<ModJar> jars, Set<String> incompatible) {
        return disable(jars, incompatible, nested -> null);
    }

    /**
     * @param outerJar the top-level jar in the mods folder that carries a nested jar of this file name (META-INF/jarjar), or null.
     *                 2026-11-22: Sodium 0.8 for NeoForge is such a nested jar in a wrapper with no mod of its own (0.6 had its
     *                 classes in the jar itself), so it stayed on in Tensura 1.1.3c and ATM10 and its mixins crashed the game. A
     *                 wrapper that also holds a mod of its own is not renamed: that is the player's call.
     */
    static Outcome disable(List<ModJar> jars, Set<String> incompatible, Function<String, Path> outerJar) {
        List<String> disabled = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> notRenamable = new ArrayList<>();
        Set<String> disabledIds = new HashSet<>();
        Set<Path> renamed = new HashSet<>();
        List<ModJar> remaining = new ArrayList<>();

        for (ModJar jar : jars) {
            if (!anyIn(jar.modIds(), incompatible)) {
                remaining.add(jar);
                continue;
            }

            String name = jar.path().getFileName().toString();
            Path target = jar.path();
            // A mod nested in another mod's jar lives on that jar's own file system; a dev run's mod is a folder
            if (!isOwnFile(target)) {
                Path outer = outerJar.apply(name);
                if (outer == null || !isOwnFile(outer)) {
                    notRenamable.add(name);
                    continue;
                }
                Path outerPath = outer.toAbsolutePath().normalize();
                boolean outerHasOtherMods = jars.stream().anyMatch(other -> isOwnFile(other.path())
                        && other.path().toAbsolutePath().normalize().equals(outerPath) && !allIn(other.modIds(), incompatible));
                if (outerHasOtherMods) {
                    failed.add(name + " (" + outer.getFileName() + ")");
                    continue;
                }
                target = outer;
            }

            Path key = target.toAbsolutePath().normalize();
            if (renamed.contains(key)) {
                jar.modIds().forEach(id -> disabledIds.add(id.toLowerCase(Locale.ROOT)));
                continue;
            }
            try {
                rename(target);
                renamed.add(key);
                disabled.add(target.getFileName().toString());
                jar.modIds().forEach(id -> disabledIds.add(id.toLowerCase(Locale.ROOT)));
            } catch (IOException e) {
                LOGGER.error("Could not rename {}", target, e);
                failed.add(target.getFileName().toString());
            }
        }

        // What went with a renamed wrapper: its nested libraries (Sodium's Fabric API modules) and the wrapper's own entry. Their
        // ids do not count as turned off: other jars often carry the same libraries, and FML picks one copy at the next start.
        remaining.removeIf(jar -> {
            Path own = isOwnFile(jar.path()) ? jar.path() : outerJar.apply(jar.path().getFileName().toString());
            return own != null && renamed.contains(own.toAbsolutePath().normalize());
        });

        // A mod that needs a turned-off mod on the client only goes too (until nothing changes: an add-on of an add-on)
        List<String> addons = new ArrayList<>();
        for (boolean changed = true; changed; ) {
            changed = false;
            for (var it = remaining.iterator(); it.hasNext(); ) {
                ModJar jar = it.next();
                Set<String> needed = lowerCaseSet(jar.requiredIds());
                needed.retainAll(disabledIds);
                if (needed.isEmpty() || !lowerCaseSet(jar.clientRequiredIds()).containsAll(needed) || !isOwnFile(jar.path())) continue;
                try {
                    rename(jar.path());
                    addons.add(jar.path().getFileName().toString());
                    jar.modIds().forEach(id -> disabledIds.add(id.toLowerCase(Locale.ROOT)));
                    it.remove();
                    changed = true;
                } catch (IOException e) {
                    LOGGER.error("Could not rename {}", jar.path(), e);
                }
            }
        }

        List<String> dependents = new ArrayList<>();
        for (ModJar jar : remaining) {
            if (anyIn(jar.requiredIds(), disabledIds)) {
                dependents.add(jar.path().getFileName().toString());
            }
        }
        return new Outcome(disabled, addons, failed, notRenamable, dependents);
    }

    private static boolean isOwnFile(Path path) {
        return path.getFileSystem() == FileSystems.getDefault() && Files.isRegularFile(path);
    }

    private static boolean anyIn(Set<String> ids, Set<String> lowerCaseIds) {
        return ids.stream().anyMatch(id -> lowerCaseIds.contains(id.toLowerCase(Locale.ROOT)));
    }

    private static boolean allIn(Set<String> ids, Set<String> lowerCaseIds) {
        return !ids.isEmpty() && ids.stream().allMatch(id -> lowerCaseIds.contains(id.toLowerCase(Locale.ROOT)));
    }

    private static Set<String> lowerCaseSet(Set<String> ids) {
        Set<String> set = new HashSet<>();
        for (String id : ids) set.add(id.toLowerCase(Locale.ROOT));
        return set;
    }

    /** The nested jars (META-INF/jarjar/*.jar) of the mods folder's top-level jars, read once and only when asked. */
    static final class NestedJarIndex {
        private final Path modsDir;
        private Map<String, Path> outerByNestedName;

        NestedJarIndex(Path modsDir) {
            this.modsDir = modsDir;
        }

        Path outerJar(String nestedName) {
            if (outerByNestedName == null) outerByNestedName = read(modsDir);
            return outerByNestedName.get(nestedName);
        }

        static Map<String, Path> read(Path modsDir) {
            Map<String, Path> index = new HashMap<>();
            if (modsDir == null || !Files.isDirectory(modsDir)) return index;
            try (var files = Files.list(modsDir)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    if (!file.getFileName().toString().endsWith(".jar") || !Files.isRegularFile(file)) continue;
                    try (ZipFile zip = new ZipFile(file.toFile())) {
                        for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                            String entry = entries.nextElement().getName();
                            if (entry.startsWith("META-INF/jarjar/") && entry.endsWith(".jar") && entry.indexOf('/', 16) < 0) {
                                index.putIfAbsent(entry.substring(16), file);
                            }
                        }
                    } catch (IOException e) {
                        LOGGER.debug("Could not read {} for nested jars", file, e);
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Could not list {}", modsDir, e);
            }
            return index;
        }
    }

    /** Never overwrites: a launcher restoring a jar can leave an older {@code .disabled} copy next to it. */
    private static void rename(Path jar) throws IOException {
        String name = jar.getFileName().toString();
        Path target = jar.resolveSibling(name + DISABLED_SUFFIX);
        for (int i = 1; ; i++) {
            try {
                Files.move(jar, target);
                return;
            } catch (FileAlreadyExistsException e) {
                if (i >= 100) throw e;
                target = jar.resolveSibling(name + "." + i + DISABLED_SUFFIX);
            }
        }
    }

    static Set<String> readKeepList(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return Set.of();

        Set<String> ids = new HashSet<>();
        for (String line : Files.readAllLines(file)) {
            String id = line.trim();
            if (!id.isEmpty() && !id.startsWith("#")) ids.add(id.toLowerCase(Locale.ROOT));
        }
        return ids;
    }

    static String message(Outcome outcome, boolean korean) {
        StringBuilder text = new StringBuilder();
        if (outcome.turnedOff()) {
            text.append(korean
                    ? "VulkanMod와 충돌하는 모드를 껐습니다. 게임을 다시 실행해 주세요.\n"
                    : "VulkanMod turned off the mods that conflict with it. Please start the game again.\n");
            appendNames(text, outcome.disabled());
            if (!outcome.addons().isEmpty()) {
                text.append(korean
                        ? "\n아래 모드는 방금 끈 모드 위에서만 작동하는 애드온이라 함께 껐습니다.\n"
                        : "\nThese add-ons only work on top of a mod that was turned off, so they were turned off as well.\n");
                appendNames(text, outcome.addons());
            }
            text.append(korean
                    ? "\n다시 켜려면 파일 이름 끝의 .disabled를 지우세요. VulkanMod를 쓰는 동안 계속 켜 두려면 모드 ID를 config/"
                    : "\nTo turn one back on, remove .disabled from its file name. To keep it on while using VulkanMod, add its mod id to config/")
                    .append(KEEP_FILE)
                    .append(korean ? "에 추가하세요.\n" : ".\n");
        }
        if (!outcome.dependents().isEmpty()) {
            text.append(korean
                    ? "\n아래 모드는 방금 끈 모드가 필요해서, 다음 실행 때 Forge가 오류를 표시합니다. 이 모드도 꺼야 합니다.\n"
                    : "\nThese mods need a mod that was turned off, so Forge will report them at the next start. They need turning off as well.\n");
            appendNames(text, outcome.dependents());
        }
        if (!outcome.failed().isEmpty()) {
            text.append(korean
                    ? "\n아래 모드도 VulkanMod와 충돌하지만 끄지 못했습니다. 직접 꺼 주세요.\n"
                    : "\nThese mods also conflict with VulkanMod but could not be turned off. Please turn them off yourself.\n");
            appendNames(text, outcome.failed());
        }
        return text.toString();
    }

    private static void appendNames(StringBuilder text, List<String> names) {
        for (String name : names) {
            text.append("  - ").append(name).append('\n');
        }
    }

    private static List<ModJar> installedJars() {
        List<ModJar> jars = new ArrayList<>();
        for (ModFileInfo file : LoadingModList.get().getModFiles()) {
            Set<String> modIds = new HashSet<>();
            Set<String> requiredIds = new HashSet<>();
            Set<String> clientRequiredIds = new HashSet<>();
            for (IModInfo mod : file.getMods()) {
                modIds.add(mod.getModId());
                for (IModInfo.ModVersion dependency : mod.getDependencies()) {
                    if (dependency.getType() != IModInfo.DependencyType.REQUIRED) continue;
                    requiredIds.add(dependency.getModId());
                    if (dependency.getSide() == IModInfo.DependencySide.CLIENT) clientRequiredIds.add(dependency.getModId());
                }
            }
            jars.add(new ModJar(file.getFile().getFilePath(), modIds, requiredIds, clientRequiredIds));
        }
        return jars;
    }

    private static Set<String> lowerCase(String[] ids) {
        Set<String> set = new HashSet<>();
        for (String id : ids) set.add(id.toLowerCase(Locale.ROOT));
        return set;
    }

    private static void showDialog(String message) {
        try {
            TinyFileDialogs.tinyfd_messageBox("VulkanMod", message, "ok", "info", true);
        } catch (Throwable t) {
            LOGGER.error("Could not show the dialog", t);
        }
    }
}
