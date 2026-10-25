package net.vulkanmod.visuals.vk.shader;

import org.lwjgl.util.shaderc.Shaderc;

/** Compiles GLSL with shaderc for an OpenGL or Vulkan target; returns null on success, else the error text. */
public final class ShadercTestUtil {
    private ShadercTestUtil() {
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
