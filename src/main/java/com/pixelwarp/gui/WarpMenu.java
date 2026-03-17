package com.pixelwarp.gui;

import com.pixelwarp.access.WarpAccessManager;
import com.pixelwarp.warp.Warp;
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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class WarpMenu implements InventoryHolder {

    private static final int SLOTS_PER_PAGE = 45;
    private static final int INVENTORY_SIZE = 54;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Sort modes for the GUI. */
    public enum SortMode {
        ALPHABETICAL("A-Z", Material.NAME_TAG),
        MOST_USED("Most Used", Material.EXPERIENCE_BOTTLE),
        NEWEST("Newest", Material.CLOCK);

        private final String displayName;
        private final Material icon;

        SortMode(String displayName, Material icon) {
            this.displayName = displayName;
            this.icon = icon;
        }

        public String getDisplayName() { return displayName; }
        public Material getIcon() { return icon; }

        /** Cycle to the next sort mode. */
        public SortMode next() {
            SortMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private final Player player;
    private final WarpManager warpManager;
    private WarpCategory categoryFilter;
    private String searchQuery;
    private int page;
    private SortMode sortMode;
    private final Inventory inventory;
    private final Map<Integer, Warp> slotMapping = new HashMap<>();

    public WarpMenu(Player player, WarpManager warpManager,
                    WarpCategory categoryFilter, String searchQuery, int page) {
        this(player, warpManager, categoryFilter, searchQuery, page, SortMode.ALPHABETICAL);
    }

    public WarpMenu(Player player, WarpManager warpManager,
                    WarpCategory categoryFilter, String searchQuery, int page, SortMode sortMode) {
        this.player = player;
        this.warpManager = warpManager;
        this.categoryFilter = categoryFilter;
        this.searchQuery = searchQuery;
        this.page = page;
        this.sortMode = sortMode;

        String title = "Warps";
        if (categoryFilter != null) title += " - " + categoryFilter.getDisplayName();
        if (searchQuery != null) title += " [" + searchQuery + "]";

        this.inventory = Bukkit.createInventory(this, INVENTORY_SIZE,
                Component.text(title, NamedTextColor.DARK_PURPLE));
        build();
    }

    public static WarpMenu open(Player player, WarpManager warpManager,
                                WarpCategory category, String search, int page) {
        return open(player, warpManager, category, search, page, SortMode.ALPHABETICAL);
    }

    public static WarpMenu open(Player player, WarpManager warpManager,
                                WarpCategory category, String search, int page, SortMode sortMode) {
        WarpMenu menu = new WarpMenu(player, warpManager, category, search, page, sortMode);
        player.openInventory(menu.getInventory());
        return menu;
    }

    public void build() {
        inventory.clear();
        slotMapping.clear();

        List<Warp> visible = getVisibleWarps();
        int totalPages = Math.max(1, (int) Math.ceil((double) visible.size() / SLOTS_PER_PAGE));
        page = Math.min(page, totalPages - 1);
        page = Math.max(page, 0);

        int start = page * SLOTS_PER_PAGE;
        int end = Math.min(start + SLOTS_PER_PAGE, visible.size());

        for (int i = start; i < end; i++) {
            Warp warp = visible.get(i);
            int slot = i - start;
            inventory.setItem(slot, createWarpItem(warp));
            slotMapping.put(slot, warp);
        }

        // --- Navigation bar (bottom row, slots 45-53) ---
        fillNavBar(Material.BLACK_STAINED_GLASS_PANE);

        if (page > 0) {
            inventory.setItem(45, createNavItem(Material.ARROW, "Previous Page", NamedTextColor.YELLOW));
        }

        String catLabel = categoryFilter != null ? categoryFilter.getDisplayName() : "All";
        inventory.setItem(47, createNavItem(Material.HOPPER, "Category: " + catLabel, NamedTextColor.AQUA));

        // Sort button (slot 48)
        inventory.setItem(48, createSortItem());

        inventory.setItem(49, createNavItem(Material.COMPASS, "Search", NamedTextColor.GREEN));
        inventory.setItem(51, createNavItem(Material.BARRIER, "Close", NamedTextColor.RED));

        if (page < totalPages - 1) {
            inventory.setItem(53, createNavItem(Material.ARROW, "Next Page", NamedTextColor.YELLOW));
        }

        // Page indicator
        inventory.setItem(50, createNavItem(Material.PAPER,
                "Page " + (page + 1) + "/" + totalPages, NamedTextColor.GRAY));
    }

    private List<Warp> getVisibleWarps() {
        Comparator<Warp> comparator = switch (sortMode) {
            case MOST_USED -> Comparator.comparingInt(Warp::getUsageCount).reversed();
            case NEWEST -> Comparator.comparing(Warp::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparing(Warp::getName, String.CASE_INSENSITIVE_ORDER);
        };

        return warpManager.getVisibleWarps(player.getUniqueId(), categoryFilter).stream()
                .filter(w -> searchQuery == null
                        || w.getName().toLowerCase().contains(searchQuery.toLowerCase()))
                .sorted(comparator)
                .collect(Collectors.toList());
    }

    private ItemStack createWarpItem(Warp warp) {
        ItemStack item = new ItemStack(warp.getIconMaterial());
        ItemMeta meta = item.getItemMeta();

        NamedTextColor nameColor = warp.isPublic() ? NamedTextColor.GREEN : NamedTextColor.GOLD;
        meta.displayName(Component.text(warp.getName(), nameColor)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(warp.getCategory().getDisplayName(), NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.ITALIC, false));

        String ownerName = Bukkit.getOfflinePlayer(warp.getOwnerUuid()).getName();
        lore.add(Component.text("Owner: " + (ownerName != null ? ownerName : "Unknown"), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));

        lore.add(Component.text(String.format("%.0f, %.0f, %.0f", warp.getX(), warp.getY(), warp.getZ()),
                        NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));

        String created = DATE_FMT.format(warp.getCreatedAt().atZone(ZoneId.systemDefault()));
        lore.add(Component.text("Created: " + created, NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));

        lore.add(Component.text("Uses: " + warp.getUsageCount(), NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));

        if (!warp.isPublic()) {
            lore.add(Component.text("PRIVATE", NamedTextColor.RED, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));

            // Shared indicator
            WarpAccessManager accessMgr = warpManager.getAccessManager();
            if (accessMgr != null) {
                Set<UUID> accessSet = accessMgr.getAccessList(warp.getId());
                if (!accessSet.isEmpty()) {
                    if (warp.getOwnerUuid().equals(player.getUniqueId())) {
                        // Owner sees player names (up to 3) or count
                        if (accessSet.size() <= 3) {
                            StringBuilder names = new StringBuilder();
                            for (UUID uuid : accessSet) {
                                String pName = Bukkit.getOfflinePlayer(uuid).getName();
                                if (!names.isEmpty()) names.append(", ");
                                names.append(pName != null ? pName : "Unknown");
                            }
                            lore.add(Component.text("Shared with: " + names, NamedTextColor.YELLOW)
                                    .decoration(TextDecoration.ITALIC, false));
                        } else {
                            lore.add(Component.text("Shared with: " + accessSet.size() + " players", NamedTextColor.YELLOW)
                                    .decoration(TextDecoration.ITALIC, false));
                        }
                    } else {
                        // Non-owner sees count
                        lore.add(Component.text("Shared with: " + accessSet.size() + " player"
                                        + (accessSet.size() != 1 ? "s" : ""), NamedTextColor.YELLOW)
                                .decoration(TextDecoration.ITALIC, false));
                    }
                }
            }
        }

        lore.add(Component.empty());
        lore.add(Component.text("Left Click → Teleport", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Right Click → Preview", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Shift Click → Info", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createSortItem() {
        ItemStack item = new ItemStack(sortMode.getIcon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Sort: " + sortMode.getDisplayName(), NamedTextColor.LIGHT_PURPLE)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        for (SortMode mode : SortMode.values()) {
            NamedTextColor color = mode == sortMode ? NamedTextColor.GREEN : NamedTextColor.GRAY;
            String prefix = mode == sortMode ? "▶ " : "  ";
            lore.add(Component.text(prefix + mode.getDisplayName(), color)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Click to cycle", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createNavItem(Material material, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private void fillNavBar(Material material) {
        ItemStack filler = new ItemStack(material);
        ItemMeta meta = filler.getItemMeta();
        meta.displayName(Component.text(" "));
        filler.setItemMeta(meta);
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }
    }

    // --- Accessors ---

    public Warp getWarp(int slot) {
        return slotMapping.get(slot);
    }

    public void nextPage() {
        page++;
        build();
    }

    public void previousPage() {
        if (page > 0) {
            page--;
            build();
        }
    }

    public void cycleSort() {
        sortMode = sortMode.next();
        page = 0;
        build();
    }

    public SortMode getSortMode() { return sortMode; }
    public WarpCategory getCategoryFilter() { return categoryFilter; }
    public String getSearchQuery() { return searchQuery; }
    public int getPage() { return page; }
    public Player getPlayer() { return player; }
    public WarpManager getWarpManager() { return warpManager; }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
