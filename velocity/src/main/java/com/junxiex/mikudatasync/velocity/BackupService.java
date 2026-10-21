package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.ProtocolException;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Snapshot backup files, kept next to the journal and rotated by count. */
public final class BackupService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path directory;
    private final int keep;
    private final Logger logger;

    public BackupService(Path directory, int keep, Logger logger) {
        this.directory = directory;
        this.keep = Math.max(1, keep);
        this.logger = logger;
    }

    public Path write(UUID uuid, String playerName, SnapshotMessage message) throws IOException {
        Files.createDirectories(directory);
        String safeName = playerName.replaceAll("[^A-Za-z0-9_.-]", "_");
        Path file = directory.resolve(safeName + "-" + uuid + "-" + LocalDateTime.now().format(STAMP) + ".mds");
        Files.write(file, message.encode());
        rotate();
        return file;
    }

    public List<Path> list() throws IOException {
        if (Files.notExists(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> files = new ArrayList<>(stream.filter(Files::isRegularFile).toList());
            files.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
            return files;
        }
    }

    public SnapshotMessage read(String fileName) throws IOException, ProtocolException {
        Path file = directory.resolve(fileName).normalize();
        if (!file.startsWith(directory.normalize()) || Files.notExists(file)) {
            throw new IOException("backup file not found: " + fileName);
        }
        return SnapshotMessage.decode(Files.readAllBytes(file));
    }

    private void rotate() {
        try {
            List<Path> files = list();
            for (int i = keep; i < files.size(); i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException e) {
            logger.warn("Failed to rotate backups: {}", e.getMessage());
        }
    }
}
