package net.vulkanmod.visuals.vk.shader;

import net.vulkanmod.visuals.vk.GlslForVulkan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The shader programs of one namespace: a name, its stage files and the defines its Java call site uses. A catalog's
 * root ("visuals/shaders") is where the stage files live under {@code assets/<namespace>/}; a stage file "post/x.comp" has
 * the file id "namespace:visuals/post/x.comp", the same form a shader's {@code #import} uses.
 */
public final class ShaderCatalog {
    public enum StageKind { VERTEX, FRAGMENT, COMPUTE }

    public record Stage(StageKind kind, String id) {
    }

    public record Program(String name, List<Stage> stages, Map<String, String> defines, Map<String, String> replacements) {
        public String source(Stage stage, Function<String, String> reader) {
            return GlslForVulkan.stripPrintf(ShaderSource.assemble(ShaderSource.parse(stage.id(), reader), this.defines, this.replacements));
        }

        /** The defines for the running machine: IS_INTEL from the GPU vendor, IS_WINDOWS from the OS. */
        public Program withPlatform(Platform platform) {
            Map<String, String> d = new LinkedHashMap<>(this.defines);
            if (platform.intel()) d.put("IS_INTEL", "");
            if (platform.windows()) d.put("IS_WINDOWS", "");
            return new Program(this.name, this.stages, d, this.replacements);
        }
    }

    public record Platform(boolean intel, boolean windows) {
        public static final List<Platform> ALL = List.of(new Platform(false, false), new Platform(false, true), new Platform(true, false), new Platform(true, true));
    }

    private static final String SHADERS_DIR = "/shaders";
    private static final ShaderCatalog VISUALS = VisualsPrograms.register(new ShaderCatalog("vulkanmod", "visuals/shaders"));

    private final String namespace;
    private final String root;
    private final Map<String, Program> programs = new LinkedHashMap<>();

    public ShaderCatalog(String namespace, String root) {
        if (!root.endsWith(SHADERS_DIR)) throw new IllegalArgumentException("a catalog root ends in /shaders: " + root);
        this.namespace = namespace;
        this.root = root;
    }

    /** The fork's own catalog. */
    public static ShaderCatalog visuals() {
        return VISUALS;
    }

    public ShaderCatalog add(String name, Program program) {
        if (this.programs.putIfAbsent(name, program) != null) throw new IllegalArgumentException("duplicate shader program " + name);
        return this;
    }

    public Program get(String name) {
        Program p = this.programs.get(name);
        if (p == null) throw new IllegalArgumentException("no shader program " + name);
        return p;
    }

    public List<Program> all() {
        return List.copyOf(this.programs.values());
    }

    /** Every program for every platform define combination, as the gates compile them. */
    public List<Program> allVariants() {
        List<Program> out = new ArrayList<>();
        for (Program p : this.programs.values()) for (Platform platform : Platform.ALL) out.add(p.withPlatform(platform));
        return out;
    }

    /** The file id of a stage file given relative to the root. */
    public String fileId(String file) {
        return this.namespace + ":" + this.root.substring(0, this.root.length() - SHADERS_DIR.length()) + "/" + file;
    }

    public Stage vertex(String file) { return new Stage(StageKind.VERTEX, fileId(file)); }
    public Stage fragment(String file) { return new Stage(StageKind.FRAGMENT, fileId(file)); }
    public Stage compute(String file) { return new Stage(StageKind.COMPUTE, fileId(file)); }
}
