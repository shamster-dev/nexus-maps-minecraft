package it.littlesquad.base_modules;

import it.littlesquad.api.BaseMetricModule;
import org.bukkit.Bukkit;

import java.util.concurrent.CompletableFuture;

public class ActivePlayersModule extends BaseMetricModule<Integer> {

    @Override
    public String getName() {
        return "activePlayers";
    }

    @Override
    public CompletableFuture<Integer> getValue() {
        return CompletableFuture.supplyAsync(() ->
                Bukkit.getOnlinePlayers().size()
        );
    }
}
