package it.littlesquad.api;

import it.littlesquad.config.NexusConfig;
import it.littlesquad.network.NexusHttpClient;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Automatically captures SEVERE logs and console errors and posts them
 * asynchronously to the server/add-error API route.
 * Includes rate-limiting/deduplication to prevent loop spam.
 */
public final class ErrorCollector extends Handler {

    private static ErrorCollector instance;
    private final Set<String> sentErrors = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<Boolean> isLogging = ThreadLocal.withInitial(() -> false);
    private final java.util.concurrent.atomic.AtomicInteger errorCount = new java.util.concurrent.atomic.AtomicInteger(0);

    private ErrorCollector() {}

    /**
     * Gets the total number of unique errors captured in this session.
     */
    public static int getErrorCount() {
        return instance != null ? instance.errorCount.get() : 0;
    }

    /**
     * Registers the ErrorCollector handler to the root logger.
     */
    public static synchronized void register() {
        if (instance != null) {
            return;
        }
        instance = new ErrorCollector();
        Logger.getLogger("").addHandler(instance);
    }

    /**
     * Unregisters the ErrorCollector handler from the root logger.
     */
    public static synchronized void unregister() {
        if (instance != null) {
            Logger.getLogger("").removeHandler(instance);
            instance = null;
        }
    }

    @Override
    public void publish(LogRecord record) {
        if (record.getLevel() != Level.SEVERE) {
            return;
        }

        // Avoid infinite loops/recursion
        if (isLogging.get()) {
            return;
        }

        // Ignore network logger output to prevent reporting errors caused by reporting errors
        String loggerName = record.getLoggerName();
        if (loggerName != null && loggerName.startsWith("it.littlesquad.network")) {
            return;
        }

        String message = record.getMessage();
        if (message != null && (message.contains("[HTTP]") || message.contains("[WS]"))) {
            return;
        }

        try {
            isLogging.set(true);
            processError(record);
        } finally {
            isLogging.set(false);
        }
    }

    private void processError(LogRecord record) {
        String message = record.getMessage();
        if (message == null) {
            message = "Severe error logged";
        }

        // Format parameters if any (for loggers that pass formatted arguments)
        if (record.getParameters() != null && record.getParameters().length > 0) {
            try {
                message = java.text.MessageFormat.format(message, record.getParameters());
            } catch (Exception ignored) {}
        }

        Throwable thrown = record.getThrown();
        String stacktrace = "";
        if (thrown != null) {
            stacktrace = getStackTrace(thrown);
        } else {
            Object[] params = record.getParameters();
            if (params != null) {
                for (Object param : params) {
                    if (param instanceof Throwable) {
                        stacktrace = getStackTrace((Throwable) param);
                        break;
                    }
                }
            }
        }

        if (stacktrace.isEmpty() && record.getSourceClassName() != null) {
            stacktrace = "at " + record.getSourceClassName() + "." + record.getSourceMethodName() + " (Unknown Source)";
        }

        // Hash the error to prevent sending the same error twice in the same session
        String hash = getMd5(message + "|" + stacktrace);
        if (sentErrors.contains(hash)) {
            return;
        }
        sentErrors.add(hash);
        errorCount.incrementAndGet();

        final String finalMessage = message;
        final String finalStacktrace = stacktrace;

        // POST request asynchronously to avoid blocking the main server thread
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> payload = Map.of(
                        "message", finalMessage,
                        "stacktrace", finalStacktrace
                );
                NexusHttpClient.getInstance().post("server/add-error", payload);
            } catch (Exception ignored) {
                // Silently ignore to avoid recursion and console spam
            }
        });
    }

    private String getStackTrace(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    private String getMd5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    @Override
    public void flush() {}

    @Override
    public void close() throws SecurityException {
        unregister();
    }
}
