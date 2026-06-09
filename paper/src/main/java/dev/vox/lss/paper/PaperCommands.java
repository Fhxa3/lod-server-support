package dev.vox.lss.paper;

import dev.vox.lss.common.DiagnosticsFormatter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;

/**
 * Bukkit command handler for /lsslod stats and /lsslod diag.
 */
public class PaperCommands implements CommandExecutor, TabCompleter {
    private final LSSPaperPlugin plugin;

    public PaperCommands(LSSPaperPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /lsslod <stats|diag>");
            return true;
        }

        var service = this.plugin.getRequestService();
        if (service == null) {
            sender.sendMessage("LSS LOD request processing is not active");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "stats" -> showStats(sender, service);
            case "diag" -> showDiagnostics(sender, service);
            default -> sender.sendMessage("Usage: /lsslod <stats|diag>");
        }

        return true;
    }

    private void showStats(CommandSender sender, PaperRequestProcessingService service) {
        var players = service.getPlayers();
        if (players.isEmpty()) {
            sender.sendMessage("No players connected with LSS");
            return;
        }

        sender.sendMessage("=== LSS LOD Request Stats ===");
        for (var state : players.values()) {
            sender.sendMessage(DiagnosticsFormatter.formatStatsLine(state));
        }
    }

    private void showDiagnostics(CommandSender sender, PaperRequestProcessingService service) {
        var config = this.plugin.getLssConfig();
        var genService = service.getGenerationService();
        var data = DiagnosticsFormatter.collectDiagData(
                config.enabled, config.lodDistanceChunks,
                config.bytesPerSecondLimitPerPlayer, config.bytesPerSecondLimitGlobal,
                config.sendQueueLimitPerPlayer,
                service.getUptimeSeconds(), service.getTickDiagnostics(), service.getWindowBandwidthRate(),
                service.getOffThreadProcessor().getDiagnostics(), service.getDiskReader(),
                service.getBandwidthLimiter(),
                genService != null ? genService.getDiagnostics() : null,
                service.getPlayers().values()
        );

        for (var line : DiagnosticsFormatter.formatDiagnostics(data)) {
            sender.sendMessage(line);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("stats", "diag").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return Collections.emptyList();
    }
}
