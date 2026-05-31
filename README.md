# Installation
https://www.nexusmaps.xyz/docs

## Requirements

- Java 21+
- Spigot / Paper / Purpur 1.21+
- A NexusMaps API key

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
-
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
```- Discord: [discord.gg/nexusmaps](https://discord.gg/nexusmaps)
- Issues: [github.com/nexusmaps/plugin/issues](https://github.com/nexusmaps/plugin/issues)
