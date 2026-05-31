package it.littlesquad.api;

import it.littlesquad.network.NexusHttpClient;
import it.littlesquad.network.NexusWebSocketClient;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.CompletableFuture;

/**
 * Base interface for all NexusMaps modules.
 *
 * <p>Modules are the core building blocks of NexusMaps data collection.
 * Each module is responsible for collecting a specific type of data
 * and sending it to the NexusMaps server.</p>
 *
 * <h2>Module Types</h2>
 * <ul>
 *   <li><b>Base Modules</b> - Collect server metrics (TPS, players, memory, etc.).
 *       These go into the "base" section of save-metrics payload.</li>
 *   <li><b>Custom Modules</b> - User-defined modules for custom data collection.
 *       These go into the "custom" section of save-metrics payload.</li>
 *   <li><b>Event Modules</b> - React to events (player join/quit) rather than
 *       periodic collection.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <pre>
 * ModuleManager.registerModule(module)
 *        │
 *        ▼
 *    onEnable()          ← Module initialization
 *        │
 *        ▼
 *    ┌─────────────────────────────────────┐
 *    │  onCycle() called every 30 minutes  │
 *    │  getValue() collected for metrics   │
 *    │  onTick() called every server tick  │
 *    └─────────────────────────────────────┘
 *        │
 *        ▼
 *    onDisable()         ← Plugin shutdown or module unregistered
 * </pre>
 *
 * <h2>Implementation Example</h2>
 * <pre>{@code
 * public class MyModule implements NexusModule<Integer> {
 *
 *     @Override
 *     public String getName() {
 *         return "my_metric";
 *     }
 *
 *     @Override
 *     public String getRoute() {
 *         return "server/save-metrics";
 *     }
 *
 *     @Override
 *     public Transport getTransport() {
 *         return Transport.HTTP;
 *     }
 *
 *     @Override
 *     public CompletableFuture<Integer> getValue() {
 *         return CompletableFuture.supplyAsync(() -> {
 *             // Collect your data here (can be async, e.g., database calls)
 *             return 42;
 *         });
 *     }
 * }
 * }</pre>
 *
 * @param <T> The type of data this module collects. For base modules, typically
 *            {@link Number}. For custom modules, typically {@link java.util.Map}
 *            containing numeric, map, and pie chart data.
 *
 * @see BaseMetricModule
 * @see PlayerEventModule
 * @see ModuleManager
 */
public interface NexusModule<T> {

    /**
     * Returns the unique identifier for this module.
     *
     * <p>This name is used as the key in the metrics payload sent to the server.
     * For base modules, use snake_case names matching the API specification
     * (e.g., "tps", "active_players", "memory_usage").</p>
     *
     * @return The unique module name, never null
     */
    String getName();

    /**
     * Returns the API route/endpoint where this module's data should be sent.
     *
     * <p>Common routes:</p>
     * <ul>
     *   <li>{@code "server/save-metrics"} - For periodic metrics (HTTP)</li>
     *   <li>{@code "dashboard_details"} - For real-time dashboard updates (WebSocket)</li>
     *   <li>{@code "player_connect"} - When a player joins (WebSocket)</li>
     *   <li>{@code "player_disconnect"} - When a player leaves (WebSocket)</li>
     *   <li>{@code "player_data"} - Real-time player position/health (WebSocket)</li>
     * </ul>
     *
     * @return The API route, never null
     */
    @NonNull String getRoute();

    /**
     * Returns the transport method for sending this module's data.
     *
     * @return The transport type (HTTP, WEBSOCKET, or BOTH)
     * @see Transport
     */
    Transport getTransport();

    /**
     * Collects and returns this module's data asynchronously.
     *
     * <p>This method is called during each metrics collection cycle (default: every 30 minutes).
     * The returned {@link CompletableFuture} allows for async operations such as
     * database queries without blocking the main server thread.</p>
     *
     * <p><b>Important:</b> This method should be thread-safe as it may be called
     * from different threads.</p>
     *
     * <h3>Return Value Format</h3>
     * <ul>
     *   <li><b>Base modules:</b> Return a single numeric value (Integer, Double, Float, Long)</li>
     *   <li><b>Custom modules:</b> Return a Map with the following structure:
     *     <pre>{@code
     *     {
     *       "numeric": {
     *         "Metric Name": 10,           // Single line chart
     *         "Multi Line": {              // Multi-line chart
     *           "Line 1": 10,
     *           "Line 2": 20
     *         }
     *       },
     *       "map": {
     *         "Leaderboard": [
     *           {"title": "Player1", "value": 100},
     *           {"title": "Player2", "value": 50}
     *         ]
     *       },
     *       "pie": {
     *         "Distribution": [
     *           {"title": "Item A", "value": 60},
     *           {"title": "Item B", "value": 40}
     *         ]
     *       }
     *     }
     *     }</pre>
     *   </li>
     * </ul>
     *
     * @return A CompletableFuture containing the collected data, or null if no data available
     */
    CompletableFuture<T> getValue();

    // ============================================================
    // LIFECYCLE HOOKS
    // ============================================================

    /**
     * Called when the module is registered with the {@link ModuleManager}.
     *
     * <p>Use this method to:</p>
     * <ul>
     *   <li>Register Bukkit event listeners</li>
     *   <li>Initialize resources (database connections, caches)</li>
     *   <li>Start background tasks</li>
     *   <li>Load configuration</li>
     * </ul>
     *
     * <p>This method is called synchronously on the main server thread.</p>
     */
    default void onEnable() {}

    /**
     * Called when the module is unregistered or the plugin shuts down.
     *
     * <p>Use this method to:</p>
     * <ul>
     *   <li>Unregister event listeners</li>
     *   <li>Close database connections</li>
     *   <li>Save pending data</li>
     *   <li>Cancel scheduled tasks</li>
     *   <li>Release resources</li>
     * </ul>
     *
     * <p>This method is called synchronously on the main server thread.</p>
     */
    default void onDisable() {}

    /**
     * Called on each metrics collection cycle.
     *
     * <p>The default cycle interval is 30 minutes (configurable in config.yml).
     * Use this method for periodic tasks that should run alongside metrics collection,
     * such as sending custom HTTP requests or WebSocket messages.</p>
     *
     * <p><b>Note:</b> This is called on an async thread. Use
     * {@code Bukkit.getScheduler().runTask()} if you need to access Bukkit API.</p>
     *
     * @param http The HTTP client for REST API calls
     * @param ws The WebSocket client for real-time messages
     */
    default void onCycle(NexusHttpClient http, NexusWebSocketClient ws) {}

    /**
     * Called every server tick (20 times per second).
     *
     * <p><b>WARNING:</b> This method is called on the main server thread.
     * Keep it extremely lightweight to avoid lag. Avoid:</p>
     * <ul>
     *   <li>Database queries</li>
     *   <li>File I/O</li>
     *   <li>Complex calculations</li>
     *   <li>Synchronous network calls</li>
     * </ul>
     *
     * <p>Typical use cases:</p>
     * <ul>
     *   <li>Tracking player positions (with change detection)</li>
     *   <li>Real-time dashboard updates (with throttling)</li>
     * </ul>
     *
     * @param ws The WebSocket client for real-time messages
     */
    default void onTick(NexusWebSocketClient ws) {}

    // ============================================================
    // MODULE CLASSIFICATION
    // ============================================================

    /**
     * Indicates whether this is a base module (server metrics).
     *
     * <p>Base modules contribute to the "base" section of the save-metrics payload:</p>
     * <pre>{@code
     * {
     *   "base": {
     *     "tps": 20,
     *     "activePlayers": 50,
     *     "memory_usage": 2048.5
     *   },
     *   "custom": { ... }
     * }
     * }</pre>
     *
     * @return {@code true} for base modules, {@code false} for custom modules
     */
    default boolean isBaseModule() {
        return false;
    }

    /**
     * Indicates whether this module is event-driven rather than periodic.
     *
     * <p>Event-based modules (e.g., player_connect, player_disconnect) are excluded
     * from the periodic metrics collection cycle. They send data immediately
     * when events occur.</p>
     *
     * @return {@code true} if this module reacts to events, {@code false} for periodic modules
     */
    default boolean isEventBased() {
        return false;
    }

    /**
     * Returns the execution priority for this module.
     *
     * <p>Modules with higher priority values are executed first during the
     * collection cycle. Use this to ensure dependencies are resolved
     * (e.g., a summary module that depends on other modules' data).</p>
     *
     * @return The priority value (default: 0). Higher = executed first.
     */
    default int getPriority() {
        return 0;
    }

    // ============================================================
    // TRANSPORT ENUM
    // ============================================================

    /**
     * Defines the transport method for sending module data to the server.
     */
    enum Transport {
        /**
         * Send data via HTTP REST API.
         * Used for periodic metrics that don't require real-time updates.
         */
        HTTP,

        /**
         * Send data via WebSocket connection.
         * Used for real-time updates (dashboard, player tracking).
         */
        WEBSOCKET,

        /**
         * Send data via both HTTP and WebSocket.
         * Used for base metrics that appear in both save-metrics and dashboard_details.
         */
        BOTH
    }
}
