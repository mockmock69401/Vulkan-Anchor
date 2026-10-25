package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An @Inject handler must take either only the callback info or every argument of the target method followed by it.
 * A handler shared by targets with different arguments fails the injection check at game start
 * (MixinTransformerError, exit code -1), which no other unit test notices. Checks every @Inject whose method
 * strings carry a full descriptor.
 */
class InjectHandlerSignatureTest {
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

    @Test
    void everyHandlerMatchesEachOfItsTargets() throws Exception {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (ClassNode mixin : mixinClasses()) {
            for (MethodNode handler : mixin.methods) {
                AnnotationNode inject = annotation(handler, INJECT);
                if (inject == null) {
                    continue;
                }
                for (Object target : (List<?>) value(inject, "method")) {
                    String spec = (String) target;
                    int paren = spec.indexOf('(');
                    if (paren < 0) {
                        continue;
                    }
                    checked++;
                    String problem = mismatch(Type.getMethodType(spec.substring(paren)), Type.getMethodType(handler.desc));
                    if (problem != null) {
                        problems.add(mixin.name + "#" + handler.name + " -> " + spec + ": " + problem);
                    }
                }
            }
        }
        assertTrue(checked > 0, "no descriptor-qualified injections found");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void detectsAHandlerSharedByTargetsWithDifferentArguments() {
        Type handler = Type.getMethodType("(I" + CIR + ")V");
        assertFalse(mismatch(Type.getMethodType("(II)Ljava/nio/ByteBuffer;"), handler) == null);
        assertTrue(mismatch(Type.getMethodType("(II)Ljava/nio/ByteBuffer;"), Type.getMethodType("(II" + CIR + ")V")) == null);
        assertTrue(mismatch(Type.getMethodType("(II)V"), Type.getMethodType("(" + CI + ")V")) == null);
    }

    /** null when the handler's parameters fit the target. */
    private static String mismatch(Type target, Type handler) {
        String callback = target.getReturnType().getSort() == Type.VOID ? CI : CIR;
        Type[] params = handler.getArgumentTypes();
        Type[] targetArgs = target.getArgumentTypes();
        if (params.length == 1) {
            return params[0].getDescriptor().equals(callback) ? null : "expected " + callback;
        }
        if (params.length < targetArgs.length + 1) {
            return "takes " + Arrays.toString(params) + " but target args are " + Arrays.toString(targetArgs);
        }
        for (int i = 0; i < targetArgs.length; i++) {
            if (!params[i].equals(targetArgs[i])) {
                return "arg " + i + " is " + params[i] + ", target has " + targetArgs[i];
            }
        }
        return params[targetArgs.length].getDescriptor().equals(callback) ? null
                : "arg " + targetArgs.length + " must be " + callback;
    }

    private List<ClassNode> mixinClasses() throws IOException, URISyntaxException {
        Path root = Path.of(getClass().getClassLoader().getResource("dev/vulkanfix/mixin").toURI());
        List<ClassNode> nodes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                ClassNode node = new ClassNode();
                try (InputStream in = Files.newInputStream(file)) {
                    new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
                }
                nodes.add(node);
            }
        }
        return nodes;
    }

    private static AnnotationNode annotation(MethodNode m, String desc) {
        return m.visibleAnnotations == null ? null
                : m.visibleAnnotations.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    private static Object value(AnnotationNode a, String key) {
        return a.values.get(a.values.indexOf(key) + 1);
    }
}
