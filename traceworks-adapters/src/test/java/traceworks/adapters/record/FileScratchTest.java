package traceworks.adapters.record;

import traceworks.core.port.ScratchEntry;
import traceworks.core.port.ScratchListing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bound of a listing's walk: what it holds at a time, the entries it listed and the paths it has seen and not
 * yet visited, never exceeds the limit, whatever the shape of the tree. The tests set the limit small.
 */
class FileScratchTest {

    private static final int LIMIT = 20;

    @TempDir
    Path dir;

    private static void files(Path folder, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            Files.createFile(folder.resolve(String.format("f%02d", i)));
        }
    }

    /**
     * Nesting must not get around the bound: five levels, each with a folder that sorts first and nine files. The
     * walk goes down first, and a bound per folder would leave the nine files of every level waiting, 45 paths.
     */
    @Test
    void aNestedTreeBeyondTheBound_neverHoldsMoreThanTheBound_andIsCutTheSameEveryTime() throws IOException {
        Path level = dir;
        for (int depth = 0; depth < 5; depth++) {
            files(level, 9);
            level = Files.createDirectory(level.resolve("a-down"));
        }
        FileScratch scratch = new FileScratch(dir, LIMIT);

        ScratchListing listing = scratch.list(ScratchListing.Known.NONE);

        assertThat(scratch.mostHeld()).as("entries listed plus paths waiting, at any time").isLessThanOrEqualTo(LIMIT);
        assertThat(listing.truncated()).isTrue();
        assertThat(listing.entries()).hasSizeLessThanOrEqualTo(LIMIT);
        assertThat(listing.entries()).extracting(ScratchEntry::path).as("the first levels are whole")
                .contains("f00", "f08", "a-down/f00", "a-down/f08", "a-down/a-down/f00")
                .doesNotContain("a-down/a-down/f08");
        assertThat(new FileScratch(dir, LIMIT).list(ScratchListing.Known.NONE)).isEqualTo(listing);
    }

    /**
     * The files listed before a folder that sorts after them take room too: at each level the walk keeps no more
     * children than the entries it listed leave room for. Such a tree beyond the bound fills it, and no more.
     */
    @Test
    void filesListedBeforeAFolder_takeRoomFromItsChildren() throws IOException {
        Path level = dir;
        for (int depth = 0; depth < 5; depth++) {
            files(level, 9);
            level = Files.createDirectory(level.resolve("z-down"));
        }
        FileScratch scratch = new FileScratch(dir, LIMIT);

        ScratchListing listing = scratch.list(ScratchListing.Known.NONE);

        assertThat(scratch.mostHeld()).as("entries listed plus paths waiting, at their most").isEqualTo(LIMIT);
        assertThat(listing.truncated()).isTrue();
        assertThat(listing.entries()).extracting(ScratchEntry::path).hasSize(LIMIT)
                .contains("f00", "f08", "z-down/f00", "z-down/f08", "z-down/z-down/f01")
                .doesNotContain("z-down/z-down/f02");
    }

    /** A tree of exactly as many files and folders as the bound is listed whole. */
    @Test
    void aTreeOfExactlyTheBound_isListedWhole() throws IOException {
        files(dir, 9);
        Path down = Files.createDirectory(dir.resolve("a-down"));
        files(down, 10);

        ScratchListing listing = new FileScratch(dir, LIMIT).list(ScratchListing.Known.NONE);

        assertThat(listing.truncated()).as("19 files and one folder").isFalse();
        assertThat(listing.entries()).hasSize(19);
    }

    @Test
    void oneFolderWithExactlyTheBoundOfFiles_isListedWhole_andOneMoreIsCut() throws IOException {
        files(dir, LIMIT);
        FileScratch scratch = new FileScratch(dir, LIMIT);

        ScratchListing exact = scratch.list(ScratchListing.Known.NONE);
        Files.createFile(dir.resolve("f99"));
        ScratchListing more = scratch.list(ScratchListing.Known.NONE);

        assertThat(exact.truncated()).isFalse();
        assertThat(exact.entries()).hasSize(LIMIT);
        assertThat(more.truncated()).isTrue();
        assertThat(more.entries()).extracting(ScratchEntry::path).hasSize(LIMIT).contains("f19")
                .doesNotContain("f99");
    }
}
