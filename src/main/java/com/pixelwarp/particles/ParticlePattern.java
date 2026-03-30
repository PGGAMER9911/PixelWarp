package com.pixelwarp.particles;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/**
 * A render strategy for warp particles.
 */
public interface ParticlePattern {

    /**
     * Render one animation frame for a single viewer.
     */
    void render(Player viewer, Location center, long tick, Particle particle, int detailLevel);
}
