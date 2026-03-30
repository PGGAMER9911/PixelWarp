package com.pixelwarp.access;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Loads and caches global warp admins from config.
 * Admin identities are stored as UUIDs for O(1) access checks.
 */
public class AdminAccessManager {

    private final Logger logger;
    private final Set<UUID> admins = ConcurrentHashMap.newKeySet();

    public AdminAccessManager(Logger logger) {
        this.logger = logger;
    }

    public void load(FileConfiguration config) {
        admins.clear();

        for (String raw : config.getStringList("admins")) {
            parseAndAdd(raw, "admins");
        }

        for (String raw : config.getStringList("admins.uuids")) {
            parseAndAdd(raw, "admins.uuids");
        }

        for (String raw : config.getStringList("admins.names")) {
            parseAndAdd(raw, "admins.names");
        }

        logger.info("Loaded " + admins.size() + " warp admin(s).");
    }

    public boolean isAdmin(UUID uuid) {
        return uuid != null && admins.contains(uuid);
    }

    public Set<UUID> getAdmins() {
        return Collections.unmodifiableSet(admins);
    }

    private void parseAndAdd(String raw, String sourcePath) {
        if (raw == null || raw.isBlank()) {
            return;
        }

        String value = raw.trim();

        try {
            admins.add(UUID.fromString(value));
            return;
        } catch (IllegalArgumentException ignored) {
            // Fall through to name-based resolution.
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(value);
        UUID resolved = offlinePlayer.getUniqueId();
        if (resolved == null) {
            logger.warning("Could not resolve admin entry in " + sourcePath + ": " + value);
            return;
        }

        admins.add(resolved);
    }
}
