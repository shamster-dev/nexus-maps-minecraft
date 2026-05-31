package it.littlesquad.base_modules;

import it.littlesquad.Main;
import it.littlesquad.api.PlayerEventModule;
import it.littlesquad.network.NexusHttpClient;
import it.littlesquad.network.NexusWebSocketClient;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.NonNull;

import java.util.Map;

public class PlayerDisconnectModule extends PlayerEventModule implements Listener {

    @Override
    public String getName() {
        return "player_disconnect";
    }

    @Override
    public @NonNull String getRoute() {
        return "player_data";
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        long unixTime = System.currentTimeMillis() / 1000L;

        // Clear the PlayerDataModule cache
        Main.getInstance().onPlayerDisconnect(event.getPlayer().getUniqueId());

        // 1. Send WebSocket event
        Map<String, Object> wsPayload = Map.of(
                "eventID", "player_disconnect",
                "steamID64", uuid
        );
        NexusWebSocketClient.getInstance().send(getRoute(), wsPayload);

        // 2. HTTP POST player/disconnected
        Map<String, Object> httpPayload = Map.of(
                "steamid64", uuid,
                "unixTime", unixTime
        );
        NexusHttpClient.getInstance().post("player/disconnected", httpPayload);
    }
}
