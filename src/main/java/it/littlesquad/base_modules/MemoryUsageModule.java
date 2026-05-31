package it.littlesquad.base_modules;

import it.littlesquad.api.BaseMetricModule;

import java.util.concurrent.CompletableFuture;

public class MemoryUsageModule extends BaseMetricModule<Double> {

    @Override
    public String getName() {
        return "memory_usage";
    }

    @Override
    public CompletableFuture<Double> getValue() {
        return CompletableFuture.supplyAsync(() -> {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            // Convert to MB
            return usedMemory / (1024.0 * 1024.0);
        });
    }
}
