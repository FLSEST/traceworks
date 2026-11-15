package traceworks.adapters.record;

import traceworks.core.port.RegisterStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** {@code registers/<key>/} in the home: a log, blobs, state.json and report.html, with one lock per register. */
class FileRegisterStoreTest {

    @TempDir
    Path home;

    private FileRegisterStore store() {
        return new FileRegisterStore(home.resolve("registers"));
    }

    private static byte[] line(int n) {
        return ("{\"seq\":" + n + "}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void open_createsNothing_andReadsAnEmptyLog() {
        try (RegisterStore.Opened opened = store().open("arch")) {
            assertThat(opened.key()).isEqualTo("arch");
            assertThat(opened.events().readAll()).isEmpty();
            assertThat(opened.state()).isEmpty();
            assertThat(opened.blobs().get("a".repeat(64))).isEmpty();
        }
        assertThat(home.resolve("registers")).doesNotExist();
        assertThat(store().keys()).isEmpty();
    }

    @Test
    void openForWrite_createsTheFolderForTheOwner_andTheLogAndBlobsAndStateLiveInIt() throws IOException {
        try (RegisterStore.Opened opened = store().openForWrite("arch")) {
            opened.events().append(line(0));
            String sha = opened.blobs().put("blob".getBytes(StandardCharsets.UTF_8)).sha256();
            opened.writeState("{\"state\":1}\n".getBytes(StandardCharsets.UTF_8));
            opened.writeView("<html>".getBytes(StandardCharsets.UTF_8));

            Path dir = home.resolve("registers").resolve("arch");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(home.resolve("registers"))))
                    .isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
            assertThat(Files.readString(dir.resolve("events.jsonl"))).isEqualTo("{\"seq\":0}\n");
            assertThat(dir.resolve("blobs").resolve(sha)).exists();
            assertThat(Files.readString(dir.resolve("state.json"))).isEqualTo("{\"state\":1}\n");
            assertThat(Files.readString(dir.resolve("report.html"))).isEqualTo("<html>");
            assertThat(opened.state()).contains("{\"state\":1}\n".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(store().keys()).containsExactly("arch");
        try (RegisterStore.Opened again = store().open("arch")) {
            assertThat(again.events().readAll()).hasSize(1);
        }
    }

    @Test
    void writeState_isAtomic_noTempFileStays() throws IOException {
        try (RegisterStore.Opened opened = store().openForWrite("arch")) {
            opened.writeState("one".getBytes(StandardCharsets.UTF_8));
            opened.writeState("two".getBytes(StandardCharsets.UTF_8));
        }
        try (Stream<Path> files = Files.list(home.resolve("registers").resolve("arch"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("blobs", "state.json",
                    "register.lock");
        }
    }

    @Test
    void aReaderCannotWrite() {
        try (RegisterStore.Opened opened = store().open("arch")) {
            assertThatThrownBy(() -> opened.writeState(new byte[0])).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("opened for reading");
            assertThatThrownBy(() -> opened.events().append(line(0))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> opened.writeView(new byte[0])).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("opened for reading");
        }
    }

    @Test
    void anInvalidKey_isRefused() {
        for (String bad : List.of("A", "a/b", "..", ".x", "", "a b")) {
            assertThatThrownBy(() -> store().open(bad)).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("register key");
            assertThatThrownBy(() -> store().openForWrite(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void twoThreads_appendInTurn() throws Exception {
        int perThread = 50;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> writers = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            writers.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < perThread; i++) {
                    try (RegisterStore.Opened opened = store().openForWrite("arch")) {
                        int next = opened.events().readAll().size();
                        opened.events().append(line(next));
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> writer : writers) {
            writer.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        try (RegisterStore.Opened opened = store().open("arch")) {
            List<byte[]> lines = opened.events().readAll();
            assertThat(lines).hasSize(2 * perThread);
            for (int i = 0; i < lines.size(); i++) {
                assertThat(new String(lines.get(i), StandardCharsets.UTF_8)).isEqualTo("{\"seq\":" + i + "}");
            }
        }
    }

    /** A second JVM appends under the same lock: the file lock serialises processes, not only threads. */
    @Test
    void twoProcesses_appendInTurn() throws Exception {
        String java = ProcessHandle.current().info().command().orElse(null);
        assumeTrue(java != null, "the java binary of this JVM is not known");
        String classPath = System.getProperty("java.class.path");
        int perProcess = 30;
        List<Process> processes = new ArrayList<>();
        for (int p = 0; p < 2; p++) {
            processes.add(new ProcessBuilder(java, "-cp", classPath, Writer.class.getName(),
                    home.resolve("registers").toString(), String.valueOf(perProcess)).inheritIO().start());
        }
        for (Process process : processes) {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("the writer process ended").isTrue();
            assertThat(process.exitValue()).isZero();
        }

        try (RegisterStore.Opened opened = store().open("arch")) {
            List<byte[]> lines = opened.events().readAll();
            assertThat(lines).hasSize(2 * perProcess);
            for (int i = 0; i < lines.size(); i++) {
                assertThat(new String(lines.get(i), StandardCharsets.UTF_8)).isEqualTo("{\"seq\":" + i + "}");
            }
        }
    }

    /** The writer of {@link #twoProcesses_appendInTurn}: appends the next seq under the lock, N times. */
    public static final class Writer {
        public static void main(String[] args) {
            FileRegisterStore store = new FileRegisterStore(Path.of(args[0]));
            for (int i = 0; i < Integer.parseInt(args[1]); i++) {
                try (RegisterStore.Opened opened = store.openForWrite("arch")) {
                    int next = opened.events().readAll().size();
                    opened.events().append(line(next));
                }
            }
        }
    }

    /**
     * The registers are the folders named like a register key. Whatever else is in the directory (a file, a folder
     * with another kind of name) is not a register, and every key that is listed can be opened.
     */
    @Test
    void keys_listOnlyTheFoldersNamedLikeARegisterKey() throws IOException {
        try (RegisterStore.Opened opened = store().openForWrite("arch")) {
            opened.events().append(line(0));
        }
        Path registers = home.resolve("registers");
        Files.createDirectories(registers.resolve("security"));
        Files.createDirectories(registers.resolve("Not A Key"));
        Files.createDirectories(registers.resolve(".tmp-arch"));
        Files.writeString(registers.resolve("notes"), "a file named like a key");

        List<String> keys = store().keys();

        assertThat(keys).containsExactly("arch", "security");
        for (String key : keys) {
            store().open(key).close();
        }
    }
}
