package com.pixelwarp.preview;

import com.pixelwarp.util.MessageUtil;
import com.pixelwarp.warp.Warp;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PreviewManager implements Listener {

    private record PreviewData(Location returnLocation, GameMode originalMode, BukkitTask task) {}

    private final Plugin plugin;
    private final int durationSeconds;
    private final Map<UUID, PreviewData> previewing = new ConcurrentHashMap<>();

    public PreviewManager(Plugin plugin, int durationSeconds) {
        this.plugin = plugin;
        this.durationSeconds = durationSeconds;
    }

    public void startPreview(Player player, Warp warp) {
        UUID uuid = player.getUniqueId();

        if (previewing.containsKey(uuid)) {
            player.sendMessage(MessageUtil.error("You are already in preview mode."));
            return;
        }

        Location warpLoc = warp.toLocation();
        if (warpLoc == null) {
            player.sendMessage(MessageUtil.error("Warp world is not loaded."));
            return;
        }

        Location returnLoc = player.getLocation().clone();
        GameMode originalMode = player.getGameMode();

        // Switch to spectator and teleport
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(warpLoc);
        player.sendMessage(MessageUtil.info(
                "Previewing warp '" + warp.getName() + "' for " + durationSeconds + " seconds..."));
        player.sendMessage(Component.text("You will be returned automatically.", NamedTextColor.GRAY));

        // Schedule auto-return
        BukkitTask task = new BukkitRunnable() {
            int ticksLeft = durationSeconds * 20;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    previewing.remove(uuid);
                    return;
                }

                ticksLeft -= 20;

                if (ticksLeft <= 0) {
                    cancel();
                    endPreview(player);
                } else {
                    int secsLeft = ticksLeft / 20;
                    player.sendActionBar(Component.text(
                            "Preview: " + secsLeft + "s remaining", NamedTextColor.LIGHT_PURPLE));
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);

        previewing.put(uuid, new PreviewData(returnLoc, originalMode, task));
    }

    public void endPreview(Player player) {
        UUID uuid = player.getUniqueId();
        PreviewData data = previewing.remove(uuid);
        if (data == null) return;

        data.task().cancel();

        if (player.isOnline()) {
            player.teleport(data.returnLocation());
            player.setGameMode(data.originalMode());
            player.sendMessage(MessageUtil.success("Preview ended. Returned to your location."));
        }
    }

    public boolean isPreviewing(UUID uuid) {
        return previewing.containsKey(uuid);
    }

    /**
     * Restore all previewing players (called on shutdown).
     */
    public void restoreAll() {
        for (Map.Entry<UUID, PreviewData> entry : previewing.entrySet()) {
            entry.getValue().task().cancel();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                player.teleport(entry.getValue().returnLocation());
                player.setGameMode(entry.getValue().originalMode());
            }
        }
        previewing.clear();
    }

    // --- Event handling ---

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        PreviewData data = previewing.remove(uuid);
        if (data != null) {
            data.task().cancel();
            // Restore location and gamemode so they rejoin at the correct spot
            player.teleport(data.returnLocation());
            player.setGameMode(data.originalMode());
        }
    }
}
