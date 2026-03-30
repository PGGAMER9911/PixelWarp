package com.pixelwarp.particles;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

public class PulseParticlePattern implements ParticlePattern {

    private final double minRadius;
    private final double maxRadius;
    private final double yOffset;
    private final double pulseSpeed;

    public PulseParticlePattern(double minRadius, double maxRadius, double yOffset, double pulseSpeed) {
        this.minRadius = minRadius;
        this.maxRadius = maxRadius;
        this.yOffset = yOffset;
        this.pulseSpeed = pulseSpeed;
    }

    @Override
    public void render(Player viewer, Location center, long tick, Particle particle, int detailLevel) {
        int points = Math.max(4, detailLevel * 2);
        double pulse = (Math.sin(tick * pulseSpeed) + 1.0) * 0.5;
        double radius = minRadius + ((maxRadius - minRadius) * pulse);
        double y = center.getY() + yOffset;

        for (int i = 0; i < points; i++) {
            double angle = 2.0 * Math.PI * i / points;
            double x = center.getX() + radius * Math.cos(angle);
            double z = center.getZ() + radius * Math.sin(angle);
            viewer.spawnParticle(particle, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
