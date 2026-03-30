package com.pixelwarp.reload;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Coordinates safe runtime reload execution and prevents concurrent reloads.
 */
public class ReloadManager {

    public enum ReloadScope {
        CONFIG,
        STORAGE,
        ALL;

        public static ReloadScope fromArg(String arg) {
            if (arg == null || arg.isBlank()) {
                return ALL;
            }

            return switch (arg.trim().toLowerCase()) {
                case "config" -> CONFIG;
                case "storage" -> STORAGE;
                case "all" -> ALL;
                default -> null;
            };
        }
    }

    public record ReloadResult(boolean success, String message) {}

    private static final long RELOAD_COOLDOWN_MS = 5000L;

    private final AtomicBoolean reloading = new AtomicBoolean(false);
    private final AtomicLong lastReloadCompletedAt = new AtomicLong(0L);

    public boolean isReloading() {
        return reloading.get();
    }

    public CompletableFuture<ReloadResult> execute(ReloadScope scope,
                                                   Function<ReloadScope, CompletableFuture<Void>> executor) {
        long now = System.currentTimeMillis();
        long lastCompleted = lastReloadCompletedAt.get();
        long sinceLastReload = now - lastCompleted;
        if (lastCompleted > 0 && sinceLastReload < RELOAD_COOLDOWN_MS) {
            long waitMs = RELOAD_COOLDOWN_MS - sinceLastReload;
            return CompletableFuture.completedFuture(
                    new ReloadResult(false, "Reload is on cooldown. Try again in " + waitMs + "ms."));
        }

        if (!reloading.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(new ReloadResult(false, "Reload already in progress."));
        }

        long start = System.currentTimeMillis();
        return executor.apply(scope).handle((unused, ex) -> {
            reloading.set(false);
            lastReloadCompletedAt.set(System.currentTimeMillis());
            if (ex != null) {
                return new ReloadResult(false, "Reload failed: " + ex.getMessage());
            }
            long elapsed = System.currentTimeMillis() - start;
            return new ReloadResult(true, "Reload completed in " + elapsed + "ms.");
        });
    }
}
