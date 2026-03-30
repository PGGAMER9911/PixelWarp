package com.pixelwarp.warp;

import com.pixelwarp.database.MySQL;
import org.bukkit.Material;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class WarpStorage implements WarpStorageProvider {

    private final MySQL mysql;
    private final Logger logger;
    private final AtomicInteger pendingOperations = new AtomicInteger(0);

    public WarpStorage(MySQL mysql, Logger logger) {
        this.mysql = mysql;
        this.logger = logger;
    }

    @Override
    public CompletableFuture<Void> saveWarp(Warp warp) {
        return track(CompletableFuture.runAsync(() -> {
            if (warp.getId() > 0) {
                upsertWithId(warp);
            } else {
                insertNew(warp);
            }
        }, mysql.getExecutor()));
    }

    @Override
    public Warp getWarp(String name) {
        String sql = "SELECT * FROM warps WHERE name = ? LIMIT 1";
        try (Connection conn = mysql.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapRow(rs);
                }
            }
        } catch (SQLException e) {
            logger.severe("Failed to load warp '" + name + "': " + e.getMessage());
        }
        return null;
    }

    @Override
    public List<Warp> getAllWarps() {
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
    }

    @Override
    public CompletableFuture<Void> deleteWarp(String name) {
        return track(CompletableFuture.runAsync(() -> {
            try (Connection conn = mysql.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    Integer warpId = null;
                    try (PreparedStatement find = conn.prepareStatement(
                            "SELECT id FROM warps WHERE name = ? LIMIT 1")) {
                        find.setString(1, name);
                        try (ResultSet rs = find.executeQuery()) {
                            if (rs.next()) {
                                warpId = rs.getInt("id");
                            }
                        }
                    }

                    if (warpId != null) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "DELETE FROM warp_access WHERE warp_id = ?")) {
                            ps.setInt(1, warpId);
                            ps.executeUpdate();
                        }
                    }

                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM warps WHERE name = ?")) {
                        ps.setString(1, name);
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
                throw new CompletionException(e);
            }
        }, mysql.getExecutor()));
    }

    @Override
    public CompletableFuture<List<Warp>> getAllWarpsAsync() {
        return track(CompletableFuture.supplyAsync(this::getAllWarps, mysql.getExecutor()));
    }

    public CompletableFuture<List<Warp>> loadAll() {
        return getAllWarpsAsync();
    }

    public CompletableFuture<Void> save(Warp warp) {
        return saveWarp(warp);
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
        return track(CompletableFuture.runAsync(() -> {
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
        }, mysql.getExecutor()));
    }

    public CompletableFuture<Void> updateLocation(String name, String world, double x, double y, double z, float yaw, float pitch) {
        return track(CompletableFuture.runAsync(() -> {
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
        }, mysql.getExecutor()));
    }

    public CompletableFuture<Void> updateCategory(String name, WarpCategory category) {
        return track(CompletableFuture.runAsync(() -> {
            String sql = "UPDATE warps SET category = ? WHERE name = ?";
            try (Connection conn = mysql.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, category.name());
                ps.setString(2, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.severe("Failed to update category for warp '" + name + "': " + e.getMessage());
            }
        }, mysql.getExecutor()));
    }

    public CompletableFuture<Void> updateVisibility(String name, boolean isPublic) {
        return track(CompletableFuture.runAsync(() -> {
            String sql = "UPDATE warps SET is_public = ? WHERE name = ?";
            try (Connection conn = mysql.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBoolean(1, isPublic);
                ps.setString(2, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.severe("Failed to update visibility for warp '" + name + "': " + e.getMessage());
            }
        }, mysql.getExecutor()));
    }

    public CompletableFuture<Void> rename(int warpId, String oldName, String newName) {
        return track(CompletableFuture.runAsync(() -> {
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
        }, mysql.getExecutor()));
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
                logger.warning("Timed out waiting for pending MySQL storage operations: " + pendingOperations.get());
            }
        });
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pendingOperations.incrementAndGet();
        return future.whenComplete((r, ex) -> pendingOperations.decrementAndGet());
    }

    private void insertNew(Warp warp) {
        String sql = """
                INSERT INTO warps (name, owner_uuid, world, x, y, z, yaw, pitch,
                                   is_public, icon_material, category, created_at, usage_count, last_used)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = mysql.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindWarp(ps, warp, false);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    warp.setId(keys.getInt(1));
                }
            }
        } catch (SQLException e) {
            logger.severe("Failed to save warp '" + warp.getName() + "': " + e.getMessage());
            throw new CompletionException(e);
        }
    }

    private void upsertWithId(Warp warp) {
        String sql = """
                INSERT INTO warps (id, name, owner_uuid, world, x, y, z, yaw, pitch,
                                   is_public, icon_material, category, created_at, usage_count, last_used)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name),
                    owner_uuid = VALUES(owner_uuid),
                    world = VALUES(world),
                    x = VALUES(x),
                    y = VALUES(y),
                    z = VALUES(z),
                    yaw = VALUES(yaw),
                    pitch = VALUES(pitch),
                    is_public = VALUES(is_public),
                    icon_material = VALUES(icon_material),
                    category = VALUES(category),
                    created_at = VALUES(created_at),
                    usage_count = VALUES(usage_count),
                    last_used = VALUES(last_used)
                """;
        try (Connection conn = mysql.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindWarp(ps, warp, true);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Failed to upsert warp '" + warp.getName() + "': " + e.getMessage());
            throw new CompletionException(e);
        }
    }

    private void bindWarp(PreparedStatement ps, Warp warp, boolean includesId) throws SQLException {
        int idx = 1;
        if (includesId) {
            ps.setInt(idx++, warp.getId());
        }

        ps.setString(idx++, warp.getName());
        ps.setString(idx++, warp.getOwnerUuid().toString());
        ps.setString(idx++, warp.getWorld());
        ps.setDouble(idx++, warp.getX());
        ps.setDouble(idx++, warp.getY());
        ps.setDouble(idx++, warp.getZ());
        ps.setFloat(idx++, warp.getYaw());
        ps.setFloat(idx++, warp.getPitch());
        ps.setBoolean(idx++, warp.isPublic());
        ps.setString(idx++, warp.getIconMaterial().name());
        ps.setString(idx++, warp.getCategory().name());
        ps.setTimestamp(idx++, Timestamp.from(warp.getCreatedAt() != null ? warp.getCreatedAt() : Instant.now()));
        ps.setInt(idx++, warp.getUsageCount());
        if (warp.getLastUsed() != null) {
            ps.setTimestamp(idx, Timestamp.from(warp.getLastUsed()));
        } else {
            ps.setNull(idx, Types.TIMESTAMP);
        }
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
