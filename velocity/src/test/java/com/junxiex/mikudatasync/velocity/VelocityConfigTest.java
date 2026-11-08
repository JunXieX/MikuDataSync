package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.ConfigException;
import com.junxiex.mikudatasync.common.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityConfigTest {

    @TempDir
    Path tempDir;

    private void write(String yaml) throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), yaml);
    }

    @Test
    void loadsValidConfig() throws Exception {
        write("protocol:\n  bind: 127.0.0.1\n  port: 25666\n  secret: 0123456789abcdef\n"
                + "store:\n  fsync-mode: always\n  fsync-interval-ms: 10\n"
                + "sync:\n  switch-timeout-ms: 800\n  get-wait-ms: 900\n  fail-mode: open\n");
        VelocityConfig config = VelocityConfig.load(tempDir);
        assertEquals("127.0.0.1", config.bindHost());
        assertEquals(25666, config.bindPort());
        assertEquals(VelocityConfig.FsyncMode.ALWAYS, config.fsyncMode());
        assertEquals(800, config.switchTimeoutMs());
        assertEquals("open", config.failMode());
    }

    @Test
    void rejectsBadSecretAndRange() throws Exception {
        write("protocol:\n  secret: short\n");
        assertThrows(ConfigException.class, () -> VelocityConfig.load(tempDir));
        write("protocol:\n  secret: 0123456789abcdef\n  port: 70000\n");
        assertThrows(ConfigException.class, () -> VelocityConfig.load(tempDir));
        write("protocol:\n  secret: 0123456789abcdef\nstore:\n  fsync-mode: sometimes\n");
        assertThrows(ConfigException.class, () -> VelocityConfig.load(tempDir));
        write("protocol:\n  secret: 0123456789abcdef\nsync:\n  fail-mode: maybe\n");
        assertThrows(ConfigException.class, () -> VelocityConfig.load(tempDir));
    }

    @Test
    void getWaitIsKeptInsteadOfRaisedToTheSwitchTimeout() throws Exception {
        // A wait longer than the backend's pre-login budget (500ms by default) can never be honoured:
        // the backend gives up first and refuses the login, so the configured value is kept as is.
        write("protocol:\n  secret: 0123456789abcdef\nsync:\n  switch-timeout-ms: 2000\n  get-wait-ms: 300\n");
        VelocityConfig config = VelocityConfig.load(tempDir);
        assertEquals(300, config.getWaitMs());
        assertEquals(2000, config.switchTimeoutMs());
    }

    @Test
    void switchTimeoutBeyondTheBackendSettlementWindowIsLowered() throws Exception {
        // Refusing to start would deny every login; the gate is lowered instead and the log says why.
        write("protocol:\n  secret: 0123456789abcdef\nsync:\n  switch-timeout-ms: 5000\n");
        VelocityConfig config = VelocityConfig.load(tempDir);
        assertEquals(2000, config.switchTimeoutMs());
        assertNotNull(config.warning());
    }

    @Test
    void placeholderSecretIsReported() throws Exception {
        write("protocol:\n  secret: \"" + Protocol.PLACEHOLDER_SECRET + "\"\n");
        String warning = VelocityConfig.load(tempDir).warning();
        assertNotNull(warning);
        assertTrue(warning.contains("protocol.secret"));
    }

    @Test
    void defaultsUseAdminPermissionAndSafeWait() throws Exception {
        write("protocol:\n  secret: 0123456789abcdef\n");
        VelocityConfig config = VelocityConfig.load(tempDir);
        assertEquals("mikudatasync.admin", config.commandPermission());
        // Below the Paper side's prelogin-fetch-timeout-ms default of 500, otherwise the wait is lost.
        assertTrue(config.getWaitMs() < 500);
        assertEquals(64L * 1024 * 1024, config.compactionThresholdBytes());
    }
}
