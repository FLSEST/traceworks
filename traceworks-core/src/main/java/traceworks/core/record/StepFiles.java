package traceworks.core.record;

import java.util.List;

/**
 * The scratch directory after a step: every regular file, every symbolic link (with its target, never followed),
 * every entry that could not be read,
 * the paths that were gone since the last listing, and whether the listing was cut.
 */
public record StepFiles(List<FileView> files, List<Link> links, List<Unreadable> unreadable, List<String> removed,
                        boolean truncated) {

    public StepFiles {
        files = List.copyOf(files);
        links = List.copyOf(links);
        unreadable = List.copyOf(unreadable);
        removed = List.copyOf(removed);
    }

    /** An entry that could not be read, and why. */
    public record Unreadable(String path, String reason) { }

    /** A symbolic link in the scratch directory. */
    public record Link(String path, String target) { }
}
