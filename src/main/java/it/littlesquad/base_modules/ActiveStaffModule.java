package it.littlesquad.base_modules;

import it.littlesquad.api.BaseMetricModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;

public class ActiveStaffModule extends BaseMetricModule<Integer> {

    private static final String STAFF_PERMISSION = "nexusmaps.staff";

    @Override
    public String getName() {
        return "activeStaff";
    }

    @Override
    public CompletableFuture<Integer> getValue() {
        return CompletableFuture.supplyAsync(() -> {
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.hasPermission(STAFF_PERMISSION) || player.isOp()) {
                    count++;
                }
            }
            return count;
        });
    }
}
