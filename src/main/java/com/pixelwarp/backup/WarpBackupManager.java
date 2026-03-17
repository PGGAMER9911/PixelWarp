package com.pixelwarp.backup;

import com.google.gson.*;
import com.pixelwarp.access.WarpAccessManager;
import com.pixelwarp.util.MessageUtil;
import com.pixelwarp.warp.Warp;
import com.pixelwarp.warp.WarpCategory;
import com.pixelwarp.warp.WarpManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Handles warp export to JSON and import from JSON files.
 */
public class WarpBackupManager {

    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Result of an import operation. */
    public record ImportResult(int errorCode, int imported, List<String> skipped) {}

    private final Plugin plugin;
    private final WarpManager warpManager;
    private final File backupDir;

    public WarpBackupManager(Plugin plugin, WarpManager warpManager) {
        this.plugin = plugin;
        this.warpManager = warpManager;
        this.backupDir = new File(plugin.getDataFolder(), "backups");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
    }

    /**
     * Export all warps to a timestamped JSON file.
     */
    public CompletableFuture<File> exportWarps(Player requester) {
        return CompletableFuture.supplyAsync(() -> {
            Collection<Warp> warps = warpManager.getAllWarps();
            JsonArray array = new JsonArray();

            for (Warp warp : warps) {
                JsonObject obj = new JsonObject();
                obj.addProperty("name", warp.getName());
                obj.addProperty("owner_uuid", warp.getOwnerUuid().toString());
                obj.addProperty("world", warp.getWorld());
                obj.addProperty("x", warp.getX());
                obj.addProperty("y", warp.getY());
                obj.addProperty("z", warp.getZ());
                obj.addProperty("yaw", warp.getYaw());
                obj.addProperty("pitch", warp.getPitch());
                obj.addProperty("is_public", warp.isPublic());
                obj.addProperty("icon_material", warp.getIconMaterial().name());
                obj.addProperty("category", warp.getCategory().name());
                obj.addProperty("usage_count", warp.getUsageCount());
                if (warp.getCreatedAt() != null) {
                    obj.addProperty("created_at", warp.getCreatedAt().toString());
                }

                // Include access sharing data
                WarpAccessManager accessMgr = warpManager.getAccessManager();
                if (accessMgr != null) {
                    Set<UUID> accessList = accessMgr.getAccessList(warp.getId());
                    if (!accessList.isEmpty()) {
                        JsonArray sharedWith = new JsonArray();
                        for (UUID uuid : accessList) {
                            sharedWith.add(uuid.toString());
                        }
                        obj.add("shared_with", sharedWith);
                    }
                }

                array.add(obj);
            }

            String timestamp = FILE_FMT.format(LocalDateTime.now());
            File file = new File(backupDir, "warps_" + timestamp + ".json");

            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                GSON.toJson(array, writer);
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to export warps: " + e.getMessage());
                return null;
            }

            return file;
        });
    }

    /**
     * Import warps from a JSON file. Only inserts warps that don't already exist.
     * Returns an ImportResult with imported count, skipped warp names, and error code.
     */
    public CompletableFuture<ImportResult> importWarps(String fileName) {
        return CompletableFuture.supplyAsync(() -> {
            File file = new File(backupDir, fileName);
            if (!file.exists()) {
                return new ImportResult(-1, 0, List.of());
            }

            JsonArray array;
            try (Reader reader = new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
                array = JsonParser.parseReader(reader).getAsJsonArray();
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to parse import file: " + e.getMessage());
                return new ImportResult(-2, 0, List.of());
            }

            int imported = 0;
            List<String> skipped = new ArrayList<>();
            WarpAccessManager accessMgr = warpManager.getAccessManager();

            for (JsonElement el : array) {
                JsonObject obj = el.getAsJsonObject();
                String name = obj.get("name").getAsString();

                // Skip if warp already exists
                if (warpManager.getWarp(name) != null) {
                    skipped.add(name);
                    continue;
                }

                try {
                    UUID ownerUuid = UUID.fromString(obj.get("owner_uuid").getAsString());
                    String world = obj.get("world").getAsString();
                    double x = obj.get("x").getAsDouble();
                    double y = obj.get("y").getAsDouble();
                    double z = obj.get("z").getAsDouble();
                    float yaw = obj.has("yaw") ? obj.get("yaw").getAsFloat() : 0f;
                    float pitch = obj.has("pitch") ? obj.get("pitch").getAsFloat() : 0f;
                    boolean isPublic = !obj.has("is_public") || obj.get("is_public").getAsBoolean();

                    Material icon = Material.ENDER_PEARL;
                    if (obj.has("icon_material")) {
                        try {
                            icon = Material.valueOf(obj.get("icon_material").getAsString());
                        } catch (IllegalArgumentException ignored) {}
                    }

                    WarpCategory category = WarpCategory.PLAYER_WARPS;
                    if (obj.has("category")) {
                        WarpCategory parsed = WarpCategory.fromString(obj.get("category").getAsString());
                        if (parsed != null) category = parsed;
                    }

                    Instant createdAt = Instant.now();
                    if (obj.has("created_at")) {
                        try {
                            createdAt = Instant.parse(obj.get("created_at").getAsString());
                        } catch (Exception ignored) {}
                    }

                    int usageCount = obj.has("usage_count") ? obj.get("usage_count").getAsInt() : 0;

                    Warp warp = new Warp(0, name, ownerUuid, world, x, y, z, yaw, pitch,
                            isPublic, icon, category, createdAt, usageCount, null);

                    // Save and wait for DB auto-increment ID
                    warpManager.createWarp(warp).join();
                    imported++;

                    // Restore shared access entries (requires warp ID from save)
                    if (obj.has("shared_with") && accessMgr != null) {
                        JsonArray sharedWith = obj.getAsJsonArray("shared_with");
                        for (JsonElement accessEl : sharedWith) {
                            try {
                                UUID accessUuid = UUID.fromString(accessEl.getAsString());
                                accessMgr.grantAccess(warp.getId(), warp.getName(), accessUuid);
                            } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Skipping invalid warp entry '" + name + "': " + e.getMessage());
                }
            }

            return new ImportResult(0, imported, skipped);
        });
    }

    /**
     * List available backup files.
     */
    public String[] listBackups() {
        String[] files = backupDir.list((dir, name) -> name.endsWith(".json"));
        return files != null ? files : new String[0];
    }
}
