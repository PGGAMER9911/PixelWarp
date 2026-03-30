package com.pixelwarp.particles;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

public class SpiralParticlePattern implements ParticlePattern {

    private final double radius;
    private final double yOffset;
    private final double height;
    private final double rotationSpeed;
    private final double verticalSpeed;

    public SpiralParticlePattern(double radius, double yOffset, double height,
                                 double rotationSpeed, double verticalSpeed) {
        this.radius = radius;
        this.yOffset = yOffset;
        this.height = height;
        this.rotationSpeed = rotationSpeed;
        this.verticalSpeed = verticalSpeed;
    }

    @Override
    public void render(Player viewer, Location center, long tick, Particle particle, int detailLevel) {
        int points = Math.max(4, detailLevel * 2);
        double baseAngle = tick * rotationSpeed;
        double verticalBase = tick * verticalSpeed;

        for (int i = 0; i < points; i++) {
            double segment = (double) i / points;
            double angle = baseAngle + (2.0 * Math.PI * segment);
            double verticalPhase = (verticalBase + segment) % 1.0;
            if (verticalPhase < 0) {
                verticalPhase += 1.0;
            }

            double x = center.getX() + radius * Math.cos(angle);
            double y = center.getY() + yOffset + (verticalPhase * height);
            double z = center.getZ() + radius * Math.sin(angle);
            viewer.spawnParticle(particle, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
