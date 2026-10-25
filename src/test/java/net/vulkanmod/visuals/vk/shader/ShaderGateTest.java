package net.vulkanmod.visuals.vk.shader;

import net.vulkanmod.visuals.vk.GlslForVulkan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G1 (shader part): every visuals program compiles for Vulkan 1.2 after conversion, within Vulkan's guaranteed limits. */
class ShaderGateTest {
    @Test
    void everyProgramCompilesForVulkan() {
        List<String> failures = new ArrayList<>();
        for (ShaderCatalog.Program p : ShaderCatalog.visuals().allVariants()) {
            for (ShaderCatalog.Stage s : p.stages()) {
                GlslForVulkan.Converted c = GlslForVulkan.toVulkan(p.source(s, ShaderSource.classpath()), p.defines());
                String err = ShadercTestUtil.compile(p.name() + ":" + s.id(), c.source(), ShadercTestUtil.kind(s.kind()), true);
                if (err != null) failures.add(p.name() + " " + s.id() + "\n" + err.lines().limit(6).reduce("", (a, b) -> a + "\n  " + b));
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " stage(s) fail:\n" + String.join("\n\n", failures));
    }

    @Test
    void pushConstantsFitTheGuaranteedLimit() {
        for (ShaderCatalog.Program p : ShaderCatalog.visuals().allVariants()) {
            for (ShaderCatalog.Stage s : p.stages()) {
                int size = GlslForVulkan.toVulkan(p.source(s, ShaderSource.classpath()), p.defines()).meta().pushConstantBytes();
                assertTrue(size <= 128, p.name() + " " + s.id() + " push constants " + size + " > 128");
            }
        }
    }

    @Test
    void noTwoResourcesShareASetAndBinding() {
        for (ShaderCatalog.Program p : ShaderCatalog.visuals().allVariants()) {
            for (ShaderCatalog.Stage s : p.stages()) {
                Set<String> seen = new HashSet<>();
                for (VulkanShaderMeta.Binding b : GlslForVulkan.toVulkan(p.source(s, ShaderSource.classpath()), p.defines()).meta().bindings()) {
                    assertTrue(seen.add(b.set() + ":" + b.binding()), p.name() + " " + s.id() + " duplicates set " + b.set() + " binding " + b.binding());
                }
            }
        }
    }

    private static final Pattern VARYING = Pattern.compile("layout\\(location = (\\d+)\\)[ \\t]*(?:\\w+[ \\t]+)*?(in|out)[ \\t]+(?:\\w+[ \\t]+)*?\\w+[ \\t]+(\\w+)[ \\t]*;");

    private static Map<String, String> varyings(String vulkanSource, String direction) {
        Map<String, String> byName = new HashMap<>();
        Matcher m = VARYING.matcher(vulkanSource);
        while (m.find()) {
            if (m.group(2).equals(direction)) byName.put(m.group(3), m.group(1));
        }
        return byName;
    }

    /** Implicit varying locations are assigned per stage; the vertex outputs a fragment stage reads must land on the same location. */
    @Test
    void vertexOutputsMeetFragmentInputsAtTheSameLocation() {
        for (ShaderCatalog.Program p : ShaderCatalog.visuals().allVariants()) {
            if (p.stages().size() != 2) continue;
            Map<String, String> outs = varyings(GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).source(), "out");
            Map<String, String> ins = varyings(GlslForVulkan.toVulkan(p.source(p.stages().get(1), ShaderSource.classpath()), p.defines()).source(), "in");
            for (Map.Entry<String, String> in : ins.entrySet()) {
                assertEquals(in.getValue(), outs.get(in.getKey()), p.name() + " varying " + in.getKey());
            }
        }
    }

    /** Push constant offsets are computed per stage; a member both stages declare must sit at the same offset. */
    @Test
    void sharedLooseUniformsHaveOneOffsetAcrossStages() {
        for (ShaderCatalog.Program p : ShaderCatalog.visuals().allVariants()) {
            if (p.stages().size() != 2) continue;
            VulkanShaderMeta vert = GlslForVulkan.toVulkan(p.source(p.stages().get(0), ShaderSource.classpath()), p.defines()).meta();
            VulkanShaderMeta frag = GlslForVulkan.toVulkan(p.source(p.stages().get(1), ShaderSource.classpath()), p.defines()).meta();
            for (VulkanShaderMeta.PushMember a : vert.push()) {
                for (VulkanShaderMeta.PushMember b : frag.push()) {
                    if (a.name().equals(b.name())) assertEquals(a.offset(), b.offset(), p.name() + " " + a.name());
                }
            }
        }
    }
}
