package it.littlesquad.api;

import it.littlesquad.network.NexusWebSocketClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.LogEvent;

import java.util.Map;

/**
 * Capture all console logs from log4j2 root logger (captures all server logs, commands, standard out)
 * and stream them to the WebSocket connection.
 */
public final class ConsoleLogAppender extends AbstractAppender {

    private static ConsoleLogAppender instance;
    private final ThreadLocal<Boolean> isLogging = ThreadLocal.withInitial(() -> false);

    private ConsoleLogAppender() {
        super("NexusConsoleLogAppender", null, null, false, Property.EMPTY_ARRAY);
    }

    /**
     * Registers the ConsoleLogAppender to the Log4j2 root logger.
     */
    public static synchronized void register() {
        if (instance != null) {
            return;
        }
        try {
            instance = new ConsoleLogAppender();
            instance.start();
            Logger root = (Logger) LogManager.getRootLogger();
            root.addAppender(instance);
        } catch (Exception ignored) {}
    }

    /**
     * Unregisters the ConsoleLogAppender from the Log4j2 root logger.
     */
    public static synchronized void unregister() {
        if (instance != null) {
            try {
                Logger root = (Logger) LogManager.getRootLogger();
                root.removeAppender(instance);
                instance.stop();
                instance = null;
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void append(LogEvent event) {
        NexusWebSocketClient ws = NexusWebSocketClient.getInstance();
        if (!ws.isConnected()) {
            return;
        }

        // Avoid infinite loop
        if (isLogging.get()) {
            return;
        }

        // Ignore logs from our own plugin/network classes to prevent loop spam
        String loggerName = event.getLoggerName();
        if (loggerName != null && loggerName.startsWith("it.littlesquad")) {
            return;
        }

        try {
            isLogging.set(true);

            String message = event.getMessage().getFormattedMessage();
            if (message == null || message.trim().isEmpty()) {
                return;
            }

            // Append throwable stacktrace if available
            Throwable thrown = event.getThrown();
            if (thrown != null) {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                thrown.printStackTrace(pw);
                message += "\n" + sw.toString();
            }

            // Map Log4j levels to console log types
            String type = "print";
            org.apache.logging.log4j.Level level = event.getLevel();
            if (level == org.apache.logging.log4j.Level.ERROR || level == org.apache.logging.log4j.Level.FATAL) {
                type = "error";
            } else if (level == org.apache.logging.log4j.Level.WARN) {
                type = "error";
            } else if (level == org.apache.logging.log4j.Level.DEBUG || level == org.apache.logging.log4j.Level.TRACE) {
                type = "serverlog";
            }

            // Match chat log patterns like "<PlayerName> message"
            if (message.matches("^<[a-zA-Z0-9_]{2,16}> .+$")) {
                type = "chat";
            }

            Map<String, Object> payload = Map.of(
                    "message", message,
                    "time", System.currentTimeMillis() / 1000.0,
                    "type", type
            );

            // Stream to ws
            ws.send("console_log", payload);

        } finally {
            isLogging.set(false);
        }
    }
}
