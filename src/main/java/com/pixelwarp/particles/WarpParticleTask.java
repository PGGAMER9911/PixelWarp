package com.pixelwarp.particles;

import com.pixelwarp.warp.Warp;
import com.pixelwarp.warp.WarpManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Periodically spawns subtle particle markers near public warp locations.
 * Uses chunk-based spatial indexing so it never loops over every warp for every player.
 */
public class WarpParticleTask extends BukkitRunnable {

    private final WarpManager warpManager;
    private final int radiusSquared;
    private final int particleCount;

    /** Chunk key → list of warp locations in that chunk. */
    private Map<Long, List<Warp>> chunkIndex = Collections.emptyMap();

    public WarpParticleTask(WarpManager warpManager, int radius, int particleCount) {
        this.warpManager = warpManager;
        this.radiusSquared = radius * radius;
        this.particleCount = Math.min(particleCount, 5); // cap at 5
    }

    @Override
    public void run() {
        rebuildIndex();

        if (chunkIndex.isEmpty()) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            checkNearbyWarps(player);
        }
    }

    private void rebuildIndex() {
        Map<Long, List<Warp>> newIndex = new HashMap<>();
        for (Warp warp : warpManager.getPublicWarps()) {
            int cx = ((int) Math.floor(warp.getX())) >> 4;
            int cz = ((int) Math.floor(warp.getZ())) >> 4;
            newIndex.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>()).add(warp);
        }
        this.chunkIndex = newIndex;
    }

    private void checkNearbyWarps(Player player) {
        Location pLoc = player.getLocation();
        World world = pLoc.getWorld();
        int pcx = pLoc.getBlockX() >> 4;
        int pcz = pLoc.getBlockZ() >> 4;

        // Check player's chunk and 8 surrounding chunks (3x3 grid)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                long key = chunkKey(pcx + dx, pcz + dz);
                List<Warp> warps = chunkIndex.get(key);
                if (warps == null) continue;

                for (Warp warp : warps) {
                    // Only show particles in the same world
                    if (!warp.getWorld().equals(world.getName())) continue;

                    Location warpLoc = warp.toLocation();
                    if (warpLoc == null) continue;

                    double distSq = warpLoc.distanceSquared(pLoc);
                    if (distSq <= radiusSquared) {
                        spawnMarkerParticles(warpLoc);
                    }
                }
            }
        }
    }

    private void spawnMarkerParticles(Location loc) {
        World world = loc.getWorld();
        if (world == null) return;

        // Spawn a small ring of particles just above the warp point
        double y = loc.getY() + 1.5;
        double radius = 0.4;

        for (int i = 0; i < particleCount; i++) {
            double angle = 2.0 * Math.PI * i / particleCount;
            double x = loc.getX() + radius * Math.cos(angle);
            double z = loc.getZ() + radius * Math.sin(angle);
            world.spawnParticle(Particle.END_ROD, x, y, z, 1, 0, 0, 0, 0);
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
