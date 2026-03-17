package com.pixelwarp.warp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.time.Instant;
import java.util.UUID;

public class Warp {

    private int id;
    private String name;
    private UUID ownerUuid;
    private String world;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private boolean isPublic;
    private Material iconMaterial;
    private WarpCategory category;
    private Instant createdAt;
    private int usageCount;
    private Instant lastUsed;

    public Warp(int id, String name, UUID ownerUuid, String world,
                double x, double y, double z, float yaw, float pitch,
                boolean isPublic, Material iconMaterial, WarpCategory category,
                Instant createdAt, int usageCount, Instant lastUsed) {
        this.id = id;
        this.name = name;
        this.ownerUuid = ownerUuid;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.isPublic = isPublic;
        this.iconMaterial = iconMaterial;
        this.category = category;
        this.createdAt = createdAt;
        this.usageCount = usageCount;
        this.lastUsed = lastUsed;
    }

    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, x, y, z, yaw, pitch);
    }

    // --- Getters & Setters ---

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public UUID getOwnerUuid() { return ownerUuid; }
    public void setOwnerUuid(UUID ownerUuid) { this.ownerUuid = ownerUuid; }

    public String getWorld() { return world; }
    public void setWorld(String world) { this.world = world; }

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }

    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    public double getZ() { return z; }
    public void setZ(double z) { this.z = z; }

    public float getYaw() { return yaw; }
    public void setYaw(float yaw) { this.yaw = yaw; }

    public float getPitch() { return pitch; }
    public void setPitch(float pitch) { this.pitch = pitch; }

    public boolean isPublic() { return isPublic; }
    public void setPublic(boolean isPublic) { this.isPublic = isPublic; }

    public Material getIconMaterial() { return iconMaterial; }
    public void setIconMaterial(Material iconMaterial) { this.iconMaterial = iconMaterial; }

    public WarpCategory getCategory() { return category; }
    public void setCategory(WarpCategory category) { this.category = category; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public int getUsageCount() { return usageCount; }
    public void setUsageCount(int usageCount) { this.usageCount = usageCount; }

    public Instant getLastUsed() { return lastUsed; }
    public void setLastUsed(Instant lastUsed) { this.lastUsed = lastUsed; }
}
