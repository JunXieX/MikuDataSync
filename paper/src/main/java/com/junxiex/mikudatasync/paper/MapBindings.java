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
    private final ConcurrentHashMap<String, Integer> bindings = new ConcurrentHashMap<>();

    public MapBindings(Path file) {
        this.file = file;
        load();
    }

    public int get(String origin, int sourceId) {
        return bindings.getOrDefault(key(origin, sourceId), -1);
    }

    public void put(String origin, int sourceId, int targetId) {
        bindings.put(key(origin, sourceId), targetId);
        save();
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
        } catch (IOException ignored) {
        }
    }

    private synchronized void save() {
        Properties properties = new Properties();
        bindings.forEach((key, value) -> properties.setProperty(key, Integer.toString(value)));
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                properties.store(out, "MikuDataSync map bindings");
            }
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
}
