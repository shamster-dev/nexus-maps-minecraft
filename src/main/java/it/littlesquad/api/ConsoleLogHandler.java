package it.littlesquad.api;

import it.littlesquad.network.NexusWebSocketClient;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Capture all console logs from the server and stream them to the WebSocket connection in real-time.
 */
public final class ConsoleLogHandler extends Handler {

    private static ConsoleLogHandler instance;
    private final ThreadLocal<Boolean> isLogging = ThreadLocal.withInitial(() -> false);

    private ConsoleLogHandler() {}

    /**
     * Registers the ConsoleLogHandler to the root logger.
     */
    public static synchronized void register() {
        if (instance != null) {
            return;
        }
        instance = new ConsoleLogHandler();
        Logger.getLogger("").addHandler(instance);
    }

    /**
     * Unregisters the ConsoleLogHandler from the root logger.
     */
    public static synchronized void unregister() {
        if (instance != null) {
            Logger.getLogger("").removeHandler(instance);
            instance = null;
        }
    }

    @Override
    public void publish(LogRecord record) {
        NexusWebSocketClient ws = NexusWebSocketClient.getInstance();
        if (!ws.isConnected()) {
            return;
        }

        // Avoid recursion
        if (isLogging.get()) {
            return;
        }

        // Ignore logs from our own plugin/network classes to prevent loop spam
        String loggerName = record.getLoggerName();
        if (loggerName != null && loggerName.startsWith("it.littlesquad")) {
            return;
        }

        try {
            isLogging.set(true);

            String message = record.getMessage();
            if (message == null) {
                return;
            }

            // Format message if it has parameters
            if (record.getParameters() != null && record.getParameters().length > 0) {
                try {
                    message = java.text.MessageFormat.format(message, record.getParameters());
                } catch (Exception ignored) {}
            }

            // Append throwable stacktrace if available
            Throwable thrown = record.getThrown();
            if (thrown != null) {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                thrown.printStackTrace(pw);
                message += "\n" + sw.toString();
            }

            // Map standard java.util.logging levels to console log types
            String type = "print";
            Level level = record.getLevel();
            if (level == Level.SEVERE) {
                type = "error";
            } else if (level == Level.WARNING) {
                type = "error";
            } else if (level == Level.CONFIG) {
                type = "serverlog";
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

    @Override
    public void flush() {}

    @Override
    public void close() throws SecurityException {
        unregister();
    }
}
