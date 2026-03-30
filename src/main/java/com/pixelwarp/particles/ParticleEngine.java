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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Category-aware warp particle engine with chunk indexing and distance-based LOD.
 */
public class ParticleEngine extends BukkitRunnable {

    private enum PatternType {
        RING,
        SPIRAL,
        PULSE;

        static PatternType fromConfig(String value) {
            if (value == null) {
                return null;
            }

            try {
                return PatternType.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    private record ParticleStyle(ParticlePattern pattern, Particle particle) {}
    private record NearbyWarp(Warp warp, double distanceSquared) {}

    private final WarpManager warpManager;
    private final double radiusSquared;
    private final int maxHeightDiff;
    private final int maxWarpsPerPlayer;
    private final int indexRefreshTicks;
    private final int chunkRadius;
    private final double intensityMultiplier;
    private final EnumMap<PatternType, ParticleStyle> patternStyles = new EnumMap<>(PatternType.class);
    private final EnumMap<WarpCategory, PatternType> categoryStyleMap = new EnumMap<>(WarpCategory.class);

    /** world name (lowercase) -> (chunk key -> warps in that chunk) */
    private Map<String, Map<Long, List<Warp>>> worldChunkIndex = Collections.emptyMap();
    private long animationTick;

    public ParticleEngine(WarpManager warpManager, int radius, int maxHeightDiff,
                          int maxWarpsPerPlayer, int indexRefreshTicks,
                          double intensityMultiplier,
                          Set<String> enabledPatterns,
                          Map<WarpCategory, String> categoryStyles) {
        this.warpManager = warpManager;

        int effectiveRadius = Math.max(8, radius);
        this.radiusSquared = effectiveRadius * effectiveRadius;
        this.maxHeightDiff = Math.max(8, maxHeightDiff);
        this.maxWarpsPerPlayer = Math.max(1, maxWarpsPerPlayer);
        this.indexRefreshTicks = Math.max(20, indexRefreshTicks);
        this.chunkRadius = Math.max(1, (int) Math.ceil(effectiveRadius / 16.0));
        this.intensityMultiplier = Math.max(0.25, intensityMultiplier);

        registerPatternStyles(enabledPatterns);
        resolveCategoryStyles(categoryStyles);
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

    private void registerPatternStyles(Set<String> enabledPatterns) {
        Set<String> enabled = enabledPatterns != null ? enabledPatterns : Set.of("RING", "SPIRAL", "PULSE");

        if (enabled.contains("RING")) {
            patternStyles.put(PatternType.RING,
                    new ParticleStyle(new RingParticlePattern(0.9, 1.35, 0.12), Particle.END_ROD));
        }
        if (enabled.contains("SPIRAL")) {
            patternStyles.put(PatternType.SPIRAL,
                    new ParticleStyle(new SpiralParticlePattern(0.6, 0.9, 1.6, 0.14, 0.045), Particle.ENCHANT));
        }
        if (enabled.contains("PULSE")) {
            patternStyles.put(PatternType.PULSE,
                    new ParticleStyle(new PulseParticlePattern(0.25, 0.9, 1.0, 0.16), Particle.PORTAL));
        }

        if (patternStyles.isEmpty()) {
            patternStyles.put(PatternType.PULSE,
                    new ParticleStyle(new PulseParticlePattern(0.25, 0.9, 1.0, 0.16), Particle.PORTAL));
        }
    }

    private void resolveCategoryStyles(Map<WarpCategory, String> categoryStyles) {
        PatternType fallback = patternStyles.keySet().iterator().next();

        for (WarpCategory category : WarpCategory.values()) {
            String configured = categoryStyles != null ? categoryStyles.get(category) : null;
            PatternType requested = PatternType.fromConfig(configured);
            if (requested != null && patternStyles.containsKey(requested)) {
                categoryStyleMap.put(category, requested);
            } else {
                categoryStyleMap.put(category, defaultStyleFor(category, fallback));
            }
        }
    }

    private PatternType defaultStyleFor(WarpCategory category, PatternType fallback) {
        PatternType preferred = switch (category) {
            case SPAWN -> PatternType.RING;
            case SHOPS -> PatternType.PULSE;
            case BASES, EVENTS -> PatternType.SPIRAL;
            case PLAYER_WARPS -> PatternType.PULSE;
        };

        return patternStyles.containsKey(preferred) ? preferred : fallback;
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

            int detailLevel = detailLevel(candidate.distanceSquared());
            if (skipFrame(detailLevel)) {
                continue;
            }

            PatternType patternType = categoryStyleMap.getOrDefault(candidate.warp().getCategory(), PatternType.PULSE);
            ParticleStyle style = patternStyles.get(patternType);
            if (style == null && !patternStyles.isEmpty()) {
                style = patternStyles.values().iterator().next();
            }
            if (style == null) {
                continue;
            }

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

    private int detailLevel(double distanceSquared) {
        double distance = Math.sqrt(distanceSquared);
        int base;
        if (distance <= 8) base = 6;
        else if (distance <= 14) base = 5;
        else if (distance <= 20) base = 4;
        else if (distance <= 28) base = 3;
        else base = 2;

        return Math.max(1, (int) Math.round(base * intensityMultiplier));
    }

    private boolean skipFrame(int detailLevel) {
        if (detailLevel <= 2) {
            return animationTick % 3 != 0;
        }
        if (detailLevel == 3) {
            return animationTick % 2 != 0;
        }
        return false;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }
}
