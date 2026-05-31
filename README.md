# NexusMaps Plugin

Real-time monitoring and analytics for Minecraft servers. NexusMaps streams live server metrics, player tracking, and custom data to the NexusMaps dashboard via WebSocket — with zero impact on server performance.

---

## Features

- **Live Dashboard** — TPS, memory, CPU, player count and more, updated every 5 seconds
- **Player Tracking** — Real-time position, health, and armor with bandwidth-efficient delta updates
- **Custom Modules** — Plug in your own data collectors: line charts, leaderboards, pie graphs
- **Async Architecture** — All network I/O is non-blocking; the main thread is never touched
- **Auto-reconnect** — Automatic WebSocket reconnection on connection loss
- **Extensible API** — Clean interface-based API for third-party module development

---

## Requirements

- Java 21+
- Spigot / Paper / Purpur 1.21+
- A NexusMaps API key

---

## Installation

1. Download the latest JAR from the [Releases](https://github.com/nexusmaps/plugin/releases) page
2. Drop it into your server's `plugins/` folder
3. Start (or reload) the server — `plugins/NexusMaps/config.yml` will be generated
4. Link your server to the dashboard:
   ```
   /nexus_link <your-api-key>
   ```

---

## Configuration

```yaml
# plugins/NexusMaps/config.yml

# Set to true to connect to a custom server instead of production
test-mode: false

# Override the default API endpoint (leave empty to use defaults)
domain: ""

# Override the default WebSocket URL (leave empty to use defaults)
websocket-url: ""

# How often base metrics are persisted to the dashboard (minutes)
metrics-interval: 30

# Print detailed request/response logs to the console
debug: false
```

> **Default endpoints**
>
> | Mode | HTTP | WebSocket |
> |------|------|-----------|
> | Production | `https://www.nexusmaps.xyz` | `wss://www.nexusmaps.xyz/socket` |
> | Test | `http://localhost:3000` | `ws://localhost:3100` |

---

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/nexus_link <api-key>` | `nexusmaps.admin` (op) | Links this server to the dashboard |
| `/nexus_link` | `nexusmaps.admin` (op) | Shows current link status |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        MINECRAFT SERVER                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────────────┐ │
│  │ Base Modules│    │Player Events│    │    Custom Modules       │ │
│  │  - TPS      │    │  - Connect  │    │  - Your metrics here    │ │
│  │  - Memory   │    │  - Quit     │    │  - Database queries     │ │
│  │  - Players  │    │  - Position │    │  - External APIs        │ │
│  └──────┬──────┘    └──────┬──────┘    └───────────┬─────────────┘ │
│         └──────────────────┼───────────────────────┘               │
│                            │                                       │
│                   ┌────────▼────────┐                              │
│                   │  ModuleManager  │                              │
│                   │  - Lifecycle    │                              │
│                   │  - Scheduling   │                              │
│                   │  - Collection   │                              │
│                   └────────┬────────┘                              │
│                            │                                       │
│         ┌──────────────────┴──────────────────┐                   │
│         │                                     │                   │
│  ┌──────▼──────┐                     ┌────────▼───────┐           │
│  │ HTTP Client │                     │   WS Client    │           │
│  │ POST /metrics                     │  real-time     │           │
│  │ POST /errors │                    │  events        │           │
│  └──────┬──────┘                     └────────┬───────┘           │
└─────────┼───────────────────────────────────── ┼──────────────────┘
          │                                      │
          │         ┌───────────────┐            │
          └────────►│ NexusMaps API │◄───────────┘
                    └───────┬───────┘
                            │
                    ┌───────▼───────┐
                    │   Dashboard   │
                    │ Graphs · Maps │
                    │  Analytics    │
                    └───────────────┘
```

---

## Data Flow

### Periodic Metrics (every 30 minutes)
```
Base Modules + Custom Modules
            │
            ▼
    ModuleManager.runCycle()
            │
            ▼
    POST /server/save-metrics
    {
      "base":   { "tps": 20, "activePlayers": 125, ... },
      "custom": { "Economy": { "numeric": {...}, "map": {...} } }
    }
```

### Real-time Updates (WebSocket)
```
DashboardModule   (every 5 seconds)   ──► WS route: dashboard_details
PlayerDataModule  (every tick, delta)  ──► WS route: player_data
PlayerJoinEvent                        ──► WS route: player_connect
PlayerQuitEvent                        ──► WS route: player_disconnect
```

---

## Creating Custom Modules

### Simple Numeric Module

```java
public class ShopRevenueModule extends BaseMetricModule<Double> {

    @Override
    public String getName() {
        return "shop_revenue";
    }

    @Override
    public CompletableFuture<Double> getValue() {
        // Async-safe: database calls are fine here
        return CompletableFuture.supplyAsync(() -> ShopPlugin.getTotalRevenue());
    }
}
```

### Advanced Module (charts, leaderboards, pie graphs)

```java
public class EconomyModule implements NexusModule<Map<String, Object>> {

    @Override public String getName()         { return "Economy"; }
    @Override public String getRoute()        { return "server/save-metrics"; }
    @Override public Transport getTransport() { return Transport.HTTP; }

    @Override
    public CompletableFuture<Map<String, Object>> getValue() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> data = new HashMap<>();

            // Line chart (single or multi-line)
            data.put("numeric", Map.of(
                "Total Balance", getTotalMoney(),
                "Transactions",  Map.of(
                    "Deposits",    getDeposits(),
                    "Withdrawals", getWithdrawals()
                )
            ));

            // Leaderboard
            data.put("map", Map.of(
                "Richest Players", getTopPlayers()  // List<Map<"title","value">>
            ));

            // Pie chart
            data.put("pie", Map.of(
                "Wealth Distribution", getDistribution()
            ));

            return data;
        });
    }
}
```

### Event-based Module

```java
public class PlayerDeathModule extends PlayerEventModule implements Listener {

    @Override public String getName()  { return "player_death"; }
    @Override public String getRoute() { return "player_death"; }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        NexusWebSocketClient.getInstance().send(Map.of(
            "route", getRoute(),
            "data",  Map.of("name", event.getEntity().getName())
        ));
    }
}
```

### Registering Modules

```java
@Override
public void onEnable() {
    ModuleManager modules = ModuleManager.getInstance();
    modules.init(this);

    modules.registerModule(new EconomyModule());
    modules.registerModule(new ShopRevenueModule());
    modules.registerModule(new PlayerDeathModule());

    modules.startCycle(); // periodic HTTP metrics
    modules.startTick();  // real-time WebSocket updates
}
```

---

## API Reference

### Module Types

| Type | Extend | Transport | Use Case |
|------|--------|-----------|----------|
| Base Metric | `BaseMetricModule<T>` | HTTP + WS | Built-in server stats |
| Custom Metric | `NexusModule<T>` | Any | Custom charts and data |
| Player Event | `PlayerEventModule` | WebSocket | Instant player events |

### HTTP Endpoints

| Route | Method | Description |
|-------|--------|-------------|
| `server/initialized` | POST | Called on plugin enable |
| `server/save-metrics` | POST | Periodic metrics payload |
| `server/add-error` | POST | Error reporting |

### WebSocket Routes

| Route | Trigger | Payload |
|-------|---------|---------|
| `dashboard_details` | Every 5 seconds | `{ tps, activePlayers, memory_usage, cpu_usage, ... }` |
| `player_connect` | Player join | `{ id, country, name }` |
| `player_disconnect` | Player quit | `"uuid"` |
| `player_data` | Every tick (delta only) | `{ name, health?, pos?, ang?, vel? }` |

### NexusModule Interface

```java
public interface NexusModule<T> {
    String    getName();          // Unique key in the metrics payload
    String    getRoute();         // API route or WebSocket route
    Transport getTransport();     // HTTP, WEBSOCKET, or BOTH

    CompletableFuture<T> getValue(); // Async data collection

    // Lifecycle hooks (all optional)
    default void onEnable()  {}
    default void onDisable() {}
    default void onCycle(NexusHttpClient http, NexusWebSocketClient ws) {}
    default void onTick(NexusWebSocketClient ws) {}

    // Classification (optional)
    default boolean isBaseModule() { return false; }
    default boolean isEventBased() { return false; }
    default int     getPriority()  { return 0; }
}
```

---

## Building

### Full plugin JAR (includes OkHttp, Gson — deploy this to your server)
```bash
mvn package
# Output: target/NexusMapsPlugin-1.0-SNAPSHOT.jar
```

### API-only JAR (for third-party module developers)
```bash
mvn package -P api-only
# Output: target/NexusMapsPlugin-1.0-SNAPSHOT-api.jar
```

The API JAR contains only the public interfaces and base classes
(`NexusModule`, `BaseMetricModule`, `PlayerEventModule`, `ModuleManager`,
`NexusHttpClient`, `NexusWebSocketClient`, `NexusConfig`) — no OkHttp,
no Kotlin runtime, no base module implementations. Distribute this JAR
to developers who want to build custom modules as a dependency.

---

## Project Structure

```
src/main/java/it/littlesquad/
├── Main.java                          # Plugin entry point
├── api/
│   ├── NexusModule.java               # Core module interface
│   ├── BaseMetricModule.java          # Abstract base for server metrics
│   ├── PlayerEventModule.java         # Abstract base for player events
│   └── ModuleManager.java             # Lifecycle & scheduling manager
├── base_modules/
│   ├── TpsModule.java
│   ├── ActivePlayersModule.java
│   ├── ActiveStaffModule.java
│   ├── MemoryUsageModule.java
│   ├── CpuUsageModule.java
│   ├── AveragePingModule.java
│   ├── EntityCountModule.java
│   ├── LoadedChunksModule.java
│   ├── WorldCountModule.java
│   ├── DashboardModule.java           # Real-time WS dashboard feed
│   ├── PlayerConnectModule.java
│   ├── PlayerDisconnectModule.java
│   └── PlayerDataModule.java          # Delta position/health tracking
├── network/
│   ├── NexusHttpClient.java           # Async HTTP client (Java HttpClient)
│   └── NexusWebSocketClient.java      # Persistent WebSocket client (OkHttp)
├── config/
│   └── NexusConfig.java               # Config & API key manager
└── commands/
    └── NexusLinkCommand.java          # /nexus_link command
```

---

## Support

- Documentation: [docs.nexusmaps.xyz](https://docs.nexusmaps.xyz)
- Discord: [discord.gg/nexusmaps](https://discord.gg/nexusmaps)
- Issues: [github.com/nexusmaps/plugin/issues](https://github.com/nexusmaps/plugin/issues)
