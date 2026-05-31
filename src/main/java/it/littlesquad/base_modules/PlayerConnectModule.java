package it.littlesquad.base_modules;

import it.littlesquad.Main;
import it.littlesquad.api.PlayerEventModule;
import it.littlesquad.network.NexusHttpClient;
import it.littlesquad.network.NexusWebSocketClient;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.NonNull;

import java.util.Map;

public class PlayerConnectModule extends PlayerEventModule implements Listener {

    @Override
    public String getName() {
        return "player_connect";
    }

    @Override
    public @NonNull String getRoute() {
        return "player_data";
    }

    @Override
    public void onEnable() {
        // Register Bukkit event listener
        Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        long unixTime = System.currentTimeMillis() / 1000L;

        // 1. Send WebSocket event
        Map<String, Object> wsPayload = Map.of(
                "eventID", "player_connect",
                "steamID64", uuid,
                "name", name,
                "world", player.getWorld().getName()
        );
        NexusWebSocketClient.getInstance().send(getRoute(), wsPayload);

        // 2. HTTP POST player/connected
        Map<String, Object> httpConnectedPayload = Map.of(
                "steamid64", uuid,
                "unixTime", unixTime,
                "name", name
        );
        NexusHttpClient.getInstance().post("player/connected", httpConnectedPayload);

        // 3. HTTP POST player/update-details
        String countryCode = getCountryCode(player);
        Map<String, Object> httpUpdatePayload = Map.of(
                "steamid64", uuid,
                "country", countryCode
        );
        NexusHttpClient.getInstance().post("player/update-details", httpUpdatePayload);
    }

    public void syncOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            String uuid = player.getUniqueId().toString();
            String name = player.getName();
            long unixTime = System.currentTimeMillis() / 1000L;

            // 1. Send WebSocket event
            Map<String, Object> wsPayload = Map.of(
                    "eventID", "player_connect",
                    "steamID64", uuid,
                    "name", name,
                    "world", player.getWorld().getName()
            );
            NexusWebSocketClient.getInstance().send(getRoute(), wsPayload);

            // 2. HTTP POST player/connected
            Map<String, Object> httpConnectedPayload = Map.of(
                    "steamid64", uuid,
                    "unixTime", unixTime,
                    "name", name
            );
            NexusHttpClient.getInstance().post("player/connected", httpConnectedPayload);

            // 3. HTTP POST player/update-details
            String countryCode = getCountryCode(player);
            Map<String, Object> httpUpdatePayload = Map.of(
                    "steamid64", uuid,
                    "country", countryCode
            );
            NexusHttpClient.getInstance().post("player/update-details", httpUpdatePayload);
        }
    }

    private String getCountryCode(Player player) {
        try {
            // player.getLocale() returns e.g. "en_us", "it_it"
            String localeStr = player.getLocale();
            if (localeStr != null && localeStr.contains("_")) {
                // Extract country code (part after _)
                String country = localeStr.split("_")[1];
                return country.toLowerCase();
            }
        } catch (Exception ignored) {}

        return "unknown";
    }
}
