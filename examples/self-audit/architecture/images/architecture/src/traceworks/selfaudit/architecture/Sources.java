package traceworks.selfaudit.architecture;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The audited sources, parsed and attributed in <em>one</em> {@link JavacTask}, so a reference resolves to the type it
 * names, not to its text. The class path is empty: the sources resolve against the platform and each other only, so a
 * library type (Jackson) does not resolve, which costs nothing here, since only references between the sources make the
 * dependency graph.
 *
 * <p>A file that does not parse, a second copy of a class, a symbolic link (to a file or a directory) and a file that
 * is no UTF-8 are not read, and {@link Loaded#notRead} names them.
 */
final class Sources {

    static final int SOURCE_ROOT_DEPTH = 6;
    private static final Set<String> SKIPPED = Set.of("target", "node_modules", "build");

    /** A Java file that was read: its path relative to the root, with {@code /}, its text and its syntax tree. */
    record Unit(String path, String content, List<String> lines, CompilationUnitTree tree) {
    }

    /** A file that was not read, with the reason. */
    record NotRead(String file, String reason) {
    }

    /** What {@link #load} read, and the compiler task that attributed it. */
    record Loaded(List<Unit> units, List<NotRead> notRead, JavacTask task, DocTrees trees) {
    }

    /** The source roots discovery found, and the symbolic links to directories it could not enter. */
    record Discovery(List<Path> roots, List<Path> linkedDirectories) {
    }

    private Sources() {
    }

    /**
     * Every {@code src/main/java} directory up to {@value #SOURCE_ROOT_DEPTH} levels below {@code root}, sorted, except
     * in {@code target}, {@code build}, {@code node_modules} and hidden directories; and every symbolic link to a
     * directory there, which the walk does not enter.
     */
    static Discovery discover(Path root) throws IOException {
        List<Path> roots = new ArrayList<>();
        List<Path> linked = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root, SOURCE_ROOT_DEPTH)) {
            walk.filter(dir -> {
                for (Path part : root.relativize(dir)) {
                    String name = part.toString();
                    if (SKIPPED.contains(name) || name.startsWith(".")) return false;
                }
                return true;
            }).forEach(dir -> {
                if (Files.isSymbolicLink(dir)) {
                    if (Files.isDirectory(dir)) linked.add(dir);
                } else if (Files.isDirectory(dir) && dir.endsWith(Path.of("src", "main", "java"))) {
                    roots.add(dir);
                }
            });
        }
        roots.sort(null);
        linked.sort(null);
        return new Discovery(List.copyOf(roots), List.copyOf(linked));
    }

    /** The Java files below {@code sourceRoots}, parsed and attributed, with paths relative to {@code root}. */
    static Loaded load(Path root, List<Path> sourceRoots) throws IOException {
        List<NotRead> notRead = new ArrayList<>();
        Map<URI, Source> sources = new LinkedHashMap<>();
        Set<Path> files = new TreeSet<>();
        Set<Path> linked = new TreeSet<>();
        for (Path sourceRoot : sourceRoots) {
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                walk.forEach(p -> {
                    String name = p.getFileName().toString();
                    if (Files.isSymbolicLink(p) && Files.isDirectory(p)) {
                        linked.add(p);
                    } else if (name.endsWith(".java") && !name.equals("module-info.java")
                            && (Files.isRegularFile(p) || Files.isSymbolicLink(p))) {
                        files.add(p);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
        for (Path link : linked) notRead.add(new NotRead(relative(root, link), "a symbolic link to a directory, not read"));
        for (Path file : files) {
            String path = relative(root, file);
            if (Files.isSymbolicLink(file)) {
                notRead.add(new NotRead(path, "a symbolic link, not read"));
                continue;
            }
            try {
                Source source = new Source(sources.size(), path, Files.readString(file));
                sources.put(source.toUri(), source);
            } catch (MalformedInputException e) {
                notRead.add(new NotRead(path, "not UTF-8, not read"));
            }
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("this Java has no compiler: it needs a JDK");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        // -XDshould-stop.ifError=FLOW: an unresolved library type is an error, and without this option javac stops
        // attributing the classes after the first one that has one. -Xmaxerrs: every file that does not parse is named.
        List<String> options = List.of("-proc:none", "-implicit:none", "-nowarn", "-Xlint:none",
                "-XDshould-stop.ifError=FLOW", "-Xmaxerrs", Integer.toString(Integer.MAX_VALUE));
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null);
        // An empty class path: the sources resolve against the platform and each other, never against the class path
        // of the tool that reads them
        fileManager.setLocation(StandardLocation.CLASS_PATH, List.of());
        JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, options, null, sources.values());
        List<Unit> units = new ArrayList<>();
        if (sources.isEmpty()) return new Loaded(List.of(), sorted(notRead), task, DocTrees.instance(task));
        List<CompilationUnitTree> trees = new ArrayList<>();
        task.parse().forEach(trees::add);

        Set<String> broken = new HashSet<>();
        // Before analyze, every error is a syntax error
        notReadFor(diagnostics.getDiagnostics(), sources, broken, notRead, d -> true);
        int seen = diagnostics.getDiagnostics().size();
        task.analyze();
        // A second copy of a class is entered as an error and never attributed
        notReadFor(diagnostics.getDiagnostics().subList(seen, diagnostics.getDiagnostics().size()), sources, broken,
                notRead, d -> "compiler.err.duplicate.class".equals(d.getCode()));
        for (CompilationUnitTree tree : trees) {
            Source source = sources.get(tree.getSourceFile().toUri());
            if (broken.contains(source.path)) continue;
            units.add(new Unit(source.path, source.content, source.content.lines().toList(), tree));
        }
        units.sort(Comparator.comparing(Unit::path));
        return new Loaded(List.copyOf(units), sorted(notRead), task, DocTrees.instance(task));
    }

    /** Names each source with an error that {@code counts} as not read, once, with the first such error. */
    private static void notReadFor(List<Diagnostic<? extends JavaFileObject>> diagnostics, Map<URI, Source> sources,
                                   Set<String> broken, List<NotRead> notRead,
                                   Predicate<Diagnostic<? extends JavaFileObject>> counts) {
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            // javac wraps the file objects it is given, so they are found again by their URI
            Source source = d.getSource() == null ? null : sources.get(d.getSource().toUri());
            if (d.getKind() != Diagnostic.Kind.ERROR || source == null || !counts.test(d)) continue;
            if (broken.add(source.path)) {
                notRead.add(new NotRead(source.path,
                        "line " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT).lines().findFirst().orElse("")));
            }
        }
    }

    private static List<NotRead> sorted(List<NotRead> notRead) {
        return notRead.stream().sorted(Comparator.comparing(NotRead::file)).toList();
    }

    static String relative(Path root, Path file) {
        return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString()
                .replace('\\', '/');
    }

    /**
     * A source file for javac, read from the text the tool read, so lines and positions agree. Its URI holds a number,
     * so two names never share one, and ends in the file name, which javac compares with a public class.
     */
    private static final class Source extends SimpleJavaFileObject {
        private final String path;
        private final String content;

        Source(int number, String path, String content) {
            super(uri(number, path), Kind.SOURCE);
            this.path = path;
            this.content = content;
        }

        private static URI uri(int number, String path) {
            try {
                return new URI("string", null, "/" + number + "/" + path, null);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("no URI for " + path, e);
            }
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
