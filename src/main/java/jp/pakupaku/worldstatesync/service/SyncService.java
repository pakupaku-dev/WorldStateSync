package jp.pakupaku.worldstatesync.service;

import jp.pakupaku.worldstatesync.WorldStateSyncPlugin;
import jp.pakupaku.worldstatesync.config.PluginSettings;
import jp.pakupaku.worldstatesync.config.ScopeResolver;
import jp.pakupaku.worldstatesync.database.SyncDatabase;
import jp.pakupaku.worldstatesync.model.PlayerState;
import jp.pakupaku.worldstatesync.model.StateCodec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

public final class SyncService {
    private static final long ACQUIRE_RETRY_TICKS = 5L;

    private final WorldStateSyncPlugin plugin;
    private final PluginSettings settings;
    private final ScopeResolver scopeResolver;
    private final SyncDatabase database;
    private final StateCodec codec = new StateCodec();
    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService databaseExecutor;
    private BukkitTask autosaveTask;
    private BukkitTask heartbeatTask;
    private volatile boolean shuttingDown;

    public SyncService(
            WorldStateSyncPlugin plugin,
            PluginSettings settings,
            ScopeResolver scopeResolver,
            SyncDatabase database
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.scopeResolver = scopeResolver;
        this.database = database;
        this.databaseExecutor = Executors.newFixedThreadPool(
                Math.min(4, settings.mysql().poolSize()),
                new DatabaseThreadFactory()
        );
    }

    public void start() {
        long autosaveTicks = settings.sync().autosaveSeconds() * 20L;
        long heartbeatTicks = settings.sync().heartbeatSeconds() * 20L;
        autosaveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveAllOnline, autosaveTicks, autosaveTicks);
        heartbeatTask = Bukkit.getScheduler().runTaskTimer(plugin, this::heartbeatAll, heartbeatTicks, heartbeatTicks);
        CompletableFuture.supplyAsync(() -> {
            try { return database.missingPortalStates(); }
            catch (java.sql.SQLException e) { throw new CompletionException(e); }
        }, databaseExecutor).thenAccept(rows -> runMain(() -> {
            for (var row : rows) {
                try {
                    String json = jp.pakupaku.worldstatesync.model.PortalSnapshot.fromState(codec.decode(row.payload()));
                    CompletableFuture.runAsync(() -> {
                        try { database.insertPortalBackfill(row, json); }
                        catch (java.sql.SQLException e) { logDatabaseFailure("portal backfill", e); }
                    }, databaseExecutor);
                } catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Could not project saved inventory for portal", e); }
            }
        })).exceptionally(error -> { logDatabaseFailure("portal backfill", error); return null; });
    }

    public void handleJoin(Player player) {
        assertMainThread();
        byte[] initialPayload = null;
        if (settings.sync().importLocalOnFirstLogin()) {
            try {
                initialPayload = codec.encode(PlayerState.capture(player));
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to capture local data for " + player.getName(), exception);
                player.kick(settings.message("database-error"));
                return;
            }
        }
        PlayerState.clear(player);
        player.sendMessage(settings.message("loading"));

        String scope = scopeResolver.resolve(player.getWorld());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.sync().acquireTimeoutSeconds());
        PlayerSession session = new PlayerSession(
                player.getUniqueId(),
                player.getName(),
                scope,
                initialPayload,
                deadline
        );
        PlayerSession previous = sessions.put(player.getUniqueId(), session);
        if (previous != null) {
            previous.mode = PlayerSession.Mode.CLOSING;
            enqueue(previous, () -> {
                database.release(previous.playerId);
                return null;
            });
        }
        attemptAcquire(session);
    }

    public void handleQuit(Player player) {
        assertMainThread();
        publishPortal(player);
        PlayerSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }

        byte[] payload = null;
        String scope = session.activeScope;
        if (session.mode == PlayerSession.Mode.ACTIVE && scope != null) {
            try {
                payload = codec.encode(PlayerState.capture(player));
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to serialize " + player.getName() + " during quit", exception);
            }
        }

        session.mode = PlayerSession.Mode.CLOSING;
        byte[] finalPayload = payload;
        String finalScope = scope;
        enqueue(session, () -> {
            if (finalPayload != null && finalScope != null) {
                database.saveAndRelease(session.playerId, session.playerName, finalScope, finalPayload);
            } else {
                database.release(session.playerId);
            }
            return null;
        }).exceptionally(exception -> {
            logDatabaseFailure("quit save for " + session.playerName, exception);
            return null;
        });
    }

    public void handleWorldChange(Player player, String oldScope, String newScope) {
        assertMainThread();
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null || session.mode == PlayerSession.Mode.CLOSING) {
            return;
        }
        session.desiredScope = newScope;

        if (session.mode != PlayerSession.Mode.ACTIVE) {
            return;
        }
        if (oldScope.equals(newScope)) {
            session.activeScope = newScope;
            return;
        }

        final byte[] oldPayload;
        publishPortal(player);
        try {
            oldPayload = codec.encode(PlayerState.capture(player));
        } catch (IOException exception) {
            failAndKick(session, "database-error", exception);
            return;
        }

        session.mode = PlayerSession.Mode.SWITCHING;
        PlayerState.clear(player);
        player.sendMessage(settings.message("switching"));
        enqueue(session, () -> database.saveAndLoad(
                session.playerId,
                session.playerName,
                oldScope,
                oldPayload,
                newScope
        )).whenComplete((result, exception) -> runMain(() -> {
            if (!isCurrent(session)) {
                return;
            }
            if (exception != null) {
                failAndKick(session, "database-error", exception);
                return;
            }
            if (!result.owned()) {
                failAndKick(session, "session-lost", null);
                return;
            }
            session.lastLeaseRenewalNanos = System.nanoTime();
            finishLoadOrRedirect(session, newScope, result.payload());
        }));
    }

    public boolean isBlocked(Player player) {
        PlayerSession session = sessions.get(player.getUniqueId());
        return session != null && session.mode != PlayerSession.Mode.ACTIVE;
    }

    public int activeSessionCount() {
        return (int) sessions.values().stream()
                .filter(session -> session.mode == PlayerSession.Mode.ACTIVE)
                .count();
    }

    public int transitioningSessionCount() {
        return sessions.size() - activeSessionCount();
    }

    public String scopeOf(Player player) {
        PlayerSession session = sessions.get(player.getUniqueId());
        return session == null ? "-" : String.valueOf(session.activeScope == null ? session.desiredScope : session.activeScope);
    }

    public void saveAllNow() {
        assertMainThread();
        saveAllOnline();
    }

    public void savePlayerAfterDelay(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                saveOne(player);
            }
        }, 1L);
    }

    public void shutdown() {
        shuttingDown = true;
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }

        List<CompletableFuture<?>> pending = new ArrayList<>();
        for (PlayerSession session : List.copyOf(sessions.values())) {
            Player portalPlayer = Bukkit.getPlayer(session.playerId);
            if (portalPlayer != null && portalPlayer.isOnline()) publishPortal(portalPlayer);
            if (!sessions.remove(session.playerId, session)) {
                continue;
            }
            Player player = Bukkit.getPlayer(session.playerId);

            byte[] payload = null;
            String scope = session.activeScope;
            if (player != null && player.isOnline()
                    && session.mode == PlayerSession.Mode.ACTIVE
                    && scope != null) {
                try {
                    payload = codec.encode(PlayerState.capture(player));
                } catch (IOException exception) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to serialize " + player.getName() + " during shutdown", exception);
                }
            }
            session.mode = PlayerSession.Mode.CLOSING;
            byte[] finalPayload = payload;
            String finalScope = scope;
            pending.add(enqueue(session, () -> {
                if (finalPayload != null && finalScope != null) {
                    database.saveAndRelease(session.playerId, session.playerName, finalScope, finalPayload);
                } else {
                    database.release(session.playerId);
                }
                return null;
            }));
        }

        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                    .get(settings.sync().shutdownTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            plugin.getLogger().severe("Timed out while saving player data during shutdown");
        } catch (Exception exception) {
            logDatabaseFailure("shutdown save", exception);
        }

        databaseExecutor.shutdown();
        try {
            if (!databaseExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                databaseExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            databaseExecutor.shutdownNow();
        }
        sessions.clear();
    }

    private void attemptAcquire(PlayerSession session) {
        if (!isCurrent(session) || session.mode != PlayerSession.Mode.ACQUIRING) {
            return;
        }
        String requestedScope = session.desiredScope;
        enqueue(session, () -> database.tryAcquireAndLoad(
                session.playerId,
                session.playerName,
                requestedScope
        )).whenComplete((result, exception) -> runMain(() -> {
            if (!isCurrent(session) || session.mode != PlayerSession.Mode.ACQUIRING) {
                if (result != null && result.acquired()) {
                    enqueue(session, () -> {
                        database.release(session.playerId);
                        return null;
                    });
                }
                return;
            }
            if (exception != null) {
                failAndKick(session, "database-error", exception);
                return;
            }
            if (result.acquired()) {
                session.lastLeaseRenewalNanos = System.nanoTime();
                finishLoadOrRedirect(session, requestedScope, result.payload());
                return;
            }
            if (System.nanoTime() >= session.acquireDeadlineNanos) {
                failAndKick(session, "session-timeout", null);
                return;
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> attemptAcquire(session), ACQUIRE_RETRY_TICKS);
        }));
    }

    private void finishLoadOrRedirect(PlayerSession session, String loadedScope, java.util.Optional<byte[]> payload) {
        Player player = Bukkit.getPlayer(session.playerId);
        if (player == null || !player.isOnline()) {
            closeWithoutSave(session);
            return;
        }

        String actualScope = scopeResolver.resolve(player.getWorld());
        session.desiredScope = actualScope;
        if (!actualScope.equals(loadedScope)) {
            loadRedirectedScope(session, actualScope);
            return;
        }

        if (payload.isEmpty()
                && session.initialPayload != null
                && session.initialScope.equals(loadedScope)
                && !session.initialImportAttempted) {
            session.initialImportAttempted = true;
            enqueue(session, () -> database.saveOwned(
                    session.playerId,
                    session.playerName,
                    loadedScope,
                    session.initialPayload
            )).whenComplete((owned, exception) -> runMain(() -> {
                if (!isCurrent(session)) {
                    return;
                }
                if (exception != null) {
                    failAndKick(session, "database-error", exception);
                    return;
                }
                if (!owned) {
                    failAndKick(session, "session-lost", null);
                    return;
                }
                session.lastLeaseRenewalNanos = System.nanoTime();
                finishLoadOrRedirect(session, loadedScope, java.util.Optional.of(session.initialPayload));
            }));
            return;
        }

        try {
            PlayerState state = payload.isPresent() ? codec.decode(payload.get()) : PlayerState.empty(player);
            state.apply(player);
            session.activeScope = loadedScope;
            session.mode = PlayerSession.Mode.ACTIVE;
            player.sendMessage(settings.message("loaded"));
        } catch (Exception exception) {
            failAndKick(session, "database-error", exception);
        }
    }

    private void loadRedirectedScope(PlayerSession session, String scope) {
        session.mode = PlayerSession.Mode.SWITCHING;
        enqueue(session, () -> database.loadOwned(session.playerId, scope))
                .whenComplete((result, exception) -> runMain(() -> {
                    if (!isCurrent(session)) {
                        return;
                    }
                    if (exception != null) {
                        failAndKick(session, "database-error", exception);
                        return;
                    }
                    if (!result.owned()) {
                        failAndKick(session, "session-lost", null);
                        return;
                    }
                    session.lastLeaseRenewalNanos = System.nanoTime();
                    finishLoadOrRedirect(session, scope, result.payload());
                }));
    }

    private void saveAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            saveOne(player);
        }
    }

    public void recordPlacement(Player player, org.bukkit.Material material) {
        if (!isBlocked(player)) jp.pakupaku.worldstatesync.model.PortalSnapshot.recordPlacement(player, material, settings.serverId());
    }

    private void publishPortal(Player player) {
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null || session.mode != PlayerSession.Mode.ACTIVE || session.activeScope == null) return;
        try {
            String snapshot = jp.pakupaku.worldstatesync.model.PortalSnapshot.capture(player, settings.serverId());
            String scope = session.activeScope;
            enqueue(session, () -> database.savePortal(session.playerId, scope, snapshot))
                    .exceptionally(error -> { logDatabaseFailure("portal snapshot for " + session.playerName, error); return false; });
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not capture portal snapshot", error);
        }
    }

    private void saveOne(Player player) {
        publishPortal(player);
        PlayerSession session = sessions.get(player.getUniqueId());
        if (session == null || session.mode != PlayerSession.Mode.ACTIVE || session.activeScope == null) {
            return;
        }

        final byte[] payload;
        try {
            payload = codec.encode(PlayerState.capture(player));
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to serialize " + player.getName(), exception);
            return;
        }

        String scope = session.activeScope;
        enqueue(session, () -> database.saveOwned(
                session.playerId,
                session.playerName,
                scope,
                payload
        )).whenComplete((owned, exception) -> runMain(() -> {
            if (!isCurrent(session)) {
                return;
            }
            if (exception != null) {
                logDatabaseFailure("autosave for " + session.playerName, exception);
                return;
            }
            if (!owned) {
                failAndKick(session, "session-lost", null);
                return;
            }
            session.lastLeaseRenewalNanos = System.nanoTime();
        }));
    }

    private void heartbeatAll() {
        for (PlayerSession session : List.copyOf(sessions.values())) {
            if (session.mode != PlayerSession.Mode.ACTIVE && session.mode != PlayerSession.Mode.SWITCHING) {
                continue;
            }
            enqueue(session, () -> database.heartbeat(session.playerId))
                    .whenComplete((renewed, exception) -> runMain(() -> {
                        if (!isCurrent(session)) {
                            return;
                        }
                        if (exception == null && renewed) {
                            session.lastLeaseRenewalNanos = System.nanoTime();
                            return;
                        }
                        if (exception == null) {
                            failAndKick(session, "session-lost", null);
                            return;
                        }

                        logDatabaseFailure("heartbeat for " + session.playerName, exception);
                        long safeWindow = TimeUnit.SECONDS.toNanos(
                                settings.sync().leaseSeconds() - settings.sync().heartbeatSeconds()
                        );
                        if (System.nanoTime() - session.lastLeaseRenewalNanos >= safeWindow) {
                            failAndKick(session, "session-lost", exception);
                        }
                    }));
        }
    }

    private void failAndKick(PlayerSession session, String messageKey, Throwable exception) {
        if (!isCurrent(session)) {
            return;
        }
        sessions.remove(session.playerId, session);
        session.mode = PlayerSession.Mode.CLOSING;
        if (exception != null) {
            logDatabaseFailure("session for " + session.playerName, exception);
        }

        Player player = Bukkit.getPlayer(session.playerId);
        if (player != null && player.isOnline()) {
            PlayerState.clear(player);
            player.kick(settings.message(messageKey));
        }
        enqueue(session, () -> {
            database.release(session.playerId);
            return null;
        });
    }

    private void closeWithoutSave(PlayerSession session) {
        sessions.remove(session.playerId, session);
        session.mode = PlayerSession.Mode.CLOSING;
        enqueue(session, () -> {
            database.release(session.playerId);
            return null;
        });
    }

    private boolean isCurrent(PlayerSession session) {
        return !shuttingDown && sessions.get(session.playerId) == session;
    }

    private <T> CompletableFuture<T> enqueue(PlayerSession session, CheckedSupplier<T> operation) {
        synchronized (session) {
            CompletableFuture<T> next = session.databaseTail
                    .handle((ignored, previousFailure) -> null)
                    .thenApplyAsync(ignored -> {
                        try {
                            return operation.get();
                        } catch (Exception exception) {
                            throw new CompletionException(exception);
                        }
                    }, databaseExecutor);
            session.databaseTail = next.handle((ignored, failure) -> null);
            return next;
        }
    }

    private void runMain(Runnable action) {
        if (shuttingDown || !plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private void logDatabaseFailure(String operation, Throwable throwable) {
        Throwable cause = throwable;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        plugin.getLogger().log(Level.SEVERE, "Database operation failed: " + operation, cause);
    }

    private static void assertMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("This operation must run on the server thread");
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private static final class DatabaseThreadFactory implements ThreadFactory {
        private int counter;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "WorldStateSync-DB-" + (++counter));
            thread.setDaemon(true);
            return thread;
        }
    }
}
