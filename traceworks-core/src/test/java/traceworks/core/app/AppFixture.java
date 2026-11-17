package traceworks.core.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import traceworks.core.blocks.CoreBlocks;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.BuiltInImageSource;
import traceworks.core.port.Clock;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.HostDirectories;
import traceworks.core.port.Ids;
import traceworks.core.port.Ledger;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.PackageImage;
import traceworks.core.port.PackageLoader;
import traceworks.core.port.SourceCheckout;
import traceworks.core.record.EventChain;
import traceworks.core.record.RecordedEvent;
import traceworks.core.testing.FakeAiCli;
import traceworks.core.testing.FakeCommandRunner;
import traceworks.core.testing.FakeContainerRuntime;
import traceworks.core.testing.InMemoryLedger;
import traceworks.core.testing.InMemoryRunStore;
import traceworks.core.testing.InMemoryRunStore.InMemoryStoredRun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** The use cases on in-memory ports: a store, a ledger, packages from JSON texts, fixed ids and a ticking clock. */
final class AppFixture {

    static final String ACTOR = "flo@box";
    static final Duration WAIT = Duration.ofSeconds(10);
    static final Path WORKSPACE = Path.of("/work");

    /** Two shell steps around an approval; the first has a {@code run_if}. */
    static final Path DEPLOY = Path.of("/pkg/deploy");
    /** Two shell steps. */
    static final Path BUILD = Path.of("/pkg/build");
    /** A sub-workflow whose child asks an approval, then a shell step. */
    static final Path PARENT = Path.of("/pkg/parent");
    /** A shell step that reports a YELLOW gate, then an approval. */
    static final Path GATE = Path.of("/pkg/gate");
    /** A human input, then a shell step that uses the answer. */
    static final Path WHO = Path.of("/pkg/who");
    /** Two approvals back to back, then a shell step. */
    static final Path TWICE = Path.of("/pkg/twice");
    /** A top-level approval at step 0, then a sub-workflow whose child asks at its own step 0. */
    static final Path NEST = Path.of("/pkg/nest");
    /** An approval a person answers with an AI's proposal below the question ({@code actor: shadow}). */
    static final Path SHADOW = Path.of("/pkg/shadow");

    /** The tag of the built-in shell image, from the digest {@link #builtIns} gives it. */
    static final String SHELL_TAG = "traceworks/shell:aaaaaaaaaaaa";
    static final String SHELL_ID = "sha256:" + "5".repeat(64);
    /** The tag of the built-in claude image, from the same digest. */
    static final String CLAUDE_TAG = "traceworks/claude:aaaaaaaaaaaa";
    static final String CLAUDE_ID = "sha256:" + "c".repeat(64);
    /** The tag of the built-in image of the egress proxy, from the same digest. */
    static final String EGRESS_TAG = "traceworks/egress:aaaaaaaaaaaa";
    static final String EGRESS_ID = "sha256:" + "e".repeat(64);
    /** A value of {@code ANTHROPIC_API_KEY} shaped like a real one. */
    static final String ANTHROPIC_KEY = "sk-ant-api03-Fake_Key-0123456789abcdef";

    final InMemoryRunStore store = new InMemoryRunStore();
    final InMemoryLedger ledger = new InMemoryLedger();
    /** The host runner: the commands of a host run that name no image. */
    final FakeCommandRunner commands = new FakeCommandRunner();
    /** The container runtime the tests script; the services find it only once {@link #withRuntime} is called. */
    final FakeContainerRuntime containers = new FakeContainerRuntime();
    /** The runtime the services find; none unless a test sets it. */
    ContainerRuntime runtime;
    /** How often the services looked for the runtime. */
    final AtomicInteger runtimeLookups = new AtomicInteger();
    /** The directories the start check refuses, with the reason. */
    final Map<Path, String> refusedDirectories = new LinkedHashMap<>();
    /** The four built-in images, every one from the same directory digest. */
    final BuiltInImageSource builtIns = new BuiltInImageSource() {
        @Override
        public List<PackageImage> all() {
            return List.of(image("shell"), image("claude"), image("pi"), image("egress"));
        }

        private PackageImage image(String name) {
            return new PackageImage(name, null, "a".repeat(64), "FROM x");
        }

        @Override
        public BuildContext context(PackageImage image) {
            throw new UnsupportedOperationException("a run never builds");
        }
    };
    /**
     * What a directory resolves to after its first resolution: something replaced it, or a folder above it, in the
     * meantime. A directory not named here always resolves to itself.
     */
    final Map<Path, String> swappedAfterFirstResolution = new LinkedHashMap<>();
    private final Map<Path, Integer> resolutions = new LinkedHashMap<>();
    /** Real paths are the paths as given; a directory is refused when {@link #refusedDirectories} says so. */
    final HostDirectories directories = new HostDirectories() {
        @Override
        public String realPath(Path directory) {
            return resolve(directory);
        }

        @Override
        public Checked check(Path directory) {
            String real = resolve(directory);
            String refusal = refusedDirectories.get(directory);
            return refusal != null ? Checked.refused(refusal) : Checked.mountable(real);
        }

        @Override
        public Checked check(Path directory, Role role) {
            checkedAs.put(directory, role);
            return check(directory);
        }
    };
    /** What the start check asked each directory as: the role it named with the check. */
    final Map<Path, HostDirectories.Role> checkedAs = new LinkedHashMap<>();

    /** One resolution of {@code directory}: itself, or what it was swapped for when it was resolved before. */
    private synchronized String resolve(Path directory) {
        boolean first = resolutions.merge(directory, 1, Integer::sum) == 1;
        return first ? directory.toString() : swappedAfterFirstResolution.getOrDefault(directory, directory.toString());
    }
    final Packages packages = new Packages();
    /** The checkouts of the runs started with a source. */
    final Sources sources = new Sources();
    /** The Claude CLI the services run AI steps with; it answers what a test scripts. */
    final FakeAiCli claude = FakeAiCli.claude();
    /** The AI CLIs the services know: {@link #claude} unless a test adds more. */
    final List<traceworks.core.port.AiCli> clis = new ArrayList<>(List.of(claude));
    private final AtomicLong tick = new AtomicLong();
    final Clock clock = () -> Instant.parse("2026-11-03T12:00:00Z").plusMillis(tick.incrementAndGet());
    private final AtomicInteger idCounter = new AtomicInteger();
    final Ids ids = () -> String.format("00000000-0000-0000-0000-%012d", idCounter.incrementAndGet());
    /** Host runs; a container run's test sets {@link OperatorSettings#defaults()}. */
    OperatorSettings settings = new OperatorSettings(true, null, List.of());
    /** The operator's hook, when {@link #settings} names one. */
    final traceworks.core.testing.FakeHookRunner hooks =
            new traceworks.core.testing.FakeHookRunner();
    /** The registers of the home: what audit runs feed. */
    final traceworks.core.testing.InMemoryRegisterStore registers =
            new traceworks.core.testing.InMemoryRegisterStore();
    /** The seals of the home: the log, the allowed signers and the seal files of the packages. */
    final traceworks.core.testing.InMemorySealStore sealStore =
            new traceworks.core.testing.InMemorySealStore();
    /** The key seals are signed with; allowed as {@link #ACTOR} once a test calls {@link #allowSigner}. */
    final traceworks.core.testing.JavaSshSigner signer =
            new traceworks.core.testing.JavaSshSigner();
    /** What {@code credentials.json} holds, read when a run that declares secrets starts. */
    final Map<String, String> credentials = new LinkedHashMap<>();
    /** The environment of the Traceworks process, where a secret not in the file comes from. */
    final Map<String, String> environment = new LinkedHashMap<>();
    /** When {@code credentials.json} was last modified; {@code null} for a time not known. */
    Instant credentialsModified;
    /** The path of the credentials file, as a refusal names it; {@code null} when the services do not know it. */
    String credentialsLocation;
    /** Which names may take their value from the environment: the operator's {@code secret_names}; all by default. */
    java.util.function.Predicate<String> environmentAllowed = name -> true;
    /** When set, reading {@code credentials.json} fails with it, as a file others can read does. */
    RuntimeException credentialsFailure;
    /** How often the services read the values of secrets and credentials. */
    final AtomicInteger credentialReads = new AtomicInteger();

    /** The home allows the fixture's signing key, as {@link #ACTOR}. */
    AppFixture allowSigner() {
        sealStore.allow(signer.allowedLine(ACTOR));
        return this;
    }

    AppFixture() {
        packages.seals = sealStore;
        packages.put(DEPLOY, """
                {"id": "wf-deploy", "name": "Deploy", "blocks": [
                  {"type": "shell", "name": "build", "config": {"command": "./build", "run_if": "test -f ready"}},
                  {"type": "approval", "name": "publish",
                   "config": {"question": "Publish {{ version }}?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "done", "config": {"command": "./done"}}
                ]}""");
        packages.put(BUILD, """
                {"id": "wf-build", "name": "Build", "blocks": [
                  {"type": "shell", "name": "build", "config": {"command": "./build"}},
                  {"type": "shell", "name": "test", "config": {"command": "./test"}}
                ]}""");
        packages.put(PARENT, """
                {"id": "wf-parent", "name": "Parent", "blocks": [
                  {"type": "sub-workflow", "name": "child", "config": {"workflow_id": "./child"}},
                  {"type": "shell", "name": "after", "config": {"command": "./after"}}
                ]}""");
        packages.put(PARENT.resolve("child"), """
                {"id": "wf-child", "name": "Child", "blocks": [
                  {"type": "approval", "name": "confirm", "config": {"question": "Go on?", "options": ["Yes", "No"]}}
                ]}""");
        packages.put(GATE, """
                {"id": "wf-gate", "name": "Gate", "blocks": [
                  {"type": "shell", "name": "lint", "config": {"command": "./lint"}},
                  {"type": "approval", "name": "publish", "config": {"question": "Publish?", "options": ["Yes", "No"]}}
                ]}""");
        packages.put(WHO, """
                {"id": "wf-who", "name": "Who", "blocks": [
                  {"type": "human-input", "name": "who", "config": {"prompt": "Who deploys?", "variable_name": "who"}},
                  {"type": "shell", "name": "say", "config": {"command": "echo {{ who }}"}}
                ]}""");
        packages.put(SHADOW, """
                {"id": "wf-shadow", "name": "Shadow", "blocks": [
                  {"type": "approval", "name": "publish",
                   "config": {"question": "Publish?", "options": ["Yes", "No"], "actor": "shadow"}}
                ]}""");
        packages.put(TWICE, """
                {"id": "wf-twice", "name": "Twice", "blocks": [
                  {"type": "approval", "name": "first", "config": {"question": "First?", "options": ["Yes", "No"]}},
                  {"type": "approval", "name": "second", "config": {"question": "Second?", "options": ["Yes", "No"]}},
                  {"type": "shell", "name": "done", "config": {"command": "./done"}}
                ]}""");
        packages.put(NEST, """
                {"id": "wf-nest", "name": "Nest", "blocks": [
                  {"type": "approval", "name": "top", "config": {"question": "Top?", "options": ["Yes", "No"]}},
                  {"type": "sub-workflow", "name": "child", "config": {"workflow_id": "../parent/child"}}
                ]}""");
        commands.on("test -f ready", "").on("./build", "built").on("./test", "tested").on("./done", "done")
                .on("./after", "after").on("echo ", "said")
                .on("./lint", "gate_status: YELLOW\ngate_category: lint\ngate_description: 3 warnings");
    }

    /** The services find the fake runtime, which has the shell image. */
    AppFixture withRuntime() {
        containers.present(SHELL_TAG, SHELL_ID);
        runtime = containers;
        return this;
    }

    /** The runtime also has the claude image, and the environment has a Claude credential. */
    AppFixture withClaude() {
        withRuntime();
        containers.present(CLAUDE_TAG, CLAUDE_ID);
        containers.present(EGRESS_TAG, EGRESS_ID);
        environment.put("ANTHROPIC_API_KEY", ANTHROPIC_KEY);
        return this;
    }

    /** What the services find on the machine: {@link #runtime} as it is when they look. */
    ContainerSupport containerSupport() {
        return new ContainerSupport(() -> {
            runtimeLookups.incrementAndGet();
            return Optional.ofNullable(runtime);
        }, builtIns, directories);
    }

    RunServices services() {
        return services(ledger);
    }

    /** With a short wait for a request in a running run's inbox, for the tests of a request nobody records. */
    RunServices services(Duration requestWait) {
        return new RunServices(store, packages, ledger, clock, ids, CoreBlocks.registry(null), commands,
                containerSupport(), sources, clis, this::secretValues, settings, registers, hooks, ACTOR,
                new SealSupport(sealStore, signer), requestWait, requestWait);
    }

    RunServices services(Ledger withLedger) {
        return new RunServices(store, packages, withLedger, clock, ids, CoreBlocks.registry(null), commands,
                containerSupport(), sources, clis, this::secretValues, settings, registers, hooks, ACTOR,
                new SealSupport(sealStore, signer));
    }

    /** The values of secrets as the services read them: {@link #credentials}, then {@link #environment}. */
    private SecretValues secretValues() {
        credentialReads.incrementAndGet();
        if (credentialsFailure != null) {
            throw credentialsFailure;
        }
        return new SecretValues(credentials, environment, credentialsModified, credentialsLocation,
                environmentAllowed);
    }

    /** A run of the package without the flag: a host run while the settings allow host execution. */
    static StartRequest request(Path pkg) {
        return new StartRequest(pkg, Map.of("version", "1.2"), WORKSPACE, false, false);
    }

    /** A run of the package in a checkout of {@code source} at {@code ref}, without the flag. */
    static StartRequest sourced(Path pkg, String source, String ref) {
        return new StartRequest(pkg, Map.of("version", "1.2"), null, false, false, source, ref);
    }

    /** A resume of the run without the flag: a host run's resume needs the settings to allow host execution. */
    static ResumeRequest resumeOf(String runId) {
        return new ResumeRequest(runId, false, false);
    }

    InMemoryStoredRun run(String runId) {
        return store.open(runId);
    }

    List<RecordedEvent> events(String runId) {
        return run(runId).log().readAll().stream().map(RecordedEvent::parse).toList();
    }

    List<String> types(String runId) {
        return events(runId).stream().map(RecordedEvent::type).toList();
    }

    List<RecordedEvent> ofType(String runId, String type) {
        return events(runId).stream().filter(e -> e.type().equals(type)).toList();
    }

    String text(String runId, RecordedEvent event, String field) {
        return new String(run(runId).blobs().get(event.blob(field).sha256()).orElseThrow(), StandardCharsets.UTF_8);
    }

    /**
     * Checkouts in memory: a checkout is a path in {@link #present}. It checks out {@link #sha}, names the source as
     * {@link #recorded} turns it, and throws {@link #failure} when a test sets it.
     */
    static final class Sources implements SourceCheckout {

        static final Path ROOT = Path.of("/home/workspaces");
        static final String SHA = "5c1f0e7a".repeat(5);

        final Set<Path> present = new LinkedHashSet<>();
        /** Every call: {@code checkout <source> <ref> <target>} and {@code remove <target>}. */
        final List<String> calls = new ArrayList<>();
        /** Where the checkouts lie; a test sets another directory. */
        Path root = ROOT;
        String sha = SHA;
        java.util.function.UnaryOperator<String> recorded = source -> source;
        RuntimeException failure;
        RuntimeException removeFailure;

        @Override
        public Path directoryOf(String runId) {
            return root.resolve(runId);
        }

        @Override
        public synchronized Checkout checkout(String source, String ref, Path target) {
            calls.add("checkout " + source + " " + ref + " " + target);
            if (failure != null) {
                throw failure;
            }
            if (!present.add(target)) {
                throw new IllegalArgumentException("The checkout " + target + " exists already");
            }
            return new Checkout(recorded.apply(source), sha);
        }

        @Override
        public synchronized boolean exists(Path target) {
            return present.contains(target);
        }

        @Override
        public synchronized void remove(Path target) {
            calls.add("remove " + target);
            if (removeFailure != null) {
                throw removeFailure;
            }
            present.remove(target);
        }

        synchronized List<String> calls() {
            return List.copyOf(calls);
        }
    }

    /** Workflow packages from JSON texts; the digest is the hash of the text. */
    static final class Packages implements PackageLoader {

        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final Map<Path, String> json = new LinkedHashMap<>();
        private final Map<Path, Map<String, PackageImage>> images = new LinkedHashMap<>();
        private final Map<Path, String> catalogs = new LinkedHashMap<>();
        /** What the files of a package besides {@code workflow.json} hold: part of its digest. */
        private final Map<Path, String> files = new LinkedHashMap<>();
        /** Where the seal file of a package is read from; {@code null} for packages that never carry one. */
        traceworks.core.testing.InMemorySealStore seals;

        /** Changes a file of the package in {@code directory} that is not its {@code workflow.json}: a script. */
        void script(Path directory, String content) {
            files.put(directory, content);
        }

        void put(Path directory, String workflowJson) {
            json.put(directory, workflowJson);
        }

        /** The package in {@code directory} carries this {@code catalog.json}. */
        void catalog(Path directory, String catalogJson) {
            catalogs.put(directory, catalogJson);
        }

        /** The package in {@code directory} is gone, as a moved or deleted directory is. */
        void remove(Path directory) {
            json.remove(directory);
        }

        /** The package in {@code directory} has these images under {@code images/}, each with {@link #IMAGE_DIGEST}. */
        void images(Path directory, String... names) {
            Map<String, PackageImage> named = new LinkedHashMap<>();
            for (String name : names) {
                named.put(name, new PackageImage(name, directory.resolve("images").resolve(name).toString(),
                        IMAGE_DIGEST, "FROM x"));
            }
            images.put(directory, named);
        }

        /** The package in {@code directory} has the image {@code name} with this Dockerfile. */
        void image(Path directory, String name, String dockerfile) {
            images.computeIfAbsent(directory, d -> new LinkedHashMap<>()).put(name, new PackageImage(name,
                    directory.resolve("images").resolve(name).toString(), IMAGE_DIGEST, dockerfile));
        }

        /** The digest of every package image: its tag ends in {@code :bbbbbbbbbbbb}. */
        static final String IMAGE_DIGEST = "b".repeat(64);

        /** The package in {@code directory} is there and cannot be read, as a folder without read permission. */
        void unreadable(Path directory) {
            unreadable.add(directory);
        }

        private final java.util.Set<Path> unreadable = new java.util.LinkedHashSet<>();

        @Override
        public LoadedPackage load(Path location) {
            Path dir = location.getFileName() != null && location.getFileName().toString().equals("workflow.json")
                    ? location.getParent() : location;
            if (unreadable.contains(dir.normalize())) {
                throw new java.io.UncheckedIOException("Cannot read the package " + dir.normalize() + ": "
                        + dir.normalize() + "/run.sh", new java.nio.file.AccessDeniedException(dir + "/run.sh"));
            }
            String text = json.get(dir.normalize());
            if (text == null) {
                throw new IllegalArgumentException("Not a package: " + dir + " has no workflow.json");
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            try {
                WorkflowDefinition definition = traceworks.core.model.WorkflowJson.read(bytes);
                String catalog = catalogs.get(dir.normalize());
                String script = files.get(dir.normalize());
                String digest = EventChain.sha256Hex(script == null ? bytes
                        : (text + "\u0000" + script).getBytes(StandardCharsets.UTF_8));
                return new LoadedPackage(dir.normalize(), definition, bytes, digest, null, null,
                        images.getOrDefault(dir.normalize(), Map.of()), List.of(),
                        catalog == null ? null : catalog.getBytes(StandardCharsets.UTF_8),
                        seals == null ? null : seals.seal(dir.normalize()));
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read " + dir + ": " + e.getMessage(), e);
            }
        }

        @Override
        public List<Path> packagesIn(Path directory) {
            return json.keySet().stream().filter(p -> directory.equals(p.getParent())).sorted().toList();
        }

        @Override
        public Optional<String> idOf(Path packageDirectory) {
            return Optional.ofNullable(json.get(packageDirectory)).map(text -> {
                try {
                    return MAPPER.readTree(text).path("id").asText();
                } catch (IOException e) {
                    return null;
                }
            });
        }
    }

    /** A listener that keeps every event and lets a test wait for one. */
    static final class Events implements java.util.function.Consumer<RecordedEvent> {

        private final List<RecordedEvent> seen = new ArrayList<>();
        private final List<String> threads = new ArrayList<>();

        @Override
        public synchronized void accept(RecordedEvent event) {
            seen.add(event);
            threads.add(Thread.currentThread().getName());
            notifyAll();
        }

        synchronized List<RecordedEvent> all() {
            return List.copyOf(seen);
        }

        synchronized List<String> threads() {
            return List.copyOf(threads);
        }

        /** Waits for the first event that matches; fails the test after {@link #WAIT}. */
        synchronized RecordedEvent await(Predicate<RecordedEvent> condition) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (true) {
                for (RecordedEvent event : seen) {
                    if (condition.test(event)) {
                        return event;
                    }
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError("no matching event within " + WAIT + "; seen: "
                            + seen.stream().map(RecordedEvent::type).toList());
                }
                wait(Math.max(1, left / 1_000_000));
            }
        }

        RecordedEvent await(String type) throws InterruptedException {
            return await(e -> e.type().equals(type));
        }
    }

    /** Work on a thread of its own, such as a run that waits for an answer. */
    static final class Background<T> {

        private final AtomicReference<T> result = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        Background(Supplier<T> work) {
            thread = Thread.ofPlatform().name("test-background").start(() -> {
                try {
                    result.set(work.get());
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
        }

        T join() throws InterruptedException {
            if (!thread.join(WAIT)) {
                throw new AssertionError("the background work did not end within " + WAIT);
            }
            if (failure.get() != null) {
                throw new AssertionError("the background work failed", failure.get());
            }
            return result.get();
        }

        /** Waits for the end and returns what the work threw; fails when it threw nothing. */
        Throwable failure() throws InterruptedException {
            if (!thread.join(WAIT)) {
                throw new AssertionError("the background work did not end within " + WAIT);
            }
            if (failure.get() == null) {
                throw new AssertionError("the background work did not fail; it returned " + result.get());
            }
            return failure.get();
        }

        /** Waits for the end without looking at the result, for cleaning up after a test. */
        void awaitEnd() throws InterruptedException {
            thread.join(WAIT);
        }
    }

    /** The threads of the use cases that are still alive; none may outlive a use case. */
    static List<String> liveServiceThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("traceworks-"))
                .toList();
    }
}
