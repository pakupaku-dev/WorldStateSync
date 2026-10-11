package jp.kamei.worldstatesync.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jp.kamei.worldstatesync.config.PluginSettings;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;

public final class SyncDatabase implements AutoCloseable {
    private final PluginSettings settings;
    private final String ownerId;
    private final String stateTable;
    private final String sessionTable;
    private HikariDataSource dataSource;

    public SyncDatabase(PluginSettings settings, String ownerId) {
        this.settings = settings;
        this.ownerId = ownerId;
        this.stateTable = settings.mysql().tablePrefix() + "player_state";
        this.sessionTable = settings.mysql().tablePrefix() + "player_session";
    }

    public void open() throws SQLException {
        PluginSettings.Mysql mysql = settings.mysql();
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("WorldStateSync-MySQL");
        hikari.setJdbcUrl(buildJdbcUrl(mysql));
        hikari.setUsername(mysql.username());
        hikari.setPassword(mysql.password());
        hikari.setMaximumPoolSize(mysql.poolSize());
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(mysql.connectionTimeoutMillis());
        hikari.setValidationTimeout(Math.min(5_000L, mysql.connectionTimeoutMillis()));
        hikari.setMaxLifetime(1_700_000L);
        hikari.setInitializationFailTimeout(mysql.connectionTimeoutMillis());
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "250");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikari.addDataSourceProperty("useServerPrepStmts", "true");
        hikari.addDataSourceProperty("rewriteBatchedStatements", "true");

        dataSource = new HikariDataSource(hikari);
        createSchema();
    }

    public AcquireResult tryAcquireAndLoad(UUID playerId, String playerName, String scope) throws SQLException {
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Connection connection = connection()) {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
                try {
                    LockRow lock = selectLock(connection, playerId);
                    boolean acquired;
                    if (lock == null) {
                        insertLock(connection, playerId, playerName);
                        acquired = true;
                    } else if (ownerId.equals(lock.ownerId()) || lock.expired()) {
                        replaceLock(connection, playerId, playerName);
                        acquired = true;
                    } else {
                        acquired = false;
                    }

                    if (!acquired) {
                        connection.rollback();
                        return new AcquireResult(false, Optional.empty(), lock.serverId());
                    }

                    Optional<byte[]> payload = loadState(connection, playerId, scope);
                    connection.commit();
                    return new AcquireResult(true, payload, settings.serverId());
                } catch (SQLIntegrityConstraintViolationException exception) {
                    rollbackQuietly(connection);
                    if (attempt == 1) {
                        throw exception;
                    }
                } catch (SQLException exception) {
                    rollbackQuietly(connection);
                    throw exception;
                }
            }
        }
        throw new SQLException("Could not acquire player session after retry");
    }

    public LoadResult loadOwned(UUID playerId, String scope) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                if (!ownsLock(connection, playerId)) {
                    connection.rollback();
                    return new LoadResult(false, Optional.empty());
                }
                renewLock(connection, playerId);
                Optional<byte[]> payload = loadState(connection, playerId, scope);
                connection.commit();
                return new LoadResult(true, payload);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
    }

    public boolean saveOwned(UUID playerId, String playerName, String scope, byte[] payload) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                if (!ownsLock(connection, playerId)) {
                    connection.rollback();
                    return false;
                }
                upsertState(connection, playerId, playerName, scope, payload);
                renewLock(connection, playerId);
                connection.commit();
                return true;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
    }

    public boolean savePortal(UUID playerId, String scope, String snapshot) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                if (!ownsLock(connection, playerId)) { connection.rollback(); return false; }
                String sql = "INSERT INTO " + settings.mysql().tablePrefix() + "portal_snapshot"
                        + " (player_uuid, scope_key, server_id, snapshot) VALUES (?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE scope_key=VALUES(scope_key), snapshot=VALUES(snapshot), updated_at=CURRENT_TIMESTAMP(3)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, playerId.toString()); statement.setString(2, scope);
                    statement.setString(3, settings.serverId()); statement.setString(4, snapshot);
                    statement.executeUpdate();
                }
                connection.commit(); return true;
            } catch (SQLException exception) { rollbackQuietly(connection); throw exception; }
        }
    }

    public record PortalBackfill(String player, String scope, byte[] payload, java.sql.Timestamp updated) { }

    public java.util.List<PortalBackfill> missingPortalStates() throws SQLException {
        var rows = new java.util.ArrayList<PortalBackfill>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT s.player_uuid, s.scope_key, s.payload, s.updated_at FROM " + stateTable + " s WHERE NOT EXISTS (SELECT 1 FROM "
                        + settings.mysql().tablePrefix() + "portal_snapshot p WHERE p.player_uuid=s.player_uuid AND p.scope_key=s.scope_key)")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(new PortalBackfill(result.getString(1), result.getString(2), result.getBytes(3), result.getTimestamp(4)));
            }
        }
        return rows;
    }

    public void insertPortalBackfill(PortalBackfill row, String snapshot) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT IGNORE INTO " + settings.mysql().tablePrefix() + "portal_snapshot (player_uuid, scope_key, server_id, snapshot, updated_at) VALUES (?, ?, 'worldstatesync', ?, ?)")) {
            statement.setString(1, row.player()); statement.setString(2, row.scope());
            statement.setString(3, snapshot); statement.setTimestamp(4, row.updated()); statement.executeUpdate();
        }
    }

    public TransitionResult saveAndLoad(
            UUID playerId,
            String playerName,
            String oldScope,
            byte[] oldPayload,
            String newScope
    ) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                if (!ownsLock(connection, playerId)) {
                    connection.rollback();
                    return new TransitionResult(false, Optional.empty());
                }
                upsertState(connection, playerId, playerName, oldScope, oldPayload);
                Optional<byte[]> payload = loadState(connection, playerId, newScope);
                renewLock(connection, playerId);
                connection.commit();
                return new TransitionResult(true, payload);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
    }

    public boolean saveAndRelease(
            UUID playerId,
            String playerName,
            String scope,
            byte[] payload
    ) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                if (!ownsLock(connection, playerId)) {
                    connection.rollback();
                    return false;
                }
                upsertState(connection, playerId, playerName, scope, payload);
                deleteLock(connection, playerId);
                connection.commit();
                return true;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            }
        }
    }

    public boolean heartbeat(UUID playerId) throws SQLException {
        String sql = "UPDATE " + sessionTable + " SET lease_until = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND), "
                + "updated_at = CURRENT_TIMESTAMP(3) WHERE player_uuid = ? AND owner_id = ?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, settings.sync().leaseSeconds());
            statement.setString(2, playerId.toString());
            statement.setString(3, ownerId);
            return statement.executeUpdate() == 1;
        }
    }

    public void release(UUID playerId) throws SQLException {
        try (Connection connection = connection()) {
            deleteLock(connection, playerId);
        }
    }

    private void createSchema() throws SQLException {
        String stateSql = "CREATE TABLE IF NOT EXISTS " + stateTable + " ("
                + "player_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                + "scope_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,"
                + "player_name VARCHAR(16) CHARACTER SET utf8mb4 NOT NULL,"
                + "payload LONGBLOB NOT NULL,"
                + "revision BIGINT NOT NULL DEFAULT 1,"
                + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),"
                + "PRIMARY KEY (player_uuid, scope_key),"
                + "INDEX idx_updated_at (updated_at)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        String sessionSql = "CREATE TABLE IF NOT EXISTS " + sessionTable + " ("
                + "player_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                + "player_name VARCHAR(16) CHARACTER SET utf8mb4 NOT NULL,"
                + "owner_id VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                + "server_id VARCHAR(96) CHARACTER SET utf8mb4 NOT NULL,"
                + "lease_until DATETIME(3) NOT NULL,"
                + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),"
                + "PRIMARY KEY (player_uuid),"
                + "INDEX idx_lease_until (lease_until)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(stateSql);
            statement.executeUpdate(sessionSql);
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + settings.mysql().tablePrefix() + "portal_snapshot ("
                    + "player_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,"
                    + "scope_key VARCHAR(191) NOT NULL, server_id VARCHAR(96) NOT NULL, snapshot JSON NOT NULL,"
                    + "updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),"
                    + "PRIMARY KEY(player_uuid, scope_key, server_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    private LockRow selectLock(Connection connection, UUID playerId) throws SQLException {
        String sql = "SELECT owner_id, server_id, (lease_until <= CURRENT_TIMESTAMP(3)) AS expired "
                + "FROM " + sessionTable + " WHERE player_uuid = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new LockRow(
                        result.getString("owner_id"),
                        result.getString("server_id"),
                        result.getBoolean("expired")
                );
            }
        }
    }

    private boolean ownsLock(Connection connection, UUID playerId) throws SQLException {
        String sql = "SELECT owner_id FROM " + sessionTable + " WHERE player_uuid = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && ownerId.equals(result.getString("owner_id"));
            }
        }
    }

    private void insertLock(Connection connection, UUID playerId, String playerName) throws SQLException {
        String sql = "INSERT INTO " + sessionTable
                + " (player_uuid, player_name, owner_id, server_id, lease_until) "
                + "VALUES (?, ?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindLock(statement, playerId, playerName);
            statement.executeUpdate();
        }
    }

    private void replaceLock(Connection connection, UUID playerId, String playerName) throws SQLException {
        String sql = "UPDATE " + sessionTable + " SET player_name = ?, owner_id = ?, server_id = ?, "
                + "lease_until = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND), updated_at = CURRENT_TIMESTAMP(3) "
                + "WHERE player_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName);
            statement.setString(2, ownerId);
            statement.setString(3, settings.serverId());
            statement.setInt(4, settings.sync().leaseSeconds());
            statement.setString(5, playerId.toString());
            statement.executeUpdate();
        }
    }

    private void bindLock(PreparedStatement statement, UUID playerId, String playerName) throws SQLException {
        statement.setString(1, playerId.toString());
        statement.setString(2, playerName);
        statement.setString(3, ownerId);
        statement.setString(4, settings.serverId());
        statement.setInt(5, settings.sync().leaseSeconds());
    }

    private Optional<byte[]> loadState(Connection connection, UUID playerId, String scope) throws SQLException {
        String sql = "SELECT payload FROM " + stateTable + " WHERE player_uuid = ? AND scope_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, scope);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getBytes("payload")) : Optional.empty();
            }
        }
    }

    private void upsertState(
            Connection connection,
            UUID playerId,
            String playerName,
            String scope,
            byte[] payload
    ) throws SQLException {
        String sql = "INSERT INTO " + stateTable
                + " (player_uuid, scope_key, player_name, payload, revision) VALUES (?, ?, ?, ?, 1) "
                + "ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), payload = VALUES(payload), "
                + "revision = revision + 1, updated_at = CURRENT_TIMESTAMP(3)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, scope);
            statement.setString(3, playerName);
            statement.setBytes(4, payload);
            statement.executeUpdate();
        }
    }

    private void renewLock(Connection connection, UUID playerId) throws SQLException {
        String sql = "UPDATE " + sessionTable + " SET lease_until = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND), "
                + "updated_at = CURRENT_TIMESTAMP(3) WHERE player_uuid = ? AND owner_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, settings.sync().leaseSeconds());
            statement.setString(2, playerId.toString());
            statement.setString(3, ownerId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Player session ownership was lost while renewing " + playerId);
            }
        }
    }

    private void deleteLock(Connection connection, UUID playerId) throws SQLException {
        String sql = "DELETE FROM " + sessionTable + " WHERE player_uuid = ? AND owner_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, ownerId);
            statement.executeUpdate();
        }
    }

    private Connection connection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("Database is not open");
        }
        return dataSource.getConnection();
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static String buildJdbcUrl(PluginSettings.Mysql mysql) {
        return "jdbc:mysql://" + mysql.host() + ":" + mysql.port() + "/" + mysql.database()
                + "?useUnicode=true&characterEncoding=UTF-8"
                + "&useSSL=" + mysql.useSsl()
                + "&allowPublicKeyRetrieval=" + mysql.allowPublicKeyRetrieval()
                + "&serverTimezone=UTC"
                + "&connectTimeout=" + mysql.connectionTimeoutMillis()
                + "&socketTimeout=" + mysql.connectionTimeoutMillis();
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    public record AcquireResult(boolean acquired, Optional<byte[]> payload, String ownerServer) {
    }

    public record LoadResult(boolean owned, Optional<byte[]> payload) {
    }

    public record TransitionResult(boolean owned, Optional<byte[]> payload) {
    }

    private record LockRow(String ownerId, String serverId, boolean expired) {
    }
}
