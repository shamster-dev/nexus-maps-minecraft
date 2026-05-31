package it.littlesquad.base_modules;

import it.littlesquad.api.BaseMetricModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public class AveragePingModule extends BaseMetricModule<Double> {

    @Override
    public String getName() {
        return "average_ping";
    }

    @Override
    public CompletableFuture<Double> getValue() {
        return CompletableFuture.supplyAsync(() -> {
            Collection<? extends Player> players = Bukkit.getOnlinePlayers();

            if (players.isEmpty()) {
                return 0.0;
            }

            int totalPing = 0;
            for (Player player : players) {
                totalPing += player.getPing();
            }

            return (double) totalPing / players.size();
        });
    }
}
