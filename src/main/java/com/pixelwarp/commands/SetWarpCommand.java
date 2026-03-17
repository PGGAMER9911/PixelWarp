package com.pixelwarp.commands;

import com.pixelwarp.WarpPlugin;
import com.pixelwarp.util.MessageUtil;
import com.pixelwarp.warp.Warp;
import com.pixelwarp.warp.WarpCategory;
import com.pixelwarp.warp.WarpManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SetWarpCommand implements CommandExecutor, TabCompleter {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{1,64}$");
    private static final long CONFIRM_TIMEOUT_MS = 30_000; // 30 seconds

    private final WarpPlugin plugin;
    private final WarpManager warpManager;

    /** Maps player UUID → pending delete entry (warp name + timestamp). */
    private final ConcurrentHashMap<UUID, PendingDelete> pendingDeletes = new ConcurrentHashMap<>();

    public SetWarpCommand(WarpPlugin plugin) {
        this.plugin = plugin;
        this.warpManager = plugin.getWarpManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MessageUtil.error("Only players can use this command."));
            return true;
        }

        if (label.equalsIgnoreCase("setwarp")) {
            return handleSetWarp(player, args);
        } else if (label.equalsIgnoreCase("delwarp")) {
            return handleDelWarp(player, args);
        }

        return true;
    }

    private boolean handleSetWarp(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(MessageUtil.info("Usage: /setwarp <name> [category] [public|private]"));
            return true;
        }

        String name = args[0];

        if (!NAME_PATTERN.matcher(name).matches()) {
            player.sendMessage(MessageUtil.error(
                    "Invalid name. Use letters, numbers, and underscores only (max 64 chars)."));
            return true;
        }

        if (warpManager.getWarp(name) != null) {
            player.sendMessage(MessageUtil.error("A warp with that name already exists."));
            return true;
        }

        // Parse category (default: PLAYER_WARPS)
        WarpCategory category = WarpCategory.PLAYER_WARPS;
        if (args.length >= 2) {
            category = WarpCategory.fromString(args[1]);
            if (category == null) {
                String valid = Arrays.stream(WarpCategory.values())
                        .map(Enum::name)
                        .collect(Collectors.joining(", "));
                player.sendMessage(MessageUtil.error("Invalid category. Valid: " + valid));
                return true;
            }
        }

        // Parse visibility (default: public)
        boolean isPublic = true;
        if (args.length >= 3 && args[2].equalsIgnoreCase("private")) {
            isPublic = false;
        }

        Location loc = player.getLocation();
        Material icon = category.getDefaultIcon();

        Warp warp = new Warp(
                0,
                name,
                player.getUniqueId(),
                loc.getWorld().getName(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch(),
                isPublic,
                icon,
                category,
                Instant.now(),
                0,
                null
        );

        warpManager.createWarp(warp);

        String visibility = isPublic ? "public" : "private";
        player.sendMessage(MessageUtil.success(
                "Warp '" + name + "' created! (" + category.getDisplayName() + ", " + visibility + ")"));
        return true;
    }

    private boolean handleDelWarp(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(MessageUtil.info("Usage: /delwarp <name>"));
            return true;
        }

        String name = args[0];
        boolean isConfirm = args.length >= 2 && args[1].equalsIgnoreCase("confirm");

        Warp warp = warpManager.getWarp(name);

        if (warp == null) {
            pendingDeletes.remove(player.getUniqueId());
            player.sendMessage(MessageUtil.error("Warp not found: " + name));
            return true;
        }

        boolean isOwner = plugin.isServerOwner(player.getUniqueId());
        boolean isWarpOwner = warp.getOwnerUuid().equals(player.getUniqueId());

        // Only warp owner or server owner can delete
        if (!isOwner && !isWarpOwner) {
            player.sendMessage(MessageUtil.error("Only the warp owner can delete this warp."));
            return true;
        }

        // Check for confirmation
        if (!isConfirm) {
            // Record pending delete
            pendingDeletes.put(player.getUniqueId(), new PendingDelete(name, System.currentTimeMillis()));

            player.sendMessage(Component.empty());
            player.sendMessage(MessageUtil.error("Are you sure you want to delete warp '" + name + "'?"));
            player.sendMessage(Component.text("  ")
                    .append(Component.text("[CONFIRM DELETE]", NamedTextColor.RED, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/delwarp " + name + " confirm")))
                    .append(Component.text("  "))
                    .append(Component.text("[CANCEL]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand("/delwarp_cancel"))));
            player.sendMessage(MessageUtil.info("This expires in 30 seconds. Type: /delwarp " + name + " confirm"));
            player.sendMessage(Component.empty());

            // Schedule expiry
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                PendingDelete pending = pendingDeletes.get(player.getUniqueId());
                if (pending != null && pending.warpName.equalsIgnoreCase(name)) {
                    pendingDeletes.remove(player.getUniqueId());
                    if (player.isOnline()) {
                        player.sendMessage(MessageUtil.info("Delete confirmation for '" + name + "' expired."));
                    }
                }
            }, 600L); // 30 seconds = 600 ticks

            return true;
        }

        // Confirm flow
        PendingDelete pending = pendingDeletes.remove(player.getUniqueId());
        if (pending == null || !pending.warpName.equalsIgnoreCase(name)) {
            player.sendMessage(MessageUtil.error("No pending delete for '" + name + "'. Use /delwarp " + name + " first."));
            return true;
        }

        if (System.currentTimeMillis() - pending.timestamp > CONFIRM_TIMEOUT_MS) {
            player.sendMessage(MessageUtil.error("Delete confirmation expired. Use /delwarp " + name + " again."));
            return true;
        }

        warpManager.deleteWarp(name);
        player.sendMessage(MessageUtil.success("Warp '" + warp.getName() + "' deleted."));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return Collections.emptyList();

        if (label.equalsIgnoreCase("setwarp")) {
            if (args.length == 2) {
                return filter(
                        Arrays.stream(WarpCategory.values()).map(Enum::name).collect(Collectors.toList()),
                        args[1]
                );
            }
            if (args.length == 3) {
                return filter(List.of("public", "private"), args[2]);
            }
            return Collections.emptyList();
        }

        if (label.equalsIgnoreCase("delwarp")) {
            if (args.length == 1) {
                return filter(getDeletableWarpNames(player), args[0]);
            }
            if (args.length == 2) {
                return filter(List.of("confirm"), args[1]);
            }
        }

        return Collections.emptyList();
    }

    private List<String> getDeletableWarpNames(Player player) {
        boolean isOwner = plugin.isServerOwner(player.getUniqueId());
        if (isOwner) {
            return warpManager.getVisibleWarpNames(player);
        }
        // Players can delete their own warps (both public and private)
        return warpManager.getVisibleWarps(player.getUniqueId()).stream()
                .filter(w -> w.getOwnerUuid().equals(player.getUniqueId()))
                .map(Warp::getName)
                .collect(Collectors.toList());
    }

    private List<String> filter(List<String> options, String prefix) {
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }

    /** Simple holder for pending delete state. */
    private record PendingDelete(String warpName, long timestamp) {}
}
