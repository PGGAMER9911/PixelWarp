package com.pixelwarp;

import com.pixelwarp.access.AdminAccessManager;
import com.pixelwarp.access.WarpAccessManager;
import com.pixelwarp.backup.WarpBackupManager;
import com.pixelwarp.commands.SetWarpCommand;
import com.pixelwarp.commands.WarpCommand;
import com.pixelwarp.config.ConfigValidator;
import com.pixelwarp.database.MySQL;
import com.pixelwarp.gui.MenuListener;
import com.pixelwarp.particles.ParticleEngine;
import com.pixelwarp.preview.PreviewManager;
import com.pixelwarp.reload.ReloadManager;
import com.pixelwarp.teleport.TeleportAnimation;
import com.pixelwarp.warp.FileStorageProvider;
import com.pixelwarp.warp.Warp;
import com.pixelwarp.warp.WarpManager;
import com.pixelwarp.warp.WarpStorage;
import com.pixelwarp.warp.WarpStorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class WarpPlugin extends JavaPlugin {

    private enum StorageType {
        MYSQL,
        FILE;

        static StorageType fromConfig(String value) {
            if (value == null) return MYSQL;
            try {
                return StorageType.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return MYSQL;
            }
        }
    }

    private MySQL mysql;
    private WarpStorage mysqlStorage;
    private FileStorageProvider fileStorageProvider;
    private WarpStorageProvider storageProvider;
    private StorageType storageType;
    private StorageType previousStorageType;
    private WarpManager warpManager;
    private WarpAccessManager accessManager;
    private WarpBackupManager backupManager;
    private TeleportAnimation teleportAnimation;
    private PreviewManager previewManager;
    private ParticleEngine particleEngine;
    private Set<UUID> serverOwners;
    private AdminAccessManager adminAccessManager;
    private ReloadManager reloadManager;
    private volatile boolean readOnlyMode;
    private volatile String readOnlyReason = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigValidator.validateAndApply(this);
        reloadManager = new ReloadManager();

        int configVersion = getConfig().getInt("config-version", -1);
        if (configVersion != 1) {
            getLogger().warning("Unknown config version: " + configVersion + ". Using defaults where possible.");
        }

        loadServerOwners();
        loadAdmins();

        if (!initStorageProviders()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

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
        if (particleEngine != null) {
            particleEngine.cancel();
        }

        try {
            flushPendingData().join();
        } catch (Exception e) {
            getLogger().warning("Flush during shutdown encountered an error: " + e.getMessage());
        }

        if (fileStorageProvider != null) {
            fileStorageProvider.close();
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

    private void loadAdmins() {
        adminAccessManager = new AdminAccessManager(getLogger());
        adminAccessManager.load(getConfig());
    }

    private boolean initStorageProviders() {
        storageType = StorageType.fromConfig(getConfig().getString("storage.type", "FILE"));
        boolean hasLastType = getConfig().contains("storage.last-type");
        previousStorageType = hasLastType
            ? StorageType.fromConfig(getConfig().getString("storage.last-type", storageType.name()))
            : (storageType == StorageType.FILE ? StorageType.MYSQL : storageType);

        boolean needsMysql = storageType == StorageType.MYSQL || previousStorageType == StorageType.MYSQL;
        boolean needsFile = storageType == StorageType.FILE || previousStorageType == StorageType.FILE;

        if (needsMysql && !initMySqlStorage()) {
            if (storageType == StorageType.MYSQL) {
                getLogger().severe("MySQL storage is configured but database initialization failed.");
                return false;
            }
            getLogger().warning("MySQL could not be initialized. Migration from MYSQL will be skipped.");
        }

        if (needsFile && !initFileStorage()) {
            getLogger().severe("Failed to initialize FILE storage provider.");
            return false;
        }

        storageProvider = storageType == StorageType.MYSQL ? mysqlStorage : fileStorageProvider;
        if (storageProvider == null) {
            getLogger().severe("No storage provider is available for storage.type=" + storageType.name());
            return false;
        }

        if (!hasLastType && storageType == StorageType.MYSQL) {
            getConfig().set("storage.last-type", storageType.name());
            saveConfig();
            previousStorageType = storageType;
        }

        refreshReadOnlyModeFromStorage();

        return true;
    }

    private boolean initMySqlStorage() {
        try {
            mysql = new MySQL(getLogger());
            mysql.connect(
                    getConfig().getString("mysql.host", getConfig().getString("database.host", "localhost")),
                    getConfig().getInt("mysql.port", getConfig().getInt("database.port", 3306)),
                    getConfig().getString("mysql.database", getConfig().getString("database.database", "pixelwarp")),
                    getConfig().getString("mysql.username", getConfig().getString("database.username", "root")),
                    getConfig().getString("mysql.password", getConfig().getString("database.password", "")),
                    getConfig().getInt("mysql.pool-size", getConfig().getInt("database.pool-size", 5))
            );
            mysql.createTables();
            mysqlStorage = new WarpStorage(mysql, getLogger());
            return true;
        } catch (Exception e) {
            getLogger().severe("Failed to initialize MySQL storage: " + e.getMessage());
            mysqlStorage = null;
            return false;
        }
    }

    private boolean initFileStorage() {
        try {
            String path = getConfig().getString("file.path", "plugins/PixelWarp/data/");
            boolean encryption = getConfig().getBoolean("file.encryption",
                getConfig().getBoolean("storage.file.encryption", true));
            String secret = getConfig().getString("file.secret-key",
                getConfig().getString("storage.file.secret-key", "change-this-to-16-char-key"));
            boolean compression = getConfig().getBoolean("file.compression", false);

            if (encryption && "change-this-to-16-char-key".equals(secret)) {
                getLogger().warning("file.secret-key is using the default value. Change it before production use.");
            }

            fileStorageProvider = new FileStorageProvider(this, getLogger(), path, encryption, secret, compression);
            refreshReadOnlyModeFromStorage();
            return true;
        } catch (IllegalArgumentException e) {
            getLogger().severe("Invalid file encryption key configuration: " + e.getMessage());
            getLogger().warning("Falling back to file storage with encryption disabled for this startup.");

            try {
                String path = getConfig().getString("file.path", "plugins/PixelWarp/data/");
                boolean compression = getConfig().getBoolean("file.compression", false);
                fileStorageProvider = new FileStorageProvider(this, getLogger(), path, false, "", compression);
                refreshReadOnlyModeFromStorage();
                return true;
            } catch (Exception fallbackEx) {
                getLogger().severe("Failed to start file storage fallback: " + fallbackEx.getMessage());
                fileStorageProvider = null;
                return false;
            }
        } catch (Exception e) {
            getLogger().severe("Failed to initialize file storage provider: " + e.getMessage());
            fileStorageProvider = null;
            return false;
        }
    }

    private void initManagers() {
        warpManager = new WarpManager(storageProvider);
        warpManager.setReadOnlyChecker(this::isReadOnlyMode);

        accessManager = storageType == StorageType.MYSQL && mysql != null
            ? new WarpAccessManager(mysql, getLogger())
            : new WarpAccessManager(getLogger());

        configureAccessManager(accessManager);

        warpManager.setAccessManager(accessManager);
        warpManager.setAdminChecker(this::isAdmin);
        backupManager = new WarpBackupManager(this, warpManager);

        int countdownSeconds = getConfig().getInt("teleport.countdown-seconds", 2);
        boolean safeTeleportEnabled = getConfig().getBoolean("teleport.safe-check", true);
        teleportAnimation = new TeleportAnimation(this, countdownSeconds, safeTeleportEnabled);

        int previewDuration = getConfig().getInt("preview.duration-seconds", 10);
        previewManager = new PreviewManager(this, previewDuration);

        migrateIfNeeded()
                .thenCompose(v -> loadCachesFromStorage())
                .exceptionally(ex -> {
            getLogger().severe("Failed to initialize storage/access data: " + ex.getMessage());
            return null;
        });
    }

    private void configureAccessManager(WarpAccessManager manager) {
        if (fileStorageProvider == null) {
            return;
        }

        manager.configureFilePersistence(
                new WarpAccessManager.FileAccessPersistence() {
                    @Override
                    public Map<String, Set<UUID>> loadAccessByWarpName() {
                        return fileStorageProvider.getAccessByWarpNameSnapshot();
                    }

                    @Override
                    public CompletableFuture<Void> saveAccessByWarpName(Map<String, Set<UUID>> accessByWarpName) {
                        return fileStorageProvider.saveAccessByWarpName(accessByWarpName);
                    }
                },
                warpManager::getWarpNameById,
                name -> {
                    Warp warp = warpManager.getWarp(name);
                    return warp != null ? warp.getId() : null;
                }
        );
    }

    private CompletableFuture<Void> loadCachesFromStorage() {
        return storageProvider.getAllWarpsAsync()
                .thenCompose(warps -> runOnMainThread(() -> {
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
                    getLogger().info("Loaded " + valid.size() + " warps from " + storageType.name() + " storage."
                            + (skipped > 0 ? " (" + skipped + " skipped — missing world)" : ""));
                }))
                .thenCompose(v -> accessManager.loadAll())
                .thenRun(() -> printStartupHealthReport());
    }

    private CompletableFuture<Void> migrateIfNeeded() {
        if (previousStorageType == storageType) {
            return CompletableFuture.completedFuture(null);
        }

        WarpStorageProvider source = providerFor(previousStorageType);
        WarpStorageProvider target = providerFor(storageType);
        if (source == null || target == null) {
            getLogger().warning("Storage migration skipped: provider missing for "
                    + previousStorageType.name() + " -> " + storageType.name());
            return CompletableFuture.completedFuture(null);
        }

        getLogger().info("Migrating warps: " + previousStorageType.name() + " -> " + storageType.name());

        return source.getAllWarpsAsync().thenCompose(sourceWarps ->
                target.getAllWarpsAsync().thenCompose(existing -> {
                    Set<String> existingNames = existing.stream()
                            .map(w -> w.getName().toLowerCase(Locale.ROOT))
                            .collect(java.util.stream.Collectors.toSet());

                    List<CompletableFuture<Void>> saves = new ArrayList<>();
                    int skippedDuplicates = 0;
                    int queuedMigrations = 0;

                    for (Warp warp : sourceWarps) {
                        String key = warp.getName().toLowerCase(Locale.ROOT);
                        if (existingNames.contains(key)) {
                            skippedDuplicates++;
                            getLogger().warning("Migration skip (duplicate warp): " + warp.getName());
                            continue;
                        }

                        existingNames.add(key);
                        queuedMigrations++;
                        saves.add(target.saveWarp(cloneWarp(warp)));
                    }

                    int finalSkippedDuplicates = skippedDuplicates;
                    int finalQueuedMigrations = queuedMigrations;
                    return allOf(saves)
                            .thenCompose(v2 -> migrateAccessData(sourceWarps))
                            .thenApply(v3 -> new int[]{finalQueuedMigrations, finalSkippedDuplicates});
                })
        ).handle((migrationStats, ex) -> {
            if (ex != null) {
                getLogger().severe("Storage migration failed: " + ex.getMessage());
            } else {
                int migrated = migrationStats != null ? migrationStats[0] : 0;
                int skipped = migrationStats != null ? migrationStats[1] : 0;
                getLogger().info("Migrated " + migrated + " warps successfully"
                        + (skipped > 0 ? " (skipped duplicates: " + skipped + ")" : ""));
                getConfig().set("storage.last-type", storageType.name());
                saveConfig();
                previousStorageType = storageType;
            }
            return null;
        });
    }

    private WarpStorageProvider providerFor(StorageType type) {
        if (type == StorageType.MYSQL) {
            return mysqlStorage;
        }
        return fileStorageProvider;
    }

    private CompletableFuture<Void> allOf(List<CompletableFuture<Void>> futures) {
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<Void> migrateAccessData(List<Warp> sourceWarps) {
        if (previousStorageType == storageType) {
            return CompletableFuture.completedFuture(null);
        }

        if (previousStorageType == StorageType.MYSQL && storageType == StorageType.FILE
                && mysql != null && fileStorageProvider != null) {
            Map<Integer, String> idToName = new HashMap<>();
            for (Warp warp : sourceWarps) {
                idToName.put(warp.getId(), warp.getName());
            }

            return readMySqlAccessByWarpName(idToName)
                    .thenCompose(fromMysql -> {
                        Map<String, Set<UUID>> merged = fileStorageProvider.getAccessByWarpNameSnapshot();
                        for (Map.Entry<String, Set<UUID>> entry : fromMysql.entrySet()) {
                            merged.computeIfAbsent(entry.getKey(), k -> new HashSet<>()).addAll(entry.getValue());
                        }
                        return fileStorageProvider.saveAccessByWarpName(merged);
                    });
        }

        if (previousStorageType == StorageType.FILE && storageType == StorageType.MYSQL
                && mysql != null && fileStorageProvider != null) {
            Map<String, Set<UUID>> accessByWarpName = fileStorageProvider.getAccessByWarpNameSnapshot();
            Map<String, Integer> nameToId = new HashMap<>();
            for (Warp warp : sourceWarps) {
                nameToId.put(warp.getName().toLowerCase(Locale.ROOT), warp.getId());
            }
            return writeMySqlAccessByWarpName(accessByWarpName, nameToId);
        }

        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Map<String, Set<UUID>>> readMySqlAccessByWarpName(Map<Integer, String> idToName) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Set<UUID>> access = new LinkedHashMap<>();
            String sql = "SELECT warp_id, warp_name, player_uuid FROM warp_access";

            try (Connection conn = mysql.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int warpId = rs.getInt("warp_id");
                    String name = idToName.get(warpId);
                    if (name == null || name.isBlank()) {
                        name = rs.getString("warp_name");
                    }

                    if (name == null || name.isBlank()) {
                        continue;
                    }

                    try {
                        UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                        access.computeIfAbsent(name, k -> new HashSet<>()).add(uuid);
                    } catch (IllegalArgumentException ignored) {
                        // skip malformed UUID rows
                    }
                }
            } catch (SQLException e) {
                getLogger().severe("Failed to read MySQL access data for migration: " + e.getMessage());
            }

            return access;
        }, mysql.getExecutor());
    }

    private CompletableFuture<Void> writeMySqlAccessByWarpName(Map<String, Set<UUID>> accessByWarpName,
                                                               Map<String, Integer> nameToId) {
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = mysql.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    String insertSql = "INSERT IGNORE INTO warp_access (warp_name, player_uuid, warp_id) VALUES (?, ?, ?)";
                    try (PreparedStatement insert = conn.prepareStatement(insertSql)) {
                        for (Map.Entry<String, Set<UUID>> entry : accessByWarpName.entrySet()) {
                            String warpName = entry.getKey();
                            Integer warpId = nameToId.get(warpName.toLowerCase(Locale.ROOT));
                            if (warpId == null || warpId <= 0 || entry.getValue() == null) {
                                continue;
                            }

                            for (UUID uuid : entry.getValue()) {
                                insert.setString(1, warpName);
                                insert.setString(2, uuid.toString());
                                insert.setInt(3, warpId);
                                insert.addBatch();
                            }
                        }
                        insert.executeBatch();
                    }

                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (SQLException e) {
                getLogger().severe("Failed to write MySQL access data for migration: " + e.getMessage());
            }
        }, mysql.getExecutor());
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

    private CompletableFuture<Void> runOnMainThread(Runnable runnable) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                runnable.run();
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
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

        PluginCommand pwarp = getCommand("pwarp");
        if (pwarp != null) {
            pwarp.setExecutor(warpCmd);
            pwarp.setTabCompleter(warpCmd);
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
        if (particleEngine != null) {
            particleEngine.cancel();
            particleEngine = null;
        }

        boolean particlesEnabled = getConfig().getBoolean("particles.enabled",
            getConfig().getBoolean("particles.engine.enabled", true));
        if (particlesEnabled) {
            int radius = getConfig().getInt("particles.radius",
                getConfig().getInt("particles.engine.radius", 12));
            int interval = getConfig().getInt("particles.interval-ticks",
                getConfig().getInt("particles.engine.interval-ticks", 40));
            int maxPerWarp = getConfig().getInt("particles.max-per-warp", 3);
            boolean dynamicScaling = getConfig().getBoolean("particles.dynamic", true);
            int indexRefreshTicks = getConfig().getInt("particles.engine.index-refresh-ticks", 40);
            int maxHeightDiff = getConfig().getInt("particles.engine.max-height-diff", 24);
            int maxWarpsPerPlayer = getConfig().getInt("particles.engine.max-warps-per-player", 4);
            double intensity = getConfig().getDouble("particles.engine.intensity", 1.0);

            Set<String> enabledPatterns = new HashSet<>();
            if (getConfig().getBoolean("particles.engine.patterns.ring", true)) {
                enabledPatterns.add("RING");
            }
            if (getConfig().getBoolean("particles.engine.patterns.spiral", true)) {
                enabledPatterns.add("SPIRAL");
            }
            if (getConfig().getBoolean("particles.engine.patterns.pulse", true)) {
                enabledPatterns.add("PULSE");
            }

            Map<com.pixelwarp.warp.WarpCategory, String> categoryStyles = new EnumMap<>(com.pixelwarp.warp.WarpCategory.class);
            for (com.pixelwarp.warp.WarpCategory category : com.pixelwarp.warp.WarpCategory.values()) {
                String path = "particles.engine.category-styles." + category.name();
                categoryStyles.put(category, getConfig().getString(path));
            }

            particleEngine = new ParticleEngine(
                    warpManager,
                    radius,
                    maxHeightDiff,
                    maxWarpsPerPlayer,
                    indexRefreshTicks,
                    maxPerWarp,
                    dynamicScaling,
                    intensity,
                    enabledPatterns,
                    categoryStyles
            );
            particleEngine.runTaskTimer(this, 40L, Math.max(20, interval));
        }
    }

    private void printStartupHealthReport() {
        for (String line : getHealthReportLines()) {
            getLogger().info(line);
        }
    }

    public List<String> getHealthReportLines() {
        List<String> lines = new ArrayList<>();
        lines.add("[PixelWarp] Storage Mode: " + storageType.name());
        lines.add("[PixelWarp] Encryption: " + (isEncryptionEnabled() ? "ENABLED" : "DISABLED"));
        lines.add("[PixelWarp] Backup System: " + (isBackupSystemActive() ? "ACTIVE" : "INACTIVE"));
        lines.add("[PixelWarp] Warps Loaded: " + (warpManager != null ? warpManager.getWarpCount() : 0));
        lines.add("[PixelWarp] Access Entries: " + (accessManager != null ? accessManager.getAccessEntryCount() : 0));
        if (isReadOnlyMode()) {
            lines.add("[PixelWarp] FAILSAFE: READ-ONLY (" + getReadOnlyReason() + ")");
        }
        return lines;
    }

    public CompletableFuture<Boolean> createStorageBackup() {
        if (fileStorageProvider == null) {
            return CompletableFuture.completedFuture(false);
        }
        return fileStorageProvider.createManualBackupAsync();
    }

    public CompletableFuture<Void> flushPendingData() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        if (fileStorageProvider != null) {
            futures.add(fileStorageProvider.flushAsync());
            futures.add(fileStorageProvider.waitForPendingWrites(5000));
        }
        if (mysqlStorage != null) {
            futures.add(mysqlStorage.flushPendingOperations(5000));
        }
        if (accessManager != null) {
            futures.add(accessManager.flushPendingOperations(5000));
        }

        return allOf(futures);
    }

    public CompletableFuture<ReloadManager.ReloadResult> reloadRuntime(ReloadManager.ReloadScope scope) {
        return reloadManager.execute(scope, this::performReload);
    }

    private CompletableFuture<Void> performReload(ReloadManager.ReloadScope scope) {
        getLogger().info("Starting runtime reload: " + scope.name());

        return flushPendingData()
                .thenCompose(v -> runOnMainThread(() -> {
                    reloadConfig();
                    ConfigValidator.validateAndApply(this);
                    loadServerOwners();
                    loadAdmins();
                }))
                .thenCompose(v -> {
                    if (scope == ReloadManager.ReloadScope.CONFIG) {
                        return runOnMainThread(this::startTasks);
                    }
                    return reloadStorageRuntime().thenCompose(v2 -> runOnMainThread(this::startTasks));
                })
                .thenRun(this::printStartupHealthReport);
    }

    private CompletableFuture<Void> reloadStorageRuntime() {
        MySQL oldMysql = this.mysql;
        WarpStorage oldMysqlStorage = this.mysqlStorage;
        FileStorageProvider oldFileProvider = this.fileStorageProvider;
        WarpStorageProvider oldProvider = this.storageProvider;

        if (!initStorageProviders()) {
            this.mysql = oldMysql;
            this.mysqlStorage = oldMysqlStorage;
            this.fileStorageProvider = oldFileProvider;
            this.storageProvider = oldProvider;

            setReadOnlyMode(true, "Storage reinitialization failed during runtime reload.");
            return CompletableFuture.failedFuture(new IllegalStateException("Storage reinitialization failed."));
        }

        if (warpManager != null) {
            warpManager.setStorageProvider(storageProvider);
        }

        WarpAccessManager newAccessManager = storageType == StorageType.MYSQL && mysql != null
                ? new WarpAccessManager(mysql, getLogger())
                : new WarpAccessManager(getLogger());
        configureAccessManager(newAccessManager);
        this.accessManager = newAccessManager;

        if (warpManager != null) {
            warpManager.setAccessManager(accessManager);
        }

        if (oldFileProvider != null && oldFileProvider != fileStorageProvider) {
            oldFileProvider.close();
        }
        if (oldMysql != null && oldMysql != mysql) {
            oldMysql.close();
        }

        refreshReadOnlyModeFromStorage();
        return migrateIfNeeded().thenCompose(v -> loadCachesFromStorage());
    }

    private void refreshReadOnlyModeFromStorage() {
        if (fileStorageProvider != null && fileStorageProvider.isReadOnlyMode()) {
            setReadOnlyMode(true, fileStorageProvider.getReadOnlyReason());
            return;
        }

        if (readOnlyMode && readOnlyReason.startsWith("Storage")) {
            setReadOnlyMode(false, "");
        }
    }

    private void setReadOnlyMode(boolean enabled, String reason) {
        this.readOnlyMode = enabled;
        this.readOnlyReason = enabled ? (reason != null ? reason : "Unknown") : "";
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

    public boolean isAdmin(UUID uuid) {
        return adminAccessManager != null && adminAccessManager.isAdmin(uuid);
    }

    public boolean isReloading() {
        return reloadManager != null && reloadManager.isReloading();
    }

    public boolean isReadOnlyMode() {
        if (fileStorageProvider != null && fileStorageProvider.isReadOnlyMode()) {
            return true;
        }
        return readOnlyMode;
    }

    public String getReadOnlyReason() {
        if (fileStorageProvider != null && fileStorageProvider.isReadOnlyMode()) {
            return fileStorageProvider.getReadOnlyReason();
        }
        return readOnlyReason;
    }

    public boolean isEncryptionEnabled() {
        return fileStorageProvider != null && fileStorageProvider.isEncryptionEnabled();
    }

    public boolean isBackupSystemActive() {
        return fileStorageProvider != null && fileStorageProvider.isBackupSystemActive();
    }

    public String getStorageMode() {
        return storageType != null ? storageType.name() : "UNKNOWN";
    }

    public int getCreateDeleteCooldownSeconds() {
        return Math.max(0, getConfig().getInt("warp.create-delete-cooldown-seconds", 5));
    }

    public long getCreateDeleteCooldownMillis() {
        return getCreateDeleteCooldownSeconds() * 1000L;
    }
}
