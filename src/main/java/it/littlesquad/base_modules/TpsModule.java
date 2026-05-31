package it.littlesquad.base_modules;

import it.littlesquad.api.BaseMetricModule;
import org.bukkit.Bukkit;

import java.util.concurrent.CompletableFuture;

public class TpsModule extends BaseMetricModule<Float> {

    @Override
    public String getName() {
        return "tps";
    }

    @Override
    public CompletableFuture<Float> getValue() {
        return CompletableFuture.supplyAsync(() ->
                Bukkit.getServerTickManager().getTickRate()
        );
    }
}
