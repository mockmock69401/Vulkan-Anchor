package net.vulkanmod.visuals.vk.shader;

import org.lwjgl.util.shaderc.Shaderc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Compiles GLSL with shaderc for an OpenGL or Vulkan target; returns null on success, else the error text. */
public final class ShadercTestUtil {
    private ShadercTestUtil() {
    }

    public record Job(String label, ShaderCatalog.Program program, ShaderCatalog.Stage stage) {
    }

    /**
     * Every stage of every program, once per platform variant that can compile differently: all four when the stage
     * mentions IS_INTEL or IS_WINDOWS, else only the plain one, since unread defines change nothing.
     */
    public static List<Job> distinctStages(ShaderCatalog catalog) {
        List<Job> jobs = new ArrayList<>();
        for (ShaderCatalog.Program p : catalog.all()) {
            for (ShaderCatalog.Stage s : p.stages()) {
                String plain = p.source(s, ShaderSource.classpath());
                if (plain.contains("IS_INTEL") || plain.contains("IS_WINDOWS")) {
                    for (ShaderCatalog.Platform platform : ShaderCatalog.Platform.ALL) jobs.add(new Job(p.name() + " " + platform, p.withPlatform(platform), s));
                } else {
                    jobs.add(new Job(p.name(), p, s));
                }
            }
        }
        return jobs;
    }

    /** Runs the check on every job across cores and returns its failures in job order; the check returns null on success. */
    public static List<String> failures(List<Job> jobs, Function<Job, String> check) {
        return jobs.parallelStream().map(check).filter(Objects::nonNull).toList();
    }

    public static int kind(ShaderCatalog.StageKind stage) {
        return switch (stage) {
            case VERTEX -> Shaderc.shaderc_glsl_vertex_shader;
            case FRAGMENT -> Shaderc.shaderc_glsl_fragment_shader;
            case COMPUTE -> Shaderc.shaderc_glsl_compute_shader;
        };
    }

    public static String compile(String name, String source, int kind, boolean vulkan) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        if (vulkan) {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
            //As the game compiles: the optimiser's legalisation rejects what the front end lets through (P7i: image parameters)
            Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
        } else {
            Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_opengl, Shaderc.shaderc_env_version_opengl_4_5);
            Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_3);
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
        }
        long result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, name, "main", options);
        try {
            if (result != 0L && Shaderc.shaderc_result_get_compilation_status(result) == Shaderc.shaderc_compilation_status_success) {
                return null;
            }
            return result == 0L ? "no result" : Shaderc.shaderc_result_get_error_message(result);
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
