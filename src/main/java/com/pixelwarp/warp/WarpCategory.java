package com.pixelwarp.warp;

import org.bukkit.Material;

public enum WarpCategory {

    SPAWN("Spawn", Material.COMPASS),
    SHOPS("Shops", Material.EMERALD),
    BASES("Bases", Material.OAK_DOOR),
    EVENTS("Events", Material.FIREWORK_ROCKET),
    PLAYER_WARPS("Player Warps", Material.ENDER_PEARL);

    private final String displayName;
    private final Material defaultIcon;

    WarpCategory(String displayName, Material defaultIcon) {
        this.displayName = displayName;
        this.defaultIcon = defaultIcon;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Material getDefaultIcon() {
        return defaultIcon;
    }

    public static WarpCategory fromString(String value) {
        if (value == null) return PLAYER_WARPS;
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
