package com.pixelwarp.gui;

import com.pixelwarp.warp.WarpCategory;
import com.pixelwarp.warp.WarpManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

public class CategoryMenu implements InventoryHolder {

    private static final int SIZE = 27;

    private final Player player;
    private final WarpManager warpManager;
    private final Inventory inventory;
    private final Map<Integer, WarpCategory> slotMapping = new HashMap<>();

    public CategoryMenu(Player player, WarpManager warpManager) {
        this.player = player;
        this.warpManager = warpManager;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Component.text("Select Category", NamedTextColor.DARK_PURPLE));
        build();
    }

    private void build() {
        // All warps
        inventory.setItem(10, createItem(Material.NETHER_STAR, "All Warps", NamedTextColor.YELLOW));

        // Individual categories
        inventory.setItem(11, createItem(Material.COMPASS, "Spawn", NamedTextColor.GREEN));
        slotMapping.put(11, WarpCategory.SPAWN);

        inventory.setItem(12, createItem(Material.EMERALD, "Shops", NamedTextColor.AQUA));
        slotMapping.put(12, WarpCategory.SHOPS);

        inventory.setItem(13, createItem(Material.OAK_DOOR, "Bases", NamedTextColor.GOLD));
        slotMapping.put(13, WarpCategory.BASES);

        inventory.setItem(14, createItem(Material.FIREWORK_ROCKET, "Events", NamedTextColor.LIGHT_PURPLE));
        slotMapping.put(14, WarpCategory.EVENTS);

        inventory.setItem(15, createItem(Material.ENDER_PEARL, "Player Warps", NamedTextColor.WHITE));
        slotMapping.put(15, WarpCategory.PLAYER_WARPS);

        // Back button
        inventory.setItem(22, createItem(Material.ARROW, "Back", NamedTextColor.RED));
    }

    private ItemStack createItem(Material material, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Returns the category for the given slot, or null for "All" (slot 10) / unassigned slots.
     */
    public WarpCategory getCategory(int slot) {
        return slotMapping.get(slot);
    }

    public boolean isAllSlot(int slot) {
        return slot == 10;
    }

    public boolean isBackSlot(int slot) {
        return slot == 22;
    }

    public boolean isCategorySlot(int slot) {
        return slot == 10 || slotMapping.containsKey(slot);
    }

    public void open() {
        player.openInventory(inventory);
    }

    public Player getPlayer() { return player; }
    public WarpManager getWarpManager() { return warpManager; }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
