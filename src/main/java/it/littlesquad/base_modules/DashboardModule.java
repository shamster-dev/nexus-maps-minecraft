package it.littlesquad.base_modules;

import it.littlesquad.Main;
import it.littlesquad.api.NexusModule;
import it.littlesquad.network.NexusWebSocketClient;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Special module that collects all base metrics and sends them
 * to the dashboard in real-time via WebSocket.
 *
 * This module runs more frequently than the standard 30-minute cycle
 * (e.g. every 5 seconds).
 */
public class DashboardModule implements NexusModule<Map<String, Object>> {

    private final OperatingSystemMXBean osBean;
    private final long startedAt;
    private int tickCounter = 0;
    private static final int TICKS_BETWEEN_UPDATES = 100; // ~5 seconds

    public DashboardModule() {
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
        this.startedAt = System.currentTimeMillis() / 1000L;
    }

    @Override
    public String getName() {
        return "dashboard";
    }

    @Override
    public @NonNull String getRoute() {
        return "dashboard_details";
    }

    @Override
    public Transport getTransport() {
        return Transport.WEBSOCKET;
    }

    @Override
    public boolean isBaseModule() {
        return true;
    }

    @Override
    public CompletableFuture<Map<String, Object>> getValue() {
        return collectAllMetricsAsync();
    }

    @Override
    public void onTick(NexusWebSocketClient ws) {
        tickCounter++;

        if (tickCounter >= TICKS_BETWEEN_UPDATES) {
            tickCounter = 0;
            sendDashboardUpdate(ws);
        }
    }

    private void sendDashboardUpdate(NexusWebSocketClient ws) {
        // Collect on main thread, then send on async thread
        collectAllMetricsAsync().thenAccept(metrics -> {
            ws.send(getRoute(), metrics);
        });
    }

    /**
     * Collects all metrics scheduling main-thread calls on the sync executor,
     * then merges with thread-safe metrics (memory, cpu) asynchronously.
     */
    private CompletableFuture<Map<String, Object>> collectAllMetricsAsync() {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

        // world.getEntities(), world.getLoadedChunks(), getOnlinePlayers() etc.
        // must be called on the main thread
        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
            Map<String, Object> metrics = new HashMap<>();

            // started_at
            metrics.put("started_at", startedAt);

            // TPS
            metrics.put("tps", Bukkit.getServerTickManager().getTickRate());

            // Error Count
            metrics.put("error_count", it.littlesquad.api.ErrorCollector.getErrorCount());

            // Players
            int totalPlayers = Bukkit.getOnlinePlayers().size();
            metrics.put("activePlayers", totalPlayers);

            // Staff count
            int staffCount = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission("nexusmaps.staff") || player.isOp()) {
                    staffCount++;
                }
            }
            metrics.put("activeStaff", staffCount);

            // Entities e Chunks (require main thread)
            int entityCount = 0;
            int chunkCount = 0;
            for (World world : Bukkit.getWorlds()) {
                entityCount += world.getEntities().size();
                chunkCount += world.getLoadedChunks().length;
            }
            metrics.put("entity_count", entityCount);
            metrics.put("loaded_chunks", chunkCount);
            metrics.put("world_count", Bukkit.getWorlds().size());

            // Average Ping
            if (totalPlayers > 0) {
                int totalPing = 0;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    totalPing += player.getPing();
                }
                metrics.put("average_ping", (double) totalPing / totalPlayers);
            } else {
                metrics.put("average_ping", 0.0);
            }

            // Memory and CPU are thread-safe, can be read anywhere
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            metrics.put("memory_usage", usedMemory / (1024.0 * 1024.0));
            metrics.put("cpu_usage", getCpuUsage());

            future.complete(metrics);
        });

        return future;
    }

    private double getCpuUsage() {
        double load = osBean.getSystemLoadAverage();

        if (load < 0 && osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            return sunBean.getCpuLoad() * 100.0;
        }

        if (load < 0) return -1.0;

        int processors = osBean.getAvailableProcessors();
        return (load / processors) * 100.0;
    }
}
