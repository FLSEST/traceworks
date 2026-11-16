package traceworks.selfaudit.areas;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import traceworks.selfaudit.areas.RuleFile.Argument;
import traceworks.selfaudit.areas.RuleFile.Matcher;
import traceworks.selfaudit.areas.RuleFile.Rule;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Finds the code the rules point at. All source roots go into <em>one</em> {@link JavacTask}, which parses and
 * attributes them, so a call is matched by the type it resolves to, not by its text: whatever the variable is called,
 * across line breaks and through method references.
 *
 * <p>Without a class path, library types do not resolve. A call on such a type is matched through the type it is
 * written with (a creation's type, a cast, a variable's declared type or its initializer's creation, read with the
 * imports of the file that writes it; {@code "resolved": "import"}); a receiver written with another type does not
 * match. Where no type is written (the result of a call whose overload failed, a method a source class inherits from
 * a library), the method name alone matches ({@code "resolved": "name"}), never for a {@code *} matcher.
 * {@link Result#unresolved} counts the references that did not resolve.
 *
 * <p>A file that does not parse, a second copy of a class, a symbolic link (to a file or a directory) and a file that
 * is no UTF-8 are not read, and {@link Result#notParsed} names them.
 *
 * <p>The same pass knows who calls whom: {@link Result#members} names every member of the files read, and
 * {@link Result#calls} every call, creation and method reference that resolved to a member of these sources, from
 * the member that holds it. A call of a method that others override counts each of them too, so a call through an
 * interface reaches every implementation in the sources. A call that did not resolve (a receiver of a library type)
 * is no call here, and neither is the read of a field. A call the compiler resolved to something a class has
 * without a line of its own (the constructor nobody wrote, the accessor of a record) goes to the head of that
 * class ({@link Result#heads}). {@link Result#resolves} names what each member's calls resolved to, by declaring
 * type and signature, and {@link Result#related} which files hold a supertype or a subtype of a type of a file. No
 * rule looks at these; the check of the changes takes its neighbours and the meaning of an area from them
 * ({@link Changes}).
 */
final class Finder {

    /** A Java file that was read: its path relative to the root, with {@code /}, and its lines. */
    record SourceFile(String path, List<String> lines) {
    }

    /**
     * The member that holds a hit: a method, constructor, field or initializer of a named class, with its first and
     * last line. A hit outside any member (an annotation of a class) is a {@code classLevel} hit on its own line.
     */
    record Member(String label, int start, int end, boolean classLevel) {
    }

    /**
     * One place that a rule points at. {@code resolved} is {@code type}, {@code import} (a library type, through the
     * written type) or {@code name} (the method name alone).
     */
    record Hit(String rule, String file, int line, String what, String resolved, Member member) {
    }

    /** A member where it is declared: the file, and the member with its lines. */
    record Declared(String file, Member member) {
    }

    /** One member of the sources calls, creates or refers to another, directly or through a method it overrides. */
    record Call(Declared from, Declared to) {
    }

    /** A file that was not read, with the reason. */
    record NotParsed(String file, String reason) {
    }

    /**
     * What {@link #find} found. {@code unresolvedTargets} are the types of the rules that are neither on the platform
     * nor in the sources, so their matchers can match only through imports or names.
     */
    record Result(List<SourceFile> files, List<Hit> hits, List<NotParsed> notParsed, int unresolved,
                  List<String> unresolvedTargets, List<Declared> members, List<Call> calls, List<Declared> heads,
                  Map<Declared, List<String>> resolves, Map<String, Set<String>> related) {

        Result(List<SourceFile> files, List<Hit> hits, List<NotParsed> notParsed, int unresolved) {
            this(files, hits, notParsed, unresolved, List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
        }

        Result(List<SourceFile> files, List<Hit> hits, List<NotParsed> notParsed, int unresolved,
               List<String> unresolvedTargets, List<Declared> members, List<Call> calls) {
            this(files, hits, notParsed, unresolved, unresolvedTargets, members, calls, List.of(), Map.of(), Map.of());
        }
    }

    /** A constant value that is not known, and the mark of an expression that is no constant. */
    private static final Object OTHER_CONSTANT = new Object();
    private static final Object NOT_CONSTANT = new Object();

    private static final int MAX_LITERAL_SHOWN = 60;
    private static final long NOPOS = -1;

    private Finder() {
    }

    /** The hits of {@code rules} in the Java files below {@code sourceRoots}, with paths relative to {@code root}. */
    static Result find(Path root, List<Path> sourceRoots, RuleFile rules) throws IOException {
        List<NotParsed> notParsed = new ArrayList<>();
        Map<URI, Source> sources = new LinkedHashMap<>();
        Walk walk = walk(sourceRoots);
        for (Path link : walk.linkedDirectories()) {
            notParsed.add(new NotParsed(relative(root, link), "a symbolic link to a directory, not read"));
        }
        for (Path file : walk.javaFiles()) {
            String path = relative(root, file);
            if (Files.isSymbolicLink(file)) {
                notParsed.add(new NotParsed(path, "a symbolic link, not read"));
                continue;
            }
            try {
                Source source = new Source(sources.size(), path, Files.readString(file));
                sources.put(source.toUri(), source);
            } catch (MalformedInputException e) {
                notParsed.add(new NotParsed(path, "not UTF-8, not read"));
            }
        }
        if (sources.isEmpty()) return new Result(List.of(), List.of(), sortedNotParsed(notParsed), 0);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("this Java has no compiler: it needs a JDK");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        // -XDshould-stop.ifError=FLOW: an unresolved library type is an error, and without this option javac stops
        // attributing the classes after the first one that has one. -Xmaxerrs: javac reports 100 errors by default,
        // and every file that does not parse must be named.
        List<String> options = List.of("-proc:none", "-implicit:none", "-nowarn", "-Xlint:none",
                "-XDshould-stop.ifError=FLOW", "-Xmaxerrs", Integer.toString(Integer.MAX_VALUE));
        // An empty class path: the audited sources resolve against the platform and each other, never against the
        // class path of the tool that reads them
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null);
        fileManager.setLocation(StandardLocation.CLASS_PATH, List.of());
        JavacTask task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, options, null, sources.values());
        List<CompilationUnitTree> units = new ArrayList<>();
        task.parse().forEach(units::add);

        Set<String> broken = new HashSet<>();
        // Before analyze, every error is a syntax error
        notReadFor(diagnostics.getDiagnostics(), sources, broken, notParsed, d -> true);
        int seen = diagnostics.getDiagnostics().size();
        task.analyze();
        // A second copy of a class is entered as an error and never attributed
        notReadFor(diagnostics.getDiagnostics().subList(seen, diagnostics.getDiagnostics().size()), sources, broken,
                notParsed, d -> "compiler.err.duplicate.class".equals(d.getCode()));

        Trees trees = Trees.instance(task);
        Scan scan = new Scan(trees, task.getElements(), task.getTypes(), rules);
        List<String> unresolvedTargets = scan.checkTargets();
        List<SourceFile> files = new ArrayList<>();
        for (CompilationUnitTree unit : units) {
            Source source = sources.get(unit.getSourceFile().toUri());
            if (broken.contains(source.path)) continue;
            files.add(new SourceFile(source.path, source.content.lines().toList()));
            scan.unit(unit, source.path);
        }
        files.sort(Comparator.comparing(SourceFile::path));
        List<String> ruleOrder = rules.rules().stream().map(Rule::id).toList();
        List<Hit> hits = new ArrayList<>(scan.hits);
        hits.sort(Comparator.comparing(Hit::file).thenComparingInt(Hit::line)
                .thenComparingInt(h -> ruleOrder.indexOf(h.rule())));
        Comparator<Declared> place = Comparator.comparing(Declared::file)
                .thenComparingInt((Declared d) -> d.member().start()).thenComparing(d -> d.member().label());
        List<Declared> members = new ArrayList<>(scan.members);
        members.sort(place);
        return new Result(List.copyOf(files), List.copyOf(hits), sortedNotParsed(notParsed), scan.unresolved,
                unresolvedTargets, List.copyOf(members), scan.calls(), scan.heads(place), scan.resolves(),
                scan.related());
    }

    /** Names each source with an error that {@code counts} as not read, once, with the first such error. */
    private static void notReadFor(List<Diagnostic<? extends JavaFileObject>> diagnostics, Map<URI, Source> sources,
                                   Set<String> broken, List<NotParsed> notParsed,
                                   java.util.function.Predicate<Diagnostic<? extends JavaFileObject>> counts) {
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            // javac wraps the file objects it is given, so they are found again by their URI
            Source source = d.getSource() == null ? null : sources.get(d.getSource().toUri());
            if (d.getKind() != Diagnostic.Kind.ERROR || source == null || !counts.test(d)) continue;
            if (broken.add(source.path)) {
                notParsed.add(new NotParsed(source.path,
                        "line " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT).lines().findFirst().orElse("")));
            }
        }
    }

    private static List<NotParsed> sortedNotParsed(List<NotParsed> notParsed) {
        return notParsed.stream().sorted(Comparator.comparing(NotParsed::file)).toList();
    }

    /** The {@code .java} files below the roots, and the symbolic links to directories, which a walk does not enter. */
    private record Walk(List<Path> javaFiles, List<Path> linkedDirectories) {
    }

    /** The walk of the roots, sorted; a module declaration is no class and is left out. */
    private static Walk walk(List<Path> sourceRoots) throws IOException {
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
        return new Walk(List.copyOf(files), List.copyOf(linked));
    }

    private static String relative(Path root, Path file) {
        return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString()
                .replace('\\', '/');
    }

    /**
     * A source file for javac, read from the text the finder read, so lines and positions agree. Its URI holds a
     * number, so two names never share one, and ends in the file name, which javac compares with a public class.
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

    /** The scan of the compilation units: collects hits and counts unresolved references. */
    private static final class Scan {
        private final Trees trees;
        private final Elements elements;
        private final Types types;
        private final RuleFile rules;
        private final SourcePositions positions;
        private final Map<String, Optional<TypeElement>> targets = new HashMap<>();
        private final Map<CompilationUnitTree, Map<String, String>> importsByUnit = new HashMap<>();
        final List<Hit> hits = new ArrayList<>();
        /** Every member of the files scanned, in the order of the scan. */
        final List<Declared> members = new ArrayList<>();
        /** The member each method and constructor of the sources is, or lies in (a method of an anonymous class). */
        private final Map<Element, Declared> declaredBy = new HashMap<>();
        /** Each resolved call: the member that holds it, and the method or constructor it resolved to. */
        private final List<Map.Entry<Declared, ExecutableElement>> called = new ArrayList<>();
        /** The head of each named class of the sources: its lines before its first member. */
        private final Map<Element, Declared> headOf = new LinkedHashMap<>();
        int unresolved;

        Scan(Trees trees, Elements elements, Types types, RuleFile rules) {
            this.trees = trees;
            this.elements = elements;
            this.types = types;
            this.rules = rules;
            this.positions = trees.getSourcePositions();
        }

        /**
         * Checks the rules' targets: a {@code call} on a type that resolves must name a method of it, else the rule
         * file is refused. Returns the target types that do not resolve, sorted.
         */
        List<String> checkTargets() {
            Set<String> missing = new TreeSet<>();
            for (Rule rule : rules.rules()) {
                for (Matcher matcher : rule.matchers()) {
                    String type = switch (matcher) {
                        case Matcher.New m -> m.type();
                        case Matcher.Call m -> m.type();
                        case Matcher.Literal m -> null;
                    };
                    if (type == null) continue;
                    Optional<TypeElement> element = target(type);
                    if (element.isEmpty()) {
                        missing.add(type);
                    } else if (matcher instanceof Matcher.Call call && !call.method().equals("*")
                            && elements.getAllMembers(element.get()).stream().noneMatch(e ->
                            e.getKind() == ElementKind.METHOD && e.getSimpleName().contentEquals(call.method()))) {
                        throw new IllegalArgumentException(
                                "rule " + rule.id() + ": " + type + " has no method " + call.method());
                    }
                }
            }
            return List.copyOf(missing);
        }

        /**
         * The calls between the members of the sources, in the order they were found, each once. A method that is
         * called stands for every method of the sources that overrides it: the type of the receiver says which of
         * them runs, and that is not known here. A member's call of itself is none.
         */
        /** The heads of the named classes, in the order of {@code place}. */
        List<Declared> heads(Comparator<Declared> place) {
            return headOf.values().stream().sorted(place).toList();
        }

        /** What a method or constructor is, apart from where it is written: its declaring type and its signature. */
        private String identity(ExecutableElement callee) {
            StringBuilder out = new StringBuilder(String.valueOf(callee.getEnclosingElement())).append('#')
                    .append(callee.getSimpleName()).append('(');
            for (int i = 0; i < callee.getParameters().size(); i++) {
                if (i > 0) out.append(',');
                out.append(types.erasure(callee.getParameters().get(i).asType()));
            }
            return out.append(')').toString();
        }

        /** What the calls of each member resolved to, each target once, sorted. */
        Map<Declared, List<String>> resolves() {
            Map<Declared, Set<String>> targets = new HashMap<>();
            for (Map.Entry<Declared, ExecutableElement> call : called) {
                targets.computeIfAbsent(call.getKey(), k -> new TreeSet<>()).add(identity(call.getValue()));
            }
            Map<Declared, List<String>> resolves = new HashMap<>();
            targets.forEach((member, set) -> resolves.put(member, List.copyOf(set)));
            return resolves;
        }

        /** For each file, the other files that hold a supertype or a subtype of one of its named classes. */
        Map<String, Set<String>> related() {
            Map<String, Set<String>> related = new HashMap<>();
            for (Map.Entry<Element, Declared> type : headOf.entrySet()) {
                Deque<TypeMirror> open = new ArrayDeque<>(types.directSupertypes(type.getKey().asType()));
                Set<Element> seen = new HashSet<>();
                while (!open.isEmpty()) {
                    Element above = types.asElement(open.removeFirst());
                    if (above == null || !seen.add(above)) continue;
                    open.addAll(types.directSupertypes(above.asType()));
                    Declared head = headOf.get(above);
                    if (head == null || head.file().equals(type.getValue().file())) continue;
                    related.computeIfAbsent(type.getValue().file(), k -> new TreeSet<>()).add(head.file());
                    related.computeIfAbsent(head.file(), k -> new TreeSet<>()).add(type.getValue().file());
                }
            }
            return related;
        }

        List<Call> calls() {
            Map<String, List<ExecutableElement>> byName = new HashMap<>();
            for (Element element : declaredBy.keySet()) {
                if (element.getKind() == ElementKind.METHOD) {
                    byName.computeIfAbsent(element.getSimpleName().toString(), k -> new ArrayList<>())
                            .add((ExecutableElement) element);
                }
            }
            Set<Call> calls = new java.util.LinkedHashSet<>();
            for (Map.Entry<Declared, ExecutableElement> call : called) {
                Declared from = call.getKey();
                ExecutableElement callee = call.getValue();
                Declared direct = declaredBy.get(callee);
                // What a class has without a line of its own (a constructor nobody wrote, a record's accessor) is
                // written in its head
                if (direct == null) direct = headOf.get(callee.getEnclosingElement());
                if (direct != null && !direct.equals(from)) calls.add(new Call(from, direct));
                if (callee.getKind() != ElementKind.METHOD) continue;
                for (ExecutableElement other : byName.getOrDefault(callee.getSimpleName().toString(), List.of())) {
                    if (other != callee && other.getEnclosingElement() instanceof TypeElement type
                            && elements.overrides(other, callee, type) && !declaredBy.get(other).equals(from)) {
                        calls.add(new Call(from, declaredBy.get(other)));
                    }
                }
            }
            Comparator<Declared> place = Comparator.comparing(Declared::file)
                    .thenComparingInt((Declared d) -> d.member().start()).thenComparing(d -> d.member().label());
            return calls.stream().sorted(Comparator.comparing(Call::from, place)
                    .thenComparing(Call::to, place)).toList();
        }

        private Optional<TypeElement> target(String type) {
            return targets.computeIfAbsent(type, t -> Optional.ofNullable(elements.getTypeElement(t)));
        }

        void unit(CompilationUnitTree unit, String path) {
            String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            List<Rule> applying = rules.rules().stream().filter(r -> r.appliesIn(pkg)).toList();
            new UnitScanner(unit, path, applying).scan(unit, null);
        }

        /** The single-type imports of a unit: simple name to qualified name. */
        private Map<String, String> imports(CompilationUnitTree unit) {
            return importsByUnit.computeIfAbsent(unit, u -> {
                Map<String, String> imports = new HashMap<>();
                for (ImportTree imported : u.getImports()) {
                    if (imported.isStatic()) continue;
                    String name = imported.getQualifiedIdentifier().toString();
                    if (!name.endsWith(".*")) imports.put(name.substring(name.lastIndexOf('.') + 1), name);
                }
                return imports;
            });
        }

        /** The qualified name a type written as {@code written} has through the imports of {@code unit}, or null. */
        private String importedName(CompilationUnitTree unit, String written) {
            String name = withoutGenerics(written).strip();
            if (name.isEmpty() || name.startsWith("<")) return null;
            int dot = name.indexOf('.');
            String first = dot < 0 ? name : name.substring(0, dot);
            String imported = imports(unit).get(first);
            if (imported != null) return dot < 0 ? imported : imported + name.substring(dot);
            return dot > 0 && Character.isLowerCase(name.charAt(0)) ? name : null;
        }

        /** Whether {@code type} is {@code target} or a subtype of it; false for a target that does not resolve. */
        private boolean isA(TypeMirror type, String target) {
            if (type == null || type.getKind() != TypeKind.DECLARED) return false;
            Optional<TypeElement> element = target(target);
            return element.isPresent()
                    && types.isSubtype(types.erasure(type), types.erasure(element.get().asType()));
        }

        /** The scanner of one compilation unit. */
        private final class UnitScanner extends TreePathScanner<Void, Void> {
            private final CompilationUnitTree unit;
            private final String path;
            private final List<Rule> applying;
            private final Deque<String> classNames = new ArrayDeque<>();
            /** The variables declared so far in this unit, so a local's declaration is found again. */
            private final Map<Element, VariableTree> declarations = new HashMap<>();
            private Member member;
            /** {@link #member} with its file, as the calls name it. */
            private Declared declared;

            UnitScanner(CompilationUnitTree unit, String path, List<Rule> applying) {
                this.unit = unit;
                this.path = path;
                this.applying = applying;
            }

            @Override
            public Void visitClass(ClassTree node, Void unused) {
                Tree parent = getCurrentPath().getParentPath().getLeaf();
                boolean named = member == null
                        && (parent instanceof CompilationUnitTree || parent instanceof ClassTree);
                if (!named) return super.visitClass(node, unused);
                classNames.addLast(node.getSimpleName().toString());
                Element type = trees.getElement(getCurrentPath());
                if (type != null) {
                    int start = line(positions.getStartPosition(unit, node));
                    int end = line(positions.getEndPosition(unit, node) - 1);
                    for (Tree inner : node.getMembers()) {
                        long at = positions.getStartPosition(unit, inner);
                        // A member javac made up has no place, or the place of the class itself
                        if (at != NOPOS && positions.getEndPosition(unit, inner) != NOPOS && line(at) > start) {
                            end = Math.min(end, line(at) - 1);
                        }
                    }
                    headOf.put(type, new Declared(path, new Member(String.join(".", classNames)
                            + " (the head of its class)", start, Math.max(start, end), true)));
                }
                try {
                    return super.visitClass(node, unused);
                } finally {
                    classNames.removeLast();
                }
            }

            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                // A constructor javac generates has no place in the source, and its super() is javac's, not the code's
                if (positions.getEndPosition(unit, node) == NOPOS) return null;
                String name = node.getName().contentEquals("<init>") ? null : node.getName().toString();
                Element element = trees.getElement(getCurrentPath());
                return inMember(node, () -> label(name) + "(" + parameters(node) + ")", () -> {
                    // Inside a member, a method (of an anonymous or a local class) is part of that member
                    if (element != null && declared != null) declaredBy.put(element, declared);
                    return super.visitMethod(node, unused);
                });
            }

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                Element declared = trees.getElement(getCurrentPath());
                if (declared != null) declarations.put(declared, node);
                return inMember(node, () -> label(node.getName().toString()), () -> super.visitVariable(node, unused));
            }

            @Override
            public Void visitBlock(BlockTree node, Void unused) {
                return inMember(node, () -> label(node.isStatic() ? "{static initializer}" : "{initializer}"),
                        () -> super.visitBlock(node, unused));
            }

            /** Runs {@code scan} with {@code node} as the member, when it is a member of a named class. */
            private Void inMember(Tree node, Supplier<String> label, Supplier<Void> scan) {
                if (member != null || !(getCurrentPath().getParentPath().getLeaf() instanceof ClassTree)) {
                    return scan.get();
                }
                member = new Member(label.get(), line(positions.getStartPosition(unit, node)),
                        line(positions.getEndPosition(unit, node) - 1), false);
                declared = new Declared(path, member);
                members.add(declared);
                try {
                    return scan.get();
                } finally {
                    member = null;
                    declared = null;
                }
            }

            /** Notes that the member around the current node calls {@code callee}, when that resolved. */
            private void calls(Element callee) {
                if (declared != null && callee instanceof ExecutableElement executable) {
                    called.add(Map.entry(declared, executable));
                }
            }

            private String label(String memberName) {
                String classes = String.join(".", classNames);
                return memberName == null ? classes : classes + "." + memberName;
            }

            private String parameters(MethodTree node) {
                List<String> names = new ArrayList<>();
                for (VariableTree parameter : node.getParameters()) {
                    names.add(withoutGenerics(parameter.getType().toString()).replaceAll("@\\S+\\s+", ""));
                }
                Element element = trees.getElement(getCurrentPath());
                if (element instanceof ExecutableElement executable && executable.isVarArgs() && !names.isEmpty()) {
                    String last = names.getLast();
                    if (last.endsWith("[]")) names.set(names.size() - 1, last.substring(0, last.length() - 2) + "...");
                }
                return String.join(", ", names);
            }

            @Override
            public Void visitNewClass(NewClassTree node, Void unused) {
                calls(trees.getElement(getCurrentPath()));
                TypeMirror type = trees.getTypeMirror(getCurrentPath());
                String imported = null;
                if (type == null || type.getKind() == TypeKind.ERROR) {
                    unresolved++;
                    imported = importedName(unit, node.getIdentifier().toString());
                }
                for (Rule rule : applying) {
                    for (Matcher matcher : rule.matchers()) {
                        if (matcher instanceof Matcher.New m && (imported != null ? m.type().equals(imported)
                                : isA(type, m.type()))) {
                            hit(rule, node, "new " + m.type(), imported != null ? "import" : "type");
                            break;
                        }
                    }
                }
                return super.visitNewClass(node, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                ExpressionTree select = node.getMethodSelect();
                String name = select instanceof MemberSelectTree ms ? ms.getIdentifier().toString()
                        : select.toString();
                Element element = trees.getElement(getCurrentPath());
                calls(element);
                // Constructor chaining is part of the constructor, no call of a method a rule could name
                if (name.equals("this") || name.equals("super")) return super.visitMethodInvocation(node, unused);
                ExpressionTree receiverTree = select instanceof MemberSelectTree ms ? ms.getExpression() : null;
                TypeMirror receiver = receiverTree != null ? typeOf(receiverTree) : null;
                boolean resolved = element instanceof ExecutableElement;
                String written = null;
                if (!resolved) {
                    unresolved++;
                    written = receiverTree != null ? writtenType(receiverTree) : null;
                }
                TypeMirror declaring = resolved ? element.getEnclosingElement().asType() : null;
                List<Object> arguments = new ArrayList<>();
                for (ExpressionTree argument : node.getArguments()) {
                    arguments.add(constantValue(new TreePath(getCurrentPath(), argument)));
                }
                // The line of the method name, not of the receiver a fluent chain starts with
                Tree at = select instanceof MemberSelectTree ms ? ms : node;
                long position = select instanceof MemberSelectTree
                        ? positions.getEndPosition(unit, at) - 1 : positions.getStartPosition(unit, node);
                for (Rule rule : applying) {
                    for (Matcher matcher : rule.matchers()) {
                        if (!(matcher instanceof Matcher.Call m) || !(m.method().equals("*") || m.method().equals(name))) {
                            continue;
                        }
                        String how = resolved
                                ? (isA(declaring, m.type()) || isA(receiver, m.type()) ? "type" : null)
                                : unresolvedMatch(m, written);
                        if (how != null && argumentMatches(m.argument(), arguments)) {
                            hitAt(rule, position, "call " + m.type() + "#" + name, how);
                            break;
                        }
                    }
                }
                return super.visitMethodInvocation(node, unused);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                boolean creation = node.getMode() == MemberReferenceTree.ReferenceMode.NEW;
                TypeMirror qualifier = typeOf(node.getQualifierExpression());
                Element element = trees.getElement(getCurrentPath());
                calls(element);
                boolean resolved = creation ? qualifier != null && qualifier.getKind() == TypeKind.DECLARED
                        : element instanceof ExecutableElement;
                String written = null;
                if (!resolved) {
                    unresolved++;
                    written = writtenType(node.getQualifierExpression());
                }
                String name = node.getName().toString();
                TypeMirror declaring = !creation && resolved ? element.getEnclosingElement().asType() : null;
                for (Rule rule : applying) {
                    for (Matcher matcher : rule.matchers()) {
                        String what = null;
                        String how = null;
                        if (creation && matcher instanceof Matcher.New m) {
                            how = resolved ? (isA(qualifier, m.type()) ? "type" : null)
                                    : m.type().equals(written) ? "import" : null;
                            what = "new " + m.type();
                        } else if (!creation && matcher instanceof Matcher.Call m
                                && (m.method().equals("*") || m.method().equals(name))
                                && m.argument().kind() != Argument.Kind.CONSTANT) {
                            how = resolved ? (isA(declaring, m.type()) || isA(qualifier, m.type()) ? "type" : null)
                                    : unresolvedMatch(m, written);
                            what = "call " + m.type() + "#" + name;
                        }
                        if (how != null) {
                            hit(rule, node, what, how);
                            break;
                        }
                    }
                }
                return super.visitMemberReference(node, unused);
            }

            @Override
            public Void visitLiteral(LiteralTree node, Void unused) {
                if (node.getKind() == Tree.Kind.STRING_LITERAL && node.getValue() instanceof String value) {
                    for (Rule rule : applying) {
                        for (Matcher matcher : rule.matchers()) {
                            if (matcher instanceof Matcher.Literal m && m.pattern().matcher(value).find()) {
                                hit(rule, node, "literal " + shown(value), "type");
                                break;
                            }
                        }
                    }
                }
                return super.visitLiteral(node, unused);
            }

            /**
             * How a call that did not resolve matches {@code m}: through the type its receiver is written with, else by
             * the method name alone; a {@code *} matcher never matches by name. Null when it does not match.
             */
            private String unresolvedMatch(Matcher.Call m, String written) {
                if (written != null) return m.type().equals(written) ? "import" : null;
                return m.method().equals("*") ? null : "name";
            }

            private TypeMirror typeOf(ExpressionTree expression) {
                return trees.getTypeMirror(new TreePath(getCurrentPath(), expression));
            }

            /**
             * The qualified type an unresolved receiver is written with: a creation's type, a cast's type, or a
             * variable's declared type (or its initializer's creation, for {@code var}), read with the imports of the
             * file that declares it; a type name for a static call. Null when none is written.
             */
            private String writtenType(ExpressionTree receiver) {
                ExpressionTree e = receiver;
                while (e instanceof ParenthesizedTree p) e = p.getExpression();
                if (e instanceof NewClassTree n) return writtenType(unit, n.getIdentifier().toString());
                if (e instanceof TypeCastTree c) return writtenType(unit, c.getType().toString());
                if (e instanceof IdentifierTree || e instanceof MemberSelectTree) {
                    Element element = trees.getElement(new TreePath(getCurrentPath(), e));
                    if (element instanceof VariableElement variable) {
                        VariableTree v = declarations.get(variable);
                        CompilationUnitTree declaring = unit;
                        if (v == null) {
                            TreePath declaration = trees.getPath(variable);
                            if (declaration == null || !(declaration.getLeaf() instanceof VariableTree found)) {
                                return null;
                            }
                            v = found;
                            declaring = declaration.getCompilationUnit();
                        }
                        // A creation says the type, also for a var, whose type tree javac replaces with an error
                        if (v.getInitializer() instanceof NewClassTree n) {
                            return writtenType(declaring, n.getIdentifier().toString());
                        }
                        String type = v.getType() == null ? "" : withoutGenerics(v.getType().toString()).strip();
                        return type.matches("[A-Za-z_$][A-Za-z0-9_$.]*") && !type.equals("var")
                                ? writtenType(declaring, type) : null;
                    }
                    if (element == null || element.asType().getKind() == TypeKind.ERROR) {
                        return importedName(unit, e.toString());
                    }
                }
                return null;
            }

            /** {@code written} in {@code where}: a type that resolves by its own name, else through the imports. */
            private String writtenType(CompilationUnitTree where, String written) {
                String imported = importedName(where, written);
                if (imported != null) return imported;
                String name = withoutGenerics(written).strip();
                return target(name).isPresent() ? name : null;
            }

            private void hit(Rule rule, Tree node, String what, String resolved) {
                hitAt(rule, positions.getStartPosition(unit, node), what, resolved);
            }

            private void hitAt(Rule rule, long position, String what, String resolved) {
                int line = line(position);
                Member where = member != null ? member
                        : new Member(classNames.isEmpty() ? path : String.join(".", classNames), line, line, true);
                hits.add(new Hit(rule.id(), path, line, what, resolved, where));
            }

            private int line(long position) {
                return (int) unit.getLineMap().getLineNumber(Math.max(0, position));
            }

            /**
             * The value of a constant expression: the string, a character, number or boolean, {@link #OTHER_CONSTANT}
             * for another constant, or {@link #NOT_CONSTANT}. A conditional with a constant condition is its branch.
             */
            private Object constantValue(TreePath expression) {
                Tree tree = expression.getLeaf();
                if (tree instanceof LiteralTree literal) {
                    return literal.getValue() == null ? OTHER_CONSTANT : literal.getValue();
                }
                switch (tree.getKind()) {
                    case PARENTHESIZED:
                        return constantValue(new TreePath(expression, ((ParenthesizedTree) tree).getExpression()));
                    case TYPE_CAST:
                        return constantValue(new TreePath(expression, ((TypeCastTree) tree).getExpression()));
                    case IDENTIFIER:
                    case MEMBER_SELECT: {
                        Element element = trees.getElement(expression);
                        if (element instanceof VariableElement variable && variable.getConstantValue() != null) {
                            return variable.getConstantValue();
                        }
                        return NOT_CONSTANT;
                    }
                    case CONDITIONAL_EXPRESSION: {
                        ConditionalExpressionTree conditional = (ConditionalExpressionTree) tree;
                        Object condition = constantValue(new TreePath(expression, conditional.getCondition()));
                        if (!(condition instanceof Boolean b)) return NOT_CONSTANT;
                        return constantValue(new TreePath(expression,
                                b ? conditional.getTrueExpression() : conditional.getFalseExpression()));
                    }
                    case PLUS: {
                        BinaryTree plus = (BinaryTree) tree;
                        Object left = constantValue(new TreePath(expression, plus.getLeftOperand()));
                        Object right = constantValue(new TreePath(expression, plus.getRightOperand()));
                        if (left == NOT_CONSTANT || right == NOT_CONSTANT) return NOT_CONSTANT;
                        if (left instanceof String || right instanceof String) {
                            return left == OTHER_CONSTANT || right == OTHER_CONSTANT ? OTHER_CONSTANT
                                    : String.valueOf(left) + right;
                        }
                        return OTHER_CONSTANT;
                    }
                    default:
                        return tree instanceof BinaryTree binary
                                && constantValue(new TreePath(expression, binary.getLeftOperand())) != NOT_CONSTANT
                                && constantValue(new TreePath(expression, binary.getRightOperand())) != NOT_CONSTANT
                                ? OTHER_CONSTANT : NOT_CONSTANT;
                }
            }
        }
    }

    /** Whether the arguments, as {@code constantValue} sees them, meet the condition. */
    private static boolean argumentMatches(Argument argument, List<Object> arguments) {
        return switch (argument.kind()) {
            case ANY -> true;
            case NONCONSTANT -> argument.index() < 0 ? arguments.contains(NOT_CONSTANT)
                    : argument.index() < arguments.size() && arguments.get(argument.index()) == NOT_CONSTANT;
            case CONSTANT -> !arguments.isEmpty() && arguments.getFirst() instanceof String s
                    && argument.pattern().matcher(s).find();
        };
    }

    private static String withoutGenerics(String type) {
        String previous;
        String current = type;
        do {
            previous = current;
            current = current.replaceAll("<[^<>]*>", "");
        } while (!current.equals(previous));
        return current;
    }

    /** A string literal as the hit shows it: quoted, escaped, and cut after {@value #MAX_LITERAL_SHOWN} characters. */
    private static String shown(String value) {
        String cut = value.length() > MAX_LITERAL_SHOWN ? value.substring(0, MAX_LITERAL_SHOWN) + "..." : value;
        StringBuilder out = new StringBuilder("\"");
        for (char c : cut.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20 || c > 0x7e) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
