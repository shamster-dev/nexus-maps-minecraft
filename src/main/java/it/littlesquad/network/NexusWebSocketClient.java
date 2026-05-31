package it.littlesquad.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.littlesquad.config.NexusConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Singleton WebSocket client for real-time communication with NexusMaps.
 *
 * <p>Uses OkHttp to support custom headers (e.g. Authorization: Bearer)
 * during the WebSocket handshake — which Java's built-in HttpClient does
 * not allow due to spec restrictions.</p>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Automatic reconnection on connection loss</li>
 *   <li>Periodic ping/pong for keep-alive</li>
 *   <li>JSON serialization via Gson</li>
 *   <li>Non-blocking async operations</li>
 * </ul>
 *
 * <h2>Message Format</h2>
 * <pre>{@code
 * {
 *   "route": "route_name",
 *   "data": { ... }
 * }
 * }</pre>
 *
 * @see NexusHttpClient
 * @see NexusConfig
 */
public final class NexusWebSocketClient {

    private static final NexusWebSocketClient INSTANCE = new NexusWebSocketClient();

    private final OkHttpClient okHttpClient;
    private final Gson gson = new GsonBuilder().create();
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private WebSocket webSocket;
    private Logger logger;
    private MessageHandler messageHandler;
    private Runnable onConnectListener;

    private NexusWebSocketClient() {
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for WS reads
                .pingInterval(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Returns the singleton instance of the WebSocket client.
     *
     * @return The NexusWebSocketClient instance
     */
    public static NexusWebSocketClient getInstance() {
        return INSTANCE;
    }

    /**
     * Sets the logger for debug output.
     *
     * @param logger The logger instance
     */
    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    /**
     * Returns the Gson instance used for JSON serialization.
     *
     * @return The Gson instance
     */
    public Gson getGson() {
        return gson;
    }

    /**
     * Sets the handler for incoming messages from the server.
     *
     * @param handler The message handler, or null to remove
     */
    public void setMessageHandler(MessageHandler handler) {
        this.messageHandler = handler;
    }

    /**
     * Sets the listener to run when the connection is opened.
     *
     * @param listener The runnable listener
     */
    public void setOnConnectListener(Runnable listener) {
        this.onConnectListener = listener;
    }

    /**
     * Establishes a WebSocket connection to the NexusMaps server.
     *
     * <p>Sends the API key as {@code Authorization: Bearer <key>} header
     * during the WebSocket handshake.</p>
     *
     * @return A CompletableFuture that completes when connected or fails
     */
    public CompletableFuture<Void> connect() {
        NexusConfig config = NexusConfig.get();
        String url = config.getWebsocketUrl();
        log("Connecting to " + url);

        Request.Builder requestBuilder = new Request.Builder().url(url);

        if (config.hasApiKey()) {
            requestBuilder.header("Authorization", "Bearer " + config.getApiKey());
        }

        Request request = requestBuilder.build();
        CompletableFuture<Void> future = new CompletableFuture<>();

        webSocket = okHttpClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                isConnected.set(true);
                log("Connection opened");
                log("Connected!");
                future.complete(null);
                if (onConnectListener != null) {
                    try {
                        onConnectListener.run();
                    } catch (Exception e) {
                        logError("Error in onConnectListener: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (NexusConfig.get().isDebug()) {
                    log("Received: " + text);
                }
                if (messageHandler != null) {
                    try {
                        messageHandler.onMessage(text);
                    } catch (Exception e) {
                        logError("Handler error: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                onMessage(ws, bytes.utf8());
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log("Closed: " + code + " - " + reason);
                isConnected.set(false);
                if (code != 1000) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                logError("Connection failed: " + t.getMessage());
                isConnected.set(false);
                if (!future.isDone()) {
                    future.completeExceptionally(t);
                }
                scheduleReconnect();
            }
        });

        return future;
    }

    /**
     * Gracefully disconnects from the WebSocket server.
     */
    public void disconnect() {
        shouldReconnect.set(false);
        scheduler.shutdown();
        if (webSocket != null) {
            log("Disconnecting...");
            webSocket.close(1000, "Plugin shutdown");
        }
        isConnected.set(false);
    }

    /**
     * Reconnects to the WebSocket server, re-enabling automatic reconnection.
     *
     * <p>Correctly resets the {@code shouldReconnect} flag so that automatic
     * reconnection on connection loss is preserved after the new connection.</p>
     */
    public void reconnect() {
        shouldReconnect.set(true);
        if (webSocket != null) {
            webSocket.close(1000, "Reconnecting");
        }
        isConnected.set(false);
        connect();
    }

    /**
     * Sends an object as JSON through the WebSocket, formatted as event|data.
     *
     * @param event The event name
     * @param data The object to serialize and send
     * @return true if the message was enqueued successfully
     */
    public boolean send(String event, Object data) {
        if (!isConnected.get() || webSocket == null) {
            return false;
        }
        String json = gson.toJson(data);
        String message = event + "|" + json;
        if (NexusConfig.get().isDebug()) {
            log("Send: " + message);
        }
        return webSocket.send(message);
    }

    /**
     * Sends an object as JSON through the WebSocket.
     * If the payload is a Map with "route" and "data", it will be unpacked
     * and sent in the "event|data" format.
     *
     * @param payload The object to serialize and send
     * @return true if the message was enqueued successfully
     */
    public boolean send(Object payload) {
        if (payload instanceof Map<?, ?> map) {
            Object route = map.get("route");
            Object data = map.get("data");
            if (route instanceof String && data != null) {
                return send((String) route, data);
            }
        }
        if (!isConnected.get() || webSocket == null) {
            return false;
        }
        String json = gson.toJson(payload);
        if (NexusConfig.get().isDebug()) {
            log("Send: " + json);
        }
        return webSocket.send(json);
    }

    /**
     * Sends a raw JSON string through the WebSocket.
     *
     * @param message The JSON string to send
     * @return true if the message was enqueued successfully
     */
    public boolean sendRaw(String message) {
        if (!isConnected.get() || webSocket == null) {
            return false;
        }
        if (NexusConfig.get().isDebug()) {
            log("Send: " + message);
        }
        return webSocket.send(message);
    }

    /**
     * Returns the current connection status.
     *
     * @return {@code true} if connected
     */
    public boolean isConnected() {
        return isConnected.get();
    }

    private void scheduleReconnect() {
        if (!shouldReconnect.get()) return;
        log("Reconnecting in 5 seconds...");
        scheduler.schedule(() -> {
            if (shouldReconnect.get() && !isConnected.get()) {
                connect();
            }
        }, 5, TimeUnit.SECONDS);
    }

    private void log(String message) {
        if (logger != null) logger.info("[WS] " + message);
    }

    private void logError(String message) {
        if (logger != null) logger.severe("[WS] " + message);
    }

    /**
     * Functional interface for handling incoming WebSocket messages.
     */
    @FunctionalInterface
    public interface MessageHandler {
        void onMessage(String message);
    }
}
