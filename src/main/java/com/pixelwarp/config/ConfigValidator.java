package com.pixelwarp.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Validates and normalizes PixelWarp configuration with safe fallbacks.
 */
public final class ConfigValidator {

    private ConfigValidator() {}

    public static void validateAndApply(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        Logger logger = plugin.getLogger();

        boolean changed = false;

        String storageType = config.getString("storage.type", "FILE");
        if (storageType == null || (!storageType.equalsIgnoreCase("FILE") && !storageType.equalsIgnoreCase("MYSQL"))) {
            logger.warning("Invalid storage.type in config. Falling back to FILE.");
            config.set("storage.type", "FILE");
            changed = true;
        }

        String filePath = config.getString("file.path", "plugins/PixelWarp/data/");
        if (filePath == null || filePath.isBlank()) {
            logger.warning("file.path is empty. Falling back to plugins/PixelWarp/data/");
            config.set("file.path", "plugins/PixelWarp/data/");
            changed = true;
            filePath = "plugins/PixelWarp/data/";
        }

        File dataDir = new File(filePath);
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            logger.warning("Unable to create file.path directory. Falling back to plugins/PixelWarp/data/");
            config.set("file.path", "plugins/PixelWarp/data/");
            changed = true;
        }

        boolean encryption = config.getBoolean("file.encryption", true);
        String secret = config.getString("file.secret-key", "");
        if (encryption && !isValidAesKey(secret)) {
            logger.severe("file.secret-key must be exactly 16, 24, or 32 bytes when encryption is enabled.");
            logger.warning("Falling back to file.encryption=false for safe startup.");
            config.set("file.encryption", false);
            changed = true;
        }

        if (!config.contains("file.compression")) {
            config.set("file.compression", false);
            changed = true;
        }

        changed |= ensureMinInt(config, "warp.create-delete-cooldown-seconds", 0, 5, logger);
        changed |= ensureBoolean(config, "particles.enabled", true);
        changed |= ensureRangeInt(config, "particles.radius", 4, 12, 12, logger);
        changed |= ensureRangeInt(config, "particles.max-per-warp", 1, 3, 3, logger);
        changed |= ensureMinInt(config, "particles.interval-ticks", 20, 40, logger);
        changed |= ensureBoolean(config, "particles.dynamic", true);

        changed |= ensureString(config, "mysql.host", "localhost", logger);
        changed |= ensurePort(config, "mysql.port", 3306, logger);
        changed |= ensureString(config, "mysql.database", "pixelwarp", logger);
        changed |= ensureString(config, "mysql.username", "root", logger);
        changed |= ensureString(config, "mysql.password", "password", logger);
        changed |= ensureMinInt(config, "mysql.pool-size", 1, 5, logger);

        // Normalize particle style values if present.
        for (String category : new String[]{"SPAWN", "SHOPS", "BASES", "EVENTS", "PLAYER_WARPS"}) {
            String key = "particles.engine.category-styles." + category;
            String value = config.getString(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            String normalized = value.trim().toUpperCase(Locale.ROOT);
            if (!normalized.equals("RING") && !normalized.equals("SPIRAL") && !normalized.equals("PULSE")) {
                logger.warning("Invalid particle style for " + category + " (" + value + "). Falling back to default style.");
                config.set(key, null);
                changed = true;
            }
        }

        if (changed) {
            plugin.saveConfig();
        }
    }

    private static boolean isValidAesKey(String key) {
        if (key == null) {
            return false;
        }
        int len = key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return len == 16 || len == 24 || len == 32;
    }

    private static boolean ensureString(FileConfiguration config, String path, String fallback, Logger logger) {
        String value = config.getString(path);
        if (value == null || value.isBlank()) {
            logger.warning(path + " is missing/blank. Falling back to '" + fallback + "'.");
            config.set(path, fallback);
            return true;
        }
        return false;
    }

    private static boolean ensurePort(FileConfiguration config, String path, int fallback, Logger logger) {
        int value = config.getInt(path, fallback);
        if (value <= 0 || value > 65535) {
            logger.warning(path + " is out of range. Falling back to " + fallback + ".");
            config.set(path, fallback);
            return true;
        }
        return false;
    }

    private static boolean ensureMinInt(FileConfiguration config, String path, int min, int fallback, Logger logger) {
        int value = config.getInt(path, fallback);
        if (value < min) {
            logger.warning(path + " is too low. Falling back to " + fallback + ".");
            config.set(path, fallback);
            return true;
        }
        return false;
    }

    private static boolean ensureRangeInt(FileConfiguration config, String path, int min, int max,
                                          int fallback, Logger logger) {
        int value = config.getInt(path, fallback);
        if (value < min || value > max) {
            logger.warning(path + " is out of range (" + min + "-" + max + "). Falling back to " + fallback + ".");
            config.set(path, fallback);
            return true;
        }
        return false;
    }

    private static boolean ensureBoolean(FileConfiguration config, String path, boolean fallback) {
        if (!config.contains(path)) {
            config.set(path, fallback);
            return true;
        }
        return false;
    }
}
