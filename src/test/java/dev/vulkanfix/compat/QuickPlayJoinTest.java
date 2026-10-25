package dev.vulkanfix.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quick play (the launcher's "join world directly") reaches QuickPlay.connect from the LoadingOverlay's finish
 * callback, while LoadingOverlay.render is drawing a Reforged frame. The resource reload is already done, so
 * thenRunAsync(join, minecraft) ran the join inline, and Minecraft.doWorldLoad's nested runTick frames ended the outer
 * frame's command recording. The rest of the loading overlay then drew with no render pass (diag: recording=false,
 * NPE "state.renderPass is null"), and Reforged's drawWithShader threw before releasing its RenderedBuffer: an
 * ImmediatelyFast pool BufferBuilder grew ~19 MB/s until it overflowed at 2 GB. vulkanfix queues the join on
 * Minecraft's task queue so it runs before the next frame begins, as a world opened from the world list does.
 */
class QuickPlayJoinTest {
    private static final String MIXIN = "reforged.QuickPlayJoinMixin";

    @Test
    void registeredAndGatedOnVulkanMod() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("vulkanfix.mixins.json")) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("\"" + MIXIN + "\""));
        }
        assertTrue(CompatGate.shouldApply(MIXIN, Set.of("vulkanmod")::contains));
        assertFalse(CompatGate.shouldApply(MIXIN, Set.<String>of()::contains));
    }

    /** What happened: an event loop executing on its own thread outside a task runs a done future's action inline. */
    @Test
    void anInlineExecutorJoinsInsideTheFrame() {
        List<String> order = new ArrayList<>();
        Executor inline = Runnable::run;
        CompletableFuture.completedFuture(null).thenRunAsync(() -> order.add("join"), inline);
        order.add("rest of frame");
        assertEquals(List.of("join", "rest of frame"), order);
    }

    @Test
    void queuedJoinRunsAfterTheFrame() {
        List<String> order = new ArrayList<>();
        ArrayDeque<Runnable> taskQueue = new ArrayDeque<>();
        CompletableFuture<Void> joined = QuickPlayJoin.queued(CompletableFuture.completedFuture(null),
                () -> order.add("join"), taskQueue::add);
        order.add("rest of frame");
        assertFalse(joined.isDone());

        taskQueue.forEach(Runnable::run);
        assertEquals(List.of("rest of frame", "join"), order);
        assertTrue(joined.isDone());
    }

    @Test
    void queuedJoinWaitsForAReloadStillRunning() {
        List<String> order = new ArrayList<>();
        ArrayDeque<Runnable> taskQueue = new ArrayDeque<>();
        CompletableFuture<Void> reload = new CompletableFuture<>();
        QuickPlayJoin.queued(reload, () -> order.add("join"), taskQueue::add);
        assertTrue(taskQueue.isEmpty());

        reload.complete(null);
        taskQueue.forEach(Runnable::run);
        assertEquals(List.of("join"), order);
    }

    @Test
    void redirectsTheJoinScheduledByConnect() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("dev/vulkanfix/mixin/reforged/QuickPlayJoinMixin.class")) {
            assertNotNull(in, "mixin class not compiled");
            new ClassReader(in).accept(node, 0);
        }
        MethodNode handler = node.methods.stream()
                .filter(m -> annotation(m.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Redirect;") != null)
                .findFirst().orElseThrow();
        AnnotationNode redirect = annotation(handler.visibleAnnotations, "Lorg/spongepowered/asm/mixin/injection/Redirect;");
        assertEquals(List.of("m_278613_(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/main/GameConfig$QuickPlayData;"
                + "Lnet/minecraft/server/packs/resources/ReloadInstance;Lcom/mojang/realmsclient/client/RealmsClient;)V"),
                value(redirect, "method"));
        assertEquals("Ljava/util/concurrent/CompletableFuture;thenRunAsync(Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)"
                + "Ljava/util/concurrent/CompletableFuture;", value((AnnotationNode) value(redirect, "at"), "target"));
        assertEquals(1, value(redirect, "require"));
        assertEquals("(Ljava/util/concurrent/CompletableFuture;Ljava/lang/Runnable;Ljava/util/concurrent/Executor;)"
                + "Ljava/util/concurrent/CompletableFuture;", handler.desc);
        assertTrue((handler.access & Opcodes.ACC_STATIC) != 0, "QuickPlay.connect is static");
    }

    private static AnnotationNode annotation(List<AnnotationNode> list, String desc) {
        return list == null ? null : list.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    private static Object value(AnnotationNode a, String key) {
        return a.values.get(a.values.indexOf(key) + 1);
    }
}
