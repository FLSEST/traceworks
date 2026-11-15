package traceworks.selfaudit.architecture;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.LinkTree;
import com.sun.source.doctree.LiteralTree;
import com.sun.source.doctree.TextTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import traceworks.selfaudit.architecture.RuleFile.Call;
import traceworks.selfaudit.architecture.RuleFile.Io;
import traceworks.selfaudit.architecture.Sources.Loaded;
import traceworks.selfaudit.architecture.Sources.Unit;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads what the facts are made of from the attributed sources: the top-level types with their one-line summary, every
 * reference from one top-level type to another (by the element it resolves to, so a call, a field, a type in a
 * signature, a constructor and a method reference all count, and an import alone does not), the direct I/O the rule
 * file names, and the members of each file with their lines and their {@link Complexity}.
 */
final class Scan {

    private static final int MAX_SUMMARY = 200;
    private static final int MAX_API = 8;
    private static final long NOPOS = -1;

    /** A top-level type of the sources: its qualified name, package, file, kind and one-line summary. */
    record Type(String name, String pkg, String file, String kind, String summary, List<String> api) {
    }

    /**
     * A reference from top-level type {@code from} to another top-level type {@code to} of the sources, on a line of
     * {@code from}'s file; {@code member} is what of {@code to} is used ({@code null} for the type itself).
     */
    record Reference(String from, String to, int line, String member) {
    }

    /** A use of I/O that rule {@code rule} names, in top-level type {@code type}: what it is and where. */
    record IoUse(String rule, String type, String file, int line, String what) {
    }

    /** A member of a file: a method, constructor, initializer, field or nested type, with its lines and complexity. */
    record Member(String file, String label, String name, String kind, int start, int end, int complexity) {
    }

    /** Everything the scan read. {@code unresolvedNames} are the type-like names that did not resolve. */
    record Model(List<Unit> units, Map<String, Type> types, List<Reference> references, List<IoUse> io,
                 List<Member> members, Map<String, String> packageSummaries, Set<String> unresolvedNames) {
    }

    private final DocTrees trees;
    private final Elements elements;
    private final Types types;
    private final RuleFile rules;
    private final SourcePositions positions;
    private final Map<String, Type> declared = new TreeMap<>();
    private final Set<String> sourceTypes = new TreeSet<>();
    private final Set<Reference> references = new LinkedHashSet<>();
    private final Set<IoUse> io = new LinkedHashSet<>();
    private final List<Member> members = new ArrayList<>();
    private final Map<String, String> packageSummaries = new TreeMap<>();
    private final Set<String> unresolvedNames = new TreeSet<>();
    private final Map<String, Optional<TypeElement>> targets = new HashMap<>();

    private Scan(Loaded loaded, RuleFile rules) {
        this.trees = loaded.trees();
        this.elements = loaded.task().getElements();
        this.types = loaded.task().getTypes();
        this.rules = rules;
        this.positions = trees.getSourcePositions();
    }

    /** The model of the loaded sources under {@code rules}. */
    static Model of(Loaded loaded, RuleFile rules) {
        Scan scan = new Scan(loaded, rules);
        scan.checkCalls();
        for (Unit unit : loaded.units()) scan.declare(unit);
        for (Unit unit : loaded.units()) new UnitScanner(scan, unit).scan(unit.tree(), null);
        return new Model(loaded.units(), Collections.unmodifiableMap(scan.declared), List.copyOf(scan.references),
                List.copyOf(scan.io), List.copyOf(scan.members), Collections.unmodifiableMap(scan.packageSummaries),
                Collections.unmodifiableSet(scan.unresolvedNames));
    }

    /** A {@code call} on a type that resolves must name a method of it, else the rule file is refused. */
    private void checkCalls() {
        for (Io rule : rules.ios()) {
            for (Call call : rule.calls()) {
                Optional<TypeElement> type = target(call.type());
                if (type.isPresent() && elements.getAllMembers(type.get()).stream().noneMatch(e ->
                        e.getKind() == ElementKind.METHOD && e.getSimpleName().contentEquals(call.method()))) {
                    throw new IllegalArgumentException("rule " + rule.id() + ": " + call.type() + " has no method "
                            + call.method());
                }
            }
        }
    }

    private Optional<TypeElement> target(String type) {
        return targets.computeIfAbsent(type, t -> Optional.ofNullable(elements.getTypeElement(t)));
    }

    /** Enters the top-level types of a unit, and the summary of a {@code package-info.java}. */
    private void declare(Unit unit) {
        CompilationUnitTree tree = unit.tree();
        String pkg = tree.getPackageName() == null ? "" : tree.getPackageName().toString();
        if (unit.path().endsWith("/package-info.java") || unit.path().equals("package-info.java")) {
            if (tree.getPackage() != null) {
                String summary = summary(trees.getDocCommentTree(new TreePath(new TreePath(tree), tree.getPackage())));
                if (!summary.isEmpty()) packageSummaries.put(pkg, summary);
            }
            return;
        }
        for (Tree decl : tree.getTypeDecls()) {
            if (!(decl instanceof ClassTree)) continue;
            TreePath path = new TreePath(new TreePath(tree), decl);
            if (!(trees.getElement(path) instanceof TypeElement type)) continue;
            String name = type.getQualifiedName().toString();
            sourceTypes.add(name);
            declared.put(name, new Type(name, pkg, unit.path(), kind(type), summary(trees.getDocCommentTree(path)),
                    api(type)));
        }
    }

    private static String kind(TypeElement type) {
        return switch (type.getKind()) {
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record";
            case ANNOTATION_TYPE -> "annotation";
            default -> type.getModifiers().contains(Modifier.ABSTRACT) ? "abstract class" : "class";
        };
    }

    /** Up to {@value #MAX_API} signatures of the type's own methods and constructors that are not private. */
    private List<String> api(TypeElement type) {
        List<String> api = new ArrayList<>();
        for (Element e : type.getEnclosedElements()) {
            if (api.size() >= MAX_API) break;
            if (!(e instanceof ExecutableElement method) || method.getModifiers().contains(Modifier.PRIVATE)) continue;
            // A member javac generates (a default constructor, a record's accessor) has no place in the source
            TreePath path = trees.getPath(method);
            if (path == null || positions.getEndPosition(path.getCompilationUnit(), path.getLeaf()) == NOPOS) continue;
            api.add(signature(method));
        }
        return List.copyOf(api);
    }

    /** {@code name(Type, Type)}, or {@code new Name(Type)} for a constructor, with simple type names. */
    static String signature(ExecutableElement method) {
        List<String> parameters = new ArrayList<>();
        for (VariableElement parameter : method.getParameters()) parameters.add(simpleType(parameter.asType()));
        String name = method.getKind() == ElementKind.CONSTRUCTOR
                ? "new " + method.getEnclosingElement().getSimpleName() : method.getSimpleName().toString();
        return name + "(" + String.join(", ", parameters) + ")";
    }

    private static String simpleType(TypeMirror type) {
        String text = type.toString().replaceAll("@\\S+\\s+", "");
        StringBuilder out = new StringBuilder();
        for (String part : text.split("(?=[<>,\\[\\]\\s?&])|(?<=[<>,\\[\\]\\s?&])")) {
            int dot = part.lastIndexOf('.');
            out.append(dot >= 0 && !part.endsWith(".") ? part.substring(dot + 1) : part);
        }
        return out.toString().replaceAll("<[^<>]*>", "").replaceAll("<[^<>]*>", "");
    }

    /** The first sentence of a doc comment as plain text on one line, cut after {@value #MAX_SUMMARY} characters. */
    static String summary(DocCommentTree doc) {
        if (doc == null) return "";
        StringBuilder out = new StringBuilder();
        for (DocTree node : doc.getFirstSentence()) {
            switch (node) {
                case TextTree text -> out.append(text.getBody());
                case LiteralTree literal -> out.append(literal.getBody().getBody());
                case LinkTree link -> out.append(link.getLabel().isEmpty() ? link.getReference().getSignature()
                        : String.join(" ", link.getLabel().stream().map(Object::toString).toList()));
                default -> {
                    if (node.getKind() != DocTree.Kind.START_ELEMENT && node.getKind() != DocTree.Kind.END_ELEMENT) {
                        out.append(node);
                    }
                }
            }
        }
        String line = out.toString().replaceAll("\\s+", " ").strip();
        return line.length() <= MAX_SUMMARY ? line : line.substring(0, MAX_SUMMARY - 3) + "...";
    }

    /** The scanner of one unit: references, I/O uses and members. */
    private static final class UnitScanner extends TreePathScanner<Void, Void> {
        private final Scan scan;
        private final Unit unit;
        private final String pkg;
        private final List<Io> applying;
        private final Deque<String> classNames = new ArrayDeque<>();
        private String topLevel;
        private boolean inMember;

        UnitScanner(Scan scan, Unit unit) {
            this.scan = scan;
            this.unit = unit;
            CompilationUnitTree tree = unit.tree();
            this.pkg = tree.getPackageName() == null ? "" : tree.getPackageName().toString();
            String relative = scan.rules.relative(pkg);
            this.applying = scan.rules.ios().stream().filter(r -> RuleFile.matchesAny(r.in(), relative)).toList();
        }

        @Override
        public Void visitImport(ImportTree node, Void unused) {
            // An import alone is no dependency; what the code does with it is
            return null;
        }

        @Override
        public Void visitPackage(PackageTree node, Void unused) {
            return null;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            Tree parent = getCurrentPath().getParentPath().getLeaf();
            boolean named = !inMember && (parent instanceof CompilationUnitTree || parent instanceof ClassTree);
            if (!named) return super.visitClass(node, unused);
            String saved = topLevel;
            if (parent instanceof CompilationUnitTree && scan.trees.getElement(getCurrentPath()) instanceof TypeElement t) {
                topLevel = t.getQualifiedName().toString();
            }
            if (parent instanceof ClassTree) member(node, label(node.getSimpleName().toString()),
                    node.getSimpleName().toString(), "type", 0);
            classNames.addLast(node.getSimpleName().toString());
            try {
                return super.visitClass(node, unused);
            } finally {
                classNames.removeLast();
                topLevel = saved;
            }
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            // A constructor javac generates has no place in the source
            if (scan.positions.getEndPosition(unit.tree(), node) == NOPOS) return null;
            if (isMember()) {
                boolean constructor = node.getName().contentEquals("<init>");
                String name = constructor ? classNames.getLast() : node.getName().toString();
                String label = (constructor ? label(null) : label(name)) + "(" + parameters(node) + ")";
                return inMember(node, label, name, constructor ? "constructor" : "method",
                        () -> super.visitMethod(node, unused));
            }
            return super.visitMethod(node, unused);
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            if (isMember() && scan.positions.getEndPosition(unit.tree(), node) != NOPOS) {
                String name = node.getName().toString();
                return inMember(node, label(name), name, "field", () -> super.visitVariable(node, unused));
            }
            return super.visitVariable(node, unused);
        }

        @Override
        public Void visitBlock(BlockTree node, Void unused) {
            if (isMember()) {
                String name = node.isStatic() ? "{static initializer}" : "{initializer}";
                return inMember(node, label(name), name, "initializer", () -> super.visitBlock(node, unused));
            }
            return super.visitBlock(node, unused);
        }

        private boolean isMember() {
            return !inMember && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree;
        }

        private Void inMember(Tree node, String label, String name, String kind, java.util.function.Supplier<Void> scan) {
            member(node, label, name, kind, Complexity.of(node));
            inMember = true;
            try {
                return scan.get();
            } finally {
                inMember = false;
            }
        }

        private void member(Tree node, String label, String name, String kind, int complexity) {
            int start = line(scan.positions.getStartPosition(unit.tree(), node));
            int end = line(scan.positions.getEndPosition(unit.tree(), node) - 1);
            scan.members.add(new Member(unit.path(), label, name, kind, start, end, complexity));
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
            return String.join(", ", names);
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void unused) {
            reference(node, node.getName().toString());
            return super.visitIdentifier(node, unused);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            reference(node, node.getIdentifier().toString());
            return super.visitMemberSelect(node, unused);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node, Void unused) {
            reference(node, node.getName().toString());
            return super.visitMemberReference(node, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree node, Void unused) {
            reference(node, null);
            return super.visitNewClass(node, unused);
        }

        /** Records what the current node resolves to: a reference to another source type, and I/O. */
        private void reference(Tree node, String name) {
            if (topLevel == null) return;
            Element element = scan.trees.getElement(getCurrentPath());
            // A field or method whose type is a library type still resolves to its owner; a type that does not
            // resolve is a library type, or a source type the scan cannot see
            if (element == null || (element instanceof TypeElement && element.asType().getKind() == TypeKind.ERROR)) {
                if (name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
                    scan.unresolvedNames.add(name);
                }
                return;
            }
            if (element instanceof PackageElement) return;
            TypeElement owner = owner(element);
            if (owner == null) return;
            int line = line(position(node));
            TypeElement outer = outermost(owner);
            String outerName = outer.getQualifiedName().toString();
            if (scan.sourceTypes.contains(outerName) && !outerName.equals(topLevel)) {
                scan.references.add(new Reference(topLevel, outerName, line, used(element, owner, outer)));
            }
            // A member used on a value of a source type depends on that type, also when the member is inherited
            // from elsewhere: view.status().name() uses RunStatus, though name() is Enum's
            if (node instanceof MemberSelectTree select && !(element instanceof TypeElement)) {
                TypeMirror receiver = scan.trees.getTypeMirror(new TreePath(getCurrentPath(), select.getExpression()));
                if (receiver != null && receiver.getKind() == TypeKind.DECLARED
                        && scan.types.asElement(receiver) instanceof TypeElement type) {
                    String typeName = outermost(type).getQualifiedName().toString();
                    if (scan.sourceTypes.contains(typeName) && !typeName.equals(topLevel) && !typeName.equals(outerName)) {
                        scan.references.add(new Reference(topLevel, typeName, line, used(element, owner, outer)));
                    }
                }
            }
            for (Io rule : applying) {
                String what = ioMatch(rule, element, owner, outer);
                if (what != null) scan.io.add(new IoUse(rule.id(), topLevel, unit.path(), line, what));
            }
        }

        /** The line of the name in a member select, not of the receiver a fluent chain starts with. */
        private long position(Tree node) {
            if (node instanceof MemberSelectTree) return scan.positions.getEndPosition(unit.tree(), node) - 1;
            return scan.positions.getStartPosition(unit.tree(), node);
        }

        /** What {@code element} matches of {@code rule}: a type it names or a call, or null. */
        private String ioMatch(Io rule, Element element, TypeElement owner, TypeElement outer) {
            String ownerName = owner.getQualifiedName().toString();
            String outerName = outer.getQualifiedName().toString();
            for (String type : rule.types()) {
                if (typeMatches(type, ownerName)) return ownerName;
                if (typeMatches(type, outerName)) return outerName;
            }
            if (element instanceof ExecutableElement method) {
                for (Call call : rule.calls()) {
                    if (!method.getSimpleName().contentEquals(call.method())) continue;
                    Optional<TypeElement> target = scan.target(call.type());
                    if (target.isPresent() && scan.types.isSubtype(scan.types.erasure(owner.asType()),
                            scan.types.erasure(target.get().asType()))) {
                        return call.toString();
                    }
                }
            }
            return null;
        }

        private static boolean typeMatches(String pattern, String type) {
            if (pattern.endsWith(".*")) {
                String base = pattern.substring(0, pattern.length() - 2);
                return type.startsWith(base + ".");
            }
            return type.equals(pattern);
        }

        /** The member of {@code owner} that {@code element} is, as the context shows it; null for the type itself. */
        private static String used(Element element, TypeElement owner, TypeElement outer) {
            String nested = owner == outer ? "" : nestedName(owner, outer);
            return switch (element) {
                case ExecutableElement method -> (nested.isEmpty() ? "" : nested + ".") + signature(method);
                case VariableElement field -> (nested.isEmpty() ? "" : nested + ".") + field.getSimpleName();
                case TypeElement type -> type == outer ? null : nested;
                default -> null;
            };
        }

        private static String nestedName(TypeElement type, TypeElement outer) {
            List<String> names = new ArrayList<>();
            for (Element e = type; e != outer && e instanceof TypeElement t; e = e.getEnclosingElement()) {
                names.addFirst(t.getSimpleName().toString());
            }
            return String.join(".", names);
        }

        private int line(long position) {
            return (int) unit.tree().getLineMap().getLineNumber(Math.max(0, position));
        }
    }

    /** The type that holds {@code element}: the type itself, or the type of a method, constructor or field. */
    private static TypeElement owner(Element element) {
        if (element instanceof TypeElement type) return type;
        ElementKind kind = element.getKind();
        if (kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR || kind == ElementKind.FIELD
                || kind == ElementKind.ENUM_CONSTANT) {
            return element.getEnclosingElement() instanceof TypeElement type ? type : null;
        }
        return null;
    }

    private static TypeElement outermost(TypeElement type) {
        TypeElement current = type;
        while (current.getEnclosingElement() instanceof TypeElement enclosing) current = enclosing;
        return current;
    }

    static String withoutGenerics(String type) {
        String previous;
        String current = type;
        do {
            previous = current;
            current = current.replaceAll("<[^<>]*>", "");
        } while (!current.equals(previous));
        return current;
    }
}
