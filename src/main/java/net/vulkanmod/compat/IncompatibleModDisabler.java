package net.vulkanmod.compat;

import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    /** One mod file: the ids of the mods in it and the ids those mods cannot load without. */
    record ModJar(Path path, Set<String> modIds, Set<String> requiredIds) {
    }

    /** File names by what happened to them. {@code dependents} are still enabled and require a mod that was turned off. */
    record Outcome(List<String> disabled, List<String> failed, List<String> notRenamable, List<String> dependents) {
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
            outcome = disable(installedJars(), incompatible);
        } catch (Throwable t) {
            LOGGER.error("Could not check for mods that conflict with VulkanMod", t);
            return;
        }

        for (String name : outcome.notRenamable()) {
            LOGGER.warn("{} conflicts with VulkanMod but is not a jar of its own, it cannot be turned off from here", name);
        }
        if (outcome.disabled().isEmpty() && outcome.failed().isEmpty()) return;

        String message = message(outcome, Locale.getDefault().getLanguage().equals("ko"));
        LOGGER.warn(message);
        showDialog(message);
        if (!outcome.disabled().isEmpty()) {
            System.exit(0);
        }
    }

    static Outcome disable(List<ModJar> jars, Set<String> incompatible) {
        List<String> disabled = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> notRenamable = new ArrayList<>();
        Set<String> disabledIds = new HashSet<>();
        List<ModJar> remaining = new ArrayList<>();

        for (ModJar jar : jars) {
            if (jar.modIds().stream().noneMatch(id -> incompatible.contains(id.toLowerCase(Locale.ROOT)))) {
                remaining.add(jar);
                continue;
            }

            String name = jar.path().getFileName().toString();
            // A mod nested in another mod's jar lives on that jar's own file system; a dev run's mod is a folder
            if (jar.path().getFileSystem() != FileSystems.getDefault() || !Files.isRegularFile(jar.path())) {
                notRenamable.add(name);
                continue;
            }
            try {
                rename(jar.path());
                disabled.add(name);
                jar.modIds().forEach(id -> disabledIds.add(id.toLowerCase(Locale.ROOT)));
            } catch (IOException e) {
                LOGGER.error("Could not rename {}", jar.path(), e);
                failed.add(name);
            }
        }

        List<String> dependents = new ArrayList<>();
        for (ModJar jar : remaining) {
            if (jar.requiredIds().stream().anyMatch(id -> disabledIds.contains(id.toLowerCase(Locale.ROOT)))) {
                dependents.add(jar.path().getFileName().toString());
            }
        }
        return new Outcome(disabled, failed, notRenamable, dependents);
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
        if (!outcome.disabled().isEmpty()) {
            text.append(korean
                    ? "VulkanMod와 충돌하는 모드를 껐습니다. 게임을 다시 실행해 주세요.\n"
                    : "VulkanMod turned off the mods that conflict with it. Please start the game again.\n");
            appendNames(text, outcome.disabled());
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
            for (IModInfo mod : file.getMods()) {
                modIds.add(mod.getModId());
                for (IModInfo.ModVersion dependency : mod.getDependencies()) {
                    if (dependency.isMandatory()) requiredIds.add(dependency.getModId());
                }
            }
            jars.add(new ModJar(file.getFile().getFilePath(), modIds, requiredIds));
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
