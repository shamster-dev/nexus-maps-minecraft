package it.littlesquad.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import it.littlesquad.config.NexusConfig;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Singleton HTTP client for communicating with the NexusMaps REST API.
 *
 * <p>This client provides asynchronous HTTP methods for sending data to the
 * NexusMaps server. All requests are non-blocking and return {@link CompletableFuture}
 * to avoid freezing the server thread.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * NexusHttpClient http = NexusHttpClient.getInstance();
 *
 * // Send a POST request
 * Map<String, Object> data = Map.of("key", "value");
 * http.post("server/save-metrics", data)
 *     .thenAccept(response -> {
 *         if (response.statusCode() == 200) {
 *             System.out.println("Success!");
 *         }
 *     });
 *
 * // Send a GET request
 * http.get("server/status")
 *     .thenAccept(response -> {
 *         String body = response.body();
 *         // Parse JSON response
 *     });
 * }</pre>
 *
 * <h2>API Routes</h2>
 * <table border="1">
 *   <tr><th>Route</th><th>Method</th><th>Description</th></tr>
 *   <tr><td>server/initialized</td><td>POST</td><td>Register server on startup</td></tr>
 *   <tr><td>server/add-error</td><td>POST</td><td>Report an error</td></tr>
 *   <tr><td>server/save-metrics</td><td>POST</td><td>Send collected metrics</td></tr>
 * </table>
 *
 * <h2>Configuration</h2>
 * <p>The base URL is determined by {@link NexusConfig}:</p>
 * <ul>
 *   <li>Production: {@code https://www.nexusmaps.xyz}</li>
 *   <li>Test mode: {@code http://localhost:3000}</li>
 * </ul>
 *
 * @see NexusWebSocketClient
 * @see NexusConfig
 */
public final class NexusHttpClient {

    private static final NexusHttpClient INSTANCE = new NexusHttpClient();

    private final HttpClient httpClient;
    private final Gson gson = new GsonBuilder().create();
    private Logger logger;

    private NexusHttpClient() {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);

        // Configure TLS/SSL context for secure connections
        try {
            SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
            sslContext.init(null, null, null);
            builder.sslContext(sslContext);
        } catch (NoSuchAlgorithmException e) {
            // TLS 1.3 not available, fall back to default (TLS 1.2)
            try {
                SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
                sslContext.init(null, null, null);
                builder.sslContext(sslContext);
            } catch (Exception ignored) {
                // Use default SSL context
            }
        } catch (Exception ignored) {
            // Use default SSL context
        }

        this.httpClient = builder.build();
    }

    /**
     * Returns the singleton instance of the HTTP client.
     *
     * @return The NexusHttpClient instance
     */
    public static NexusHttpClient getInstance() {
        return INSTANCE;
    }

    /**
     * Sets the logger for debug output.
     *
     * <p>When debug mode is enabled in config.yml, request/response details
     * will be logged to this logger.</p>
     *
     * @param logger The logger instance (typically from JavaPlugin.getLogger())
     */
    public void setLogger(Logger logger) {
        this.logger = logger;
    }

    /**
     * Returns the Gson instance used for JSON serialization.
     *
     * <p>Use this to serialize/deserialize custom objects consistently
     * with the client's configuration.</p>
     *
     * @return The Gson instance
     */
    public Gson getGson() {
        return gson;
    }

    /**
     * Sends an asynchronous POST request with a JSON body.
     *
     * <p>The payload object is automatically serialized to JSON using Gson.
     * The request includes appropriate Content-Type and Accept headers.</p>
     *
     * <h3>Example</h3>
     * <pre>{@code
     * Map<String, Object> payload = Map.of(
     *     "ip", "0.0.0.0",
     *     "port", 25565,
     *     "seed", 123456789L
     * );
     *
     * http.post("server/initialized", payload)
     *     .thenAccept(response -> {
     *         System.out.println("Status: " + response.statusCode());
     *         System.out.println("Body: " + response.body());
     *     })
     *     .exceptionally(error -> {
     *         error.printStackTrace();
     *         return null;
     *     });
     * }</pre>
     *
     * @param route The API endpoint (without base URL), e.g., "server/initialized"
     * @param payload The object to serialize as JSON body
     * @return A CompletableFuture containing the HTTP response
     */
    public CompletableFuture<HttpResponse<String>> post(String route, Object payload) {
        String json = gson.toJson(payload);
        String url = NexusConfig.get().getDomain() + "/api/webhooks/" + route;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json));

        NexusConfig config = NexusConfig.get();
        if (config.hasApiKey()) {
            builder.header("API-KEY", config.getApiKey());
        }

        HttpRequest request = builder.build();

        if (NexusConfig.get().isDebug()) {
            log("POST -> " + url);
        }

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        logError("Error: " + error.getMessage());
                    } else if (NexusConfig.get().isDebug()) {
                        log("Response [" + response.statusCode() + "]");
                    }
                });
    }

    /**
     * Sends an asynchronous GET request.
     *
     * <h3>Example</h3>
     * <pre>{@code
     * http.get("server/status")
     *     .thenAccept(response -> {
     *         if (response.statusCode() == 200) {
     *             MyData data = gson.fromJson(response.body(), MyData.class);
     *         }
     *     });
     * }</pre>
     *
     * @param route The API endpoint (without base URL)
     * @return A CompletableFuture containing the HTTP response
     */
    public CompletableFuture<HttpResponse<String>> get(String route) {
        String url = NexusConfig.get().getDomain() + "/api/webhooks/" + route;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET();

        NexusConfig config = NexusConfig.get();
        if (config.hasApiKey()) {
            builder.header("API-KEY", config.getApiKey());
        }

        HttpRequest request = builder.build();

        if (NexusConfig.get().isDebug()) {
            log("GET -> " + url);
        }

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        logError("Error: " + error.getMessage());
                    } else if (NexusConfig.get().isDebug()) {
                        log("Response [" + response.statusCode() + "]");
                    }
                });
    }

    private void log(String message) {
        if (logger != null) logger.info("[HTTP] " + message);
    }

    private void logError(String message) {
        if (logger != null) logger.severe("[HTTP] " + message);
    }
}
