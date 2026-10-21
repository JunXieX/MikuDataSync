package com.junxiex.mikudatasync.paper;

import com.junxiex.mikudatasync.common.ConfigException;
import com.junxiex.mikudatasync.common.Segment;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperConfigTest {

    private static PaperConfig load(String yaml) throws ConfigException {
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        return PaperConfig.load(configuration);
    }

    @Test
    void defaultsEnableCoreSegments() throws Exception {
        PaperConfig config = load("proxy:\n  server-id: survival-1\n  secret: 0123456789abcdef\n");
        assertEquals("survival-1", config.serverId());
        assertTrue(config.enabledSegments().contains(Segment.CORE));
        assertTrue(config.enabledSegments().contains(Segment.INVENTORY));
        assertTrue(config.enabledSegments().contains(Segment.ENDER_CHEST));
        assertTrue(config.enabledSegments().contains(Segment.STATISTICS));
        assertTrue(config.enabledSegments().contains(Segment.ADVANCEMENTS));
        assertFalse(config.enabledSegments().contains(Segment.RECIPES));
        assertEquals(3000, config.connectTimeoutMs());
        assertEquals("kick", config.onSyncUnavailable());
    }

    @Test
    void sectionsCanBeDisabled() throws Exception {
        PaperConfig config = load("proxy:\n  server-id: s\n  secret: 0123456789abcdef\n"
                + "sync:\n  sections:\n    inventory: false\n    ender-chest: false\n"
                + "    health: false\n    food: false\n    experience: false\n"
                + "    game-mode: false\n    flight: false\n");
        assertFalse(config.enabledSegments().contains(Segment.INVENTORY));
        assertFalse(config.enabledSegments().contains(Segment.ENDER_CHEST));
        assertFalse(config.enabledSegments().contains(Segment.CORE));
    }

    @Test
    void invalidProxyConfigIsRejected() {
        assertThrows(ConfigException.class, () -> load("proxy:\n  server-id: \n  secret: 0123456789abcdef\n"));
        assertThrows(ConfigException.class, () -> load("proxy:\n  server-id: s\n  secret: short\n"));
        assertThrows(ConfigException.class,
                () -> load("proxy:\n  server-id: s\n  secret: 0123456789abcdef\n  port: 0\n"));
        assertThrows(ConfigException.class, () -> load("proxy:\n  server-id: s\n  secret: 0123456789abcdef\n"
                + "sync:\n  on-sync-unavailable: maybe\n"));
    }
}
