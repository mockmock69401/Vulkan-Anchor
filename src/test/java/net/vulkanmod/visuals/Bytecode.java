package net.vulkanmod.visuals;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Reads compiled classes from the test classpath for the wiring tests. */
public final class Bytecode {
    private Bytecode() {
    }

    public static ClassNode read(String internalName) throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = Bytecode.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, internalName);
            new ClassReader(in).accept(node, 0);
        }
        return node;
    }

    /** The first method with this name. */
    public static MethodNode method(String internalName, String name) throws Exception {
        for (MethodNode m : read(internalName).methods) {
            if (m.name.equals(name)) return m;
        }
        throw new AssertionError("no method " + name + " in " + internalName);
    }

    /** The calls of every method with this name (and its lambdas), as "Owner.name" with the owner's simple name. */
    public static List<String> calls(String internalName, String method) throws Exception {
        List<String> calls = new ArrayList<>();
        for (MethodNode m : read(internalName).methods) {
            if (!m.name.equals(method) && !m.name.startsWith("lambda$" + method + "$")) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) calls.add(name(c));
            }
        }
        return calls;
    }

    public static String name(MethodInsnNode c) {
        return c.owner.substring(c.owner.lastIndexOf('/') + 1) + "." + c.name;
    }

    /** The real instruction before insn (labels, line numbers and frames skipped), or null. */
    public static AbstractInsnNode previous(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && p.getOpcode() < 0) p = p.getPrevious();
        return p;
    }

    /** The nearest method call before insn, or null. */
    public static MethodInsnNode previousCall(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && !(p instanceof MethodInsnNode)) p = p.getPrevious();
        return (MethodInsnNode) p;
    }

    /** Whether expected appears in calls in this order (other calls may sit between). */
    public static boolean inOrder(List<String> calls, List<String> expected) {
        int next = 0;
        for (String c : calls) if (next < expected.size() && c.equals(expected.get(next))) next++;
        return next == expected.size();
    }

    public static long count(List<String> calls, String call) {
        return calls.stream().filter(call::equals).count();
    }
}
