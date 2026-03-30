package com.pixelwarp.warp;

import com.pixelwarp.access.WarpAccessManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.logging.Logger;

public class WarpManager {

    private final ConcurrentHashMap<String, Warp> warps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Warp> warpIdIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WarpCategory, List<Warp>> categoryIndex = new ConcurrentHashMap<>();
    private volatile WarpStorageProvider storage;
    private WarpAccessManager accessManager;
    private Predicate<UUID> adminChecker = uuid -> false;
    private Supplier<Boolean> readOnlyChecker = () -> false;

    public WarpManager(WarpStorageProvider storage) {
        this.storage = storage;
    }

    public void setStorageProvider(WarpStorageProvider storage) {
        this.storage = storage;
    }

    public void setAccessManager(WarpAccessManager accessManager) {
        this.accessManager = accessManager;
    }

    public WarpAccessManager getAccessManager() {
        return accessManager;
    }

    public void setAdminChecker(Predicate<UUID> adminChecker) {
        this.adminChecker = adminChecker != null ? adminChecker : (uuid -> false);
    }

    public void setReadOnlyChecker(Supplier<Boolean> readOnlyChecker) {
        this.readOnlyChecker = readOnlyChecker != null ? readOnlyChecker : (() -> false);
    }

    public boolean isReadOnly() {
        return readOnlyChecker.get();
    }

    public boolean isAdmin(UUID playerUuid) {
        return playerUuid != null && adminChecker.test(playerUuid);
    }

    public void initialize(List<Warp> warpList) {
        warps.clear();
        warpIdIndex.clear();
        categoryIndex.clear();
        for (Warp warp : warpList) {
            putWarpInCache(warp);
        }
    }

    public Warp getWarp(String name) {
        return warps.get(name.toLowerCase());
    }

    public CompletableFuture<Void> createWarp(Warp warp) {
        if (isReadOnly()) {
            return CompletableFuture.completedFuture(null);
        }

        Warp toPersist = cloneWarp(warp);
        return storage.saveWarp(toPersist).thenRun(() -> {
            applyWarpFields(warp, toPersist);
            putWarpInCache(warp);
        });
    }

    public void deleteWarp(String name) {
        if (isReadOnly()) {
            return;
        }

        Warp warp = warps.get(name.toLowerCase());
        if (warp != null) {
            storage.deleteWarp(name).thenRun(() -> {
                removeWarpFromCache(warp);
                if (accessManager != null) {
                    accessManager.removeAllAccess(warp.getId());
                }
            }).exceptionally(ex -> {
                logFailure("delete", name, ex);
                return null;
            });
        }
    }

    /**
     * Rename a warp in cache and database.
     * Access cache is keyed by warp_id so no access update needed on rename.
     */
    public void renameWarp(String oldName, String newName) {
        if (isReadOnly()) {
            return;
        }

        Warp warp = warps.get(oldName.toLowerCase());
        if (warp != null) {
            Warp toPersist = cloneWarp(warp);
            toPersist.setName(newName);

            storage.saveWarp(toPersist).thenRun(() -> {
                removeWarpFromCache(warp);
                applyWarpFields(warp, toPersist);
                putWarpInCache(warp);
            }).exceptionally(ex -> {
                logFailure("rename", oldName + " -> " + newName, ex);
                return null;
            });
        }
    }

    /**
     * Update warp location in cache and database.
     */
    public void updateLocation(Warp warp, Location loc) {
        if (isReadOnly()) {
            return;
        }

        Warp toPersist = cloneWarp(warp);
        toPersist.setWorld(loc.getWorld().getName());
        toPersist.setX(loc.getX());
        toPersist.setY(loc.getY());
        toPersist.setZ(loc.getZ());
        toPersist.setYaw(loc.getYaw());
        toPersist.setPitch(loc.getPitch());

        storage.saveWarp(toPersist).thenRun(() -> applyWarpFields(warp, toPersist)).exceptionally(ex -> {
            logFailure("update location", warp.getName(), ex);
            return null;
        });
    }

    /**
     * Update warp category in cache, category index, and database.
     */
    public void updateCategory(Warp warp, WarpCategory newCategory) {
        if (isReadOnly()) {
            return;
        }

        Warp toPersist = cloneWarp(warp);
        toPersist.setCategory(newCategory);

        storage.saveWarp(toPersist).thenRun(() -> {
            removeWarpFromCache(warp);
            applyWarpFields(warp, toPersist);
            putWarpInCache(warp);
        }).exceptionally(ex -> {
            logFailure("update category", warp.getName(), ex);
            return null;
        });
    }

    /**
     * Update warp visibility in cache and database.
     */
    public void updateVisibility(Warp warp, boolean isPublic) {
        if (isReadOnly()) {
            return;
        }

        Warp toPersist = cloneWarp(warp);
        toPersist.setPublic(isPublic);

        storage.saveWarp(toPersist).thenRun(() -> applyWarpFields(warp, toPersist)).exceptionally(ex -> {
            logFailure("update visibility", warp.getName(), ex);
            return null;
        });
    }

    public void incrementUsage(String name) {
        if (isReadOnly()) {
            return;
        }

        Warp warp = warps.get(name.toLowerCase());
        if (warp != null) {
            Warp toPersist = cloneWarp(warp);
            toPersist.setUsageCount(toPersist.getUsageCount() + 1);
            toPersist.setLastUsed(Instant.now());

            storage.saveWarp(toPersist).thenRun(() -> applyWarpFields(warp, toPersist)).exceptionally(ex -> {
                logFailure("increment usage", warp.getName(), ex);
                return null;
            });
        }
    }

    /**
     * Returns warps visible to a specific player, optionally filtered by category.
     * Uses the category index for O(category_size) instead of O(total_warps) when filtered.
     */
    public List<Warp> getVisibleWarps(UUID playerUuid, WarpCategory category) {
        Collection<Warp> source;
        if (category != null) {
            List<Warp> catList = categoryIndex.get(category);
            source = catList != null ? catList : Collections.emptyList();
        } else {
            source = warps.values();
        }

        if (isAdmin(playerUuid)) {
            return source.stream()
                    .sorted(Comparator.comparing(Warp::getName, String.CASE_INSENSITIVE_ORDER))
                    .collect(Collectors.toList());
        }

        return source.stream()
                .filter(w -> w.isPublic()
                        || w.getOwnerUuid().equals(playerUuid)
                        || (accessManager != null && accessManager.hasAccess(w.getId(), playerUuid)))
                .sorted(Comparator.comparing(Warp::getName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    /**
     * Returns warps visible to a specific player (all categories).
     */
    public List<Warp> getVisibleWarps(UUID playerUuid) {
        return getVisibleWarps(playerUuid, null);
    }

    /**
     * Check if a player can use a specific warp (teleport, preview, stats).
     */
    public boolean canAccess(Warp warp, UUID playerUuid) {
        if (isAdmin(playerUuid)) return true;
        if (warp.isPublic()) return true;
        if (warp.getOwnerUuid().equals(playerUuid)) return true;
        return accessManager != null && accessManager.hasAccess(warp.getId(), playerUuid);
    }

    /**
     * Returns warp names visible to a player (for tab completion).
     */
    public List<String> getVisibleWarpNames(Player player) {
        return getVisibleWarps(player.getUniqueId()).stream()
                .map(Warp::getName)
                .collect(Collectors.toList());
    }

    /**
     * Returns all public warps (for particle effects, top warps, etc.).
     */
    public Collection<Warp> getPublicWarps() {
        return warps.values().stream()
                .filter(Warp::isPublic)
                .collect(Collectors.toList());
    }

    /**
     * Returns all warps (for backup/export).
     */
    public Collection<Warp> getAllWarps() {
        return Collections.unmodifiableCollection(warps.values());
    }

    /**
     * Returns warps in a specific category (from the cached index).
     */
    public List<Warp> getWarpsByCategory(WarpCategory category) {
        List<Warp> list = categoryIndex.get(category);
        return list != null ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    /**
     * Returns the top N most-used public warps.
     */
    public List<Warp> getTopWarps(int limit) {
        return warps.values().stream()
                .filter(Warp::isPublic)
                .sorted(Comparator.comparingInt(Warp::getUsageCount).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Returns the total number of loaded warps.
     */
    public int getWarpCount() {
        return warps.size();
    }

    public Warp getWarpById(int id) {
        return warpIdIndex.get(id);
    }

    public String getWarpNameById(int id) {
        Warp warp = warpIdIndex.get(id);
        return warp != null ? warp.getName() : null;
    }

    public WarpStorageProvider getStorage() {
        return storage;
    }

    private void putWarpInCache(Warp warp) {
        warps.put(warp.getName().toLowerCase(), warp);
        warpIdIndex.put(warp.getId(), warp);
        categoryIndex.computeIfAbsent(warp.getCategory(), k -> new CopyOnWriteArrayList<>()).add(warp);
    }

    private void removeWarpFromCache(Warp warp) {
        warps.remove(warp.getName().toLowerCase());
        warpIdIndex.remove(warp.getId());
        List<Warp> catList = categoryIndex.get(warp.getCategory());
        if (catList != null) {
            catList.remove(warp);
        }
    }

    private Warp cloneWarp(Warp warp) {
        return new Warp(
                warp.getId(),
                warp.getName(),
                warp.getOwnerUuid(),
                warp.getWorld(),
                warp.getX(),
                warp.getY(),
                warp.getZ(),
                warp.getYaw(),
                warp.getPitch(),
                warp.isPublic(),
                warp.getIconMaterial(),
                warp.getCategory(),
                warp.getCreatedAt(),
                warp.getUsageCount(),
                warp.getLastUsed()
        );
    }

    private void applyWarpFields(Warp target, Warp source) {
        target.setId(source.getId());
        target.setName(source.getName());
        target.setOwnerUuid(source.getOwnerUuid());
        target.setWorld(source.getWorld());
        target.setX(source.getX());
        target.setY(source.getY());
        target.setZ(source.getZ());
        target.setYaw(source.getYaw());
        target.setPitch(source.getPitch());
        target.setPublic(source.isPublic());
        target.setIconMaterial(source.getIconMaterial());
        target.setCategory(source.getCategory());
        target.setCreatedAt(source.getCreatedAt());
        target.setUsageCount(source.getUsageCount());
        target.setLastUsed(source.getLastUsed());
    }

    private void logFailure(String action, String target, Throwable ex) {
        Logger.getLogger(WarpManager.class.getName())
                .warning("Warp storage write failed during " + action + " for '" + target + "': " + ex.getMessage());
    }
}
