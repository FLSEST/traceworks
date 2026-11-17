package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.analyzer.ShellQuoteScanner.Heredoc;
import traceworks.core.analyzer.ShellQuoteScanner.Placeholder;
import traceworks.core.analyzer.ShellQuoteScanner.Quote;
import traceworks.core.context.PlaceholderSyntax;
import traceworks.core.context.ShellLineRefusedException;
import traceworks.core.model.BlockDefinition;
import traceworks.core.port.WorkflowResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rules for placeholders in shell code whose value the shell has to take as one piece of text.
 *
 * <p>A run treats two kinds of value differently. A <b>protected</b> value (outside text, and what a model wrote,
 * both below) never goes into shell code: a shell line that names one is refused by the run, wherever the
 * placeholder stands, and the value reaches a command as a shell variable the block names under {@code values}
 * ({@code core.engine.ShellValues}). Every other value is <b>inserted</b> as it is, or in single quotes with
 * {@code shell_escape_variables}; the rules for those read the shell text ({@link ShellQuoteScanner}), as they
 * always did, to warn of a value that would not be one piece of text.
 *
 * <ul>
 *   <li>{@code protected-value-in-shell} ({@code ERROR}): a placeholder the workflow shows to be protected stands
 *       in shell code, anywhere, with or without {@code shell_escape_variables}. The run would refuse the line
 *       ({@code ShellLineRefusedException}), so the check refuses the workflow.</li>
 *   <li>{@code quoted-placeholder} ({@code WARNING}): an inserted block output inside quotes. An apostrophe or a
 *       quote in the value ends them ({@code '; id} runs {@code id}); in a block with
 *       {@code shell_escape_variables} also any other placeholder in single quotes, where the two pairs cancel out
 *       ({@code ''x; id''}).</li>
 *   <li>{@code placeholder-self-compare}: {@code [ "$X" = '{{ x }}' ]} after {@code X='{{ x }}'} is meant to tell
 *       whether x was passed. The resolver substitutes the comparison too, so without escaping the check is always
 *       true once x is set, and the passed value gets overwritten.</li>
 *   <li>{@code heredoc-placeholder}: an inserted block output in the body of a heredoc, which
 *       {@code quoted-placeholder} leaves out because quotes mean nothing there. What counts is what reads the body:
 *       an interpreter that takes it as its program ({@code python3 - <<'PY'}, {@link HeredocReader}) turns the value
 *       into code ({@code ERROR}); an unquoted delimiter lets the shell run {@code $(…)} in the value
 *       ({@code WARNING}); a quoted heredoc whose reader takes the body as input ({@code cat > file},
 *       {@code python3 tool.py}) is fine. In a block with {@code shell_escape_variables} every inserted placeholder
 *       in a body is a {@code WARNING}: the resolver quotes it there too and the quotes land in the text.</li>
 * </ul>
 *
 * <p>Which placeholders the workflow shows to be protected is {@link ProtectedValues}'s. What the block list does
 * not tell (a loop item, a variable, a child whose reference is a placeholder, the values a parent hands to a child)
 * the run decides: it refuses the shell line ({@code ShellLineRefusedException}).
 *
 * <p>The TODO and goal variables ({@code todo_title}, {@code open_todos}, {@code goal_status}, ...) are no outside
 * text: Traceworks does not provide them. A run input of the same name is used as is (never
 * for a platform data name such as {@code open_todos}, which no input sets), otherwise the placeholder stays
 * literal; {@code unprovided-variable} reports them.
 */
final class QuotingRules {

    private QuotingRules() {}

    private static final Pattern OUTPUT = Pattern.compile("previous_output|step_\\d+_output|block\\.[^.\\s]+\\.output");
    private static final String PLACEHOLDER = "\\{\\{ ?([^{}]+?) ?\\}\\}";
    private static final String NAME = "([A-Za-z_][A-Za-z0-9_]*)";
    /** {@code X='{{ x }}'}: the whole value is the placeholder. */
    private static final Pattern ASSIGN = Pattern.compile(
            "(?<![\\w$])" + NAME + "=(['\"]?)" + PLACEHOLDER + "\\2(?=$|[\\s;&|)])", Pattern.MULTILINE);
    /** {@code set -- {{ x }}; X="$*"}: the recommended form for values that may be unset. */
    private static final Pattern SET_ARGS = Pattern.compile(
            "set\\s+--\\s+(['\"]?)" + PLACEHOLDER + "\\1\\s*(?:;|&&|\\n)\\s*" + NAME + "=\"\\$\\*\"");
    /** {@code "$X" = '{{ x }}'}, also {@code ==} and {@code !=}. */
    private static final Pattern COMPARE = Pattern.compile(
            "\"\\$\\{?" + NAME + "\\}?\"\\s*!?==?\\s*(['\"]?)" + PLACEHOLDER + "\\2");

    /**
     * A shell text of the block, whether the resolver escapes every placeholder in it, and whether it is a field of
     * the block itself rather than of a block nested in it.
     */
    private record ShellText(String code, boolean escaped, boolean own) {}

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        apply(blocks, null, out);
    }

    /**
     * @param children finds the workflows of {@code sub-workflow} and {@code foreach} blocks, to see whether one
     *                 asks a model; {@code null} looks into none
     */
    static void apply(List<BlockDefinition> blocks, WorkflowResolver children, List<AnalyzerFinding> out) {
        ProtectedValues model = ProtectedValues.of(blocks, children);
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) continue;
            List<ShellText> texts = shellTexts(block, i);
            if (texts.isEmpty()) continue;
            protectedInShell(model, texts, i, block.getName(), out);
            quoted(model, texts, i, block.getName(), out);
            selfCompare(texts, i, block.getName(), out);
            heredoc(model, texts, i, block.getName(), out);
        }
    }

    /**
     * Whether the run protects the value of the placeholder {@code key} in block {@code i}, as far as the workflow
     * tells: outside text, or what a model wrote.
     */
    private static boolean protectedValue(ProtectedValues model, int i, String key, boolean own) {
        return model.protects(i, key, own);
    }

    /**
     * A placeholder the workflow shows to be protected, anywhere in shell code: in a comment or a heredoc body too,
     * because the run fills those in like the rest and refuses the line.
     */
    private static void protectedInShell(ProtectedValues model, List<ShellText> texts, int i, String name,
                                         List<AnalyzerFinding> out) {
        Set<String> keys = new LinkedHashSet<>();
        Set<String> sources = new LinkedHashSet<>();
        for (ShellText text : texts) {
            for (String key : PlaceholderSyntax.keysIn(text.code())) {
                String stripped = key.strip();
                String source = model.sourceOf(i, stripped, text.own());
                if (source != null) {
                    keys.add(stripped);
                    sources.add(source);
                }
            }
        }
        if (keys.isEmpty()) return;
        String first = keys.iterator().next();
        String variable = ShellLineRefusedException.variableName(first);
        out.add(AnalyzerFinding.of("protected-value-in-shell", Severity.ERROR, i, name,
                "puts " + keys.stream().map(key -> "{{ " + key + " }}").collect(java.util.stream.Collectors.joining(", "))
                        + " into shell code, " + String.join(", ", sources) + " — text a model wrote and text from"
                        + " outside the run never go into shell code, quoted or not: a model reads the workspace, and"
                        + " what it reads can steer its answer; a run refuses this shell line before anything runs,"
                        + " so Traceworks refuses the workflow. Correct: hand the value to the command as a variable:"
                        + " \"values\": {\"" + variable + "\": \"{{ " + first + " }}\"} in this block's config, and"
                        + " \"$" + variable + "\" where the placeholder stands"));
    }

    /**
     * A value the run inserts as it is, inside the author's quotes. A protected value is not judged here: it is
     * {@link #protectedInShell}'s, wherever it stands.
     */
    private static void quoted(ProtectedValues model, List<ShellText> texts, int i, String name,
                               List<AnalyzerFinding> out) {
        Set<String> names = new LinkedHashSet<>();
        for (ShellText text : texts) {
            for (Placeholder placeholder : ShellQuoteScanner.scan(text.code())) {
                if (placeholder.quote() == Quote.NONE || protectedValue(model, i, placeholder.name(), text.own())) {
                    continue;
                }
                if (OUTPUT.matcher(placeholder.name()).matches()
                        || (text.escaped() && placeholder.quote() == Quote.SINGLE)) {
                    names.add("{{ " + placeholder.name() + " }}");
                }
            }
        }
        if (names.isEmpty()) return;
        out.add(AnalyzerFinding.of("quoted-placeholder", Severity.WARNING, i, name,
                "puts " + String.join(", ", names) + " inside quotes in shell code — the value is inserted as it is,"
                        + " so an apostrophe or a quote in it ends them, and with shell_escape_variables its own"
                        + " quotes land inside the author's (''x; id'' runs id); correct: without quotes plus"
                        + " shell_escape_variables: true, for a variable that may be unset set -- {{ x }}; X=\"$*\""));
    }

    /** What the heredoc bodies of one block hold, sorted by what reads them. */
    private static final class HeredocHits {
        final Set<String> source = new LinkedHashSet<>();
        final Set<String> readers = new LinkedHashSet<>();
        final Set<String> expanded = new LinkedHashSet<>();
        final Set<String> delimiters = new LinkedHashSet<>();
        final Set<String> escaped = new LinkedHashSet<>();
    }

    /**
     * A value the run inserts as it is, in the body of a heredoc. A protected value is not judged here: it is
     * {@link #protectedInShell}'s, wherever it stands.
     */
    private static void heredoc(ProtectedValues model, List<ShellText> texts, int i, String name,
                                List<AnalyzerFinding> out) {
        HeredocHits hits = new HeredocHits();
        for (ShellText text : texts) {
            for (Heredoc heredoc : ShellQuoteScanner.read(text.code()).heredocs()) {
                if (heredoc.placeholders().isEmpty()) continue;
                String reader = HeredocReader.sourceReader(heredoc.command(), heredoc.rest());
                for (String placeholder : heredoc.placeholders()) {
                    if (protectedValue(model, i, placeholder, text.own())) continue;
                    String shown = "{{ " + placeholder + " }}";
                    boolean free = OUTPUT.matcher(placeholder).matches();
                    if (free && reader != null) {
                        hits.source.add(shown);
                        hits.readers.add(reader);
                    } else if (free && !heredoc.quoted()) {
                        hits.expanded.add(shown);
                        hits.delimiters.add(heredoc.delimiter());
                    } else if (text.escaped()) {
                        hits.escaped.add(shown);
                    }
                }
            }
        }
        if (!hits.source.isEmpty()) out.add(programSource(hits, i, name));
        if (!hits.expanded.isEmpty()) out.add(shellExpanded(hits, i, name));
        if (!hits.escaped.isEmpty()) out.add(quotesInBody(hits, i, name));
    }

    private static AnalyzerFinding programSource(HeredocHits hits, int i, String name) {
        String names = String.join(", ", hits.source);
        String program = String.join("/", hits.readers);
        String example = "X=" + hits.source.iterator().next() + " " + hits.readers.iterator().next() + " - <<'PY'";
        return AnalyzerFinding.of("heredoc-placeholder", Severity.ERROR, i, name,
                "puts " + names + " into the body of a heredoc that " + program + " reads as its program — the value"
                        + " turns into source code: a ''' or a quote in it ends the string and the rest runs as code,"
                        + " and where the resolver quotes the value its quotes land in the program; correct: pass the"
                        + " value on the shell line (" + example + " plus shell_escape_variables: true) and read it"
                        + " from the environment in the script (Python: os.environ['X'], shell: \"$X\")");
    }

    private static AnalyzerFinding shellExpanded(HeredocHits hits, int i, String name) {
        String names = String.join(", ", hits.expanded);
        String delimiter = hits.delimiters.iterator().next();
        return AnalyzerFinding.of("heredoc-placeholder", Severity.WARNING, i, name,
                "puts " + names + " into the body of a heredoc with an unquoted delimiter (<<" + delimiter + ") — the"
                        + " shell runs $(…) and backticks in the value, quotes do not protect there, not even the"
                        + " resolver's; correct: quote the delimiter (<<'" + delimiter + "')");
    }

    private static AnalyzerFinding quotesInBody(HeredocHits hits, int i, String name) {
        String names = String.join(", ", hits.escaped);
        String first = hits.escaped.iterator().next();
        return AnalyzerFinding.of("heredoc-placeholder", Severity.WARNING, i, name,
                "puts " + names + " into the body of a heredoc although the block sets shell_escape_variables — the"
                        + " resolver quotes the value there too, so its quotes land in the text; correct: pass the value"
                        + " outside the body, e.g. printf '%s\\n' " + first + " > file instead of cat <<'EOF', or"
                        + " X=" + first + " python3 - <<'PY'");
    }

    private static void selfCompare(List<ShellText> texts, int i, String name, List<AnalyzerFinding> out) {
        Set<String> pairs = new LinkedHashSet<>();
        for (ShellText text : texts) {
            // With escaping the comparison side differs from the variable ("'x'" against x), the recommended idiom
            if (text.escaped()) continue;
            Map<String, String> assignedFrom = new HashMap<>();
            Matcher assign = ASSIGN.matcher(text.code());
            while (assign.find()) assignedFrom.put(assign.group(1), assign.group(3).strip());
            Matcher setArgs = SET_ARGS.matcher(text.code());
            while (setArgs.find()) assignedFrom.put(setArgs.group(3), setArgs.group(2).strip());
            Matcher compare = COMPARE.matcher(text.code());
            while (compare.find()) {
                String placeholder = compare.group(3).strip();
                if (placeholder.equals(assignedFrom.get(compare.group(1)))) {
                    pairs.add("$" + compare.group(1) + " = {{ " + placeholder + " }}");
                }
            }
        }
        if (pairs.isEmpty()) return;
        String list = String.join(", ", pairs);
        out.add(AnalyzerFinding.of("placeholder-self-compare", Severity.WARNING, i, name,
                "tests " + list + " to tell whether a value was passed — the resolver substitutes the comparison"
                        + " too, so without shell_escape_variables the test is always true once the value is set and"
                        + " overwrites it; correct: case \"$X\" in '{{ '*) … esac"));
    }

    /** The shell texts of a block and its nested blocks, each with the escaping of the config it belongs to. */
    private static List<ShellText> shellTexts(BlockDefinition block, int i) {
        List<ShellText> out = new ArrayList<>();
        BlockConfigs.walk(block, i, config -> add(config.type(), config.config(), !config.nested(), out));
        return out;
    }

    private static void add(String type, Map<String, Object> config, boolean own, List<ShellText> out) {
        if (config == null) return;
        Object escape = config.get("shell_escape_variables");
        // A value that is no boolean fails the block before any command runs (invalid-config-value says so)
        if (escape != null && !(escape instanceof Boolean)) return;
        boolean escaped = Boolean.TRUE.equals(escape);
        for (String code : BlockText.shellTexts(type, config)) {
            if (code.contains("{{")) out.add(new ShellText(code, escaped, own));
        }
    }
}
