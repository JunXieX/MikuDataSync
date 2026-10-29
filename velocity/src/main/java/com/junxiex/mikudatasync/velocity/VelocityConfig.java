package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.ConfigException;
import com.junxiex.mikudatasync.common.MiniYaml;
import com.junxiex.mikudatasync.common.Protocol;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

public record VelocityConfig(
        String bindHost,
        int bindPort,
        String secret,
        String journalFile,
        FsyncMode fsyncMode,
        int fsyncIntervalMs,
        int switchTimeoutMs,
        int getWaitMs,
        String failMode,
        int periodicFlushSeconds,
        int maxSnapshotBytes,
        int readTimeoutMs,
        boolean retainOffline,
        int backupsKeep,
        String commandPermission,
        long compactionThresholdBytes,
        long retainOfflineBytes,
        String warning
) {

    public enum FsyncMode {
        ALWAYS,
        INTERVAL,
        NEVER
    }

    public static VelocityConfig load(Path dataDirectory) throws IOException, ConfigException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.yml");
        if (Files.notExists(file)) {
            try (InputStream in = VelocityConfig.class.getResourceAsStream("/config.yml")) {
                if (in == null) {
                    throw new IOException("bundled config.yml is missing");
                }
                Files.copy(in, file);
            }
        }

        Map<String, Object> root = MiniYaml.parse(Files.readString(file, StandardCharsets.UTF_8));

        String host = MiniYaml.getString(root, "protocol.bind", "127.0.0.1");
        int port = MiniYaml.getInt(root, "protocol.port", 25666);
        // Raw read: a numeric secret with leading zeros must not be rewritten into a different value.
        String secret = MiniYaml.getRawString(root, "protocol.secret", "");
        int readTimeoutMs = MiniYaml.getInt(root, "protocol.read-timeout-ms", 60000);

        String journalFile = MiniYaml.getString(root, "store.journal-file", "data/journal.mds");
        String fsyncModeRaw = MiniYaml.getString(root, "store.fsync-mode", "interval");
        int fsyncIntervalMs = Math.max(10, Math.min(10_000, MiniYaml.getInt(root, "store.fsync-interval-ms", 50)));
        boolean retainOffline = MiniYaml.getBoolean(root, "store.retain-offline-snapshots", false);

        int switchTimeoutMs = MiniYaml.getInt(root, "sync.switch-timeout-ms", 1500);
        // A GET that lands while a switch flush is in flight must wait at least as long as the gate,
        // otherwise it returns a pre-switch snapshot and the target server applies stale data.
        int getWaitMs = MiniYaml.getInt(root, "sync.get-wait-ms", 1500);
        String failMode = MiniYaml.getString(root, "sync.fail-mode", "closed");
        int periodicFlushSeconds = Math.max(1, Math.min(3_600, MiniYaml.getInt(root, "sync.periodic-flush-seconds", 30)));
        int maxSnapshotBytes = MiniYaml.getInt(root, "sync.max-snapshot-bytes", Protocol.MAX_SNAPSHOT_BYTES);
        int backupsKeep = Math.max(1, Math.min(1000, MiniYaml.getInt(root, "backups.keep", 20)));
        String commandPermission = MiniYaml.getString(root, "commands.permission", "mikudatasync.admin");
        int compactionThresholdMb = MiniYaml.getInt(root, "store.compaction-threshold-mb", 64);
        int retainOfflineMb = MiniYaml.getInt(root, "store.retain-offline-budget-mb", 256);

        if (secret.length() < 16) {
            throw new ConfigException("protocol.secret must be at least 16 characters");
        }
        if (port < 1 || port > 65535) {
            throw new ConfigException("protocol.port out of range: " + port);
        }
        if (maxSnapshotBytes < 1024 || maxSnapshotBytes > Protocol.MAX_SNAPSHOT_BYTES) {
            throw new ConfigException("sync.max-snapshot-bytes out of range: " + maxSnapshotBytes);
        }
        if (getWaitMs < 10 || getWaitMs > 60000) {
            throw new ConfigException("sync.get-wait-ms out of range: " + getWaitMs);
        }
        boolean getWaitAdjusted = false;
        if (getWaitMs < switchTimeoutMs) {
            // A GET that gives up before the switch gate does returns the pre-switch snapshot, so the
            // value is raised instead of rejecting the file: 1.5.x shipped get-wait-ms: 300 and an
            // upgrade must not refuse to start (that would deny every login on every backend).
            getWaitMs = switchTimeoutMs;
            getWaitAdjusted = true;
        }
        if (switchTimeoutMs < 50 || switchTimeoutMs > 30000) {
            throw new ConfigException("sync.switch-timeout-ms out of range: " + switchTimeoutMs);
        }
        if (readTimeoutMs < 1000 || readTimeoutMs > 600_000) {
            throw new ConfigException("protocol.read-timeout-ms out of range: " + readTimeoutMs);
        }
        if (compactionThresholdMb < 1 || compactionThresholdMb > 4096) {
            throw new ConfigException("store.compaction-threshold-mb out of range: " + compactionThresholdMb);
        }
        if (retainOfflineMb < 1 || retainOfflineMb > 16_384) {
            throw new ConfigException("store.retain-offline-budget-mb out of range: " + retainOfflineMb);
        }
        FsyncMode fsyncMode;
        try {
            fsyncMode = FsyncMode.valueOf(fsyncModeRaw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("store.fsync-mode must be always, interval or never");
        }
        if (!failMode.equals("closed") && !failMode.equals("open") && !failMode.equals("kick")) {
            throw new ConfigException("sync.fail-mode must be closed, open or kick");
        }

        VelocityConfig loaded = new VelocityConfig(
                host,
                port,
                secret,
                journalFile,
                fsyncMode,
                fsyncIntervalMs,
                switchTimeoutMs,
                getWaitMs,
                failMode,
                periodicFlushSeconds,
                maxSnapshotBytes,
                readTimeoutMs,
                retainOffline,
                backupsKeep,
                commandPermission,
                compactionThresholdMb * 1024L * 1024L,
                retainOfflineMb * 1024L * 1024L,
                warning(getWaitAdjusted, switchTimeoutMs, secret)
        );
        return loaded;
    }

    private static String warning(boolean getWaitAdjusted, int switchTimeoutMs, String secret) {
        String message = null;
        if (getWaitAdjusted) {
            message = "sync.get-wait-ms was raised to sync.switch-timeout-ms (" + switchTimeoutMs
                    + "); update config.yml to silence this notice";
        }
        if (Protocol.PLACEHOLDER_SECRET.equals(secret)) {
            String placeholder = "protocol.secret is still the value shipped in config.yml; anyone who can "
                    + "reach the IPC port can write snapshots for any player. Replace it with a random "
                    + "secret of 32 characters or more";
            message = message == null ? placeholder : message + "; " + placeholder;
        }
        return message;
    }

    public boolean failClosed() {
        // "kick" was accepted historically and must behave like fail-closed, never fail-open.
        return !"open".equals(failMode);
    }

    /** fail-mode: kick also disconnects the player so they cannot keep playing on unconfirmed data. */
    public boolean kickOnFailure() {
        return "kick".equals(failMode);
    }
}
