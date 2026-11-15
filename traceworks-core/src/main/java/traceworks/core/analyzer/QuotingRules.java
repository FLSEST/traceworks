package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.analyzer.ShellQuoteScanner.Heredoc;
import traceworks.core.analyzer.ShellQuoteScanner.Placeholder;
import traceworks.core.analyzer.ShellQuoteScanner.Quote;
import traceworks.core.engine.NestedBlocks;
import traceworks.core.engine.StepModes;
import traceworks.core.model.BlockDefinition;

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
 * <ul>
 *   <li>{@code quoted-placeholder}: a placeholder for free text stands inside quotes. Without escaping, an apostrophe
 *       in the value ends the quotes ({@code '; id} runs {@code id}); where the resolver quotes the value itself
 *       (outside text always, every output of a tainted run, every value with {@code shell_escape_variables}) the two
 *       pairs cancel out ({@code ''x; id''}). {@code ERROR} for outside text, {@code WARNING} for block outputs; in a
 *       block with {@code shell_escape_variables} also {@code WARNING} for any other placeholder in single
 *       quotes.</li>
 *   <li>{@code placeholder-self-compare}: {@code [ "$X" = '{{ x }}' ]} after {@code X='{{ x }}'} is meant to tell
 *       whether x was passed. The resolver substitutes the comparison too, so without escaping the check is always
 *       true once x is set, and the passed value gets overwritten.</li>
 *   <li>{@code heredoc-placeholder}: the same free text in the body of a heredoc, which {@code quoted-placeholder}
 *       leaves out because quotes mean nothing there. What counts is what reads the body: an interpreter that takes it
 *       as its program ({@code python3 - <<'PY'}, {@link HeredocReader}) turns the value into code ({@code ERROR}); an
 *       unquoted delimiter lets the shell run {@code $(…)} in the value ({@code ERROR} for outside text,
 *       {@code WARNING} for block outputs); a quoted heredoc whose reader takes the body as input
 *       ({@code cat > file}, {@code python3 tool.py}) is fine. In a block with {@code shell_escape_variables} every
 *       placeholder in a body is a {@code WARNING}: the resolver quotes it there too and the quotes land in the
 *       text.</li>
 *   <li>{@code unquoted-ai-output}: the output of an AI step ({@code simple}, {@code agentic}, {@code compress-ai}, a
 *       wrapper in AI mode, a block with AI steps nested in it) stands without quotes in shell code of a block without
 *       {@code shell_escape_variables}. The resolver inserts it as it is unless the run carries untrusted input, so a
 *       {@code $(…)} or {@code ;} in a model's answer runs as a command, and a model can be steered by what it reads
 *       (the workspace, say). {@code WARNING}. The step is found through {@code block.<name>.output},
 *       {@code previous_output} in the block's own fields, and {@code step_N_output} while no wrapper before it draws
 *       further step indices; the output of a sub-workflow is not followed.</li>
 * </ul>
 *
 * <p>The TODO and goal variables ({@code todo_title}, {@code open_todos}, {@code goal_status}, ...) are no outside
 * text: Traceworks does not provide them. A run input of the same name is used as is (never
 * for a platform data name such as {@code open_todos}, which no input sets), otherwise the placeholder stays
 * literal; {@code unprovided-variable} reports them.
 */
final class QuotingRules {

    private QuotingRules() {}

    /** Outputs of earlier runs: always untrusted, always single-quoted by the resolver. */
    static final Set<String> PLATFORM_DATA = Set.of("previous_report", "audit_findings", "audit_items");

    /** Text from outside: a chain hands on the last entry's output. */
    private static final Set<String> FOREIGN_TEXT = Set.of("previous_chain_output");

    private static final Pattern OUTPUT = Pattern.compile("previous_output|step_\\d+_output|block\\.[^.\\s]+\\.output");
    private static final Pattern STEP_OUTPUT = Pattern.compile("step_(\\d{1,6})_output");
    private static final Pattern BLOCK_OUTPUT = Pattern.compile("block\\.([^.\\s]+)\\.output");

    /** Blocks that draw further step indices, so a later step_N_output no longer matches the block position. */
    private static final Set<String> INDEX_DRAWING = Set.of("foreach", "while", "retry", "conditional", "switch",
            "sub-workflow");

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
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) continue;
            List<ShellText> texts = shellTexts(block, i);
            if (texts.isEmpty()) continue;
            quoted(texts, i, block.getName(), out);
            selfCompare(texts, i, block.getName(), out);
            heredoc(texts, i, block.getName(), out);
            unquotedAiOutput(blocks, texts, i, out);
        }
    }

    private static void unquotedAiOutput(List<BlockDefinition> blocks, List<ShellText> texts, int i,
                                         List<AnalyzerFinding> out) {
        Set<String> placeholders = new LinkedHashSet<>();
        Set<String> steps = new LinkedHashSet<>();
        for (ShellText text : texts) {
            if (text.escaped()) continue;
            for (Placeholder placeholder : ShellQuoteScanner.scan(text.code())) {
                if (placeholder.quote() != Quote.NONE) continue;
                String step = aiStep(blocks, i, placeholder.name().strip(), text.own());
                if (step != null) {
                    placeholders.add("{{ " + placeholder.name() + " }}");
                    steps.add(step);
                }
            }
        }
        if (placeholders.isEmpty()) return;
        out.add(AnalyzerFinding.of("unquoted-ai-output", Severity.WARNING, i, blocks.get(i).getName(),
                "puts " + String.join(", ", placeholders) + " into shell code without quotes, the output of the AI"
                        + " step " + String.join(", ", steps) + " — the resolver inserts a model's answer as it is"
                        + " unless the run carries untrusted input, so a $(…) or ; in it runs as a command, and what"
                        + " the model reads can steer its answer; correct: shell_escape_variables: true on this block,"
                        + " which quotes every value"));
    }

    /** The label of the AI step whose output {@code name} stands for in block {@code i}, or null. */
    private static String aiStep(List<BlockDefinition> blocks, int i, String name, boolean own) {
        BlockDefinition source = null;
        Matcher byName = BLOCK_OUTPUT.matcher(name);
        Matcher byIndex = STEP_OUTPUT.matcher(name);
        if (byName.matches()) {
            source = named(blocks, byName.group(1));
        } else if ("previous_output".equals(name)) {
            source = own && i > 0 ? blocks.get(i - 1) : null;
        } else if (byIndex.matches()) {
            source = atStepIndex(blocks, Integer.parseInt(byIndex.group(1)), i);
        }
        if (source == null || !writesAiText(source)) return null;
        return source.getName() != null && !source.getName().isBlank() ? source.getName() : source.getType();
    }

    /** The block of that name: a top-level one first, else a nested one. */
    private static BlockDefinition named(List<BlockDefinition> blocks, String name) {
        for (BlockDefinition block : blocks) {
            if (block != null && name.equals(block.getName())) return block;
        }
        List<BlockDefinition> nested = new ArrayList<>();
        for (BlockDefinition block : blocks) {
            if (block == null) continue;
            NestedBlocks.visit(block.getConfig(), inner -> {
                if (name.equals(inner.name())) {
                    nested.add(BlockDefinition.builder().type(inner.type()).name(inner.name())
                            .config(inner.config() != null ? inner.config() : Map.of()).build());
                }
            });
        }
        return nested.isEmpty() ? null : nested.getFirst();
    }

    /** The block at position {@code index}, before {@code i}, while no block before it draws further indices. */
    private static BlockDefinition atStepIndex(List<BlockDefinition> blocks, int index, int i) {
        if (index >= i) return null;
        for (int j = 0; j < index; j++) {
            if (blocks.get(j) == null || INDEX_DRAWING.contains(blocks.get(j).getType())) return null;
        }
        return blocks.get(index);
    }

    /** Whether the block's output is text a model wrote: it calls an AI itself, or a block nested in it does. */
    private static boolean writesAiText(BlockDefinition block) {
        if (block.getType() == null) return false;
        if (StepModes.usesAi(block)) return true;
        boolean[] nestedAi = {false};
        NestedBlocks.visit(block.getConfig(), inner -> {
            if (inner.type() != null && StepModes.usesAi(BlockDefinition.builder().type(inner.type())
                    .config(inner.config() != null ? inner.config() : Map.of()).build())) {
                nestedAi[0] = true;
            }
        });
        return nestedAi[0];
    }

    private static void quoted(List<ShellText> texts, int i, String name, List<AnalyzerFinding> out) {
        Set<String> severe = new LinkedHashSet<>();
        Set<String> other = new LinkedHashSet<>();
        for (ShellText text : texts) {
            for (Placeholder placeholder : ShellQuoteScanner.scan(text.code())) {
                if (placeholder.quote() == Quote.NONE) continue;
                String shown = "{{ " + placeholder.name() + " }}";
                if (outsideText(placeholder.name())) {
                    severe.add(shown);
                } else if (OUTPUT.matcher(placeholder.name()).matches()
                        || (text.escaped() && placeholder.quote() == Quote.SINGLE)) {
                    other.add(shown);
                }
            }
        }
        if (severe.isEmpty() && other.isEmpty()) return;
        Set<String> all = new LinkedHashSet<>(severe);
        all.addAll(other);
        String names = String.join(", ", all);
        out.add(AnalyzerFinding.of("quoted-placeholder", severe.isEmpty() ? Severity.WARNING : Severity.ERROR, i, name,
                "puts " + names + " inside quotes in shell code — an apostrophe in the value ends them, and where"
                        + " the resolver quotes the value itself both pairs cancel out (''x; id'' runs id); correct:"
                        + " without quotes plus shell_escape_variables: true, for a variable that may be unset"
                        + " set -- {{ x }}; X=\"$*\""));
    }

    private static boolean outsideText(String name) {
        return PLATFORM_DATA.contains(name) || FOREIGN_TEXT.contains(name);
    }

    /** What the heredoc bodies of one block hold, sorted by what reads them. */
    private static final class HeredocHits {
        final Set<String> source = new LinkedHashSet<>();
        final Set<String> readers = new LinkedHashSet<>();
        final Set<String> expandedSevere = new LinkedHashSet<>();
        final Set<String> expandedOther = new LinkedHashSet<>();
        final Set<String> delimiters = new LinkedHashSet<>();
        final Set<String> escaped = new LinkedHashSet<>();
    }

    private static void heredoc(List<ShellText> texts, int i, String name, List<AnalyzerFinding> out) {
        HeredocHits hits = new HeredocHits();
        for (ShellText text : texts) {
            for (Heredoc heredoc : ShellQuoteScanner.read(text.code()).heredocs()) {
                if (heredoc.placeholders().isEmpty()) continue;
                String reader = HeredocReader.sourceReader(heredoc.command(), heredoc.rest());
                for (String placeholder : heredoc.placeholders()) {
                    String shown = "{{ " + placeholder + " }}";
                    boolean free = outsideText(placeholder) || OUTPUT.matcher(placeholder).matches();
                    if (free && reader != null) {
                        hits.source.add(shown);
                        hits.readers.add(reader);
                    } else if (free && !heredoc.quoted()) {
                        (outsideText(placeholder) ? hits.expandedSevere : hits.expandedOther).add(shown);
                        hits.delimiters.add(heredoc.delimiter());
                    } else if (text.escaped()) {
                        hits.escaped.add(shown);
                    }
                }
            }
        }
        if (!hits.source.isEmpty()) out.add(programSource(hits, i, name));
        if (!hits.expandedSevere.isEmpty() || !hits.expandedOther.isEmpty()) out.add(shellExpanded(hits, i, name));
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
        Set<String> all = new LinkedHashSet<>(hits.expandedSevere);
        all.addAll(hits.expandedOther);
        String names = String.join(", ", all);
        String delimiter = hits.delimiters.iterator().next();
        return AnalyzerFinding.of("heredoc-placeholder",
                hits.expandedSevere.isEmpty() ? Severity.WARNING : Severity.ERROR, i, name,
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
