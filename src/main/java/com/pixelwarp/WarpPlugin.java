package com.pixelwarp;

import com.pixelwarp.access.WarpAccessManager;
import com.pixelwarp.backup.WarpBackupManager;
import com.pixelwarp.commands.SetWarpCommand;
import com.pixelwarp.commands.WarpCommand;
import com.pixelwarp.database.MySQL;
import com.pixelwarp.gui.MenuListener;
import com.pixelwarp.particles.WarpParticleTask;
import com.pixelwarp.preview.PreviewManager;
import com.pixelwarp.teleport.TeleportAnimation;
import com.pixelwarp.warp.Warp;
import com.pixelwarp.warp.WarpManager;
import com.pixelwarp.warp.WarpStorage;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class WarpPlugin extends JavaPlugin {

    private MySQL mysql;
    private WarpStorage warpStorage;
    private WarpManager warpManager;
    private WarpAccessManager accessManager;
    private WarpBackupManager backupManager;
    private TeleportAnimation teleportAnimation;
    private PreviewManager previewManager;
    private Set<UUID> serverOwners;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        int configVersion = getConfig().getInt("config-version", -1);
        if (configVersion != 1) {
            getLogger().warning("Unknown config version: " + configVersion + ". Using defaults where possible.");
        }

        loadServerOwners();
        initDatabase();
        initManagers();
        registerCommands();
        registerListeners();
        startTasks();

        getLogger().info("PixelWarp enabled.");
    }

    @Override
    public void onDisable() {
        if (previewManager != null) {
            previewManager.restoreAll();
        }
        if (teleportAnimation != null) {
            teleportAnimation.cancelAll();
        }
        if (mysql != null) {
            mysql.close();
        }
        getLogger().info("PixelWarp disabled.");
    }

    private void loadServerOwners() {
        serverOwners = new HashSet<>();
        List<String> uuids = getConfig().getStringList("server-owners");
        for (String raw : uuids) {
            try {
                serverOwners.add(UUID.fromString(raw));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid UUID in server-owners: " + raw);
            }
        }
        getLogger().info("Loaded " + serverOwners.size() + " server owner(s).");
    }

    private void initDatabase() {
        mysql = new MySQL(getLogger());
        mysql.connect(
                getConfig().getString("database.host", "localhost"),
                getConfig().getInt("database.port", 3306),
                getConfig().getString("database.database", "pixelwarp"),
                getConfig().getString("database.username", "root"),
                getConfig().getString("database.password", ""),
                getConfig().getInt("database.pool-size", 5)
        );
        mysql.createTables();
    }

    private void initManagers() {
        warpStorage = new WarpStorage(mysql, getLogger());
        warpManager = new WarpManager(warpStorage);
        accessManager = new WarpAccessManager(mysql, getLogger());
        warpManager.setAccessManager(accessManager);
        backupManager = new WarpBackupManager(this, warpManager);

        int countdownSeconds = getConfig().getInt("teleport.countdown-seconds", 2);
        boolean safeTeleportEnabled = getConfig().getBoolean("teleport.safe-check", true);
        teleportAnimation = new TeleportAnimation(this, countdownSeconds, safeTeleportEnabled);

        int previewDuration = getConfig().getInt("preview.duration-seconds", 10);
        previewManager = new PreviewManager(this, previewDuration);

        // Load warps from database asynchronously, then load access entries
        warpStorage.loadAll().thenAccept(warps -> {
            // World validation — skip warps whose world doesn't exist
            List<Warp> valid = new ArrayList<>();
            int skipped = 0;
            for (Warp warp : warps) {
                if (Bukkit.getWorld(warp.getWorld()) != null) {
                    valid.add(warp);
                } else {
                    getLogger().warning("Warp '" + warp.getName()
                            + "' skipped because world '" + warp.getWorld() + "' does not exist.");
                    skipped++;
                }
            }
            warpManager.initialize(valid);
            getLogger().info("Loaded " + valid.size() + " warps from database."
                    + (skipped > 0 ? " (" + skipped + " skipped — missing world)" : ""));
        }).thenCompose(v -> accessManager.loadAll()).exceptionally(ex -> {
            getLogger().severe("Failed to load warps or access data: " + ex.getMessage());
            return null;
        });
    }

    private void registerCommands() {
        WarpCommand warpCmd = new WarpCommand(this);
        SetWarpCommand setWarpCmd = new SetWarpCommand(this);

        PluginCommand warp = getCommand("warp");
        if (warp != null) {
            warp.setExecutor(warpCmd);
            warp.setTabCompleter(warpCmd);
        }

        PluginCommand warps = getCommand("warps");
        if (warps != null) {
            warps.setExecutor(warpCmd);
            warps.setTabCompleter(warpCmd);
        }

        PluginCommand setwarp = getCommand("setwarp");
        if (setwarp != null) {
            setwarp.setExecutor(setWarpCmd);
            setwarp.setTabCompleter(setWarpCmd);
        }

        PluginCommand delwarp = getCommand("delwarp");
        if (delwarp != null) {
            delwarp.setExecutor(setWarpCmd);
            delwarp.setTabCompleter(setWarpCmd);
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new MenuListener(this), this
        );
        getServer().getPluginManager().registerEvents(previewManager, this);
    }

    private void startTasks() {
        boolean particlesEnabled = getConfig().getBoolean("particles.warp-marker.enabled", true);
        if (particlesEnabled) {
            int radius = getConfig().getInt("particles.warp-marker.radius", 10);
            int count = getConfig().getInt("particles.warp-marker.particle-count", 4);
            int interval = getConfig().getInt("particles.warp-marker.interval-ticks", 20);

            WarpParticleTask task = new WarpParticleTask(warpManager, radius, count);
            task.runTaskTimer(this, 40L, interval);
        }
    }

    // --- Accessors ---

    public WarpManager getWarpManager() { return warpManager; }
    public WarpAccessManager getAccessManager() { return accessManager; }
    public WarpBackupManager getBackupManager() { return backupManager; }
    public TeleportAnimation getTeleportAnimation() { return teleportAnimation; }
    public PreviewManager getPreviewManager() { return previewManager; }

    public boolean isServerOwner(UUID uuid) {
        return serverOwners.contains(uuid);
    }
}
