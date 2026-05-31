package it.littlesquad.base_modules;

import it.littlesquad.Main;
import it.littlesquad.api.NexusModule;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class MinecraftActivityModule implements NexusModule<Map<String, Object>> {

    @Override
    public String getName() {
        return "Minecraft Activity";
    }

    @Override
    public String getRoute() {
        return "server/save-metrics";
    }

    @Override
    public Transport getTransport() {
        return Transport.HTTP;
    }

    @Override
    public CompletableFuture<Map<String, Object>> getValue() {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
            Map<String, Object> moduleData = new HashMap<>();

            // 1. Numeric stats
            Map<String, Object> numeric = new HashMap<>();
            numeric.put("Total Offline Players", Bukkit.getOfflinePlayers().length);
            numeric.put("Whitelisted Players", Bukkit.getWhitelistedPlayers().size());
            numeric.put("Banned Players", Bukkit.getBannedPlayers().size());
            numeric.put("Operators Count", Bukkit.getOperators().size());
            moduleData.put("numeric", numeric);

            // 2. Pie Chart: GameMode Distribution of online players
            Map<String, Object> pie = new HashMap<>();
            Map<String, Integer> gameModeCounts = new HashMap<>();
            for (GameMode gm : GameMode.values()) {
                gameModeCounts.put(gm.name(), 0);
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                GameMode gm = player.getGameMode();
                gameModeCounts.put(gm.name(), gameModeCounts.getOrDefault(gm.name(), 0) + 1);
            }
            List<Map<String, Object>> gameModeDistribution = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : gameModeCounts.entrySet()) {
                if (entry.getValue() > 0 || Bukkit.getOnlinePlayers().isEmpty()) {
                    Map<String, Object> item = new HashMap<>();
                    item.put("title", entry.getKey());
                    item.put("value", entry.getValue());
                    gameModeDistribution.add(item);
                }
            }
            pie.put("GameMode Distribution", gameModeDistribution);
            moduleData.put("pie", pie);

            // 3. Map Chart: World Player Distribution
            Map<String, Object> map = new HashMap<>();
            List<Map<String, Object>> worldDistribution = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                Map<String, Object> item = new HashMap<>();
                item.put("title", world.getName());
                item.put("value", world.getPlayers().size());
                worldDistribution.add(item);
            }
            map.put("World Player Distribution", worldDistribution);
            moduleData.put("map", map);

            future.complete(moduleData);
        });

        return future;
    }
}
