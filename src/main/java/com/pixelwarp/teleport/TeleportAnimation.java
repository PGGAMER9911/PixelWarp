package com.pixelwarp.teleport;

import com.pixelwarp.util.MessageUtil;
import com.pixelwarp.util.SafeTeleportCheck;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TeleportAnimation {

    private final Plugin plugin;
    private final int countdownSeconds;
    private final boolean safeTeleportEnabled;
    private final Map<UUID, BukkitTask> activeTeleports = new ConcurrentHashMap<>();

    public TeleportAnimation(Plugin plugin, int countdownSeconds, boolean safeTeleportEnabled) {
        this.plugin = plugin;
        this.countdownSeconds = countdownSeconds;
        this.safeTeleportEnabled = safeTeleportEnabled;
    }

    public void teleport(Player player, Location destination) {
        UUID uuid = player.getUniqueId();

        if (activeTeleports.containsKey(uuid)) {
            player.sendMessage(MessageUtil.error("You are already teleporting."));
            return;
        }

        // Safe teleport check
        if (safeTeleportEnabled && !SafeTeleportCheck.isSafe(destination)) {
            player.sendMessage(MessageUtil.error("Teleport cancelled — destination is not safe!"));
            return;
        }

        if (countdownSeconds <= 0) {
            // Instant teleport if countdown is disabled
            performTeleport(player, destination);
            return;
        }

        BossBar bar = BossBar.bossBar(
                Component.text("Teleporting in " + countdownSeconds + "s...", NamedTextColor.LIGHT_PURPLE),
                1.0f,
                BossBar.Color.PURPLE,
                BossBar.Overlay.PROGRESS
        );
        player.showBossBar(bar);
        player.sendMessage(MessageUtil.info("Teleporting in " + countdownSeconds + " seconds. Don't move!"));

        Location startLoc = player.getLocation().clone();
        int totalTicks = countdownSeconds * 20;

        BukkitTask task = new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                // Check if player went offline
                if (!player.isOnline()) {
                    cleanup(uuid, bar, player);
                    cancel();
                    return;
                }

                // Check if player moved (precise detection — ~0.1 block tolerance)
                if (player.getLocation().distanceSquared(startLoc) > 0.01) {
                    player.sendMessage(MessageUtil.error("Teleport cancelled — you moved!"));
                    cleanup(uuid, bar, player);
                    cancel();
                    return;
                }

                ticks++;
                float progress = 1.0f - ((float) ticks / totalTicks);
                bar.progress(Math.max(0f, Math.min(1f, progress)));

                int remaining = (totalTicks - ticks) / 20 + 1;
                bar.name(Component.text("Teleporting in " + remaining + "s...", NamedTextColor.LIGHT_PURPLE));

                // Spawn small particle circle (6 particles rotating)
                spawnCircle(player.getLocation().add(0, 0.1, 0), ticks);

                if (ticks >= totalTicks) {
                    cleanup(uuid, bar, player);
                    cancel();
                    performTeleport(player, destination);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);

        activeTeleports.put(uuid, task);
    }

    private void performTeleport(Player player, Location destination) {
        player.teleport(destination);
        player.playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);

        // Small arrival particle burst
        destination.getWorld().spawnParticle(Particle.PORTAL, destination.clone().add(0, 1, 0),
                10, 0.3, 0.5, 0.3, 0.1);
        player.sendMessage(MessageUtil.success("Teleported!"));
    }

    private void spawnCircle(Location center, int tick) {
        int count = 6;
        double radius = 0.8;
        double angleOffset = tick * Math.PI / 15.0; // slow rotation

        for (int i = 0; i < count; i++) {
            double angle = angleOffset + (2.0 * Math.PI * i / count);
            double x = center.getX() + radius * Math.cos(angle);
            double z = center.getZ() + radius * Math.sin(angle);
            center.getWorld().spawnParticle(Particle.END_ROD, x, center.getY() + 0.5, z,
                    1, 0, 0, 0, 0);
        }
    }

    private void cleanup(UUID uuid, BossBar bar, Player player) {
        activeTeleports.remove(uuid);
        if (player.isOnline()) {
            player.hideBossBar(bar);
        }
    }

    /**
     * Cancels all active teleports (called on shutdown).
     */
    public void cancelAll() {
        for (Map.Entry<UUID, BukkitTask> entry : activeTeleports.entrySet()) {
            entry.getValue().cancel();
        }
        activeTeleports.clear();
    }

    public boolean isTeleporting(UUID uuid) {
        return activeTeleports.containsKey(uuid);
    }
}
