package com.pixelwarp.commands;

import com.pixelwarp.WarpPlugin;
import com.pixelwarp.access.WarpAccessManager;
import com.pixelwarp.backup.WarpBackupManager;
import com.pixelwarp.gui.WarpMenu;
import com.pixelwarp.preview.PreviewManager;
import com.pixelwarp.reload.ReloadManager;
import com.pixelwarp.teleport.TeleportAnimation;
import com.pixelwarp.util.MessageUtil;
import com.pixelwarp.warp.Warp;
import com.pixelwarp.warp.WarpCategory;
import com.pixelwarp.warp.WarpManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WarpCommand implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{1,64}$");

    private final WarpPlugin plugin;
    private final WarpManager warpManager;
    private final WarpBackupManager backupManager;
    private final TeleportAnimation teleportAnimation;
    private final PreviewManager previewManager;

    public WarpCommand(WarpPlugin plugin) {
        this.plugin = plugin;
        this.warpManager = plugin.getWarpManager();
        this.backupManager = plugin.getBackupManager();
        this.teleportAnimation = plugin.getTeleportAnimation();
        this.previewManager = plugin.getPreviewManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String base = label.toLowerCase(Locale.ROOT);

        if (plugin.isReloading()) {
            sender.sendMessage(MessageUtil.info("PixelWarp is currently reloading. Please try again in a moment."));
            return true;
        }

        // /warps → open GUI
        if (base.equals("warps")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(MessageUtil.error("Only players can use this command."));
                return true;
            }
            WarpMenu.open(player, warpManager, null, null, 0);
            return true;
        }

        if (base.equals("pwarp")) {
            if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
                sender.sendMessage(MessageUtil.info("Usage: /pwarp reload [config|storage|all]"));
                return true;
            }
            return handleReload(sender, args);
        }

        // /warp with no args → usage
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("help")) {
            sendUsage(sender);
            return true;
        }
        if (sub.equals("version")) {
            return handleVersion(sender);
        }
        if (sub.equals("info")) {
            return handleInfo(sender);
        }

        if (sub.equals("debug")) {
            return handleDebug(sender);
        }
        if (sub.equals("reload")) {
            return handleReload(sender, args);
        }
        if (sub.equals("backup")) {
            return handleBackup(sender, args);
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageUtil.error("Only players can use this command."));
            return true;
        }

        switch (sub) {
            case "preview" -> {
                if (args.length < 2) {
                    player.sendMessage(MessageUtil.info("Usage: /warp preview <name>"));
                    return true;
                }
                handlePreview(player, args[1]);
            }
            case "stats" -> {
                if (args.length < 2) {
                    player.sendMessage(MessageUtil.info("Usage: /warp stats <name>"));
                    return true;
                }
                handleStats(player, args[1]);
            }
            case "top" -> handleTop(player);
            case "edit" -> handleEdit(player, args);
            case "rename" -> handleRename(player, args);
            case "access" -> handleAccess(player, args);
            case "export" -> handleExport(player);
            case "import" -> handleImport(player, args);
            default -> handleTeleport(player, args[0]);
        }

        return true;
    }

    // =========================================================================
    // Teleport
    // =========================================================================

    private void handleTeleport(Player player, String name) {
        Warp warp = warpManager.getWarp(name);
        if (warp == null) {
            player.sendMessage(MessageUtil.error("Warp not found: " + name));
            return;
        }

        if (!warpManager.canAccess(warp, player.getUniqueId())) {
            player.sendMessage(MessageUtil.error("That warp is private."));
            return;
        }

        Location loc = warp.toLocation();
        if (loc == null) {
            player.sendMessage(MessageUtil.error("Warp world is not loaded."));
            return;
        }

        warpManager.incrementUsage(warp.getName());
        teleportAnimation.teleport(player, loc);
    }

    // =========================================================================
    // Preview
    // =========================================================================

    private void handlePreview(Player player, String name) {
        Warp warp = warpManager.getWarp(name);
        if (warp == null) {
            player.sendMessage(MessageUtil.error("Warp not found: " + name));
            return;
        }

        if (!warpManager.canAccess(warp, player.getUniqueId())) {
            player.sendMessage(MessageUtil.error("That warp is private."));
            return;
        }

        previewManager.startPreview(player, warp);
    }

    // =========================================================================
    // Stats
    // =========================================================================

    private void handleStats(Player player, String name) {
        Warp warp = warpManager.getWarp(name);
        if (warp == null) {
            player.sendMessage(MessageUtil.error("Warp not found: " + name));
            return;
        }

        String ownerName = Bukkit.getOfflinePlayer(warp.getOwnerUuid()).getName();
        if (ownerName == null) ownerName = "Unknown";

        String created = DATE_FMT.format(warp.getCreatedAt().atZone(ZoneId.systemDefault()));
        String lastUsed = warp.getLastUsed() != null
                ? DATETIME_FMT.format(warp.getLastUsed().atZone(ZoneId.systemDefault()))
                : "Never";

        player.sendMessage(Component.empty());
        player.sendMessage(MessageUtil.header("Warp: " + warp.getName()));
        player.sendMessage(MessageUtil.label("Owner: ", ownerName));
        player.sendMessage(MessageUtil.label("Category: ", warp.getCategory().getDisplayName()));
        player.sendMessage(MessageUtil.label("Location: ",
                String.format("%.0f, %.0f, %.0f", warp.getX(), warp.getY(), warp.getZ())));
        player.sendMessage(MessageUtil.label("Public: ",
                Component.text(warp.isPublic() ? "Yes" : "No",
                        warp.isPublic() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        player.sendMessage(MessageUtil.label("Created: ", created));
        player.sendMessage(MessageUtil.label("Times Used: ", String.valueOf(warp.getUsageCount())));
        player.sendMessage(MessageUtil.label("Last Used: ", lastUsed));
        player.sendMessage(Component.empty());
    }

    // =========================================================================
    // Top
    // =========================================================================

    private void handleTop(Player player) {
        List<Warp> top = warpManager.getTopWarps(5);

        player.sendMessage(Component.empty());
        player.sendMessage(MessageUtil.header("Top Warps"));

        if (top.isEmpty()) {
            player.sendMessage(MessageUtil.info("No warps found."));
        } else {
            for (int i = 0; i < top.size(); i++) {
                Warp w = top.get(i);
                player.sendMessage(Component.text(" " + (i + 1) + ". ", NamedTextColor.GOLD)
                        .append(Component.text(w.getName(), NamedTextColor.WHITE))
                        .append(Component.text(" - " + w.getUsageCount() + " uses", NamedTextColor.GRAY)));
            }
        }
        player.sendMessage(Component.empty());
    }

    // =========================================================================
    // Edit
    // =========================================================================

    private void handleEdit(Player player, String[] args) {
        if (plugin.isReadOnlyMode()) {
            player.sendMessage(MessageUtil.error("PixelWarp is in read-only failsafe mode: " + plugin.getReadOnlyReason()));
            return;
        }

        // /warp edit <name> <location|category|public|private> [value]
        if (args.length < 3) {
            player.sendMessage(MessageUtil.info("Usage: /warp edit <name> <location|category|public|private>"));
            return;
        }

        String warpName = args[1];
        String action = args[2].toLowerCase();

        Warp warp = warpManager.getWarp(warpName);
        if (warp == null) {
            player.sendMessage(MessageUtil.error("Warp not found: " + warpName));
            return;
        }

        if (!canManage(player, warp)) {
            player.sendMessage(MessageUtil.error("Only the warp owner, a warp admin, or a server owner can edit this warp."));
            return;
        }

        switch (action) {
            case "location" -> {
                Location loc = player.getLocation();
                warpManager.updateLocation(warp, loc);
                player.sendMessage(MessageUtil.success("Warp '" + warp.getName() + "' location updated."));
            }
            case "category" -> {
                if (args.length < 4) {
                    player.sendMessage(MessageUtil.info("Usage: /warp edit <name> category <category>"));
                    return;
                }
                WarpCategory cat = WarpCategory.fromString(args[3]);
                if (cat == null) {
                    String valid = Arrays.stream(WarpCategory.values())
                            .map(Enum::name).collect(Collectors.joining(", "));
                    player.sendMessage(MessageUtil.error("Invalid category. Valid: " + valid));
                    return;
                }
                warpManager.updateCategory(warp, cat);
                player.sendMessage(MessageUtil.success(
                        "Warp '" + warp.getName() + "' category set to " + cat.getDisplayName() + "."));
            }
            case "public" -> {
                warpManager.updateVisibility(warp, true);
                player.sendMessage(MessageUtil.success("Warp '" + warp.getName() + "' is now public."));
            }
            case "private" -> {
                warpManager.updateVisibility(warp, false);
                player.sendMessage(MessageUtil.success("Warp '" + warp.getName() + "' is now private."));
            }
            default -> player.sendMessage(
                    MessageUtil.info("Usage: /warp edit <name> <location|category|public|private>"));
        }
    }

    // =========================================================================
    // Rename
    // =========================================================================

    private void handleRename(Player player, String[] args) {
        if (plugin.isReadOnlyMode()) {
            player.sendMessage(MessageUtil.error("PixelWarp is in read-only failsafe mode: " + plugin.getReadOnlyReason()));
            return;
        }

        // /warp rename <old> <new>
        if (args.length < 3) {
            player.sendMessage(MessageUtil.info("Usage: /warp rename <old> <new>"));
            return;
        }

        String oldName = args[1];
        String newName = args[2];

        Warp warp = warpManager.getWarp(oldName);
        if (warp == null) {
            player.sendMessage(MessageUtil.error("Warp not found: " + oldName));
            return;
        }

        if (!canManage(player, warp)) {
            player.sendMessage(MessageUtil.error("Only the warp owner, a warp admin, or a server owner can rename this warp."));
            return;
        }

        if (!NAME_PATTERN.matcher(newName).matches()) {
            player.sendMessage(MessageUtil.error(
                    "Invalid name. Use letters, numbers, and underscores only (max 64 chars)."));
            return;
        }

        if (warpManager.getWarp(newName) != null) {
            player.sendMessage(MessageUtil.error("A warp named '" + newName + "' already exists."));
            return;
        }

        warpManager.renameWarp(oldName, newName);
        player.sendMessage(MessageUtil.success(
                "Warp renamed from '" + oldName + "' to '" + newName + "'."));
    }

    // =========================================================================
    // Access (sharing private warps)
    // =========================================================================

    private void handleAccess(Player player, String[] args) {
        // /warp access add <warp> <player>
        // /warp access remove <warp> <player>
        // /warp access list <warp>
        if (args.length < 3) {
            player.sendMessage(MessageUtil.info("Usage: /warp access <add|remove|list> <warp> [player]"));
            return;
        }

        String action = args[1].toLowerCase();
        String warpName = args[2];

        if (plugin.isReadOnlyMode() && !action.equals("list")) {
            player.sendMessage(MessageUtil.error("PixelWarp is in read-only failsafe mode: " + plugin.getReadOnlyReason()));
            return;
        }

        Warp warp = warpManager.getWarp(warpName);
        if (warp == null) {
            player.sendMessage(MessageUtil.error("Warp not found: " + warpName));
            return;
        }

        WarpAccessManager accessManager = plugin.getAccessManager();
        if (accessManager == null) {
            player.sendMessage(MessageUtil.error("Access manager is not ready yet."));
            return;
        }

        if (!canManage(player, warp)) {
            player.sendMessage(MessageUtil.error("Only the warp owner, a warp admin, or a server owner can manage access."));
            return;
        }

        switch (action) {
            case "add" -> {
                if (args.length < 4) {
                    player.sendMessage(MessageUtil.info("Usage: /warp access add <warp> <player>"));
                    return;
                }
                OfflinePlayer target = resolvePlayer(args[3]);
                if (!target.hasPlayedBefore() && !target.isOnline()) {
                    player.sendMessage(MessageUtil.error("Player '" + args[3] + "' has never joined this server."));
                    return;
                }
                if (target.getUniqueId().equals(warp.getOwnerUuid())) {
                    player.sendMessage(MessageUtil.error("The owner already has access."));
                    return;
                }
                accessManager.grantAccess(warp.getId(), warp.getName(), target.getUniqueId());
                String targetName = target.getName() != null ? target.getName() : args[3];
                player.sendMessage(MessageUtil.success(
                        targetName + " now has access to warp '" + warp.getName() + "'."));
            }
            case "remove" -> {
                if (args.length < 4) {
                    player.sendMessage(MessageUtil.info("Usage: /warp access remove <warp> <player>"));
                    return;
                }
                OfflinePlayer target = resolvePlayer(args[3]);
                if (!target.hasPlayedBefore() && !target.isOnline()) {
                    player.sendMessage(MessageUtil.error("Player '" + args[3] + "' has never joined this server."));
                    return;
                }
                accessManager.revokeAccess(warp.getId(), target.getUniqueId());
                String targetName = target.getName() != null ? target.getName() : args[3];
                player.sendMessage(MessageUtil.success(
                        targetName + "'s access to warp '" + warp.getName() + "' has been revoked."));
            }
            case "list" -> {
                Set<UUID> uuids = accessManager.getAccessList(warp.getId());
                player.sendMessage(Component.empty());
                player.sendMessage(MessageUtil.header("Access: " + warp.getName()));
                if (uuids.isEmpty()) {
                    player.sendMessage(MessageUtil.info("No players have been granted access."));
                } else {
                    for (UUID uuid : uuids) {
                        String name = Bukkit.getOfflinePlayer(uuid).getName();
                        player.sendMessage(Component.text(" - ", NamedTextColor.GRAY)
                                .append(Component.text(name != null ? name : uuid.toString(),
                                        NamedTextColor.WHITE)));
                    }
                }
                player.sendMessage(Component.empty());
            }
            default -> player.sendMessage(
                    MessageUtil.info("Usage: /warp access <add|remove|list> <warp> [player]"));
        }
    }

    // =========================================================================
    // Export
    // =========================================================================

    private void handleExport(Player player) {
        if (!plugin.isServerOwner(player.getUniqueId())) {
            player.sendMessage(MessageUtil.error("Only server owners can export warps."));
            return;
        }

        player.sendMessage(MessageUtil.info("Exporting warps..."));
        backupManager.exportWarps(player).thenAccept(file -> {
            if (file != null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(MessageUtil.success("Exported to: " + file.getName())));
            } else {
                Bukkit.getScheduler().runTask(plugin, () ->
                        player.sendMessage(MessageUtil.error("Export failed. Check console.")));
            }
        });
    }

    // =========================================================================
    // Import
    // =========================================================================

    private void handleImport(Player player, String[] args) {
        if (plugin.isReadOnlyMode()) {
            player.sendMessage(MessageUtil.error("PixelWarp is in read-only failsafe mode: " + plugin.getReadOnlyReason()));
            return;
        }

        if (!plugin.isServerOwner(player.getUniqueId())) {
            player.sendMessage(MessageUtil.error("Only server owners can import warps."));
            return;
        }

        if (args.length < 2) {
            // List available backups
            String[] backups = backupManager.listBackups();
            if (backups.length == 0) {
                player.sendMessage(MessageUtil.info("No backup files found."));
            } else {
                player.sendMessage(MessageUtil.header("Available Backups"));
                for (String f : backups) {
                    player.sendMessage(Component.text(" - " + f, NamedTextColor.GRAY));
                }
                player.sendMessage(MessageUtil.info("Usage: /warp import <filename>"));
            }
            return;
        }

        String fileName = args[1];
        player.sendMessage(MessageUtil.info("Importing from " + fileName + "..."));

        backupManager.importWarps(fileName).thenAccept(result -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (result.errorCode() == -1) {
                    player.sendMessage(MessageUtil.error("File not found: " + fileName));
                } else if (result.errorCode() == -2) {
                    player.sendMessage(MessageUtil.error("Failed to parse file. Check console."));
                } else {
                    player.sendMessage(MessageUtil.success("Imported " + result.imported() + " new warp(s)."));
                    if (!result.skipped().isEmpty()) {
                        player.sendMessage(MessageUtil.info(
                                "Skipped " + result.skipped().size() + " warp(s) (already exist):"));
                        for (String s : result.skipped()) {
                            player.sendMessage(Component.text("  - " + s, NamedTextColor.GRAY));
                        }
                    }
                }
            });
        });
    }

    // =========================================================================
    // Usage
    // =========================================================================

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(MessageUtil.header("Warp Commands"));
        sender.sendMessage(MessageUtil.info("/warp help - Show command guide"));
        sender.sendMessage(MessageUtil.info("/warp info - Plugin info and runtime stats"));
        sender.sendMessage(MessageUtil.info("/warp version - Show plugin version"));
        sender.sendMessage(MessageUtil.info("/warp <name> - Teleport to warp"));
        sender.sendMessage(MessageUtil.info("/warp preview <name> - Preview warp"));
        sender.sendMessage(MessageUtil.info("/warp stats <name> - Warp statistics"));
        sender.sendMessage(MessageUtil.info("/warp top - Top 5 warps"));
        sender.sendMessage(MessageUtil.info("/warp edit <name> <...> - Edit warp"));
        sender.sendMessage(MessageUtil.info("/warp rename <old> <new> - Rename warp"));
        sender.sendMessage(MessageUtil.info("/warp access <add|remove|list> <warp> - Manage access"));
        sender.sendMessage(MessageUtil.info("/warp export - Export warps to JSON"));
        sender.sendMessage(MessageUtil.info("/warp import [file] - Import warps from JSON"));
        sender.sendMessage(MessageUtil.info("/warp debug - Show plugin health report"));
        sender.sendMessage(MessageUtil.info("/warp reload [config|storage|all] - Safe runtime reload"));
        sender.sendMessage(MessageUtil.info("/warp backup confirm - Create immediate file storage backup"));
        sender.sendMessage(Component.empty());
    }

    private boolean handleVersion(CommandSender sender) {
        sender.sendMessage(MessageUtil.success("PixelWarp version " + plugin.getDescription().getVersion()));
        return true;
    }

    private boolean handleInfo(CommandSender sender) {
        sender.sendMessage(Component.empty());
        sender.sendMessage(MessageUtil.header("PixelWarp Info"));
        sender.sendMessage(MessageUtil.label("Plugin: ", "PixelWarp"));
        sender.sendMessage(MessageUtil.label("Version: ", plugin.getDescription().getVersion()));
        sender.sendMessage(MessageUtil.label("Author: ", "Parthiv Gamit"));
        sender.sendMessage(MessageUtil.label("Storage: ", plugin.getStorageMode()));
        sender.sendMessage(MessageUtil.label("Total Warps: ", String.valueOf(warpManager.getWarpCount())));
        sender.sendMessage(Component.empty());
        return true;
    }

    private boolean handleDebug(CommandSender sender) {
        if (!isAdminSender(sender)) {
            sender.sendMessage(MessageUtil.error("You do not have permission to run debug."));
            return true;
        }

        for (String line : plugin.getHealthReportLines()) {
            sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
        return true;
    }

    private boolean handleReload(CommandSender sender, String[] args) {
        if (!isAdminSender(sender)) {
            sender.sendMessage(MessageUtil.error("You do not have permission to reload PixelWarp."));
            return true;
        }

        ReloadManager.ReloadScope scope = ReloadManager.ReloadScope.ALL;
        if (args.length >= 2) {
            scope = ReloadManager.ReloadScope.fromArg(args[1]);
            if (scope == null) {
                sender.sendMessage(MessageUtil.info("Usage: /warp reload [config|storage|all]"));
                return true;
            }
        }

        sender.sendMessage(MessageUtil.info("Starting reload: " + scope.name()));
        plugin.reloadRuntime(scope).thenAccept(result ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.success()) {
                        sender.sendMessage(MessageUtil.success(result.message()));
                    } else {
                        sender.sendMessage(MessageUtil.error(result.message()));
                    }
                })
        );
        return true;
    }

    private boolean handleBackup(CommandSender sender, String[] args) {
        if (!isAdminSender(sender)) {
            sender.sendMessage(MessageUtil.error("You do not have permission to run backup."));
            return true;
        }

        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            sender.sendMessage(MessageUtil.info("Usage: /warp backup confirm"));
            return true;
        }

        plugin.createStorageBackup().thenAccept(success ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (success) {
                        sender.sendMessage(MessageUtil.success("Storage backup created."));
                    } else {
                        sender.sendMessage(MessageUtil.error("Backup is only available in FILE mode or backup creation failed."));
                    }
                })
        );
        return true;
    }

    private boolean isAdminSender(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        UUID uuid = player.getUniqueId();
        return plugin.isAdmin(uuid) || plugin.isServerOwner(uuid);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean canManage(Player player, Warp warp) {
        return warp.getOwnerUuid().equals(player.getUniqueId())
                || plugin.isAdmin(player.getUniqueId())
                || plugin.isServerOwner(player.getUniqueId());
    }

    private OfflinePlayer resolvePlayer(String name) {
        return Bukkit.getOfflinePlayer(name);
    }

    // =========================================================================
    // Tab Completion
    // =========================================================================

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        String base = label.toLowerCase(Locale.ROOT);
        if (base.equals("warps")) return Collections.emptyList();

        if (base.equals("pwarp")) {
            if (args.length == 1) {
                return filter(List.of("reload"), args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
                return filter(List.of("config", "storage", "all"), args[1]);
            }
            return Collections.emptyList();
        }

        if (!(sender instanceof Player player)) {
            if (args.length == 1) {
                return filter(List.of("help", "info", "version", "debug", "reload", "backup"), args[0]);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
                return filter(List.of("config", "storage", "all"), args[1]);
            }
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.addAll(List.of("help", "info", "version", "preview", "stats", "top", "edit", "rename", "access", "export", "import",
                    "debug", "reload", "backup"));
            completions.addAll(warpManager.getVisibleWarpNames(player));
            return filter(completions, args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            return switch (sub) {
                case "preview", "stats" -> filter(warpManager.getVisibleWarpNames(player), args[1]);
                case "edit", "rename" -> filter(getManageableWarpNames(player), args[1]);
                case "access" -> filter(List.of("add", "remove", "list"), args[1]);
                case "import" -> filter(List.of(backupManager.listBackups()), args[1]);
                case "reload" -> filter(List.of("config", "storage", "all"), args[1]);
                case "backup" -> filter(List.of("confirm"), args[1]);
                default -> Collections.emptyList();
            };
        }

        if (args.length == 3) {
            String sub = args[0].toLowerCase();
            return switch (sub) {
                case "edit" -> filter(List.of("location", "category", "public", "private"), args[2]);
                case "access" -> filter(getManageableWarpNames(player), args[2]);
                default -> Collections.emptyList();
            };
        }

        if (args.length == 4) {
            String sub = args[0].toLowerCase();
            if (sub.equals("edit") && args[2].equalsIgnoreCase("category")) {
                return filter(
                        Arrays.stream(WarpCategory.values()).map(Enum::name).collect(Collectors.toList()),
                        args[3]);
            }
            if (sub.equals("access") && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
                return filter(
                        Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList()),
                        args[3]);
            }
        }

        return Collections.emptyList();
    }

    private List<String> getManageableWarpNames(Player player) {
        if (plugin.isAdmin(player.getUniqueId()) || plugin.isServerOwner(player.getUniqueId())) {
            return warpManager.getAllWarps().stream()
                    .map(Warp::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }
        return warpManager.getAllWarps().stream()
                .filter(w -> w.getOwnerUuid().equals(player.getUniqueId()))
                .map(Warp::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    private List<String> filter(List<String> options, String prefix) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }
}
