package traceworks.adapters.record;

import traceworks.core.port.ScratchDirectory;
import traceworks.core.port.ScratchEntry;
import traceworks.core.port.ScratchListing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The scratch directory of a run in its folder. A step can leave anything in it, so this class never follows a link,
 * never opens a special file (a pipe would block), and never reads outside the directory. It works on the
 * {@link Path} objects of the walk, which keep names that are not valid text; the text path of an entry is only its
 * name for the record, and {@link #readScratch} looks the {@code Path} up again.
 */
final class FileScratch implements ScratchDirectory {

    private final Path dir;
    /** The relative paths of the last listing by their text path. */
    private Map<String, Path> listed = new HashMap<>();

    /** The most a listing holds, and its walk at a time: {@link ScratchEntry#MAX_ENTRIES} outside a test. */
    private final int limit;
    /** The most entries and waiting paths a walk held at a time, over all listings so far. */
    private int mostHeld;

    FileScratch(Path dir) {
        this(dir, ScratchEntry.MAX_ENTRIES);
    }

    FileScratch(Path dir, int limit) {
        this.dir = dir;
        this.limit = limit;
    }

    /** For the test of the bound: the most entries and waiting paths a walk of this directory held at a time. */
    synchronized int mostHeld() {
        return mostHeld;
    }

    @Override
    public synchronized ScratchListing list(ScratchListing.Known known) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            listed = new HashMap<>();
            return new ScratchListing(List.of(), List.of(), false);
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("The scratch directory is not a directory: " + dir);
        }
        Walk walk = new Walk(known);
        walk.run(dir);
        walk.entries.sort(Comparator.comparing(ScratchEntry::path));
        listed = walk.paths;
        return new ScratchListing(walk.entries, walk.unreadable, walk.truncated);
    }

    /** One listing in progress. */
    private final class Walk {
        final ScratchListing.Known known;
        final List<ScratchEntry> entries = new ArrayList<>();
        final List<ScratchListing.Unreadable> unreadable = new ArrayList<>();
        final Map<String, Path> paths = new HashMap<>();
        /** Whether the listing leaves something out: a directory had more children than the walk had room for. */
        boolean truncated;

        Walk(ScratchListing.Known known) {
            this.known = known;
        }

        /**
         * Walks the tree below the directory in the order of the names, depth first, with a stack of its own: a
         * tree as deep as the path length allows must not overflow the stack of the thread.
         */
        void run(Path root) {
            Deque<Path> pending = new ArrayDeque<>();
            expand(root, pending);
            while (!pending.isEmpty()) {
                mostHeld = Math.max(mostHeld, pending.size() + entries.size());
                entry(pending.pop(), pending);
            }
        }

        /**
         * Puts the children of a directory on the stack so that the first by name comes out first, as many as the
         * walk has room for: what it holds at a time, the entries it listed and the paths that wait on the stack, is
         * never more than the limit of a listing. So no tree a step builds, wide or nested, makes the walk hold more
         * than that. Of a directory with more children than there is room for, the first by name are kept while they
         * are read; the rest is not listed, and the listing is cut. The room depends only on the tree, so the cut is
         * the same every time.
         */
        private void expand(Path directory, Deque<Path> pending) {
            int room = limit - entries.size() - pending.size();
            // the largest name on top, so the one to drop is at hand
            PriorityQueue<Path> first = new PriorityQueue<>(Comparator.reverseOrder());
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    if (room <= 0) {
                        truncated = true;
                        break;
                    }
                    first.add(child);
                    if (first.size() > room) {
                        first.poll();
                        truncated = true;
                    }
                }
            } catch (IOException | DirectoryIteratorException e) {
                fail(directory, e instanceof DirectoryIteratorException d ? d.getCause() : e);
                return;
            }
            List<Path> children = new ArrayList<>(first);
            children.sort(null);
            for (int i = children.size() - 1; i >= 0; i--) {
                pending.push(children.get(i));
            }
        }

        private void entry(Path path, Deque<Path> pending) {
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                fail(path, e);
                return;
            }
            if (attrs.isDirectory()) {
                expand(path, pending);
            } else if (attrs.isSymbolicLink()) {
                try {
                    add(path, new ScratchEntry(text(path), 0, null, Files.readSymbolicLink(path).toString()));
                } catch (IOException e) {
                    fail(path, e);
                }
            } else if (attrs.isRegularFile()) {
                regular(path, attrs);
            }
            // anything else (pipe, socket, device) is skipped
        }

        private void regular(Path path, BasicFileAttributes attrs) {
            String text = text(path);
            long size = attrs.size();
            long modified = nanos(attrs.lastModifiedTime());
            if (size > ScratchEntry.MAX_HASH_BYTES) {
                add(path, new ScratchEntry(text, size, null, null, modified, ScratchEntry.TOO_LARGE_TO_HASH));
                return;
            }
            String reused = known.sha256(text, size, modified);
            if (reused != null) {
                add(path, new ScratchEntry(text, size, reused, null, modified, null));
                return;
            }
            MessageDigest digest = digest();
            long read = 0;
            // Reads at most the size the walk saw, so a file that grows is not read without end
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                while (read < size) {
                    buffer.clear().limit((int) Math.min(buffer.capacity(), size - read));
                    int n = channel.read(buffer);
                    if (n < 0) {
                        break;
                    }
                    buffer.flip();
                    digest.update(buffer);
                    read += n;
                }
            } catch (IOException e) {
                fail(path, e);
                return;
            }
            add(path, new ScratchEntry(text, read, HexFormat.of().formatHex(digest.digest()), null, modified, null));
        }

        private void add(Path path, ScratchEntry entry) {
            if (entries.size() >= limit) {
                // never reached while the walk keeps its bound; a listing still never holds more than its limit
                truncated = true;
                return;
            }
            if (paths.putIfAbsent(entry.path(), dir.relativize(path)) != null) {
                // two names that read the same as text: the second cannot be told apart, so it is not hidden
                fail(path, "name reads like another name");
                return;
            }
            entries.add(entry);
        }

        private void fail(Path path, Throwable e) {
            fail(path, reason(e));
        }

        private void fail(Path path, String reason) {
            if (unreadable.size() >= ScratchEntry.MAX_ENTRIES) {
                truncated = true;
                return;
            }
            unreadable.add(new ScratchListing.Unreadable(text(path), reason));
        }
    }

    private static String reason(Throwable e) {
        if (e instanceof AccessDeniedException) {
            return "permission denied";
        }
        if (e instanceof NoSuchFileException) {
            return "vanished while listed";
        }
        if (e instanceof FileSystemException f && f.getReason() != null) {
            String why = f.getReason().toLowerCase(java.util.Locale.ROOT);
            if (why.contains("too long")) {
                return "path too long";
            }
            if (why.contains("permission")) {
                return "permission denied";
            }
        }
        return "I/O error";
    }

    private String text(Path path) {
        Path relative = dir.relativize(path);
        List<String> names = new ArrayList<>();
        relative.forEach(name -> names.add(name.toString()));
        return String.join("/", names);
    }

    private static long nanos(FileTime time) {
        java.time.Instant instant = time.toInstant();
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    @Override
    public synchronized byte[] readScratch(String path, long maxBytes) {
        Path file = resolve(path);
        int limit = (int) Math.max(0, Math.min(maxBytes, ScratchEntry.MAX_BLOB_BYTES + 1));
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS);
             InputStream in = Channels.newInputStream(channel)) {
            return in.readNBytes(limit);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the scratch file " + path, e);
        }
    }

    /**
     * The file of a path of the last listing, or else of a plain text path; no part of the path may be a link, and
     * the file must be a regular file.
     */
    private Path resolve(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Not a path in the scratch directory: " + path);
        }
        List<Path> names = new ArrayList<>();
        Path known = listed.get(path);
        if (known != null) {
            known.forEach(names::add);
        } else {
            for (String part : path.split("/", -1)) {
                names.add(dir.getFileSystem().getPath(part));
            }
        }
        Path current = dir;
        for (Path name : names) {
            String part = name.toString();
            if (part.isEmpty() || part.equals(".") || part.equals("..") || name.getNameCount() != 1) {
                throw new IllegalArgumentException("Not a path in the scratch directory: " + path);
            }
            current = current.resolve(name);
            BasicFileAttributes attrs;
            try {
                attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read the scratch file " + path, e);
            }
            if (attrs.isSymbolicLink()) {
                throw new IllegalArgumentException("A link in the scratch directory is never followed: " + path);
            }
        }
        if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Not a regular file in the scratch directory: " + path);
        }
        return current;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
