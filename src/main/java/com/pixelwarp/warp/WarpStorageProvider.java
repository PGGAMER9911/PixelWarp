package com.pixelwarp.warp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface WarpStorageProvider {

    CompletableFuture<Void> saveWarp(Warp warp);

    Warp getWarp(String name);

    List<Warp> getAllWarps();

    CompletableFuture<Void> deleteWarp(String name);

    default CompletableFuture<List<Warp>> getAllWarpsAsync() {
        return CompletableFuture.supplyAsync(this::getAllWarps);
    }
}
