package traceworks.adapters.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import traceworks.core.port.NoSuchRunException;
import traceworks.core.port.RunStore;
import traceworks.core.port.StoredRun;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs as folders: {@code <runs>/<UTC date of the start>/<run id>/}, where {@code <runs>} is the runs folder of the
 * home: {@code <home>/runs} unless the operator names another one.
 */
public final class FileRunStore implements RunStore {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path runsDir;

    /** @param runsDir the runs folder of the home; it is created with the first run */
    public FileRunStore(Path runsDir) {
        this.runsDir = runsDir;
    }

    @Override
    public StoredRun create(String runId, Instant startedAt) {
        if (runId.isEmpty() || runId.contains("/") || runId.contains("\\") || runId.startsWith(".")) {
            throw new IllegalArgumentException("Not a usable run id: '" + runId + "'");
        }
        if (find(runId) != null) {
            throw new IllegalStateException("Run already exists: " + runId);
        }
        Path dir = runsDir.resolve(DAY.format(startedAt)).resolve(runId);
        try {
            Files.createDirectories(dir.getParent());
            Files.createDirectory(dir);
            Files.createDirectories(dir.resolve("blobs"));
            Files.createDirectories(dir.resolve("scratch"));
            Files.createDirectories(dir.resolve("inbox").resolve("done"));
            Files.createFile(dir.resolve("events.jsonl"));
        } catch (FileAlreadyExistsException e) {
            throw new IllegalStateException("Run already exists: " + runId, e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create " + dir, e);
        }
        FileStoredRun run = new FileStoredRun(runId, dir, true);
        if (!run.tryLockWriter()) {
            throw new IllegalStateException("Cannot lock the new run " + runId);
        }
        return run;
    }

    @Override
    public StoredRun open(String runRef) {
        // A reference is a folder only when it is written as a path. A bare id or prefix never resolves against the
        // working directory: that is the default workspace, where a step with a writable workspace could leave a
        // folder named like its run's id, and "show <id>" would then show a record the step wrote
        if (writtenAsPath(runRef)) {
            Path asPath = asRunFolder(runRef);
            if (asPath != null) {
                return new FileStoredRun(asPath.getFileName().toString(), asPath, isRunFolderOfThisHome(asPath));
            }
            throw new NoSuchRunException(runRef);
        }
        List<Path> all = folders();
        String id;
        try {
            id = RunStore.match(runRef, all.stream().map(p -> p.getFileName().toString()).toList());
        } catch (NoSuchRunException e) {
            throw asRunFolder(runRef) == null ? e : new NoSuchRunException(runRef, "a folder of that name is in the"
                    + " working directory; to open it, write it as a path: ./" + runRef);
        }
        for (Path p : all) {
            if (p.getFileName().toString().equals(id)) {
                return new FileStoredRun(id, p, true);
            }
        }
        throw new NoSuchRunException(runRef);
    }

    /** Whether a run reference is written as a path: with a separator, or {@code .} or {@code ..} itself. */
    public static boolean writtenAsPath(String runRef) {
        return runRef.indexOf('/') >= 0 || runRef.indexOf(java.io.File.separatorChar) >= 0 || runRef.equals(".")
                || runRef.equals("..");
    }

    @Override
    public List<String> runIds() {
        return folders().stream().map(p -> p.getFileName().toString()).toList();
    }

    private static Path asRunFolder(String ref) {
        try {
            // Resolved once, by the file system, and that one path is checked and returned: removing ".." as text
            // names another folder than the file system does when a symbolic link comes before it
            Path p = Path.of(ref).toRealPath();
            return Files.isDirectory(p) && Files.isRegularFile(p.resolve("events.jsonl")) ? p : null;
        } catch (InvalidPathException | IOException e) {
            return null;
        }
    }

    /**
     * Whether a folder is {@code <day>/<run id>} in the runs folder of this home, also through a symbolic link.
     *
     * @param folder the real path of the folder, as {@link #asRunFolder} resolved it: the one path that is opened is
     *               the one that is judged, and it is not resolved a second time, when it could name another folder
     */
    private boolean isRunFolderOfThisHome(Path folder) {
        try {
            Path parent = folder.getParent();
            return parent != null && parent.getParent() != null && Files.isDirectory(runsDir)
                    && parent.getParent().equals(runsDir.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    private Path find(String runId) {
        for (Path p : folders()) {
            if (p.getFileName().toString().equals(runId)) {
                return p;
            }
        }
        return null;
    }

    /** All run folders, newest first: by date folder, then by the time of the first event. */
    private List<Path> folders() {
        if (!Files.isDirectory(runsDir)) {
            return List.of();
        }
        record Entry(Path path, String day, Instant started) {}
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> days = Files.list(runsDir)) {
            for (Path day : days.filter(Files::isDirectory).toList()) {
                try (Stream<Path> runs = Files.list(day)) {
                    for (Path run : runs.filter(Files::isDirectory).toList()) {
                        entries.add(new Entry(run, day.getFileName().toString(), startOf(run)));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + runsDir, e);
        }
        entries.sort(Comparator.comparing(Entry::day).thenComparing(Entry::started).reversed());
        return entries.stream().map(Entry::path).toList();
    }

    private static Instant startOf(Path run) {
        Path events = run.resolve("events.jsonl");
        try {
            if (Files.isRegularFile(events)) {
                try (var lines = Files.lines(events)) {
                    String first = lines.findFirst().orElse(null);
                    if (first != null) {
                        JsonNode at = MAPPER.readTree(first).path("at");
                        if (at.isTextual()) {
                            return Instant.parse(at.asText());
                        }
                    }
                }
            }
            return Files.getLastModifiedTime(run).toInstant();
        } catch (IOException | RuntimeException e) {
            return Instant.EPOCH;
        }
    }
}
