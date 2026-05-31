package it.littlesquad.base_modules;

import it.littlesquad.Main;
import it.littlesquad.api.BaseMetricModule;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

public class EntityCountModule extends BaseMetricModule<Integer> {

    @Override
    public String getName() {
        return "entity_count";
    }

    @Override
    public CompletableFuture<Integer> getValue() {
        // world.getEntities() must run on the main thread
        CompletableFuture<Integer> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(Main.getInstance(), () -> {
            int count = 0;
            for (World world : Bukkit.getWorlds()) {
                count += world.getEntities().size();
            }
            future.complete(count);
        });
        return future;
    }
}
