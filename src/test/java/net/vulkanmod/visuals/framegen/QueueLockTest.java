package net.vulkanmod.visuals.framegen;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every queue submit, present and wait-idle goes through QueueLock (FG design 3.2); a direct call elsewhere would race the pacer thread. */
class QueueLockTest {
    private static final Pattern CALLS = Pattern.compile("vkQueueSubmit\\(|vkQueuePresentKHR\\(|vkQueueWaitIdle\\(|vkDeviceWaitIdle\\(");
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    @Test
    void onlyQueueLockTouchesTheQueue() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String root : List.of("src/main/java", "src/vulkanfix/java")) {
            if (!Files.isDirectory(Path.of(root))) continue;
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    if (p.getFileName().toString().equals("QueueLock.java")) continue;
                    //Comments are stripped first so a mention in prose does not trip the scan
                    String code = COMMENTS.matcher(Files.readString(p)).replaceAll("");
                    if (CALLS.matcher(code).find()) offenders.add(p.toString().replace('\\', '/'));
                }
            }
        }
        assertEquals(List.of(), offenders);
    }
}
