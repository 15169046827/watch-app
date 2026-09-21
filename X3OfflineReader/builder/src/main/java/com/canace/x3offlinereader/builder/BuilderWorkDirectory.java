package com.canace.x3offlinereader.builder;

import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Resolves and safely migrates cache/checkpoint data outside replaceable app images. */
final class BuilderWorkDirectory {

    static final String ENV_WORK_ROOT = "X3_BUILDER_WORK_ROOT";

    static final String PROPERTY_WORK_ROOT = "x3.builder.workRoot";

    record MigrationResult(long files, long bytes, boolean sourceRemoved) { }

    private BuilderWorkDirectory() { }

    static Path legacyRoot(Path projectRoot) {
        return projectRoot.resolve("builder/.work").toAbsolutePath().normalize();
    }

    static Path resolve(Path projectRoot) {
        String configured = System.getProperty(PROPERTY_WORK_ROOT);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(ENV_WORK_ROOT);
        }
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException(ENV_WORK_ROOT + " must be an absolute path");
            }
            return validatePersistentRoot(projectRoot, path.normalize());
        }
        if (System.getProperty("jpackage.app-path") != null) {
            return validatePersistentRoot(
                    projectRoot,
                    persistentRoot(
                            System.getenv(),
                            System.getProperty("user.home", "."),
                            System.getProperty("os.name", "")));
        }
        return legacyRoot(projectRoot);
    }

    static Path validatePersistentRoot(Path projectRoot, Path candidate) {
        Path project = projectRoot.toAbsolutePath().normalize();
        Path work = candidate.toAbsolutePath().normalize();
        if (work.startsWith(project)) {
            throw new IllegalArgumentException(
                    "Persistent Builder work must be outside the project and app image");
        }
        return work;
    }

    static Path persistentRoot(Map<String, String> environment, String userHome, String osName) {
        if (osName.toLowerCase(java.util.Locale.ROOT).contains("win")) {
            String local = environment.get("LOCALAPPDATA");
            Path base =
                    local == null || local.isBlank()
                            ? Path.of(userHome, "AppData", "Local")
                            : Path.of(local);
            return base.resolve("Canace/X3AudiobookBuilder/work").toAbsolutePath().normalize();
        }
        String xdg = environment.get("XDG_DATA_HOME");
        Path base =
                xdg == null || xdg.isBlank() ? Path.of(userHome, ".local", "share") : Path.of(xdg);
        return base.resolve("Canace/X3AudiobookBuilder/work").toAbsolutePath().normalize();
    }

    static Path initialize(Path projectRoot) throws Exception {
        Path target = resolve(projectRoot);
        Path legacy = legacyRoot(projectRoot);
        Files.createDirectories(target);
        if (!target.equals(legacy) && Files.isDirectory(legacy)) {
            migrate(legacy, target);
        }
        return target;
    }

    static MigrationResult migrate(Path source, Path target) throws Exception {
        Path from = source.toAbsolutePath().normalize();
        Path to = target.toAbsolutePath().normalize();
        if (from.equals(to) || !Files.isDirectory(from)) {
            return new MigrationResult(0, 0, false);
        }
        requireIndependent(from, to);
        Files.createDirectories(to);
        Path lockFile = to.resolve(".migration.lock");
        try (FileChannel channel =
                        FileChannel.open(
                                lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock()) {
            requireLock(lock);
            MigrationStats stats = copyTree(from, to);
            deleteTree(from);
            return new MigrationResult(stats.files, stats.bytes, !Files.exists(from));
        } finally {
            Files.deleteIfExists(lockFile);
        }
    }

    private static void requireIndependent(Path source, Path target) {
        if (source.startsWith(target) || target.startsWith(source)) {
            throw new IllegalArgumentException(
                    "Legacy and persistent work directories must be independent");
        }
    }

    private static void requireLock(FileLock lock) {
        if (lock == null) {
            throw new IllegalStateException("Another Builder data migration is already running");
        }
    }

    private static MigrationStats copyTree(Path source, Path target) throws Exception {
        MigrationStats stats = new MigrationStats();
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.comparingInt(Path::getNameCount)).toList()) {
                long length = copyEntry(source, target, path);
                if (length >= 0) {
                    stats.files++;
                    stats.bytes += length;
                }
            }
        }
        return stats;
    }

    private static long copyEntry(Path source, Path target, Path path) throws Exception {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalStateException("Legacy work contains an unsupported symbolic link");
        }
        Path relative = source.relativize(path);
        Path destination = target.resolve(relative).normalize();
        requireDestination(target, destination);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(destination);
            return -1;
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Legacy work contains an unsupported entry");
        }
        long length = Files.size(path);
        if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
            requireSameFile(path, destination, relative, length);
        } else {
            copyVerified(path, destination, relative, length);
        }
        return length;
    }

    private static void requireDestination(Path root, Path destination) {
        if (!destination.startsWith(root)) {
            throw new IllegalStateException("Unsafe legacy work path");
        }
    }

    private static void requireSameFile(
            Path source, Path destination, Path relative, long length) throws Exception {
        if (length != Files.size(destination) || !digest(source).equals(digest(destination))) {
            throw new IllegalStateException(
                    "Persistent work contains a conflicting file: " + relative);
        }
    }

    private static void copyVerified(
            Path source, Path destination, Path relative, long length) throws Exception {
        Files.createDirectories(destination.getParent());
        Path temporary =
                destination.resolveSibling(
                        destination.getFileName() + ".migrate-" + UUID.randomUUID());
        try {
            Files.copy(source, temporary, StandardCopyOption.COPY_ATTRIBUTES);
            if (length != Files.size(temporary) || !digest(source).equals(digest(temporary))) {
                throw new IllegalStateException("Legacy work verification failed: " + relative);
            }
            move(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void deleteTree(Path source) throws Exception {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class MigrationStats {

        long files;

        long bytes;
    }
    private static void move(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static String digest(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
