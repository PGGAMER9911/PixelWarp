package com.pixelwarp.warp;

import com.pixelwarp.access.WarpAccessManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class WarpManager {

    private final ConcurrentHashMap<String, Warp> warps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WarpCategory, List<Warp>> categoryIndex = new ConcurrentHashMap<>();
    private final WarpStorage storage;
    private WarpAccessManager accessManager;

    public WarpManager(WarpStorage storage) {
        this.storage = storage;
    }

    public void setAccessManager(WarpAccessManager accessManager) {
        this.accessManager = accessManager;
    }

    public WarpAccessManager getAccessManager() {
        return accessManager;
    }

    public void initialize(List<Warp> warpList) {
        warps.clear();
        categoryIndex.clear();
        for (Warp warp : warpList) {
            warps.put(warp.getName().toLowerCase(), warp);
            categoryIndex.computeIfAbsent(warp.getCategory(), k -> new CopyOnWriteArrayList<>()).add(warp);
        }
    }

    public Warp getWarp(String name) {
        return warps.get(name.toLowerCase());
    }

    public CompletableFuture<Void> createWarp(Warp warp) {
        warps.put(warp.getName().toLowerCase(), warp);
        categoryIndex.computeIfAbsent(warp.getCategory(), k -> new CopyOnWriteArrayList<>()).add(warp);
        return storage.save(warp);
    }

    public void deleteWarp(String name) {
        Warp warp = warps.remove(name.toLowerCase());
        if (warp != null) {
            List<Warp> catList = categoryIndex.get(warp.getCategory());
            if (catList != null) catList.remove(warp);
            if (accessManager != null) {
                accessManager.removeAllAccess(warp.getId());
            }
            storage.delete(warp.getId(), name);
        }
    }

    /**
     * Rename a warp in cache and database.
     * Access cache is keyed by warp_id so no access update needed on rename.
     */
    public void renameWarp(String oldName, String newName) {
        Warp warp = warps.remove(oldName.toLowerCase());
        if (warp != null) {
            int warpId = warp.getId();
            warp.setName(newName);
            warps.put(newName.toLowerCase(), warp);
            // Access cache uses warp_id — no update needed on rename.
            // warp_access.warp_name updated for backward compat inside storage transaction.
            storage.rename(warpId, oldName, newName);
        }
    }

    /**
     * Update warp location in cache and database.
     */
    public void updateLocation(Warp warp, Location loc) {
        warp.setWorld(loc.getWorld().getName());
        warp.setX(loc.getX());
        warp.setY(loc.getY());
        warp.setZ(loc.getZ());
        warp.setYaw(loc.getYaw());
        warp.setPitch(loc.getPitch());
        storage.updateLocation(warp.getName(), loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
    }

    /**
     * Update warp category in cache, category index, and database.
     */
    public void updateCategory(Warp warp, WarpCategory newCategory) {
        WarpCategory oldCategory = warp.getCategory();
        List<Warp> oldList = categoryIndex.get(oldCategory);
        if (oldList != null) oldList.remove(warp);
        warp.setCategory(newCategory);
        categoryIndex.computeIfAbsent(newCategory, k -> new CopyOnWriteArrayList<>()).add(warp);
        storage.updateCategory(warp.getName(), newCategory);
    }

    /**
     * Update warp visibility in cache and database.
     */
    public void updateVisibility(Warp warp, boolean isPublic) {
        warp.setPublic(isPublic);
        storage.updateVisibility(warp.getName(), isPublic);
    }

    public void incrementUsage(String name) {
        Warp warp = warps.get(name.toLowerCase());
        if (warp != null) {
            warp.setUsageCount(warp.getUsageCount() + 1);
            warp.setLastUsed(Instant.now());
            storage.updateUsage(warp.getName(), warp.getUsageCount(), warp.getLastUsed());
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

    public WarpStorage getStorage() {
        return storage;
    }
}
