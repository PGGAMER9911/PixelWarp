package com.pixelwarp.gui;

import com.pixelwarp.WarpPlugin;
import com.pixelwarp.preview.PreviewManager;
import com.pixelwarp.teleport.TeleportAnimation;
import com.pixelwarp.util.MessageUtil;
import com.pixelwarp.warp.Warp;
import com.pixelwarp.warp.WarpCategory;
import com.pixelwarp.warp.WarpManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class MenuListener implements Listener {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final WarpPlugin plugin;
    private final WarpManager warpManager;
    private final TeleportAnimation teleportAnimation;
    private final PreviewManager previewManager;

    public MenuListener(WarpPlugin plugin) {
        this.plugin = plugin;
        this.warpManager = plugin.getWarpManager();
        this.teleportAnimation = plugin.getTeleportAnimation();
        this.previewManager = plugin.getPreviewManager();
    }

    // =========================================================================
    // Inventory Click
    // =========================================================================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // --- Anvil Search ---
        if (event.getInventory().getType() == InventoryType.ANVIL
                && SearchMenu.isSearching(player.getUniqueId())) {
            handleAnvilClick(event, player);
            return;
        }

        // --- Warp Menu ---
        if (event.getInventory().getHolder() instanceof WarpMenu menu) {
            event.setCancelled(true);
            handleWarpMenuClick(event, player, menu);
            return;
        }

        // --- Category Menu ---
        if (event.getInventory().getHolder() instanceof CategoryMenu catMenu) {
            event.setCancelled(true);
            handleCategoryClick(event, player, catMenu);
        }
    }

    // =========================================================================
    // Anvil Preparation (set repair cost to 0, create result item)
    // =========================================================================

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) return;
        if (!SearchMenu.isSearching(player.getUniqueId())) return;

        AnvilView anvilView = (AnvilView) event.getView();
        anvilView.setRepairCost(0);

        String text = anvilView.getRenameText();
        ItemStack result = new ItemStack(Material.COMPASS);
        ItemMeta meta = result.getItemMeta();
        meta.displayName(Component.text("Search: " + (text != null ? text : ""),
                NamedTextColor.GREEN));
        result.setItemMeta(meta);
        event.setResult(result);
    }

    // =========================================================================
    // Inventory Drag (prevent dragging in our GUIs)
    // =========================================================================

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof WarpMenu
                || event.getInventory().getHolder() instanceof CategoryMenu) {
            event.setCancelled(true);
        }
    }

    // =========================================================================
    // Inventory Close (clean up search state)
    // =========================================================================

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getType() == InventoryType.ANVIL) {
            SearchMenu.stopSearching(event.getPlayer().getUniqueId());
        }
    }

    // =========================================================================
    // Private Handlers
    // =========================================================================

    private void handleAnvilClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);

        // Only process clicks on the result slot (slot 2)
        if (event.getRawSlot() != 2) return;

        AnvilView anvilView = (AnvilView) event.getView();
        String query = anvilView.getRenameText();

        if (query == null || query.isBlank()) {
            player.sendMessage(MessageUtil.error("Enter a search term first."));
            return;
        }

        SearchMenu.stopSearching(player.getUniqueId());
        player.closeInventory();
        playClickSound(player);

        // Open warp menu with search filter
        WarpMenu.open(player, warpManager, null, query.trim(), 0);
    }

    private void handleWarpMenuClick(InventoryClickEvent event, Player player, WarpMenu menu) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        // --- Navigation bar ---
        if (slot >= 45) {
            switch (slot) {
                case 45 -> { // Previous page
                    menu.previousPage();
                    playClickSound(player);
                }
                case 47 -> { // Category filter
                    playClickSound(player);
                    new CategoryMenu(player, warpManager).open();
                }
                case 48 -> { // Sort cycle
                    playClickSound(player);
                    menu.cycleSort();
                }
                case 49 -> { // Search
                    playClickSound(player);
                    SearchMenu.open(player);
                }
                case 51 -> { // Close
                    playClickSound(player);
                    player.closeInventory();
                }
                case 53 -> { // Next page
                    menu.nextPage();
                    playClickSound(player);
                }
            }
            return;
        }

        // --- Warp item click ---
        Warp warp = menu.getWarp(slot);
        if (warp == null) return;

        playClickSound(player);

        if (event.isShiftClick()) {
            // Shift click → show info
            player.closeInventory();
            showWarpInfo(player, warp);
        } else if (event.isRightClick()) {
            // Right click → preview
            player.closeInventory();
            previewManager.startPreview(player, warp);
        } else {
            // Left click → teleport
            player.closeInventory();
            Location loc = warp.toLocation();
            if (loc == null) {
                player.sendMessage(MessageUtil.error("Warp world is not loaded."));
                return;
            }
            warpManager.incrementUsage(warp.getName());
            teleportAnimation.teleport(player, loc);
        }
    }

    private void handleCategoryClick(InventoryClickEvent event, Player player, CategoryMenu catMenu) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 27) return;

        playClickSound(player);

        if (catMenu.isBackSlot(slot)) {
            WarpMenu.open(player, warpManager, null, null, 0);
            return;
        }

        if (catMenu.isCategorySlot(slot)) {
            WarpCategory category = catMenu.isAllSlot(slot) ? null : catMenu.getCategory(slot);
            WarpMenu.open(player, warpManager, category, null, 0);
        }
    }

    private void showWarpInfo(Player player, Warp warp) {
        String ownerName = Bukkit.getOfflinePlayer(warp.getOwnerUuid()).getName();
        if (ownerName == null) ownerName = "Unknown";

        String created = DATE_FMT.format(warp.getCreatedAt().atZone(ZoneId.systemDefault()));
        String lastUsed = warp.getLastUsed() != null
                ? DATETIME_FMT.format(warp.getLastUsed().atZone(ZoneId.systemDefault()))
                : "Never";

        player.sendMessage(Component.empty());
        player.sendMessage(MessageUtil.header("Warp: " + warp.getName()));
        player.sendMessage(MessageUtil.label("Owner: ", ownerName));
        player.sendMessage(MessageUtil.label("Category: ", warp.getCategory().getDisplayName()));
        player.sendMessage(MessageUtil.label("Location: ",
                String.format("%.0f, %.0f, %.0f", warp.getX(), warp.getY(), warp.getZ())));
        player.sendMessage(MessageUtil.label("Public: ",
                Component.text(warp.isPublic() ? "Yes" : "No",
                        warp.isPublic() ? NamedTextColor.GREEN : NamedTextColor.RED)));
        player.sendMessage(MessageUtil.label("Created: ", created));
        player.sendMessage(MessageUtil.label("Times Used: ", String.valueOf(warp.getUsageCount())));
        player.sendMessage(MessageUtil.label("Last Used: ", lastUsed));
        player.sendMessage(Component.empty());
    }

    private void playClickSound(Player player) {
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
    }
}
