package com.pixelwarp.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the anvil-based search GUI for warps.
 * Players type a search term in the anvil rename field and click the result to search.
 */
public class SearchMenu {

    private static final Set<UUID> SEARCHING = ConcurrentHashMap.newKeySet();

    private SearchMenu() {}

    public static void open(Player player) {
        SEARCHING.add(player.getUniqueId());

        Inventory anvil = Bukkit.createInventory(null, InventoryType.ANVIL,
                Component.text("Search Warps", NamedTextColor.DARK_PURPLE));

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Type name...", NamedTextColor.GRAY));
        item.setItemMeta(meta);
        anvil.setItem(0, item);

        player.openInventory(anvil);
    }

    public static boolean isSearching(UUID uuid) {
        return SEARCHING.contains(uuid);
    }

    public static void stopSearching(UUID uuid) {
        SEARCHING.remove(uuid);
    }
}
