package it.littlesquad.base_modules;

import it.littlesquad.api.BaseMetricModule;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.CompletableFuture;

public class CpuUsageModule extends BaseMetricModule<Double> {

    private final OperatingSystemMXBean osBean;

    public CpuUsageModule() {
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
    }

    @Override
    public String getName() {
        return "cpu_usage";
    }

    @Override
    public CompletableFuture<Double> getValue() {
        return CompletableFuture.supplyAsync(() -> {
            // getSystemLoadAverage returns -1 if not available
            double load = osBean.getSystemLoadAverage();

            if (load < 0) {
                // Fallback: try com.sun.management if available
                if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                    return sunBean.getCpuLoad() * 100.0; // Percentage
                }
                return -1.0; // Not available
            }

            // Normalize by number of CPUs
            int processors = osBean.getAvailableProcessors();
            return (load / processors) * 100.0; // Percentage
        });
    }
}
