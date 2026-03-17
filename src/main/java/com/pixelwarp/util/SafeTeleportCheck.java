package com.pixelwarp.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Checks if a warp destination is safe for teleportation.
 */
public final class SafeTeleportCheck {

    private SafeTeleportCheck() {}

    /**
     * Returns true if the location is safe:
     * - Block below feet must be solid (something to stand on)
     * - Block at feet must not be solid (room for body)
     * - Block at head must not be solid (room for head)
     */
    public static boolean isSafe(Location location) {
        World world = location.getWorld();
        if (world == null) return false;

        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();

        Block below = world.getBlockAt(x, y - 1, z);
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);

        // Below must be solid
        if (!below.getType().isSolid()) return false;

        // Feet and head must not be solid
        if (feet.getType().isSolid()) return false;
        if (head.getType().isSolid()) return false;

        // Extra: check for dangerous blocks at feet level
        Material feetMat = feet.getType();
        if (feetMat == Material.LAVA || feetMat == Material.FIRE || feetMat == Material.CAMPFIRE
                || feetMat == Material.SOUL_FIRE || feetMat == Material.SOUL_CAMPFIRE) {
            return false;
        }

        return true;
    }
}
