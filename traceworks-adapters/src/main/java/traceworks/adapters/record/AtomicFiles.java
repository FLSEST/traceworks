package traceworks.adapters.record;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writes a file so that readers see all of it or none of it. */
final class AtomicFiles {

    private AtomicFiles() {}

    /** Writes {@code content} to a temp file next to {@code target} and moves it into place. */
    static void write(Path target, byte[] content) {
        try {
            Path temp = Files.createTempFile(target.getParent(), ".tmp-", ".part");
            try {
                Files.write(temp, content);
                move(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + target, e);
        }
    }

    static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
