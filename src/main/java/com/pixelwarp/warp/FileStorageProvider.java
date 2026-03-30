package com.pixelwarp.warp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Material;
import org.bukkit.plugin.Plugin;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * File-backed storage provider for warps.
 * Data is cached in-memory and persisted asynchronously.
 */
public class FileStorageProvider implements WarpStorageProvider {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;
    private static final byte GZIP_MAGIC_1 = (byte) 0x1F;
    private static final byte GZIP_MAGIC_2 = (byte) 0x8B;

    private final Logger logger;
    private final File dataFile;
    private final File backupFile;
    private final boolean encryptionEnabled;
    private final boolean compressionEnabled;
    private final SecretKeySpec secretKey;
    private final ExecutorService ioExecutor;
    private final SecureRandom secureRandom = new SecureRandom();

    private final Map<String, Warp> cache = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> accessByWarpName = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicInteger pendingWrites = new AtomicInteger(0);

    private volatile boolean readOnlyMode;
    private volatile String readOnlyReason = "";

    public FileStorageProvider(Plugin plugin, Logger logger, String configuredPath,
                               boolean encryptionEnabled, String secret,
                               boolean compressionEnabled) {
        this.logger = logger;
        this.encryptionEnabled = encryptionEnabled;
        this.compressionEnabled = compressionEnabled;
        this.secretKey = encryptionEnabled ? buildKey(secret) : null;
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PixelWarp-FileStorage");
            t.setDaemon(true);
            return t;
        });

        File dataDir = resolveDataDir(plugin, configuredPath);
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            throw new IllegalStateException("Failed to create data directory: " + dataDir.getAbsolutePath());
        }

        this.dataFile = new File(dataDir, "warps.dat");
        this.backupFile = new File(dataDir, "warps_backup.dat");
        loadFromDisk();
    }

    @Override
    public CompletableFuture<Void> saveWarp(Warp warp) {
        if (readOnlyMode) {
            return CompletableFuture.completedFuture(null);
        }

        synchronized (this) {
            upsertInMemory(warp);
        }
        return runWriteAsync(this::flushSnapshot);
    }

    @Override
    public Warp getWarp(String name) {
        Warp warp = cache.get(normalize(name));
        return warp != null ? copy(warp) : null;
    }

    @Override
    public List<Warp> getAllWarps() {
        List<Warp> all = new ArrayList<>();
        for (Warp warp : cache.values()) {
            all.add(copy(warp));
        }
        all.sort(Comparator.comparing(Warp::getName, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    @Override
    public CompletableFuture<Void> deleteWarp(String name) {
        if (readOnlyMode) {
            return CompletableFuture.completedFuture(null);
        }

        synchronized (this) {
            String key = normalize(name);
            cache.remove(key);
            accessByWarpName.remove(key);
        }
        return runWriteAsync(this::flushSnapshot);
    }

    @Override
    public CompletableFuture<List<Warp>> getAllWarpsAsync() {
        return CompletableFuture.completedFuture(getAllWarps());
    }

    public Map<String, Set<UUID>> getAccessByWarpNameSnapshot() {
        synchronized (this) {
            Map<String, Set<UUID>> snapshot = new LinkedHashMap<>();
            for (Map.Entry<String, Set<UUID>> entry : accessByWarpName.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }

                String warpName = resolveDisplayWarpName(entry.getKey());
                snapshot.put(warpName, new HashSet<>(entry.getValue()));
            }
            return snapshot;
        }
    }

    public CompletableFuture<Void> saveAccessByWarpName(Map<String, Set<UUID>> accessSnapshot) {
        if (readOnlyMode) {
            return CompletableFuture.completedFuture(null);
        }

        synchronized (this) {
            accessByWarpName.clear();
            if (accessSnapshot != null) {
                for (Map.Entry<String, Set<UUID>> entry : accessSnapshot.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                        continue;
                    }
                    String key = normalize(entry.getKey());
                    accessByWarpName.put(key, new HashSet<>(entry.getValue()));
                }
            }
        }
        return runWriteAsync(this::flushSnapshot);
    }

    public CompletableFuture<Void> flushAsync() {
        if (readOnlyMode) {
            return CompletableFuture.completedFuture(null);
        }
        return runWriteAsync(this::flushSnapshot);
    }

    public CompletableFuture<Void> waitForPendingWrites(long timeoutMillis) {
        return CompletableFuture.runAsync(() -> {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
            while (pendingWrites.get() > 0 && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (pendingWrites.get() > 0) {
                logger.warning("Timed out waiting for pending file writes: " + pendingWrites.get());
            }
        });
    }

    public CompletableFuture<Boolean> createManualBackupAsync() {
        return CompletableFuture.supplyAsync(() -> {
            createBackupIfPresent();
            return backupFile.exists();
        }, ioExecutor);
    }

    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }

    public boolean isBackupSystemActive() {
        return true;
    }

    public boolean isReadOnlyMode() {
        return readOnlyMode;
    }

    public String getReadOnlyReason() {
        return readOnlyReason;
    }

    public void close() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void upsertInMemory(Warp warp) {
        if (warp.getId() <= 0) {
            warp.setId(nextId.getAndIncrement());
        } else {
            nextId.updateAndGet(current -> Math.max(current, warp.getId() + 1));
        }

        int warpId = warp.getId();
        String newKey = normalize(warp.getName());

        String previousKey = null;
        for (Map.Entry<String, Warp> entry : cache.entrySet()) {
            if (entry.getValue().getId() == warpId) {
                previousKey = entry.getKey();
                break;
            }
        }

        if (previousKey != null && !previousKey.equals(newKey)) {
            cache.remove(previousKey);

            Set<UUID> accessSet = accessByWarpName.remove(previousKey);
            if (accessSet != null && !accessSet.isEmpty()) {
                accessByWarpName.put(newKey, accessSet);
            }
        }

        cache.put(newKey, copy(warp));
    }

    private void loadFromDisk() {
        if (!dataFile.exists()) {
            return;
        }

        if (tryLoadFromFile(dataFile, false)) {
            return;
        }

        if (restoreBackupIfAvailable()) {
            logger.warning("Primary warp file appears corrupted. Restored from backup.");
            if (tryLoadFromFile(dataFile, true)) {
                return;
            }
        }

        logger.severe("File storage data could not be recovered. Starting with empty cache.");
        synchronized (this) {
            resetCaches();
        }
        enterReadOnlyMode("Storage file could not be recovered after backup restore attempt.");
    }

    private void flushSnapshot() {
        WarpFileData snapshot;
        synchronized (this) {
            snapshot = new WarpFileData();
            snapshot.version = 1;
            snapshot.nextId = nextId.get();
            snapshot.warps = new ArrayList<>();
            for (Warp warp : cache.values()) {
                snapshot.warps.add(warpToRecord(warp));
            }
            snapshot.warps.sort(Comparator.comparing(w -> w.name, String.CASE_INSENSITIVE_ORDER));

            snapshot.access = new LinkedHashMap<>();
            List<String> keys = new ArrayList<>(accessByWarpName.keySet());
            keys.sort(String.CASE_INSENSITIVE_ORDER);
            for (String key : keys) {
                Set<UUID> set = accessByWarpName.get(key);
                if (set == null || set.isEmpty()) {
                    continue;
                }

                String warpName = resolveDisplayWarpName(key);
                List<String> uuids = set.stream()
                        .map(UUID::toString)
                        .sorted(String::compareToIgnoreCase)
                        .toList();
                snapshot.access.put(warpName, uuids);
            }
        }

        String json = GSON.toJson(snapshot);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        try {
            createBackupIfPresent();

            byte[] payload = compressionEnabled ? gzip(bytes) : bytes;
            byte[] output = encryptionEnabled ? encrypt(payload) : payload;

            Files.write(dataFile.toPath(), output,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception e) {
            logger.severe("Failed to write file storage data: " + e.getMessage());
            if (!restoreBackupIfAvailable()) {
                enterReadOnlyMode("Write failed and backup restore failed.");
            }
            throw new RuntimeException("Failed to write file storage snapshot", e);
        }
    }

    private SecretKeySpec buildKey(String secret) {
        if (secret == null) {
            throw new IllegalArgumentException("file.secret-key must be set when encryption is enabled.");
        }

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        int len = keyBytes.length;
        if (len != 16 && len != 24 && len != 32) {
            throw new IllegalArgumentException("file.secret-key must be exactly 16, 24, or 32 bytes.");
        }

        return new SecretKeySpec(keyBytes, "AES");
    }

    private byte[] encrypt(byte[] plainText) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(plainText);

        ByteBuffer buffer = ByteBuffer.allocate(IV_LENGTH + encrypted.length);
        buffer.put(iv);
        buffer.put(encrypted);
        return buffer.array();
    }

    private byte[] decrypt(byte[] cipherText) throws Exception {
        if (cipherText.length <= IV_LENGTH) {
            throw new IOException("Encrypted data is too short.");
        }

        ByteBuffer buffer = ByteBuffer.wrap(cipherText);
        byte[] iv = new byte[IV_LENGTH];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(encrypted);
    }

    private static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private String resolveDisplayWarpName(String key) {
        Warp warp = cache.get(key);
        return warp != null ? warp.getName() : key;
    }

    private File resolveDataDir(Plugin plugin, String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return new File(plugin.getDataFolder(), "data");
        }

        File candidate = new File(configuredPath.trim());
        if (candidate.isAbsolute()) {
            return candidate;
        }

        // Relative paths are resolved from server root.
        return new File(candidate.getPath());
    }

    private void enterReadOnlyMode(String reason) {
        if (!readOnlyMode) {
            readOnlyMode = true;
            readOnlyReason = reason;
            logger.severe("File storage entered read-only mode: " + reason);
        }
    }

    private boolean tryLoadFromFile(File file, boolean restoredFromBackup) {
        try {
            byte[] raw = Files.readAllBytes(file.toPath());
            if (raw.length == 0) {
                synchronized (this) {
                    resetCaches();
                }
                return true;
            }

            byte[] payload = encryptionEnabled ? decrypt(raw) : raw;
            byte[] jsonBytes = isGzip(payload) ? gunzip(payload) : payload;
            String json = new String(jsonBytes, StandardCharsets.UTF_8);
            WarpFileData data = GSON.fromJson(json, WarpFileData.class);
            if (data == null || data.warps == null) {
                throw new IOException("Invalid file format.");
            }
            if (data.version > 1) {
                logger.warning("Warp file payload version " + data.version + " is newer than this plugin expects.");
            }

            synchronized (this) {
                resetCaches();
                int maxId = 0;
                for (WarpRecord rec : data.warps) {
                    Warp warp = recordToWarp(rec);
                    if (warp == null) {
                        continue;
                    }
                    cache.put(normalize(warp.getName()), warp);
                    maxId = Math.max(maxId, warp.getId());
                }

                if (data.access != null) {
                    for (Map.Entry<String, List<String>> entry : data.access.entrySet()) {
                        if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isEmpty()) {
                            continue;
                        }

                        Set<UUID> uuids = new HashSet<>();
                        for (String rawUuid : entry.getValue()) {
                            try {
                                uuids.add(UUID.fromString(rawUuid));
                            } catch (IllegalArgumentException ex) {
                                logger.warning("Skipping invalid access UUID for warp '" + entry.getKey() + "': " + rawUuid);
                            }
                        }

                        if (!uuids.isEmpty()) {
                            accessByWarpName.put(normalize(entry.getKey()), uuids);
                        }
                    }
                }

                nextId.set(Math.max(maxId + 1, Math.max(1, data.nextId)));
            }

            logger.info("Loaded " + cache.size() + " warps from file storage"
                    + (restoredFromBackup ? " (restored backup)." : "."));
            readOnlyMode = false;
            readOnlyReason = "";
            return true;
        } catch (Exception e) {
            if (isLikelyWrongKey(e)) {
                logger.warning("Encryption key mismatch detected for file storage. Trying backup fallback.");
                logger.severe("Failed to decrypt file storage data. Secret key may be wrong.");
            } else {
                logger.severe("Failed to read file storage data: " + e.getMessage());
            }
            return false;
        }
    }

    private byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(input);
        }
        return baos.toByteArray();
    }

    private byte[] gunzip(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(input))) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
        }
        return baos.toByteArray();
    }

    private boolean isGzip(byte[] input) {
        return input != null
                && input.length >= 2
                && input[0] == GZIP_MAGIC_1
                && input[1] == GZIP_MAGIC_2;
    }

    private boolean restoreBackupIfAvailable() {
        if (!backupFile.exists()) {
            return false;
        }

        try {
            Files.copy(backupFile.toPath(), dataFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            return true;
        } catch (IOException e) {
            logger.severe("Failed to restore backup file: " + e.getMessage());
            return false;
        }
    }

    private void createBackupIfPresent() {
        if (!dataFile.exists()) {
            return;
        }

        try {
            Files.copy(dataFile.toPath(), backupFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException e) {
            logger.warning("Failed to create file storage backup: " + e.getMessage());
        }
    }

    private void resetCaches() {
        cache.clear();
        accessByWarpName.clear();
        nextId.set(1);
    }

    private CompletableFuture<Void> runWriteAsync(Runnable runnable) {
        pendingWrites.incrementAndGet();
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            if (readOnlyMode) {
                return;
            }
            runnable.run();
        }, ioExecutor);
        return future.whenComplete((r, ex) -> pendingWrites.decrementAndGet());
    }

    private boolean isLikelyWrongKey(Exception e) {
        if (e.getClass().getSimpleName().equalsIgnoreCase("AEADBadTagException")) {
            return true;
        }

        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }

        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("tag mismatch")
                || lower.contains("mac check")
                || lower.contains("authentication failed");
    }

    private Warp copy(Warp warp) {
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

    private Warp recordToWarp(WarpRecord rec) {
        try {
            Material icon;
            try {
                icon = Material.valueOf(rec.iconMaterial);
            } catch (Exception e) {
                icon = Material.ENDER_PEARL;
            }

            WarpCategory category;
            try {
                category = WarpCategory.valueOf(rec.category);
            } catch (Exception e) {
                category = WarpCategory.PLAYER_WARPS;
            }

            Instant createdAt = rec.createdAt != null ? Instant.parse(rec.createdAt) : Instant.now();
            Instant lastUsed = rec.lastUsed != null && !rec.lastUsed.isBlank() ? Instant.parse(rec.lastUsed) : null;

            return new Warp(
                    rec.id,
                    rec.name,
                    UUID.fromString(rec.ownerUuid),
                    rec.world,
                    rec.x,
                    rec.y,
                    rec.z,
                    rec.yaw,
                    rec.pitch,
                    rec.isPublic,
                    icon,
                    category,
                    createdAt,
                    rec.usageCount,
                    lastUsed
            );
        } catch (Exception e) {
            logger.warning("Skipping invalid file warp record: " + rec.name + " (" + e.getMessage() + ")");
            return null;
        }
    }

    private WarpRecord warpToRecord(Warp warp) {
        WarpRecord rec = new WarpRecord();
        rec.id = warp.getId();
        rec.name = warp.getName();
        rec.ownerUuid = warp.getOwnerUuid().toString();
        rec.world = warp.getWorld();
        rec.x = warp.getX();
        rec.y = warp.getY();
        rec.z = warp.getZ();
        rec.yaw = warp.getYaw();
        rec.pitch = warp.getPitch();
        rec.isPublic = warp.isPublic();
        rec.iconMaterial = warp.getIconMaterial().name();
        rec.category = warp.getCategory().name();
        rec.createdAt = warp.getCreatedAt() != null ? warp.getCreatedAt().toString() : Instant.now().toString();
        rec.usageCount = warp.getUsageCount();
        rec.lastUsed = warp.getLastUsed() != null ? warp.getLastUsed().toString() : null;
        return rec;
    }

    private static class WarpFileData {
        int version = 1;
        int nextId = 1;
        List<WarpRecord> warps = new ArrayList<>();
        Map<String, List<String>> access = new LinkedHashMap<>();
    }

    private static class WarpRecord {
        int id;
        String name;
        String ownerUuid;
        String world;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        boolean isPublic;
        String iconMaterial;
        String category;
        String createdAt;
        int usageCount;
        String lastUsed;
    }
}
