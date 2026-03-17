package com.pixelwarp.warp;

import com.pixelwarp.database.MySQL;
import org.bukkit.Material;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class WarpStorage {

    private final MySQL mysql;
    private final Logger logger;

    public WarpStorage(MySQL mysql, Logger logger) {
        this.mysql = mysql;
        this.logger = logger;
    }

    public CompletableFuture<List<Warp>> loadAll() {
        return CompletableFuture.supplyAsync(() -> {
            List<Warp> warps = new ArrayList<>();
            String sql = "SELECT * FROM warps";
            try (Connection conn = mysql.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    warps.add(mapRow(rs));
                }
            } catch (SQLException e) {
                logger.severe("Failed to load warps: " + e.getMessage());
            }
            return warps;
        }, mysql.getExecutor());
    }

    public CompletableFuture<Void> save(Warp warp) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                    INSERT INTO warps (name, owner_uuid, world, x, y, z, yaw, pitch,
                                       is_public, icon_material, category, created_at, usage_count, last_used)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (Connection conn = mysql.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, warp.getName());
                ps.setString(2, warp.getOwnerUuid().toString());
                ps.setString(3, warp.getWorld());
                ps.setDouble(4, warp.getX());
                ps.setDouble(5, warp.getY());
                ps.setDouble(6, warp.getZ());
                ps.setFloat(7, warp.getYaw());
                ps.setFloat(8, warp.getPitch());
                ps.setBoolean(9, warp.isPublic());
                ps.setString(10, warp.getIconMaterial().name());
                ps.setString(11, warp.getCategory().name());
                ps.setTimestamp(12, Timestamp.from(warp.getCreatedAt()));
                ps.setInt(13, warp.getUsageCount());
                if (warp.getLastUsed() != null) {
                    ps.setTimestamp(14, Timestamp.from(warp.getLastUsed()));
                } else {
                    ps.setNull(14, Types.TIMESTAMP);
                }
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        warp.setId(keys.getInt(1));
                    }
                }
            } catch (SQLException e) {
                logger.severe("Failed to save warp '" + warp.getName() + "': " + e.getMessage());
            }
        }, mysql.getExecutor());
    }

    public CompletableFuture<Void> delete(int warpId, String name) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = mysql.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM warp_access WHERE warp_id = ?")) {
                        ps.setInt(1, warpId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM warps WHERE id = ?")) {
                        ps.setInt(1, warpId);
                        ps.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                logger.severe("Failed to delete warp '" + name + "': " + e.getMessage());
            }
        }, mysql.getExecutor());
    }

    public CompletableFuture<Void> updateUsage(String name, int usageCount, Instant lastUsed) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE warps SET usage_count = ?, last_used = ? WHERE name = ?";
            try (Connection conn = mysql.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, usageCount);
                ps.setTimestamp(2, Timestamp.from(lastUsed));
                ps.setString(3, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.severe("Failed to update usage for warp '" + name + "': " + e.getMessage());
            }
        }, mysql.getExecutor());
    }

    public CompletableFuture<Void> updateLocation(String name, String world, double x, double y, double z, float yaw, float pitch) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE warps SET world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ? WHERE name = ?";
            try (Connection conn = mysql.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, world);
                ps.setDouble(2, x);
                ps.setDouble(3, y);
                ps.setDouble(4, z);
                ps.setFloat(5, yaw);
                ps.setFloat(6, pitch);
                ps.setString(7, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.severe("Failed to update location for warp '" + name + "': " + e.getMessage());
            }
        }, mysql.getExecutor());
    }

    public CompletableFuture<Void> updateCategory(String name, WarpCategory category) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE warps SET category = ? WHERE name = ?";
            try (Connection conn = mysql.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, category.name());
                ps.setString(2, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.severe("Failed to update category for warp '" + name + "': " + e.getMessage());
            }
        }, mysql.getExecutor());
    }

    public CompletableFuture<Void> updateVisibility(String name, boolean isPublic) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE warps SET is_public = ? WHERE name = ?";
            try (Connection conn = mysql.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBoolean(1, isPublic);
                ps.setString(2, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.severe("Failed to update visibility for warp '" + name + "': " + e.getMessage());
            }
        }, mysql.getExecutor());
    }

    public CompletableFuture<Void> rename(int warpId, String oldName, String newName) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = mysql.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE warps SET name = ? WHERE name = ?")) {
                        ps.setString(1, newName);
                        ps.setString(2, oldName);
                        ps.executeUpdate();
                    }
                    // Keep warp_name in sync for backward compatibility
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE warp_access SET warp_name = ? WHERE warp_id = ?")) {
                        ps.setString(1, newName);
                        ps.setInt(2, warpId);
                        ps.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                logger.severe("Failed to rename warp '" + oldName + "' to '" + newName + "': " + e.getMessage());
            }
        }, mysql.getExecutor());
    }

    private Warp mapRow(ResultSet rs) throws SQLException {
        Material icon;
        try {
            icon = Material.valueOf(rs.getString("icon_material"));
        } catch (IllegalArgumentException e) {
            icon = Material.ENDER_PEARL;
        }

        WarpCategory category;
        try {
            category = WarpCategory.valueOf(rs.getString("category"));
        } catch (IllegalArgumentException e) {
            category = WarpCategory.PLAYER_WARPS;
        }

        Timestamp lastUsedTs = rs.getTimestamp("last_used");
        Instant lastUsed = lastUsedTs != null ? lastUsedTs.toInstant() : null;

        Timestamp createdTs = rs.getTimestamp("created_at");
        Instant createdAt = createdTs != null ? createdTs.toInstant() : Instant.now();

        return new Warp(
                rs.getInt("id"),
                rs.getString("name"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getFloat("yaw"),
                rs.getFloat("pitch"),
                rs.getBoolean("is_public"),
                icon,
                category,
                createdAt,
                rs.getInt("usage_count"),
                lastUsed
        );
    }
}
