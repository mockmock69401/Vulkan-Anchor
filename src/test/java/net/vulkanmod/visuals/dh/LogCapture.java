package net.vulkanmod.visuals.dh;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Collects the warnings one logger writes while it is open. */
final class LogCapture implements AutoCloseable {
    private final org.apache.logging.log4j.core.Logger logger;
    private final AbstractAppender appender;
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    LogCapture(String loggerName) {
        this.logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerName);
        this.appender = new AbstractAppender("capture-" + System.nanoTime(), null, null, true) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.WARN) LogCapture.this.warnings.add(event.getMessage().getFormattedMessage());
            }
        };
        this.appender.start();
        this.logger.addAppender(this.appender);
    }

    /** The visuals' logger (VisualsLog). */
    static LogCapture visuals() {
        return new LogCapture("VulkanMod Visuals");
    }

    List<String> warnings() {
        return this.warnings;
    }

    @Override
    public void close() {
        this.logger.removeAppender(this.appender);
        this.appender.stop();
    }
}
