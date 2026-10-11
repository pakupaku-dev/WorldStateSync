package jp.pakupaku.worldstatesync.command;

import jp.pakupaku.worldstatesync.config.PluginSettings;
import jp.pakupaku.worldstatesync.service.SyncService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class WorldStateSyncCommand implements CommandExecutor, TabCompleter {
    private final PluginSettings settings;
    private final SyncService syncService;

    public WorldStateSyncCommand(PluginSettings settings, SyncService syncService) {
        this.settings = settings;
        this.syncService = syncService;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§6WorldStateSync §f1.0.0");
            sender.sendMessage("§7Server ID: §f" + settings.serverId());
            sender.sendMessage("§7Active: §f" + syncService.activeSessionCount()
                    + " §7Loading/Switching: §f" + syncService.transitioningSessionCount());
            if (sender instanceof Player player) {
                sender.sendMessage("§7Your scope: §f" + syncService.scopeOf(player));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("saveall")) {
            syncService.saveAllNow();
            sender.sendMessage("§aオンラインプレイヤーの保存をキューへ追加しました。対象: "
                    + Bukkit.getOnlinePlayers().size());
            return true;
        }

        return false;
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
        return List.of("status", "saveall").stream()
                .filter(value -> value.startsWith(prefix))
                .toList();
    }
}
