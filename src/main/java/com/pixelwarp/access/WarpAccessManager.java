package com.pixelwarp.access;

import com.pixelwarp.database.MySQL;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.logging.Logger;

/**
 * Manages warp access sharing — which players have access to which private warps.
 * In-memory cache backed by async MySQL operations.
 */
public class WarpAccessManager {

    public interface FileAccessPersistence {
        Map<String, Set<UUID>> loadAccessByWarpName();
        CompletableFuture<Void> saveAccessByWarpName(Map<String, Set<UUID>> accessByWarpName);
    }

    private final MySQL mysql;
    private final Logger logger;
    private final boolean persistent;
    private final Executor executor;

    /** warp id → set of player UUIDs with access */
    private final ConcurrentHashMap<Integer, Set<UUID>> accessMap = new ConcurrentHashMap<>();
    private final AtomicInteger pendingOperations = new AtomicInteger(0);
    private FileAccessPersistence filePersistence;
    private IntFunction<String> warpNameResolver = id -> null;
    private Function<String, Integer> warpIdResolver = name -> null;

    public WarpAccessManager(MySQL mysql, Logger logger) {
        this.mysql = mysql;
        this.logger = logger;
        this.persistent = mysql != null;
        this.executor = persistent ? mysql.getExecutor() : Runnable::run;
    }

    public WarpAccessManager(Logger logger) {
        this.mysql = null;
        this.logger = logger;
        this.persistent = false;
        this.executor = Runnable::run;
    }

    public void configureFilePersistence(FileAccessPersistence filePersistence,
                                         IntFunction<String> warpNameResolver,
                                         Function<String, Integer> warpIdResolver) {
        this.filePersistence = filePersistence;
        this.warpNameResolver = warpNameResolver != null ? warpNameResolver : (id -> null);
        this.warpIdResolver = warpIdResolver != null ? warpIdResolver : (name -> null);
    }

    /**
     * Load all access entries from database.
     */
    public CompletableFuture<Void> loadAll() {
        if (!persistent) {
            accessMap.clear();
            if (filePersistence != null) {
                Map<String, Set<UUID>> byName = filePersistence.loadAccessByWarpName();
                int count = 0;
                for (Map.Entry<String, Set<UUID>> entry : byName.entrySet()) {
                    Integer warpId = warpIdResolver.apply(entry.getKey());
                    if (warpId == null || warpId <= 0) {
                        continue;
                    }
                    Set<UUID> target = accessMap.computeIfAbsent(warpId, k -> ConcurrentHashMap.newKeySet());
                    target.addAll(entry.getValue());
                    count += entry.getValue().size();
                }
                logger.info("Loaded " + count + " warp access entries from file storage.");
            }
            return CompletableFuture.completedFuture(null);
        }

        return track(CompletableFuture.runAsync(() -> {
            accessMap.clear();
            String sql = "SELECT warp_id, player_uuid FROM warp_access WHERE warp_id IS NOT NULL";
            try (Connection conn = mysql.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    int warpId = rs.getInt("warp_id");
                    UUID playerUuid = UUID.fromString(rs.getString("player_uuid"));
                    accessMap.computeIfAbsent(warpId, k -> ConcurrentHashMap.newKeySet()).add(playerUuid);
                    count++;
                }
                logger.info("Loaded " + count + " warp access entries.");
            } catch (SQLException e) {
                logger.severe("Failed to load warp access: " + e.getMessage());
            }
        }, executor));
    }

    /**
     * Grant access to a warp for a player.
     * @param warpId   the warp's database ID (primary lookup key)
     * @param warpName the warp's name (kept in DB for backward compatibility)
     */
    public void grantAccess(int warpId, String warpName, UUID playerUuid) {
        accessMap.computeIfAbsent(warpId, k -> ConcurrentHashMap.newKeySet()).add(playerUuid);

        if (!persistent) {
            persistFileSnapshot();
            return;
        }

        track(CompletableFuture.runAsync(() -> {
            String sql = "INSERT IGNORE INTO warp_access (warp_name, player_uuid, warp_id) VALUES (?, ?, ?)";
            try (Connection conn = mysql.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, warpName);
                ps.setString(2, playerUuid.toString());
                ps.setInt(3, warpId);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.severe("Failed to grant warp access: " + e.getMessage());
            }
        }, executor));
    }

    /**
     * Revoke access to a warp for a player.
     */
    public void revokeAccess(int warpId, UUID playerUuid) {
        Set<UUID> set = accessMap.get(warpId);
        if (set != null) {
            set.remove(playerUuid);
            if (set.isEmpty()) accessMap.remove(warpId);
        }

        if (!persistent) {
            persistFileSnapshot();
            return;
        }

        track(CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM warp_access WHERE warp_id = ? AND player_uuid = ?";
            try (Connection conn = mysql.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, warpId);
                ps.setString(2, playerUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.severe("Failed to revoke warp access: " + e.getMessage());
            }
        }, executor));
    }

    /**
     * Check if a player has been granted access to a warp.
     */
    public boolean hasAccess(int warpId, UUID playerUuid) {
        Set<UUID> set = accessMap.get(warpId);
        return set != null && set.contains(playerUuid);
    }

    /**
     * Get all UUIDs that have access to a warp.
     */
    public Set<UUID> getAccessList(int warpId) {
        Set<UUID> set = accessMap.get(warpId);
        return set != null ? Collections.unmodifiableSet(set) : Collections.emptySet();
    }

    /**
     * Remove all access entries for a warp from the in-memory cache.
     * Database cleanup is handled by WarpStorage in a transaction.
     */
    public void removeAllAccess(int warpId) {
        accessMap.remove(warpId);
        if (!persistent) {
            persistFileSnapshot();
        }
    }

    public int getAccessEntryCount() {
        int count = 0;
        for (Set<UUID> set : accessMap.values()) {
            count += set.size();
        }
        return count;
    }

    public CompletableFuture<Void> flushPendingOperations(long timeoutMillis) {
        return CompletableFuture.runAsync(() -> {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
            while (pendingOperations.get() > 0 && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (pendingOperations.get() > 0) {
                logger.warning("Timed out waiting for pending access operations: " + pendingOperations.get());
            }
        });
    }

    private void persistFileSnapshot() {
        if (filePersistence == null) {
            return;
        }

        Map<String, Set<UUID>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<UUID>> entry : accessMap.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }

            String warpName = warpNameResolver.apply(entry.getKey());
            if (warpName == null || warpName.isBlank()) {
                continue;
            }

            snapshot.put(warpName, new HashSet<>(entry.getValue()));
        }

        track(filePersistence.saveAccessByWarpName(snapshot));
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pendingOperations.incrementAndGet();
        return future.whenComplete((r, ex) -> pendingOperations.decrementAndGet());
    }
}
