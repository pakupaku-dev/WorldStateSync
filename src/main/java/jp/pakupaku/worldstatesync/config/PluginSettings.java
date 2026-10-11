package jp.pakupaku.worldstatesync.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record PluginSettings(
        String serverId,
        Mysql mysql,
        Sync sync,
        Map<String, String> worldMappings,
        Map<String, Component> messages
) {
    private static final Pattern TABLE_PREFIX = Pattern.compile("[A-Za-z0-9_]{1,48}");
    private static final LegacyComponentSerializer MESSAGE_SERIALIZER = LegacyComponentSerializer.legacyAmpersand();

    public static PluginSettings load(FileConfiguration config) {
        String configuredServerId = config.getString("server-id", "").trim();
        String serverId = configuredServerId.isEmpty()
                ? Bukkit.getServer().getName() + ":" + Bukkit.getServer().getPort()
                : configuredServerId;
        requireLength("server-id", serverId, 1, 96);

        Mysql mysql = new Mysql(
                required(config, "mysql.host"),
                config.getInt("mysql.port", 3306),
                required(config, "mysql.database"),
                required(config, "mysql.username"),
                Objects.requireNonNullElse(config.getString("mysql.password"), ""),
                config.getBoolean("mysql.use-ssl", false),
                config.getBoolean("mysql.allow-public-key-retrieval", true),
                config.getInt("mysql.pool-size", 10),
                config.getLong("mysql.connection-timeout-millis", 10_000L),
                required(config, "mysql.table-prefix")
        );
        validate(mysql);

        Sync sync = new Sync(
                config.getInt("sync.autosave-seconds", 30),
                config.getBoolean("sync.import-local-on-first-login", true),
                config.getInt("sync.acquire-timeout-seconds", 45),
                config.getInt("sync.lease-seconds", 20),
                config.getInt("sync.heartbeat-seconds", 5),
                config.getInt("sync.shutdown-timeout-seconds", 15)
        );
        validate(sync);

        Map<String, String> mappings = new LinkedHashMap<>();
        ConfigurationSection mappingSection = config.getConfigurationSection("world-mappings");
        if (mappingSection != null) {
            for (String worldName : mappingSection.getKeys(false)) {
                String scope = mappingSection.getString(worldName, "").trim();
                requireLength("world-mappings." + worldName, scope, 1, 128);
                mappings.put(worldName, scope);
            }
        }

        Map<String, Component> messages = new LinkedHashMap<>();
        ConfigurationSection messageSection = config.getConfigurationSection("messages");
        if (messageSection != null) {
            for (String key : messageSection.getKeys(false)) {
                messages.put(key, color(messageSection.getString(key, "")));
            }
        }

        return new PluginSettings(
                serverId,
                mysql,
                sync,
                Collections.unmodifiableMap(mappings),
                Collections.unmodifiableMap(messages)
        );
    }

    public Component message(String key) {
        return messages.getOrDefault(key, Component.text("WorldStateSync message missing: " + key));
    }

    private static void validate(Mysql mysql) {
        if (mysql.port() < 1 || mysql.port() > 65_535) {
            throw new IllegalArgumentException("mysql.port must be between 1 and 65535");
        }
        if (mysql.poolSize() < 2 || mysql.poolSize() > 64) {
            throw new IllegalArgumentException("mysql.pool-size must be between 2 and 64");
        }
        if (mysql.connectionTimeoutMillis() < 1_000L || mysql.connectionTimeoutMillis() > 60_000L) {
            throw new IllegalArgumentException("mysql.connection-timeout-millis must be between 1000 and 60000");
        }
        if (!TABLE_PREFIX.matcher(mysql.tablePrefix()).matches()) {
            throw new IllegalArgumentException("mysql.table-prefix may contain only A-Z, a-z, 0-9 and _ (max 48)");
        }
    }

    private static void validate(Sync sync) {
        if (sync.autosaveSeconds() < 5) {
            throw new IllegalArgumentException("sync.autosave-seconds must be at least 5");
        }
        if (sync.acquireTimeoutSeconds() < 5 || sync.acquireTimeoutSeconds() > 300) {
            throw new IllegalArgumentException("sync.acquire-timeout-seconds must be between 5 and 300");
        }
        if (sync.leaseSeconds() < 10 || sync.leaseSeconds() > 120) {
            throw new IllegalArgumentException("sync.lease-seconds must be between 10 and 120");
        }
        if (sync.heartbeatSeconds() < 2 || sync.heartbeatSeconds() * 3 >= sync.leaseSeconds()) {
            throw new IllegalArgumentException("sync.heartbeat-seconds must be at least 2 and less than one third of lease-seconds");
        }
        if (sync.shutdownTimeoutSeconds() < 5 || sync.shutdownTimeoutSeconds() > 60) {
            throw new IllegalArgumentException("sync.shutdown-timeout-seconds must be between 5 and 60");
        }
    }

    private static String required(FileConfiguration config, String path) {
        String value = Objects.requireNonNullElse(config.getString(path), "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(path + " must not be empty");
        }
        return value;
    }

    private static void requireLength(String path, String value, int min, int max) {
        if (value.length() < min || value.length() > max) {
            throw new IllegalArgumentException(path + " length must be between " + min + " and " + max);
        }
    }

    private static Component color(String value) {
        return MESSAGE_SERIALIZER.deserialize(Objects.requireNonNullElse(value, ""));
    }

    public record Mysql(
            String host,
            int port,
            String database,
            String username,
            String password,
            boolean useSsl,
            boolean allowPublicKeyRetrieval,
            int poolSize,
            long connectionTimeoutMillis,
            String tablePrefix
    ) {
    }

    public record Sync(
            int autosaveSeconds,
            boolean importLocalOnFirstLogin,
            int acquireTimeoutSeconds,
            int leaseSeconds,
            int heartbeatSeconds,
            int shutdownTimeoutSeconds
    ) {
    }
}
