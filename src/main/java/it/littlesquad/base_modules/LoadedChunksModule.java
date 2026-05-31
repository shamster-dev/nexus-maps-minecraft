package it.littlesquad.base_modules;

import it.littlesquad.Main;
import it.littlesquad.api.BaseMetricModule;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

public class LoadedChunksModule extends BaseMetricModule<Integer> {

    @Override
    public String getName() {
        return "loaded_chunks";
    }

    @Override
    public CompletableFuture<Integer> getValue() {
        // world.getLoadedChunks() must run on the main thread
        CompletableFuture<Integer> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
            int count = 0;
            for (World world : Bukkit.getWorlds()) {
                count += world.getLoadedChunks().length;
            }
            future.complete(count);
        });
        return future;
    }
}
