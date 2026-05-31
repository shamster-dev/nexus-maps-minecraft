package it.littlesquad.commands;

import it.littlesquad.config.NexusConfig;
import it.littlesquad.network.NexusWebSocketClient;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/**
 * Command handler for /nexus_link.
 *
 * <p>Links this server to the NexusMaps dashboard by setting the API key.
 * The key is stored persistently and sent as the {@code API-KEY} header
 * in all HTTP requests and the WebSocket handshake.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 * /nexus_link &lt;api_key&gt;  - Links the server with the given API key
 * /nexus_link             - Shows current link status
 * </pre>
 *
 * <h2>Permission</h2>
 * <p>Requires operator permissions (op) to use.</p>
 */
public class NexusLinkCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("\u00a7cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            if (NexusConfig.get().hasApiKey()) {
                String key = NexusConfig.get().getApiKey();
                String masked = maskApiKey(key);
                sender.sendMessage("\u00a7aServer is linked to NexusMaps: \u00a77" + masked);
            } else {
                sender.sendMessage("\u00a7eServer is not linked.");
                sender.sendMessage("\u00a77Usage: /nexus_link <api_key>");
            }
            return true;
        }

        String newApiKey = args[0];

        if (newApiKey.length() < 8) {
            sender.sendMessage("\u00a7cAPI key is too short. Must be at least 8 characters.");
            return true;
        }

        boolean saved = NexusConfig.get().setApiKey(newApiKey);

        if (saved) {
            sender.sendMessage("\u00a7aServer linked to NexusMaps successfully!");
            sender.sendMessage("\u00a77Reconnecting to apply changes...");

            NexusWebSocketClient.getInstance().reconnect();
        } else {
            sender.sendMessage("\u00a7cFailed to save API key. Check server logs.");
        }

        return true;
    }

    private String maskApiKey(String key) {
        if (key == null || key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
