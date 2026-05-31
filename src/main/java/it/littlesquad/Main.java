package it.littlesquad;

import it.littlesquad.api.ModuleManager;
import it.littlesquad.api.NexusModule;
import it.littlesquad.base_modules.*;
import it.littlesquad.commands.NexusLinkCommand;
import it.littlesquad.config.NexusConfig;
import it.littlesquad.network.NexusHttpClient;
import it.littlesquad.network.NexusWebSocketClient;
import it.littlesquad.base_modules.PlayerConnectModule;
import it.littlesquad.base_modules.PlayerDataModule;
import it.littlesquad.base_modules.PlayerDisconnectModule;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.Map;

public class Main extends JavaPlugin implements org.bukkit.event.Listener {

    private static Main instance;
    private PlayerDataModule playerDataModule;
    private final java.util.Set<java.util.UUID> frozenPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static class PlayerActionPayload {
        String command;
        String steamId;
        String userInput;
    }

    @Override
    public void onLoad() {
        instance = this;
        NexusConfig.init(this);
    }

    @Override
    public void onEnable() {
        // Setup clients
        NexusHttpClient http = NexusHttpClient.getInstance();
        http.setLogger(getLogger());

        // Register ErrorCollector to capture all SEVERE console messages
        it.littlesquad.api.ErrorCollector.register();

        // Register ConsoleLogAppender to stream console logs
        it.littlesquad.api.ConsoleLogAppender.register();

        NexusWebSocketClient ws = NexusWebSocketClient.getInstance();
        ws.setLogger(getLogger());
        ws.setMessageHandler(this::handleServerMessage);

        // Register freeze event listener
        getServer().getPluginManager().registerEvents(this, this);

        // Debug log
        NexusConfig config = NexusConfig.get();
        if (config.isDebug()) {
            getLogger().info("Test Mode: " + config.isTestMode());
            getLogger().info("Domain: " + config.getDomain());
            getLogger().info("WebSocket: " + config.getWebsocketUrl());
        }

        // Load API key from world folder if not already loaded
        NexusConfig.get().loadApiKeyFromWorld();

        // Connect WebSocket
        ws.setOnConnectListener(() -> {
            sendServerInformation();
            try {
                NexusModule<?> rawModule = ModuleManager.getInstance().getModule("player_connect");
                if (rawModule instanceof PlayerConnectModule connectModule) {
                    connectModule.syncOnlinePlayers();
                }
            } catch (Exception e) {
                getLogger().severe("Failed to sync online players on connect: " + e.getMessage());
            }
        });
        ws.connect();

        // Setup modules
        ModuleManager modules = ModuleManager.getInstance();
        modules.init(this);

        // === BASE MODULES (server metrics) ===
        modules.registerModule(new TpsModule());
        modules.registerModule(new ActivePlayersModule());
        modules.registerModule(new ActiveStaffModule());
        modules.registerModule(new MemoryUsageModule());
        modules.registerModule(new EntityCountModule());
        modules.registerModule(new LoadedChunksModule());
        modules.registerModule(new WorldCountModule());
        modules.registerModule(new CpuUsageModule());
        modules.registerModule(new AveragePingModule());

        // Dashboard real-time (WebSocket)
        modules.registerModule(new DashboardModule());

        // === PLAYER MODULES (events) ===
        modules.registerModule(new PlayerConnectModule());
        modules.registerModule(new PlayerDisconnectModule());

        // Custom metrics modules
        modules.registerModule(new MinecraftActivityModule());

        // Player data tracking (real-time position/health)
        playerDataModule = new PlayerDataModule();
        modules.registerModule(playerDataModule);

        // Start cycles
        modules.startCycle();  // Metrics every 30 min
        modules.startTick();   // Real-time updates

        // Register commands
        PluginCommand linkCmd = getCommand("nexus_link");
        if (linkCmd != null) linkCmd.setExecutor(new NexusLinkCommand());

        getLogger().info("NexusMaps Plugin enabled!");
    }

    @Override
    public void onDisable() {
        // Unregister ErrorCollector
        it.littlesquad.api.ErrorCollector.unregister();

        // Unregister ConsoleLogAppender
        it.littlesquad.api.ConsoleLogAppender.unregister();

        // Shutdown modules
        ModuleManager.getInstance().shutdown();

        // Disconnect WebSocket
        NexusWebSocketClient.getInstance().disconnect();

        getLogger().info("NexusMaps Plugin disabled!");
    }

    private void sendServerInformation() {
        String ip = Bukkit.getIp().isEmpty() ? "0.0.0.0" : Bukkit.getIp();
        int port = Bukkit.getPort();
        String ipPort = ip + ":" + port;
        String seed = Bukkit.getWorlds().isEmpty() ? "0" : String.valueOf(Bukkit.getWorlds().getFirst().getSeed());
        String name = Bukkit.getMotd().isEmpty() ? Bukkit.getServer().getName() : Bukkit.getMotd();
        String gamemode = Bukkit.getDefaultGameMode().name();
        String version = Bukkit.getBukkitVersion().split("-")[0];

        Map<String, Object> payload = Map.of(
                "name", name,
                "seed", seed,
                "gamemode", gamemode,
                "game", "Minecraft",
                "version", version,
                "ip", ipPort
        );

        NexusWebSocketClient.getInstance().send("information", payload);
    }

    private void handleServerMessage(String message) {
        if (NexusConfig.get().isDebug()) {
            getLogger().info("Server: " + message);
        }
        if (message.startsWith("consoleCommand|")) {
            try {
                String command = message.substring("consoleCommand|".length());
                Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
            } catch (Exception e) {
                getLogger().severe("Failed to process console command: " + e.getMessage());
            }
        }
        if (message.startsWith("playerAction|")) {
            try {
                String jsonStr = message.substring("playerAction|".length());
                com.google.gson.Gson gson = new com.google.gson.Gson();
                PlayerActionPayload payload = gson.fromJson(jsonStr, PlayerActionPayload.class);
                if (payload != null && payload.steamId != null) {
                    java.util.UUID uuid = java.util.UUID.fromString(payload.steamId);
                    Bukkit.getScheduler().runTask(this, () -> executePlayerAction(uuid, payload.command, payload.userInput));
                }
            } catch (Exception e) {
                getLogger().severe("Failed to process player action: " + e.getMessage());
            }
        }
    }

    private void executePlayerAction(java.util.UUID uuid, String command, String userInput) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            getLogger().warning("Player with UUID " + uuid + " not found online.");
            return;
        }

        getLogger().info("Executing action: " + command + " on player: " + player.getName());

        switch (command) {
            case "kick":
                String kickReason = (userInput == null || userInput.trim().isEmpty()) ? "Kicked by administrator" : userInput;
                player.kickPlayer(kickReason);
                break;
            case "ban":
                String banReason = (userInput == null || userInput.trim().isEmpty()) ? "Banned by administrator" : userInput;
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + player.getName() + " " + banReason);
                break;
            case "kill":
                player.setHealth(0.0);
                break;
            case "mute":
                String muteReason = (userInput == null || userInput.trim().isEmpty()) ? "" : " " + userInput;
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mute " + player.getName() + muteReason);
                break;
            case "unmute":
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "unmute " + player.getName());
                break;
            case "gamemode_survival":
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamemode survival " + player.getName());
                break;
            case "gamemode_creative":
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamemode creative " + player.getName());
                break;
            case "gamemode_spectator":
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamemode spectator " + player.getName());
                break;
            case "op":
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "op " + player.getName());
                break;
            case "deop":
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "deop " + player.getName());
                break;
            case "freeze":
                frozenPlayers.add(uuid);
                player.sendMessage("§cYou have been frozen by an administrator.");
                break;
            case "unfreeze":
                frozenPlayers.remove(uuid);
                player.sendMessage("§aYou have been unfrozen.");
                break;
            default:
                getLogger().warning("Unknown command action: " + command);
                break;
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            org.bukkit.Location from = event.getFrom();
            org.bukkit.Location to = event.getTo();
            if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
                event.setTo(new org.bukkit.Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
            }
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        NexusWebSocketClient ws = NexusWebSocketClient.getInstance();
        if (ws.isConnected()) {
            Map<String, Object> payload = Map.of(
                    "message", "<" + event.getPlayer().getName() + "> " + event.getMessage(),
                    "time", System.currentTimeMillis() / 1000.0,
                    "type", "chat"
            );
            ws.send("console_log", payload);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(org.bukkit.event.server.ServerCommandEvent event) {
        NexusWebSocketClient ws = NexusWebSocketClient.getInstance();
        if (ws.isConnected()) {
            Map<String, Object> payload = Map.of(
                    "message", "> " + event.getCommand(),
                    "time", System.currentTimeMillis() / 1000.0,
                    "type", "print"
            );
            ws.send("console_log", payload);
        }
    }

    /**
     * Called when a player disconnects to clear their cached data.
     */
    public void onPlayerDisconnect(java.util.UUID uuid) {
        frozenPlayers.remove(uuid);
        if (playerDataModule != null) {
            playerDataModule.removePlayer(uuid);
        }
    }

    public void reloadNexusConfig() {
        NexusConfig.reload(this);
        getLogger().info("Configuration reloaded!");
    }

    public static Main getInstance() {
        return instance;
    }
}
