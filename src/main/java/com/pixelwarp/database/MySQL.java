package com.pixelwarp.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class MySQL {

    private HikariDataSource dataSource;
    private ExecutorService executor;
    private final Logger logger;

    public MySQL(Logger logger) {
        this.logger = logger;
    }

    public void connect(String host, int port, String database, String username, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(1);
        config.setIdleTimeout(30000);
        config.setMaxLifetime(600000);
        config.setConnectionTimeout(10000);
        config.setPoolName("PixelWarp-Pool");

        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");

        this.dataSource = new HikariDataSource(config);
        this.executor = Executors.newFixedThreadPool(poolSize);

        logger.info("MySQL connection pool established.");
    }

    public void createTables() {
        String warpsTable = """
                CREATE TABLE IF NOT EXISTS warps (
                    id          INT AUTO_INCREMENT PRIMARY KEY,
                    name        VARCHAR(64)  NOT NULL,
                    owner_uuid  VARCHAR(36)  NOT NULL,
                    world       VARCHAR(64)  NOT NULL,
                    x           DOUBLE       NOT NULL,
                    y           DOUBLE       NOT NULL,
                    z           DOUBLE       NOT NULL,
                    yaw         FLOAT        NOT NULL,
                    pitch       FLOAT        NOT NULL,
                    is_public   TINYINT(1)   NOT NULL DEFAULT 1,
                    icon_material VARCHAR(64) NOT NULL DEFAULT 'ENDER_PEARL',
                    category    VARCHAR(32)  NOT NULL DEFAULT 'PLAYER_WARPS',
                    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    usage_count INT          NOT NULL DEFAULT 0,
                    last_used   TIMESTAMP    NULL DEFAULT NULL,
                    UNIQUE KEY uk_name (name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """;

        String accessTable = """
                CREATE TABLE IF NOT EXISTS warp_access (
                    warp_name   VARCHAR(64)  NOT NULL,
                    player_uuid VARCHAR(36)  NOT NULL,
                    warp_id     INT          NULL,
                    PRIMARY KEY (warp_name, player_uuid),
                    INDEX idx_warp_access_warp_id (warp_id, player_uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
                """;

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(warpsTable);
            stmt.execute(accessTable);
            logger.info("Database tables verified.");
        } catch (SQLException e) {
            logger.severe("Failed to create tables: " + e.getMessage());
        }

        migrateAccessTable();
    }

    /**
     * Migration: add warp_id column to warp_access if missing, then backfill from warps table.
     * Safe to run multiple times (idempotent).
     */
    private void migrateAccessTable() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Add warp_id column if it doesn't exist
            try {
                stmt.execute("ALTER TABLE warp_access ADD COLUMN warp_id INT NULL");
                logger.info("Migration: added warp_id column to warp_access.");
            } catch (SQLException ignored) {
                // Column already exists — normal for servers that have already migrated
            }

            // Add index on (warp_id, player_uuid) if it doesn't exist
            try {
                stmt.execute("CREATE INDEX idx_warp_access_warp_id ON warp_access(warp_id, player_uuid)");
            } catch (SQLException ignored) {
                // Index already exists
            }

            // Backfill warp_id for any rows where it is still NULL
            int updated = stmt.executeUpdate("""
                    UPDATE warp_access wa
                    JOIN warps w ON w.name = wa.warp_name
                    SET wa.warp_id = w.id
                    WHERE wa.warp_id IS NULL
                    """);
            if (updated > 0) {
                logger.info("Migration: backfilled warp_id for " + updated + " access entries.");
            }
        } catch (SQLException e) {
            logger.severe("Failed to migrate warp_access table: " + e.getMessage());
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public void close() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("MySQL connection pool closed.");
        }
    }

    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }
}
