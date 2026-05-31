package it.littlesquad.base_modules;

import it.littlesquad.api.BaseMetricModule;
import org.bukkit.Bukkit;

import java.util.concurrent.CompletableFuture;

public class WorldCountModule extends BaseMetricModule<Integer> {

    @Override
    public String getName() {
        return "world_count";
    }

    @Override
    public CompletableFuture<Integer> getValue() {
        return CompletableFuture.supplyAsync(() ->
                Bukkit.getWorlds().size()
        );
    }
}
