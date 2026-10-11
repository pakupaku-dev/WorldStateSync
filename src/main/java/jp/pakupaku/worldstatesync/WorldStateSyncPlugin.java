package jp.pakupaku.worldstatesync;

import jp.pakupaku.worldstatesync.command.WorldStateSyncCommand;
import jp.pakupaku.worldstatesync.config.PluginSettings;
import jp.pakupaku.worldstatesync.config.ScopeResolver;
import jp.pakupaku.worldstatesync.database.SyncDatabase;
import jp.pakupaku.worldstatesync.listener.PlayerSyncListener;
import jp.pakupaku.worldstatesync.service.SyncService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Level;

public final class WorldStateSyncPlugin extends JavaPlugin {
    private SyncDatabase database;
    private SyncService syncService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        final PluginSettings settings;
        try {
            settings = PluginSettings.load(getConfig());
        } catch (IllegalArgumentException exception) {
            getLogger().log(Level.SEVERE, "Invalid config.yml: " + exception.getMessage(), exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        String ownerId = settings.serverId() + ":" + UUID.randomUUID();
        database = new SyncDatabase(settings, ownerId);
        try {
            database.open();
        } catch (SQLException | RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Could not connect to MySQL; disabling to protect player data", exception);
            database.close();
            database = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ScopeResolver scopeResolver = new ScopeResolver(settings.worldMappings());
        syncService = new SyncService(this, settings, scopeResolver, database);
        getServer().getPluginManager().registerEvents(
                new PlayerSyncListener(syncService, scopeResolver),
                this
        );

        PluginCommand command = getCommand("worldstatesync");
        if (command == null) {
            getLogger().severe("Command worldstatesync is missing from plugin.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        WorldStateSyncCommand commandHandler = new WorldStateSyncCommand(settings, syncService);
        command.setExecutor(commandHandler);
        command.setTabCompleter(commandHandler);
        syncService.start();
        getServer().getOnlinePlayers().forEach(syncService::handleJoin);

        getLogger().info("Enabled for server-id=" + settings.serverId()
                + " with " + settings.worldMappings().size() + " world mapping(s)");
    }

    @Override
    public void onDisable() {
        if (syncService != null) {
            syncService.shutdown();
            syncService = null;
        }
        if (database != null) {
            database.close();
            database = null;
        }
    }
}
