package com.junxiex.mikudatasync.paper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/** Local map-id bindings: (originServer, originalMapId) -> this server's map id. */
public final class MapBindings {

    private final Path file;
    private final java.util.logging.Logger logger;
    private final ConcurrentHashMap<String, Integer> bindings = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public MapBindings(Path file, java.util.logging.Logger logger) {
        this.file = file;
        this.logger = logger;
        load();
    }

    public int get(String origin, int sourceId) {
        return bindings.getOrDefault(key(origin, sourceId), -1);
    }

    /**
     * Updates the in-memory binding only. Persisting on every call rewrote the whole properties file
     * once per map on the entity thread, which turned a login with many maps into dozens of fsyncs.
     */
    public void put(String origin, int sourceId, int targetId) {
        Integer previous = bindings.put(key(origin, sourceId), targetId);
        if (previous == null || previous != targetId) {
            // A re-binding (the target map was deleted, so a new id was allocated) must be persisted
            // too, otherwise the stale binding comes back after a restart.
            dirty = true;
        }
    }

    /** Writes pending bindings; called on a timer and at shutdown. */
    public synchronized void flush() {
        if (!dirty) {
            return;
        }
        Properties properties = new Properties();
        bindings.forEach((key, value) -> properties.setProperty(key, Integer.toString(value)));
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                properties.store(out, "MikuDataSync map bindings");
            }
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException e) {
            logger.warning("Failed to save map bindings: " + e.getMessage());
        }
    }

    private static String key(String origin, int id) {
        return origin + "#" + id;
    }

    private void load() {
        if (Files.notExists(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
            for (String name : properties.stringPropertyNames()) {
                try {
                    bindings.put(name, Integer.parseInt(properties.getProperty(name)));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            logger.warning("Failed to load map bindings: " + e.getMessage());
        }
    }
}
