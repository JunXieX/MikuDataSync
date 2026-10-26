package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.ConfigException;
import com.junxiex.mikudatasync.common.Segment;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record PaperConfig(
        String proxyHost,
        int proxyPort,
        String serverId,
        String secret,
        int connectTimeoutMs,
        Set<Integer> enabledSegments,
        boolean pdcMerge,
        boolean advancementStrict,
        int periodicSnapshotSeconds,
        int preloginFetchTimeoutMs,
        String onSyncUnavailable,
        boolean logTimings,
        boolean debugEnabled
) {

    public static PaperConfig load(FileConfiguration cfg) throws ConfigException {
        String host = cfg.getString("proxy.host", "127.0.0.1");
        int port = cfg.getInt("proxy.port", 25666);
        String serverId = cfg.getString("proxy.server-id", "");
        String secret = cfg.getString("proxy.secret", "");
        int connectTimeoutMs = Math.max(100, Math.min(30_000, cfg.getInt("proxy.connect-timeout-ms", 3000)));

        if (serverId.isBlank()) {
            throw new ConfigException("proxy.server-id must not be empty");
        }
        // The id travels inside SnapshotMessage with a 128-byte UTF-8 limit; a longer id would
        // encode on the source server but fail to decode everywhere else.
        int serverIdBytes = serverId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (serverIdBytes > com.junxiex.mikudatasync.common.Protocol.MAX_SERVER_ID_BYTES) {
            throw new ConfigException("proxy.server-id is too long: " + serverIdBytes + " bytes (max "
                    + com.junxiex.mikudatasync.common.Protocol.MAX_SERVER_ID_BYTES + ")");
        }
        if (serverId.indexOf('|') >= 0) {
            throw new ConfigException("proxy.server-id must not contain '|'");
        }
        if (secret.length() < 16) {
            throw new ConfigException("proxy.secret must be at least 16 characters");
        }
        if (port < 1 || port > 65535) {
            throw new ConfigException("proxy.port out of range: " + port);
        }

        Set<Integer> segments = new HashSet<>();
        if (cfg.getBoolean("sync.sections.inventory", true)) {
            segments.add(Segment.INVENTORY);
        }
        if (cfg.getBoolean("sync.sections.ender-chest", true)) {
            segments.add(Segment.ENDER_CHEST);
        }
        if (cfg.getBoolean("sync.sections.effects", true)) {
            segments.add(Segment.EFFECTS);
        }
        if (cfg.getBoolean("sync.sections.advancements", true)) {
            segments.add(Segment.ADVANCEMENTS);
        }
        if (cfg.getBoolean("sync.sections.statistics", true)) {
            segments.add(Segment.STATISTICS);
        }
        if (cfg.getBoolean("sync.sections.attributes", true)) {
            segments.add(Segment.ATTRIBUTES);
        }
        if (cfg.getBoolean("sync.sections.pdc", true)) {
            segments.add(Segment.PDC);
        }
        if (cfg.getBoolean("sync.sections.maps", true)) {
            segments.add(Segment.MAPS);
        }
        if (cfg.getBoolean("sync.sections.cursor-item", true)) {
            segments.add(Segment.MISC);
        }
        boolean core = cfg.getBoolean("sync.sections.health", true)
                || cfg.getBoolean("sync.sections.food", true)
                || cfg.getBoolean("sync.sections.experience", true)
                || cfg.getBoolean("sync.sections.game-mode", true)
                || cfg.getBoolean("sync.sections.flight", true);
        if (core) {
            segments.add(Segment.CORE);
        }

        String onSyncUnavailable = cfg.getString("sync.on-sync-unavailable", "kick");
        if (!onSyncUnavailable.equals("kick") && !onSyncUnavailable.equals("continue")) {
            throw new ConfigException("sync.on-sync-unavailable must be kick or continue");
        }

        return new PaperConfig(
                host,
                port,
                serverId,
                secret,
                connectTimeoutMs,
                Set.copyOf(segments),
                cfg.getBoolean("sync.pdc-merge", false),
                cfg.getBoolean("sync.advancement-strict", true),
                Math.max(5, Math.min(3600, cfg.getInt("sync.periodic-snapshot-seconds", 30))),
                // This timeout blocks the login thread; a large value turns one slow IPC round trip
                // into a client-side "connection timed out" kick.
                Math.max(50, Math.min(3_000, cfg.getInt("sync.prelogin-fetch-timeout-ms", 500))),
                onSyncUnavailable,
                cfg.getBoolean("debug.log-timings", false),
                cfg.getBoolean("debug.enabled", false)
        );
    }

    public boolean enabled(int segmentId) {
        return enabledSegments.contains(segmentId);
    }
}
