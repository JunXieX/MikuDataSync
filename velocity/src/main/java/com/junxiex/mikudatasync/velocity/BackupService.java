package com.junxiex.mikudatasync.velocity;

import com.junxiex.mikudatasync.common.ProtocolException;
import com.junxiex.mikudatasync.common.SnapshotMessage;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Snapshot backup files, kept next to the journal and rotated by count. */
public final class BackupService {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /** Only files produced by this service may be listed, read or rotated away. */
    private static final Pattern FILE_NAME = Pattern.compile(
            "[A-Za-z0-9_.-]+-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "-\\d{8}-\\d{6}-\\d{3}\\.mds");

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
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        rotate();
        return file;
    }

    public List<Path> list() throws IOException {
        if (Files.notExists(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> files = new ArrayList<>(stream
                    .filter(Files::isRegularFile)
                    .filter(path -> FILE_NAME.matcher(path.getFileName().toString()).matches())
                    .toList());
            files.sort(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed());
            return files;
        }
    }

    public SnapshotMessage read(String fileName) throws IOException, ProtocolException {
        if (!FILE_NAME.matcher(fileName).matches()) {
            throw new IOException("not a backup file: " + fileName);
        }
        Path file = directory.resolve(fileName).normalize();
        if (!file.startsWith(directory.normalize()) || Files.notExists(file)) {
            throw new IOException("backup file not found: " + fileName);
        }
        return SnapshotMessage.decode(Files.readAllBytes(file));
    }

    /** True when the file name carries this player's UUID (used to refuse cross-player restores). */
    public static boolean belongsTo(String fileName, UUID uuid) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            return false;
        }
        String lowered = fileName.toLowerCase(java.util.Locale.ROOT);
        return lowered.contains("-" + uuid + "-");
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
