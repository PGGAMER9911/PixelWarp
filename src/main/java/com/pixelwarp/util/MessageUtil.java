package com.pixelwarp.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class MessageUtil {

    private static final Component PREFIX = Component.text("")
            .append(Component.text("Warp", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD))
            .append(Component.text(" » ", NamedTextColor.GRAY));

    private MessageUtil() {}

    public static Component success(String message) {
        return PREFIX.append(Component.text(message, NamedTextColor.GREEN));
    }

    public static Component error(String message) {
        return PREFIX.append(Component.text(message, NamedTextColor.RED));
    }

    public static Component info(String message) {
        return PREFIX.append(Component.text(message, NamedTextColor.GRAY));
    }

    public static Component label(String label, String value) {
        return Component.text(label, NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    public static Component label(String label, Component value) {
        return Component.text(label, NamedTextColor.GRAY).append(value);
    }

    public static Component header(String text) {
        return Component.text("══ " + text + " ══", NamedTextColor.GOLD, TextDecoration.BOLD);
    }
}
