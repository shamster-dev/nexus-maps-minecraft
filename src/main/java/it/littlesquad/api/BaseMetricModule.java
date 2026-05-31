package it.littlesquad.api;

import org.jspecify.annotations.NonNull;

/**
 * Abstract base class for modules that collect server metrics.
 *
 * <p>Base metric modules collect fundamental server statistics such as TPS,
 * player count, memory usage, etc. These metrics are:</p>
 * <ul>
 *   <li>Sent to {@code server/save-metrics} via HTTP every 30 minutes</li>
 *   <li>Sent to {@code dashboard_details} via WebSocket for real-time updates</li>
 *   <li>Placed in the "base" section of the metrics payload</li>
 * </ul>
 *
 * <h2>Payload Structure</h2>
 * <p>Base modules contribute to the following payload structure:</p>
 * <pre>{@code
 * POST /server/save-metrics
 * {
 *   "base": {
 *     "tps": 20.0,
 *     "activePlayers": 125,
 *     "activeStaff": 5,
 *     "memory_usage": 2048.5,
 *     "entity_count": 1500,
 *     "loaded_chunks": 3200,
 *     "world_count": 3,
 *     "cpu_usage": 45.2,
 *     "average_ping": 67.5
 *   },
 *   "custom": { ... }
 * }
 * }</pre>
 *
 * <h2>Implementation Example</h2>
 * <pre>{@code
 * public class TpsModule extends BaseMetricModule<Float> {
 *
 *     @Override
 *     public String getName() {
 *         return "tps";
 *     }
 *
 *     @Override
 *     public CompletableFuture<Float> getValue() {
 *         return CompletableFuture.supplyAsync(() ->
 *             Bukkit.getServerTickManager().getTickRate()
 *         );
 *     }
 * }
 * }</pre>
 *
 * <h2>Available Base Metrics</h2>
 * <table border="1">
 *   <tr><th>Name</th><th>Type</th><th>Description</th></tr>
 *   <tr><td>tps</td><td>Float</td><td>Server ticks per second (max 20)</td></tr>
 *   <tr><td>activePlayers</td><td>Integer</td><td>Online player count</td></tr>
 *   <tr><td>activeStaff</td><td>Integer</td><td>Online staff count</td></tr>
 *   <tr><td>memory_usage</td><td>Double</td><td>RAM usage in MB</td></tr>
 *   <tr><td>entity_count</td><td>Integer</td><td>Total loaded entities</td></tr>
 *   <tr><td>loaded_chunks</td><td>Integer</td><td>Total loaded chunks</td></tr>
 *   <tr><td>world_count</td><td>Integer</td><td>Number of loaded worlds</td></tr>
 *   <tr><td>cpu_usage</td><td>Double</td><td>CPU usage percentage</td></tr>
 *   <tr><td>average_ping</td><td>Double</td><td>Average player ping in ms</td></tr>
 * </table>
 *
 * @param <T> The numeric type returned by this module (Integer, Float, Double, Long)
 *
 * @see NexusModule
 * @see PlayerEventModule
 */
public abstract class BaseMetricModule<T extends Number> implements NexusModule<T> {

    /**
     * Returns the fixed route for base metrics.
     *
     * @return Always returns {@code "server/save-metrics"}
     */
    @Override
    public final @NonNull String getRoute() {
        return "server/save-metrics";
    }

    /**
     * Returns the transport type for base metrics.
     *
     * <p>Base metrics use BOTH transport because they are sent to:</p>
     * <ul>
     *   <li>HTTP endpoint for periodic storage ({@code server/save-metrics})</li>
     *   <li>WebSocket for real-time dashboard updates ({@code dashboard_details})</li>
     * </ul>
     *
     * @return Always returns {@link Transport#BOTH}
     */
    @Override
    public final Transport getTransport() {
        return Transport.BOTH;
    }

    /**
     * Identifies this as a base module.
     *
     * @return Always returns {@code true}
     */
    @Override
    public final boolean isBaseModule() {
        return true;
    }

    /**
     * Base metrics are collected periodically, not event-driven.
     *
     * @return Always returns {@code false}
     */
    @Override
    public final boolean isEventBased() {
        return false;
    }
}
