package it.littlesquad.base_modules;

import it.littlesquad.api.PlayerEventModule;
import it.littlesquad.network.NexusWebSocketClient;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.jspecify.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module for tracking player data in real-time.
 * IMPORTANT: Only sends data that has changed since the last update.
 */
public class PlayerDataModule extends PlayerEventModule {

    // Cache of previous data for each player
    private final ConcurrentHashMap<UUID, PlayerSnapshot> lastSnapshots = new ConcurrentHashMap<>();

    // Cache of the last biome grid center coordinates sent for each player
    private final ConcurrentHashMap<UUID, GridCoords> lastSentGrids = new ConcurrentHashMap<>();

    // Thresholds for considering a change significant
    private static final double POSITION_THRESHOLD = 0.5;  // Blocks
    private static final double ANGLE_THRESHOLD = 5.0;     // Degrees
    private static final double VELOCITY_THRESHOLD = 0.1;

    private long lastReportTime = 0;

    @Override
    public String getName() {
        return "player_data";
    }

    @Override
    public @NonNull String getRoute() {
        return "player_data";
    }

    @Override
    public void onTick(NexusWebSocketClient ws) {
        long now = System.currentTimeMillis();
        long interval = it.littlesquad.config.NexusConfig.get().getMapReportIntervalMs();
        if (interval <= 0) {
            interval = 250; // Fallback to 250ms
        }
        if (now - lastReportTime < interval) {
            return;
        }
        lastReportTime = now;

        Map<String, Map<String, Object>> statsChangesMap = new HashMap<>();
        Map<String, Map<String, Object>> positionChangesMap = new HashMap<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            String uuidStr = uuid.toString();
            PlayerSnapshot current = createSnapshot(player);
            PlayerSnapshot last = lastSnapshots.get(uuid);

            if (last == null) {
                lastSnapshots.put(uuid, current);

                // First time: send everything
                Map<String, Object> stats = new HashMap<>();
                stats.put("health", current.health);
                stats.put("armor", current.armor);
                stats.put("name", player.getName());
                stats.put("biome", current.biome);
                statsChangesMap.put(uuidStr, stats);

                Map<String, Object> posMap = new HashMap<>();
                posMap.put("pos", formatCoords(current.pos));
                posMap.put("ang", formatCoords(current.ang));
                posMap.put("vel", formatCoords(current.vel));
                posMap.put("world", current.world);
                positionChangesMap.put(uuidStr, posMap);
                continue;
            }

            // Check stats changes
            Map<String, Object> statsChanges = new HashMap<>();
            if (current.health != last.health) {
                statsChanges.put("health", current.health);
            }
            if (current.armor != last.armor) {
                statsChanges.put("armor", current.armor);
            }
            if (!current.biome.equals(last.biome)) {
                statsChanges.put("biome", current.biome);
            }
            if (!statsChanges.isEmpty()) {
                statsChanges.put("name", player.getName());
                statsChangesMap.put(uuidStr, statsChanges);
            }

            // Check position changes
            Map<String, Object> positionChanges = new HashMap<>();
            boolean posChanged = hasPositionChanged(current.pos, last.pos);
            boolean angChanged = hasAngleChanged(current.ang, last.ang);
            boolean velChanged = hasVelocityChanged(current.vel, last.vel);
            boolean worldChanged = !current.world.equals(last.world);

            if (posChanged) {
                positionChanges.put("pos", formatCoords(current.pos));
            }
            if (angChanged) {
                positionChanges.put("ang", formatCoords(current.ang));
            }
            if (velChanged) {
                positionChanges.put("vel", formatCoords(current.vel));
            }
            if (worldChanged) {
                positionChanges.put("world", current.world);
            }

            if (!positionChanges.isEmpty()) {
                positionChangesMap.put(uuidStr, positionChanges);
            }

            if (!statsChanges.isEmpty() || !positionChanges.isEmpty()) {
                lastSnapshots.put(uuid, current);
            }

            // Send biome grid update if player crossed grid boundary (every 64 blocks)
            Location loc = player.getLocation();
            int gridX = (int) Math.floor(loc.getX() / 64.0) * 64;
            int gridZ = (int) Math.floor(loc.getZ() / 64.0) * 64;
            GridCoords lastGrid = lastSentGrids.get(uuid);
            if (lastGrid == null || lastGrid.x != gridX || lastGrid.z != gridZ) {
                lastSentGrids.put(uuid, new GridCoords(gridX, gridZ));
                sendBiomeGrid(ws, player, gridX, gridZ);
            }
        }

        if (!statsChangesMap.isEmpty()) {
            Map<String, Object> payload = Map.of(
                    "eventID", "player_stats_changed",
                    "data", statsChangesMap
            );
            ws.send(getRoute(), payload);
        }

        if (!positionChangesMap.isEmpty()) {
            Map<String, Object> payload = Map.of(
                    "eventID", "player_position",
                    "data", positionChangesMap
            );
            ws.send(getRoute(), payload);
        }
    }

    private String formatCoords(double[] coords) {
        return String.format(Locale.ROOT, "%.2f %.2f %.2f", coords[0], coords[1], coords[2]);
    }

    private PlayerSnapshot createSnapshot(Player player) {
        Location loc = player.getLocation();
        Vector vel = player.getVelocity();

        return new PlayerSnapshot(
                player.getHealth(),
                getArmorValue(player),
                new double[]{loc.getX(), loc.getY(), loc.getZ()},
                new double[]{
                        0, // Roll does not exist in Minecraft, put it first to match "roll pitch yaw"
                        normalizeAngle(loc.getPitch()),
                        normalizeAngle(loc.getYaw())
                },
                new double[]{vel.getX(), vel.getY(), vel.getZ()},
                loc.getWorld().getName(),
                loc.getBlock().getBiome().name()
        );
    }


    private double getArmorValue(Player player) {
        double armor = 0;
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null) {
                // Approximate armor point values
                String type = item.getType().name();
                if (type.contains("LEATHER")) armor += type.contains("CHESTPLATE") ? 3 : type.contains("LEGGINGS") ? 2 : 1;
                else if (type.contains("CHAINMAIL")) armor += type.contains("CHESTPLATE") ? 5 : type.contains("LEGGINGS") ? 4 : 2;
                else if (type.contains("IRON")) armor += type.contains("CHESTPLATE") ? 6 : type.contains("LEGGINGS") ? 5 : 2;
                else if (type.contains("GOLD")) armor += type.contains("CHESTPLATE") ? 5 : type.contains("LEGGINGS") ? 3 : 2;
                else if (type.contains("DIAMOND")) armor += type.contains("CHESTPLATE") ? 8 : type.contains("LEGGINGS") ? 6 : 3;
                else if (type.contains("NETHERITE")) armor += type.contains("CHESTPLATE") ? 8 : type.contains("LEGGINGS") ? 6 : 3;
            }
        }
        return armor;
    }

    private double normalizeAngle(double angle) {
        // Convert from -180/180 to 0-360
        angle = angle % 360;
        if (angle < 0) angle += 360;
        return angle;
    }

    private boolean hasPositionChanged(double[] current, double[] last) {
        return Math.abs(current[0] - last[0]) > POSITION_THRESHOLD ||
               Math.abs(current[1] - last[1]) > POSITION_THRESHOLD ||
               Math.abs(current[2] - last[2]) > POSITION_THRESHOLD;
    }

    private boolean hasAngleChanged(double[] current, double[] last) {
        return Math.abs(current[0] - last[0]) > ANGLE_THRESHOLD ||
               Math.abs(current[1] - last[1]) > ANGLE_THRESHOLD;
    }

    private boolean hasVelocityChanged(double[] current, double[] last) {
        return Math.abs(current[0] - last[0]) > VELOCITY_THRESHOLD ||
               Math.abs(current[1] - last[1]) > VELOCITY_THRESHOLD ||
               Math.abs(current[2] - last[2]) > VELOCITY_THRESHOLD;
    }

    /**
     * Removes the player from the cache on disconnect.
     */
    public void removePlayer(UUID uuid) {
        lastSnapshots.remove(uuid);
        lastSentGrids.remove(uuid);
    }

    private void sendBiomeGrid(NexusWebSocketClient ws, Player player, int gridX, int gridZ) {
        org.bukkit.World world = player.getWorld();
        int startX = gridX - 256;
        int startZ = gridZ - 256;
        byte[] biomeData = new byte[64 * 64];
        
        for (int dz = 0; dz < 64; dz++) {
            for (int dx = 0; dx < 64; dx++) {
                int x = startX + dx * 8;
                int z = startZ + dz * 8;
                org.bukkit.block.Biome biome = world.getBiome(x, 64, z);
                biomeData[dz * 64 + dx] = getBiomeGroup(biome);
            }
        }
        
        String base64Data = java.util.Base64.getEncoder().encodeToString(biomeData);
        
        Map<String, Object> payload = Map.of(
            "eventID", "biome_grid",
            "playerUUID", player.getUniqueId().toString(),
            "world", world.getName(),
            "x", startX,
            "z", startZ,
            "data", base64Data
        );
        ws.send(getRoute(), payload);
    }

    private byte getBiomeGroup(org.bukkit.block.Biome biome) {
        if (biome == null) return 0;
        String name = biome.name().toUpperCase();
        if (name.contains("OCEAN") || name.contains("RIVER") || name.contains("LAKE") || name.contains("WATER") || name.contains("BEACH")) {
            if (name.contains("BEACH")) return 2; // Sand
            return 1; // Water
        }
        if (name.contains("DESERT") || name.contains("SAND") || name.contains("BADLANDS") || name.contains("MESA")) {
            return 2; // Desert / Sand
        }
        if (name.contains("FOREST") || name.contains("TAIGA") || name.contains("WOODS") || name.contains("JUNGLE") || name.contains("GROVE") || name.contains("BIRCH")) {
            return 3; // Forest
        }
        if (name.contains("MOUNTAIN") || name.contains("PEAK") || name.contains("HILL") || name.contains("CLIFF") || name.contains("SLOPES")) {
            return 4; // Mountain
        }
        if (name.contains("SWAMP") || name.contains("MANGROVE")) {
            return 5; // Swamp
        }
        if (name.contains("NETHER") || name.contains("BASALT") || name.contains("WASTES") || name.contains("VALLEY") || name.contains("WARPED") || name.contains("CRIMSON")) {
            return 6; // Nether
        }
        if (name.contains("END") || name.contains("VOID")) {
            return 7; // The End
        }
        if (name.contains("SNOW") || name.contains("ICE") || name.contains("FROZEN") || name.contains("SLOPES")) {
            return 8; // Snow / Ice
        }
        return 0; // Plains / Grass / Default
    }

    private record GridCoords(int x, int z) {}

    private record PlayerSnapshot(
            double health,
            double armor,
            double[] pos,
            double[] ang,
            double[] vel,
            String world,
            String biome
    ) {}
}
