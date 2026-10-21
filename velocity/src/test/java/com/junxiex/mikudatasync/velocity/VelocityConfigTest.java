package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                + "sync:\n  switch-timeout-ms: 800\n  fail-mode: open\n");
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
}
