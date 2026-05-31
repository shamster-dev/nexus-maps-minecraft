package it.littlesquad.api;

import java.util.concurrent.CompletableFuture;

/**
 * Abstract base class for modules that handle player-related events.
 *
 * <p>Player event modules react to player actions (join, quit, movement) and send
 * real-time updates via WebSocket. Unlike periodic modules, these are triggered
 * by Bukkit events and send data immediately.</p>
 *
 * <h2>Event Types</h2>
 * <table border="1">
 *   <tr><th>Route</th><th>Trigger</th><th>Payload</th></tr>
 *   <tr>
 *     <td>{@code player_connect}</td>
 *     <td>PlayerJoinEvent</td>
 *     <td>{@code {"id": "uuid", "country": "us", "name": "Steve"}}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code player_disconnect}</td>
 *     <td>PlayerQuitEvent</td>
 *     <td>{@code "uuid"} (just the UUID string)</td>
 *   </tr>
 *   <tr>
 *     <td>{@code player_data}</td>
 *     <td>Every tick (with change detection)</td>
 *     <td>{@code {"name": "Steve", "health": 20, "pos": [x,y,z], ...}}</td>
 *   </tr>
 * </table>
 *
 * <h2>Implementation Example</h2>
 * <pre>{@code
 * public class PlayerConnectModule extends PlayerEventModule implements Listener {
 *
 *     @Override
 *     public String getName() {
 *         return "player_connect";
 *     }
 *
 *     @Override
 *     public String getRoute() {
 *         return "player_connect";
 *     }
 *
 *     @Override
 *     public void onEnable() {
 *         // Register Bukkit event listener
 *         Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
 *     }
 *
 *     @EventHandler
 *     public void onPlayerJoin(PlayerJoinEvent event) {
 *         Player player = event.getPlayer();
 *
 *         Map<String, Object> payload = Map.of(
 *             "route", getRoute(),
 *             "data", Map.of(
 *                 "id", player.getUniqueId().toString(),
 *                 "country", getCountryCode(player),
 *                 "name", player.getName()
 *             )
 *         );
 *
 *         NexusWebSocketClient.getInstance().send(payload);
 *     }
 * }
 * }</pre>
 *
 * <h2>Player Data Optimization</h2>
 * <p>For {@code player_data} events, only send fields that have changed since
 * the last update to minimize bandwidth:</p>
 * <pre>{@code
 * // Good - only changed fields
 * {"name": "Steve", "pos": [100, 64, -200]}
 *
 * // Bad - sending everything every tick
 * {"name": "Steve", "health": 20, "armor": 10, "pos": [...], "ang": [...], "vel": [...]}
 * }</pre>
 *
 * @see NexusModule
 * @see BaseMetricModule
 */
public abstract class PlayerEventModule implements NexusModule<Void> {

    /**
     * Returns the transport type for player events.
     *
     * <p>Player events always use WebSocket for real-time delivery.</p>
     *
     * @return Always returns {@link Transport#WEBSOCKET}
     */
    @Override
    public final Transport getTransport() {
        return Transport.WEBSOCKET;
    }

    /**
     * Player event modules are not base metrics.
     *
     * @return Always returns {@code false}
     */
    @Override
    public final boolean isBaseModule() {
        return false;
    }

    /**
     * Identifies this as an event-driven module.
     *
     * <p>Event-based modules are excluded from the periodic metrics collection
     * cycle since they send data in response to events, not on a schedule.</p>
     *
     * @return Always returns {@code true}
     */
    @Override
    public final boolean isEventBased() {
        return true;
    }

    /**
     * Event modules don't participate in periodic value collection.
     *
     * <p>Since event modules send data when events occur (via Bukkit listeners
     * or onTick), they don't need to return values during the collection cycle.</p>
     *
     * @return Always returns a completed future with {@code null}
     */
    @Override
    public CompletableFuture<Void> getValue() {
        return CompletableFuture.completedFuture(null);
    }
}
