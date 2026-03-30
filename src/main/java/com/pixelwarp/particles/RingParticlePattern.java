package com.pixelwarp.particles;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

public class RingParticlePattern implements ParticlePattern {

    private final double radius;
    private final double yOffset;
    private final double rotationSpeed;

    public RingParticlePattern(double radius, double yOffset, double rotationSpeed) {
        this.radius = radius;
        this.yOffset = yOffset;
        this.rotationSpeed = rotationSpeed;
    }

    @Override
    public void render(Player viewer, Location center, long tick, Particle particle, int detailLevel) {
        int points = Math.max(1, detailLevel);
        double angleOffset = tick * rotationSpeed;
        double y = center.getY() + yOffset;

        for (int i = 0; i < points; i++) {
            double angle = angleOffset + (2.0 * Math.PI * i / points);
            double x = center.getX() + radius * Math.cos(angle);
            double z = center.getZ() + radius * Math.sin(angle);
            viewer.spawnParticle(particle, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
