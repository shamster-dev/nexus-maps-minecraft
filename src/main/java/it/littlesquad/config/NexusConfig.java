package it.littlesquad.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

/**
 * Configuration manager for the NexusMaps plugin.
 *
 * <p>This class loads and provides access to configuration values from
 * {@code config.yml}. It follows the singleton pattern and must be
 * initialized before use.</p>
 *
 * <h2>Configuration File (config.yml)</h2>
 * <pre>{@code
 * # Enable test mode for local development
 * test-mode: false
 *
 * # Custom API domain (leave empty for defaults)
 * domain: ""
 *
 * # Custom WebSocket URL (leave empty for defaults)
 * websocket-url: ""
 *
 * # Metrics collection interval in minutes
 * metrics-interval: 30
 *
 * # Enable debug logging
 * debug: false
 * }</pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // In onLoad() or onEnable()
 * NexusConfig.init(this);
 *
 * // Access configuration values
 * NexusConfig config = NexusConfig.get();
 * String domain = config.getDomain();
 * boolean debug = config.isDebug();
 *
 * // Reload configuration at runtime
 * NexusConfig.reload(plugin);
 * }</pre>
 *
 * <h2>URL Resolution</h2>
 * <p>The domain and WebSocket URLs are resolved in the following order:</p>
 * <ol>
 *   <li>Custom value from config.yml (if not empty)</li>
 *   <li>Default based on test-mode setting</li>
 * </ol>
 *
 * <table border="1">
 *   <tr><th>Setting</th><th>Test Mode</th><th>Production</th></tr>
 *   <tr><td>domain</td><td>http://localhost:3000</td><td>https://www.nexusmaps.xyz</td></tr>
 *   <tr><td>websocket-url</td><td>ws://localhost:3100</td><td>wss://www.nexusmaps.xyz/socket</td></tr>
 * </table>
 *
 * @see it.littlesquad.network.NexusHttpClient
 * @see it.littlesquad.network.NexusWebSocketClient
 */
public final class NexusConfig {

    private static final String DEFAULT_DOMAIN_PROD = "https://www.nexusmaps.xyz";
    private static final String DEFAULT_DOMAIN_TEST = "http://localhost:3000";
    private static final String DEFAULT_WS_PROD = "wss://www.nexusmaps.xyz/socket";
    private static final String DEFAULT_WS_TEST = "ws://localhost:3100";

    private static NexusConfig instance;
    private static JavaPlugin plugin;
    private static File apiKeyFile;

    private boolean testMode;
    private String domain;
    private String websocketUrl;
    private int metricsIntervalSeconds;
    private int mapReportIntervalMs;
    private boolean debug;
    private String apiKey;

    private NexusConfig() {}

    /**
     * Initializes the configuration from the plugin's config.yml.
     *
     * <p>This method must be called once during plugin startup, typically in
     * {@code onLoad()} or {@code onEnable()}. It will:</p>
     * <ol>
     *   <li>Save the default config.yml if it doesn't exist</li>
     *   <li>Load all configuration values</li>
     * </ol>
     *
     * @param plugin The JavaPlugin instance
     * @throws IllegalArgumentException if plugin is null
     */
    public static void init(JavaPlugin pluginInstance) {
        plugin = pluginInstance;
        plugin.saveDefaultConfig();
        apiKeyFile = new File(plugin.getDataFolder(), "apikey.yml");
        instance = new NexusConfig();
        instance.load(plugin.getConfig());
        instance.loadApiKey();
    }

    /**
     * Reloads the configuration from disk.
     *
     * <p>Use this method to apply changes made to config.yml without
     * restarting the server. Note that some settings (like WebSocket URL)
     * may require reconnection to take effect.</p>
     *
     * @param plugin The JavaPlugin instance
     */
    public static void reload(JavaPlugin plugin) {
        plugin.reloadConfig();
        instance.load(plugin.getConfig());
    }

    /**
     * Returns the configuration instance.
     *
     * @return The NexusConfig instance
     * @throws IllegalStateException if {@link #init(JavaPlugin)} was not called
     */
    public static NexusConfig get() {
        if (instance == null) {
            throw new IllegalStateException(
                    "NexusConfig not initialized! Call NexusConfig.init(plugin) first."
            );
        }
        return instance;
    }

    private void load(FileConfiguration config) {
        this.testMode = config.getBoolean("test-mode", false);
        this.metricsIntervalSeconds = config.getInt("metrics-interval-seconds", 30);
        this.mapReportIntervalMs = config.getInt("map-report-interval-ms", 250);
        this.debug = config.getBoolean("debug", false);

        // Domain: use custom value if provided, otherwise default based on test-mode
        String customDomain = config.getString("domain", "");
        if (customDomain == null || customDomain.isEmpty()) {
            this.domain = testMode ? DEFAULT_DOMAIN_TEST : DEFAULT_DOMAIN_PROD;
        } else {
            this.domain = customDomain;
        }

        // WebSocket URL: use custom value if provided, otherwise default based on test-mode
        String customWs = config.getString("websocket-url", "");
        if (customWs == null || customWs.isEmpty()) {
            this.websocketUrl = testMode ? DEFAULT_WS_TEST : DEFAULT_WS_PROD;
        } else {
            this.websocketUrl = customWs;
        }

        // Validate TLS/SSL in production mode
        if (!testMode) {
            validateSecureUrls();
        }
    }

    /**
     * Validates that URLs use TLS/SSL in production mode.
     *
     * @throws IllegalStateException if insecure URLs are used in production
     */
    private void validateSecureUrls() {
        if (!domain.startsWith("https://")) {
            throw new IllegalStateException(
                "Security Error: HTTP domain must use HTTPS in production mode. " +
                "Current: " + domain + ". Use 'https://' or enable test-mode."
            );
        }

        if (!websocketUrl.startsWith("wss://")) {
            throw new IllegalStateException(
                "Security Error: WebSocket must use WSS (secure) in production mode. " +
                "Current: " + websocketUrl + ". Use 'wss://' or enable test-mode."
            );
        }
    }

    // ============================================================
    // GETTERS
    // ============================================================

    /**
     * Returns whether test mode is enabled.
     *
     * <p>When test mode is enabled, the plugin connects to local development
     * servers instead of production.</p>
     *
     * @return {@code true} if test mode is enabled
     */
    public boolean isTestMode() {
        return testMode;
    }

    /**
     * Returns the HTTP API domain.
     *
     * <p>This is the base URL for all HTTP requests (without trailing slash).</p>
     *
     * @return The domain URL (e.g., "https://www.nexusmaps.xyz")
     */
    public String getDomain() {
        return domain;
    }

    /**
     * Returns the WebSocket server URL.
     *
     * @return The WebSocket URL (e.g., "wss://www.nexusmaps.xyz/socket")
     */
    public String getWebsocketUrl() {
        return websocketUrl;
    }

    /**
     * Returns the metrics collection interval in seconds.
     *
     * <p>This determines how often the {@link it.littlesquad.api.ModuleManager}
     * runs the collection cycle and sends metrics to the server.</p>
     *
     * @return The interval in seconds (default: 30)
     */
    public int getMetricsIntervalSeconds() {
        return metricsIntervalSeconds;
    }

    /**
     * Returns the live map reporting interval in milliseconds.
     *
     * @return The interval in milliseconds (default: 250)
     */
    public int getMapReportIntervalMs() {
        return mapReportIntervalMs;
    }

    /**
     * Returns whether debug logging is enabled.
     *
     * <p>When enabled, the HTTP and WebSocket clients will log detailed
     * information about requests, responses, and messages.</p>
     *
     * @return {@code true} if debug mode is enabled
     */
    public boolean isDebug() {
        return debug;
    }

    /**
     * Returns whether the HTTP connection uses TLS/SSL (HTTPS).
     *
     * @return {@code true} if using HTTPS
     */
    public boolean isHttpSecure() {
        return domain.startsWith("https://");
    }

    /**
     * Returns whether the WebSocket connection uses TLS/SSL (WSS).
     *
     * @return {@code true} if using WSS
     */
    public boolean isWebSocketSecure() {
        return websocketUrl.startsWith("wss://");
    }

    /**
     * Returns whether all connections are secured with TLS/SSL.
     *
     * @return {@code true} if both HTTP and WebSocket use TLS
     */
    public boolean isFullySecure() {
        return isHttpSecure() && isWebSocketSecure();
    }

    // ============================================================
    // API KEY MANAGEMENT
    // ============================================================

    /**
     * Returns the API key for authentication.
     *
     * @return The API key, or null if not set
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Returns whether an API key is configured.
     *
     * @return {@code true} if an API key is set
     */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isEmpty();
    }

    /**
     * Sets the API key and persists it to disk.
     *
     * <p>The API key is stored in a separate file (apikey.yml) for security,
     * keeping it separate from the main configuration.</p>
     *
     * @param newApiKey The API key to set
     * @return {@code true} if saved successfully
     */
    public boolean setApiKey(String newApiKey) {
        this.apiKey = newApiKey;
        return saveApiKey();
    }

    /**
     * Loads the API key from the apikey.yml file.
     */
    private void loadApiKey() {
        if (apiKeyFile.exists()) {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(apiKeyFile);
            this.apiKey = yaml.getString("api-key", null);
        }
    }

    public void loadApiKeyFromWorld() {
        if (this.apiKey == null || this.apiKey.isEmpty()) {
            File worldFile = getWorldApiKeyFile();
            if (worldFile != null && worldFile.exists()) {
                try {
                    this.apiKey = new String(java.nio.file.Files.readAllBytes(worldFile.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
                } catch (IOException ignored) {}
            }
        }
    }

    private File getWorldApiKeyFile() {
        if (!org.bukkit.Bukkit.getWorlds().isEmpty()) {
            File worldFolder = org.bukkit.Bukkit.getWorlds().get(0).getWorldFolder();
            return new File(worldFolder, "nexus_api_key.txt");
        }
        return null;
    }

    /**
     * Saves the API key to the apikey.yml file.
     *
     * @return {@code true} if saved successfully
     */
    private boolean saveApiKey() {
        try {
            if (!apiKeyFile.getParentFile().exists()) {
                apiKeyFile.getParentFile().mkdirs();
            }

            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("api-key", apiKey);
            yaml.save(apiKeyFile);

            // Also save to world folder as backup persistence
            File worldFile = getWorldApiKeyFile();
            if (worldFile != null && apiKey != null) {
                java.nio.file.Files.writeString(worldFile.toPath(), apiKey, java.nio.charset.StandardCharsets.UTF_8);
            }
            return true;
        } catch (IOException e) {
            if (plugin != null) {
                plugin.getLogger().severe("Failed to save API key: " + e.getMessage());
            }
            return false;
        }
    }
}
