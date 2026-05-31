package it.littlesquad.api;

import it.littlesquad.config.NexusConfig;
import it.littlesquad.network.NexusHttpClient;
import it.littlesquad.network.NexusWebSocketClient;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Central manager for all NexusMaps modules.
 *
 * <p>The ModuleManager is a singleton responsible for:</p>
 * <ul>
 *   <li>Registering and unregistering modules</li>
 *   <li>Managing module lifecycle (enable/disable)</li>
 *   <li>Running the metrics collection cycle</li>
 *   <li>Executing tick updates for real-time modules</li>
 *   <li>Collecting and sending combined metrics to the server</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // In your plugin's onEnable()
 * ModuleManager modules = ModuleManager.getInstance();
 * modules.init(this);
 *
 * // Register modules
 * modules.registerModule(new TpsModule());
 * modules.registerModule(new ActivePlayersModule());
 * modules.registerModule(new MyCustomModule());
 *
 * // Start the collection cycle (every 30 minutes by default)
 * modules.startCycle();
 *
 * // Start tick updates (for real-time player tracking)
 * modules.startTick();
 *
 * // In your plugin's onDisable()
 * modules.shutdown();
 * }</pre>
 *
 * <h2>Collection Cycle</h2>
 * <p>Every cycle (default: 30 minutes), the manager:</p>
 * <ol>
 *   <li>Calls {@code onCycle()} on all modules (sorted by priority)</li>
 *   <li>Collects values from all non-event modules via {@code getValue()}</li>
 *   <li>Combines base and custom metrics into a single payload</li>
 *   <li>Sends the payload to {@code POST /server/save-metrics}</li>
 * </ol>
 *
 * <h2>Payload Structure</h2>
 * <pre>{@code
 * {
 *   "base": {
 *     "tps": 20.0,
 *     "activePlayers": 125,
 *     "memory_usage": 2048.5,
 *     ...
 *   },
 *   "custom": {
 *     "MyModule": {
 *       "numeric": {...},
 *       "map": {...},
 *       "pie": {...}
 *     }
 *   }
 * }
 * }</pre>
 *
 * @see NexusModule
 * @see BaseMetricModule
 * @see PlayerEventModule
 */
public final class ModuleManager {

    private static final ModuleManager INSTANCE = new ModuleManager();

    private final ConcurrentHashMap<String, NexusModule<?>> modules = new ConcurrentHashMap<>();

    private JavaPlugin plugin;
    private Logger logger;
    private BukkitTask cycleTask;
    private BukkitTask tickTask;

    private ModuleManager() {}

    /**
     * Returns the singleton instance of the ModuleManager.
     *
     * @return The ModuleManager instance
     */
    public static ModuleManager getInstance() {
        return INSTANCE;
    }

    /**
     * Initializes the ModuleManager with the plugin instance.
     *
     * <p>This method must be called in your plugin's {@code onEnable()} before
     * registering any modules or starting the cycle.</p>
     *
     * @param plugin The JavaPlugin instance
     * @throws IllegalArgumentException if plugin is null
     */
    public void init(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    /**
     * Starts the automatic metrics collection cycle.
     *
     * <p>The cycle runs asynchronously at the interval specified in config.yml
     * (default: 30 minutes). Each cycle:</p>
     * <ol>
     *   <li>Triggers {@code onCycle()} on all modules</li>
     *   <li>Collects metrics from eligible modules</li>
     *   <li>Sends combined payload to the server</li>
     * </ol>
     *
     * <p>The first cycle runs 1 second after calling this method.</p>
     *
     * @see #runCycle()
     * @see #stopCycle()
     */
    public void startCycle() {
        if (cycleTask != null) {
            cycleTask.cancel();
        }

        long intervalSeconds = it.littlesquad.config.NexusConfig.get().getMetricsIntervalSeconds();
        if (intervalSeconds <= 0) {
            intervalSeconds = 30L;
        }
        long intervalTicks = intervalSeconds * 20L;

        cycleTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::runCycle, 20L, intervalTicks
        );

        log("Cycle started (every " + intervalSeconds + " seconds)");
    }

    /**
     * Starts the tick handler for real-time updates.
     *
     * <p>When enabled, {@code onTick()} is called on all modules every server tick
     * (20 times per second). This is used for real-time features like player
     * position tracking.</p>
     *
     * <p><b>Warning:</b> Only enable this if you have modules that require
     * tick-level updates. The tick handler runs on an async thread to avoid
     * impacting server performance.</p>
     *
     * @see NexusModule#onTick(NexusWebSocketClient)
     * @see #stopTick()
     */
    public void startTick() {
        if (tickTask != null) {
            tickTask.cancel();
        }

        tickTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::runTick, 1L, 1L
        );
        log("Tick handler started");
    }

    /**
     * Stops the metrics collection cycle.
     */
    public void stopCycle() {
        if (cycleTask != null) {
            cycleTask.cancel();
            cycleTask = null;
            log("Cycle stopped");
        }
    }

    /**
     * Stops the tick handler.
     */
    public void stopTick() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
            log("Tick handler stopped");
        }
    }

    /**
     * Shuts down the ModuleManager and all registered modules.
     *
     * <p>This method:</p>
     * <ol>
     *   <li>Cancels the cycle task</li>
     *   <li>Cancels the tick task</li>
     *   <li>Calls {@code onDisable()} on all registered modules</li>
     * </ol>
     *
     * <p>Call this in your plugin's {@code onDisable()} method.</p>
     */
    public void shutdown() {
        stopCycle();
        stopTick();

        modules.values().forEach(module -> {
            try {
                module.onDisable();
            } catch (Exception e) {
                logError("Error disabling module " + module.getName() + ": " + e.getMessage());
            }
        });

        modules.clear();
        log("ModuleManager shutdown complete");
    }

    // ============================================================
    // MODULE REGISTRATION
    // ============================================================

    /**
     * Registers a module with the manager.
     *
     * <p>Upon registration:</p>
     * <ol>
     *   <li>The module is stored in the internal registry</li>
     *   <li>{@code onEnable()} is called on the module</li>
     * </ol>
     *
     * <p>If a module with the same name already exists, it will be replaced.</p>
     *
     * @param module The module to register
     * @throws IllegalArgumentException if module is null
     *
     * @see #unregisterModule(String)
     * @see NexusModule#onEnable()
     */
    public void registerModule(NexusModule<?> module) {
        modules.put(module.getName(), module);

        try {
            module.onEnable();
            log("Module registered: " + module.getName());
        } catch (Exception e) {
            logError("Error enabling module " + module.getName() + ": " + e.getMessage());
        }
    }

    /**
     * Unregisters a module by name.
     *
     * <p>Upon unregistration, {@code onDisable()} is called on the module
     * to allow cleanup of resources.</p>
     *
     * @param name The name of the module to unregister
     *
     * @see #registerModule(NexusModule)
     * @see NexusModule#onDisable()
     */
    public void unregisterModule(String name) {
        NexusModule<?> module = modules.remove(name);

        if (module != null) {
            try {
                module.onDisable();
                log("Module unregistered: " + name);
            } catch (Exception e) {
                logError("Error disabling module " + name + ": " + e.getMessage());
            }
        }
    }

    /**
     * Retrieves a module by name.
     *
     * @param name The module name
     * @param <T> The expected return type of the module's getValue()
     * @return The module, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T> NexusModule<T> getModule(String name) {
        return (NexusModule<T>) modules.get(name);
    }

    /**
     * Returns all registered modules.
     *
     * @return A new list containing all modules (modifications don't affect the registry)
     */
    public List<NexusModule<?>> getAllModules() {
        return new ArrayList<>(modules.values());
    }

    /**
     * Returns all modules sorted by priority (highest first).
     *
     * <p>Modules with higher priority values are placed earlier in the list.
     * This ordering is used during the collection cycle to ensure dependencies
     * are resolved correctly.</p>
     *
     * @return A new sorted list of modules
     *
     * @see NexusModule#getPriority()
     */
    public List<NexusModule<?>> getModulesByPriority() {
        List<NexusModule<?>> list = new ArrayList<>(modules.values());
        list.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        return list;
    }

    // ============================================================
    // CYCLE EXECUTION
    // ============================================================

    /**
     * Manually triggers a metrics collection cycle.
     *
     * <p>This method is automatically called by the cycle scheduler, but can
     * also be invoked manually for testing or on-demand collection.</p>
     *
     * <p>The cycle:</p>
     * <ol>
     *   <li>Calls {@code onCycle()} on all modules (sorted by priority)</li>
     *   <li>Collects values from eligible modules</li>
     *   <li>Sends the combined payload to the server</li>
     * </ol>
     */
    public void runCycle() {
        NexusHttpClient http = NexusHttpClient.getInstance();
        NexusWebSocketClient ws = NexusWebSocketClient.getInstance();

        List<NexusModule<?>> sortedModules = getModulesByPriority();

        for (NexusModule<?> module : sortedModules) {
            try {
                module.onCycle(http, ws);
            } catch (Exception e) {
                logError("Error in onCycle for " + module.getName() + ": " + e.getMessage());
            }
        }

        collectAndSendMetrics();
    }

    /**
     * Executes onTick() on all registered modules.
     *
     * <p>Called every server tick when the tick handler is running.
     * Errors are silently ignored to prevent log spam.</p>
     */
    private void runTick() {
        NexusWebSocketClient ws = NexusWebSocketClient.getInstance();

        for (NexusModule<?> module : modules.values()) {
            try {
                module.onTick(ws);
            } catch (Exception e) {
                // Silently ignore tick errors to prevent log spam
            }
        }
    }

    /**
     * Collects values from custom modules and sends them via WebSocket save_metrics.
     */
    private void collectAndSendMetrics() {
        NexusWebSocketClient ws = NexusWebSocketClient.getInstance();
        if (!ws.isConnected()) {
            return;
        }

        NexusModule<?> dashboardModule = modules.get("dashboard");
        CompletableFuture<Map<String, Object>> baseFuture;
        if (dashboardModule != null) {
            baseFuture = dashboardModule.getValue().thenApply(val -> {
                if (val instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) val;
                    return map;
                }
                return Collections.emptyMap();
            });
        } else {
            baseFuture = CompletableFuture.completedFuture(Collections.emptyMap());
        }

        List<Map<String, Object>> customDataList = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<?>> futures = new ArrayList<>();

        for (NexusModule<?> module : modules.values()) {
            // Skip event-based modules (player_connect, etc.)
            if (module.isEventBased()) continue;
            // Skip base modules since they are already collected by the dashboard module
            if (module.isBaseModule()) continue;

            CompletableFuture<?> future = module.getValue()
                    .thenAccept(value -> {
                        if (value == null) return;
                        Map<String, Object> moduleEntry = new ConcurrentHashMap<>();
                        moduleEntry.put("moduleName", module.getName());
                        moduleEntry.put("data", value);
                        customDataList.add(moduleEntry);
                    })
                    .exceptionally(e -> {
                        logError("Error collecting " + module.getName() + ": " + e.getMessage());
                        return null;
                    });

            futures.add(future);
        }

        // Ensure we also wait for the base metrics from the dashboard module
        CompletableFuture<?> combinedBaseFuture = baseFuture.thenAccept(baseMetrics -> {});
        futures.add(combinedBaseFuture);

        // Wait for all modules to complete, then send
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    try {
                        Map<String, Object> payload = new ConcurrentHashMap<>();
                        Map<String, Object> baseMetrics = baseFuture.getNow(Collections.emptyMap());
                        payload.putAll(baseMetrics);
                        payload.put("custom_data", customDataList);

                        ws.send("save_metrics", payload);
                        log("Metrics sent via WebSocket");
                    } catch (Exception e) {
                        logError("Error constructing metrics payload: " + e.getMessage());
                    }
                });
    }

    private void log(String message) {
        if (logger != null) logger.info("[Modules] " + message);
    }

    private void logError(String message) {
        if (logger != null) logger.severe("[Modules] " + message);
    }
}
