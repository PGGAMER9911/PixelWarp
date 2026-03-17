package com.pixelwarp.access;

import com.pixelwarp.database.MySQL;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages warp access sharing — which players have access to which private warps.
 * In-memory cache backed by async MySQL operations.
 */
public class WarpAccessManager {

    private final MySQL mysql;
    private final Logger logger;

    /** warp id → set of player UUIDs with access */
    private final ConcurrentHashMap<Integer, Set<UUID>> accessMap = new ConcurrentHashMap<>();

    public WarpAccessManager(MySQL mysql, Logger logger) {
        this.mysql = mysql;
        this.logger = logger;
    }

    /**
     * Load all access entries from database.
     */
    public CompletableFuture<Void> loadAll() {
        return CompletableFuture.runAsync(() -> {
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
        }, mysql.getExecutor());
    }

    /**
     * Grant access to a warp for a player.
     * @param warpId   the warp's database ID (primary lookup key)
     * @param warpName the warp's name (kept in DB for backward compatibility)
     */
    public void grantAccess(int warpId, String warpName, UUID playerUuid) {
        accessMap.computeIfAbsent(warpId, k -> ConcurrentHashMap.newKeySet()).add(playerUuid);

        CompletableFuture.runAsync(() -> {
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
        }, mysql.getExecutor());
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

        CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM warp_access WHERE warp_id = ? AND player_uuid = ?";
            try (Connection conn = mysql.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, warpId);
                ps.setString(2, playerUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.severe("Failed to revoke warp access: " + e.getMessage());
            }
        }, mysql.getExecutor());
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
    }
}
