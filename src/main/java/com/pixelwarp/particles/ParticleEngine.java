package com.pixelwarp.particles;

import com.pixelwarp.warp.Warp;
import com.pixelwarp.warp.WarpCategory;
import com.pixelwarp.warp.WarpManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Balanced particle engine with distance-based modes and chunk indexing.
 */
public class ParticleEngine extends BukkitRunnable {

    private record ParticleStyle(ParticlePattern pattern, Particle particle) {}
    private record NearbyWarp(Warp warp, double distanceSquared) {}

    private static final double FOCUS_DISTANCE = 5.0;

    private final WarpManager warpManager;
    private final double radiusSquared;
    private final double focusDistanceSquared;
    private final int maxHeightDiff;
    private final int maxWarpsPerPlayer;
    private final int maxParticlesPerWarp;
    private final boolean dynamicScaling;
    private final int indexRefreshTicks;
    private final int chunkRadius;
    private final ParticleStyle idleStyle;
    private final ParticleStyle focusStyle;

    /** world name (lowercase) -> (chunk key -> warps in that chunk) */
    private Map<String, Map<Long, List<Warp>>> worldChunkIndex = Collections.emptyMap();
    private long animationTick;

    public ParticleEngine(WarpManager warpManager, int radius, int maxHeightDiff,
                          int maxWarpsPerPlayer, int indexRefreshTicks,
                          int maxParticlesPerWarp, boolean dynamicScaling,
                          double intensityMultiplier,
                          Set<String> enabledPatterns,
                          Map<WarpCategory, String> categoryStyles) {
        this.warpManager = warpManager;

        // Hard cap to keep rendering bounded and predictable.
        int effectiveRadius = Math.max(4, Math.min(12, radius));
        this.radiusSquared = effectiveRadius * effectiveRadius;
        this.focusDistanceSquared = FOCUS_DISTANCE * FOCUS_DISTANCE;
        this.maxHeightDiff = Math.max(8, maxHeightDiff);
        this.maxWarpsPerPlayer = Math.max(1, maxWarpsPerPlayer);
        this.maxParticlesPerWarp = Math.max(1, Math.min(3, maxParticlesPerWarp));
        this.dynamicScaling = dynamicScaling;
        this.indexRefreshTicks = Math.max(20, indexRefreshTicks);
        this.chunkRadius = Math.max(1, (int) Math.ceil(effectiveRadius / 16.0));
        this.idleStyle = new ParticleStyle(new RingParticlePattern(0.9, 1.35, 0.12), Particle.END_ROD);
        this.focusStyle = new ParticleStyle(new SpiralParticlePattern(0.55, 0.85, 1.4, 0.18, 0.06), Particle.PORTAL);
    }

    @Override
    public void run() {
        animationTick++;

        if (animationTick == 1 || animationTick % indexRefreshTicks == 0) {
            rebuildIndex();
        }

        if (worldChunkIndex.isEmpty()) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            renderForPlayer(player);
        }
    }

    private void renderForPlayer(Player player) {
        World world = player.getWorld();
        Map<Long, List<Warp>> chunkMap = worldChunkIndex.get(world.getName().toLowerCase(Locale.ROOT));
        if (chunkMap == null || chunkMap.isEmpty()) {
            return;
        }

        Location playerLoc = player.getLocation();
        int centerChunkX = playerLoc.getBlockX() >> 4;
        int centerChunkZ = playerLoc.getBlockZ() >> 4;

        List<NearbyWarp> nearby = new ArrayList<>();

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                long key = chunkKey(centerChunkX + dx, centerChunkZ + dz);
                List<Warp> warps = chunkMap.get(key);
                if (warps == null) {
                    continue;
                }

                for (Warp warp : warps) {
                    double dy = Math.abs(warp.getY() - playerLoc.getY());
                    if (dy > maxHeightDiff) {
                        continue;
                    }

                    double dxPos = warp.getX() - playerLoc.getX();
                    double dzPos = warp.getZ() - playerLoc.getZ();
                    double distanceSquared = (dxPos * dxPos) + (dy * dy) + (dzPos * dzPos);
                    if (distanceSquared <= radiusSquared) {
                        nearby.add(new NearbyWarp(warp, distanceSquared));
                    }
                }
            }
        }

        if (nearby.isEmpty()) {
            return;
        }

        nearby.sort(Comparator.comparingDouble(NearbyWarp::distanceSquared));

        int rendered = 0;
        for (NearbyWarp candidate : nearby) {
            if (rendered >= maxWarpsPerPlayer) {
                break;
            }

            boolean focusMode = candidate.distanceSquared() < focusDistanceSquared;
            int detailLevel = detailLevel(candidate.distanceSquared(), focusMode);
            if (skipFrame(detailLevel, focusMode)) {
                continue;
            }

            ParticleStyle style = focusMode ? focusStyle : idleStyle;

            Location center = new Location(world,
                    candidate.warp().getX(),
                    candidate.warp().getY(),
                    candidate.warp().getZ());
            style.pattern().render(player, center, animationTick, style.particle(), detailLevel);
            rendered++;
        }
    }

    private void rebuildIndex() {
        Map<String, Map<Long, List<Warp>>> newIndex = new HashMap<>();

        for (Warp warp : warpManager.getPublicWarps()) {
            if (warp.getWorld() == null || warp.getWorld().isBlank()) {
                continue;
            }

            String worldKey = warp.getWorld().toLowerCase(Locale.ROOT);
            int chunkX = ((int) Math.floor(warp.getX())) >> 4;
            int chunkZ = ((int) Math.floor(warp.getZ())) >> 4;

            newIndex
                    .computeIfAbsent(worldKey, k -> new HashMap<>())
                    .computeIfAbsent(chunkKey(chunkX, chunkZ), k -> new ArrayList<>())
                    .add(warp);
        }

        this.worldChunkIndex = newIndex;
    }

    private int detailLevel(double distanceSquared, boolean focusMode) {
        if (!dynamicScaling) {
            if (focusMode) {
                return Math.max(2, maxParticlesPerWarp);
            }
            return Math.min(2, maxParticlesPerWarp);
        }

        if (focusMode) {
            double normalized = Math.max(0.0, Math.min(1.0, distanceSquared / focusDistanceSquared));
            double scaled = maxParticlesPerWarp - normalized;
            return Math.max(2, Math.min(maxParticlesPerWarp, (int) Math.round(scaled)));
        }

        double idleRange = Math.max(1.0, radiusSquared - focusDistanceSquared);
        double normalized = Math.max(0.0, Math.min(1.0, (distanceSquared - focusDistanceSquared) / idleRange));
        double scaled = 2.0 - normalized;
        return Math.max(1, Math.min(Math.min(2, maxParticlesPerWarp), (int) Math.round(scaled)));
    }

    private boolean skipFrame(int detailLevel, boolean focusMode) {
        if (!focusMode && detailLevel <= 1) {
            return animationTick % 2 != 0;
        }
        return false;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
