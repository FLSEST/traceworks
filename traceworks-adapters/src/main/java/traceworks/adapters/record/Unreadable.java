package traceworks.adapters.record;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * What is said of a part of a register that cannot be read, and when a part counts as absent.
 *
 * <p><b>Why.</b> The reason is the kind of failure, in fixed words where the kind is known: the sentence of a
 * check holds it, and an acknowledgement covers a problem by its sentence. The words of the operating system are
 * used only where no kind fits; they depend on the machine's language.
 *
 * <p><b>Absent.</b> A part is absent only when the file system says that it is. A part whose existence cannot be
 * asked (its folder cannot be entered) is not absent: read as "no log", it would make a register that was fed and
 * decided in look like one nobody had begun.
 */
final class Unreadable {

    private Unreadable() {
    }

    /** {@code Cannot read <file>: <cause>}, with the failure as its cause. */
    static UncheckedIOException of(Path file, IOException failure) {
        return new UncheckedIOException("Cannot read " + file + ": " + cause(file, failure), failure);
    }

    /** The kind of a failure to read {@code file}. */
    static String cause(Path file, IOException failure) {
        if (Files.isDirectory(file)) {
            return "a folder is in its place";
        }
        if (failure instanceof AccessDeniedException) {
            return "access denied";
        }
        if (failure instanceof NoSuchFileException) {
            return "no such file";
        }
        if (failure instanceof FileSystemLoopException) {
            return "a loop of links";
        }
        if (failure instanceof FileSystemException named && named.getReason() != null) {
            return named.getReason();
        }
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /**
     * Whether {@code file} is absent, as the file system says.
     *
     * @return {@code true} when the file system says that nothing is there; {@code false} when something is
     * @throws UncheckedIOException {@code Cannot read <file>: <cause>} when the file system cannot say
     */
    static boolean absent(Path file, LinkOption... options) {
        if (Files.exists(file, options)) {
            return false;
        }
        if (Files.notExists(file, options)) {
            return true;
        }
        try {
            Files.readAttributes(file, BasicFileAttributes.class, options);
            return false;   // it came into being in between
        } catch (NoSuchFileException e) {
            return true;    // or went
        } catch (IOException e) {
            throw of(file, e);
        }
    }
}
