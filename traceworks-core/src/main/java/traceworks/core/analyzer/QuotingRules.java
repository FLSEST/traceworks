package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.context.ShellQuoteScanner;
import traceworks.core.context.ShellQuoteScanner.Heredoc;
import traceworks.core.context.ShellQuoteScanner.Placeholder;
import traceworks.core.context.ShellQuoteScanner.Quote;
import traceworks.core.engine.ChildWorkflows;
import traceworks.core.engine.NestedBlocks;
import traceworks.core.engine.StepModes;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
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
 * <ul>
 *   <li>{@code quoted-placeholder}: a placeholder for free text stands inside quotes. Without escaping, an apostrophe
 *       in the value ends the quotes ({@code '; id} runs {@code id}); where the resolver quotes the value itself
 *       (outside text always, every output of a tainted run, every value with {@code shell_escape_variables}) the two
 *       pairs cancel out ({@code ''x; id''}). {@code ERROR} for outside text and for what a model wrote (below),
 *       {@code WARNING} for the other block outputs; in a block with {@code shell_escape_variables} also
 *       {@code WARNING} for any other placeholder in single quotes.</li>
 *   <li>{@code placeholder-self-compare}: {@code [ "$X" = '{{ x }}' ]} after {@code X='{{ x }}'} is meant to tell
 *       whether x was passed. The resolver substitutes the comparison too, so without escaping the check is always
 *       true once x is set, and the passed value gets overwritten.</li>
 *   <li>{@code heredoc-placeholder}: the same free text in the body of a heredoc, which {@code quoted-placeholder}
 *       leaves out because quotes mean nothing there. What counts is what reads the body: an interpreter that takes it
 *       as its program ({@code python3 - <<'PY'}, {@link HeredocReader}) turns the value into code ({@code ERROR}); an
 *       unquoted delimiter lets the shell run {@code $(…)} in the value ({@code ERROR} for outside text,
 *       {@code WARNING} for the other block outputs); a quoted heredoc whose reader takes the body as input
 *       ({@code cat > file}, {@code python3 tool.py}) is fine. In a block with {@code shell_escape_variables} every
 *       placeholder in a body is a {@code WARNING}: the resolver quotes it there too and the quotes land in the
 *       text. What a model wrote is an {@code ERROR} in every body: the run quotes it there, and a line of it that
 *       equals the delimiter ends the body.</li>
 *   <li>{@code unquoted-ai-output}: what a model wrote stands in shell code of a block without
 *       {@code shell_escape_variables}, outside quotes and heredoc bodies. A model reads the workspace, and what it
 *       reads can steer its answer, so a run quotes what a model wrote in every shell line
 *       ({@code ExecutionContextTaint}); the command would not run as its author wrote it, and a workflow behaves
 *       as written or is refused. {@code ERROR}.</li>
 * </ul>
 *
 * <p><b>What a model wrote</b>, as far as the block list tells: the output of a block that asks a model
 * ({@code simple}, {@code agentic}, {@code compress-ai}, a wrapper in AI mode, a block with AI steps nested in it, a
 * step an AI actor decides, and a {@code sub-workflow} or {@code foreach} whose child workflow has such a block, when
 * the analyzer can find the child), and the output of every block after the first of them, because the run quotes
 * those too: a later step can print what the model wrote without naming it. The block is found through
 * {@code block.<name>.output}, {@code previous_output} in the block's own fields, and {@code step_N_output} while no
 * wrapper before it draws further step indices. A {@code step_N_output} after such a wrapper, and a
 * {@code previous_output} in a nested block, name an output whose step the list does not tell: they count as what a
 * model wrote when the workflow asks a model before the block. What the list does not tell at all (a loop item, a
 * variable, a child whose reference is a placeholder, the values a parent hands to a child) the run decides: it
 * quotes such a value where it stands bare and refuses the shell line where it does not
 * ({@code ShellLineRefusedException}).
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
        apply(blocks, null, out);
    }

    /**
     * @param children finds the workflows of {@code sub-workflow} and {@code foreach} blocks, to see whether one
     *                 asks a model; {@code null} looks into none
     */
    static void apply(List<BlockDefinition> blocks, WorkflowResolver children, List<AnalyzerFinding> out) {
        ModelSteps model = ModelSteps.of(blocks, children);
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) continue;
            List<ShellText> texts = shellTexts(block, i);
            if (texts.isEmpty()) continue;
            quoted(model, texts, i, block.getName(), out);
            selfCompare(texts, i, block.getName(), out);
            heredoc(model, texts, i, block.getName(), out);
            unquotedAiOutput(model, texts, i, out);
        }
    }

    private static void unquotedAiOutput(ModelSteps model, List<ShellText> texts, int i, List<AnalyzerFinding> out) {
        Set<String> placeholders = new LinkedHashSet<>();
        Set<String> sources = new LinkedHashSet<>();
        for (ShellText text : texts) {
            if (text.escaped()) continue;
            for (Placeholder placeholder : ShellQuoteScanner.scan(text.code())) {
                if (placeholder.quote() != Quote.NONE) continue;
                String source = model.sourceOf(i, placeholder.name().strip(), text.own());
                if (source != null) {
                    placeholders.add("{{ " + placeholder.name() + " }}");
                    sources.add(source);
                }
            }
        }
        if (placeholders.isEmpty()) return;
        out.add(AnalyzerFinding.of("unquoted-ai-output", Severity.ERROR, i, model.blocks().get(i).getName(),
                "puts " + String.join(", ", placeholders) + " into shell code without shell_escape_variables, "
                        + String.join(", ", sources) + " — a model reads the workspace, and what it reads can steer"
                        + " its answer, so a run quotes what a model wrote in every shell line and this command would"
                        + " not run as written; Traceworks refuses the workflow. Correct: shell_escape_variables: true"
                        + " on this block, which quotes every value; to hand the text on as standard input or as a"
                        + " file, write printf '%s\\n' " + placeholders.iterator().next() + " | tool, or > file"));
    }

    /**
     * A block a placeholder stands for, and the position of the top-level block it is, or is nested in.
     *
     * @param afterNestedModel for a nested block: whether a block nested in the same top-level block may ask a model
     *                         before it runs, one written before it in a {@code switch}, any one in a loop
     */
    private record Source(BlockDefinition block, int position, boolean afterNestedModel) {

        Source(BlockDefinition block, int position) {
            this(block, position, false);
        }
    }

    /**
     * The blocks of a workflow that ask a model, as far as the definition tells, and with them what a run quotes as
     * text a model wrote: the output of such a block, and the output of every block after the first of them.
     *
     * @param first the position of the first top-level block that asks a model; -1 for a workflow without one
     */
    private record ModelSteps(List<BlockDefinition> blocks, WorkflowResolver children, int first) {

        static ModelSteps of(List<BlockDefinition> blocks, WorkflowResolver children) {
            int first = -1;
            for (int j = 0; j < blocks.size() && first < 0; j++) {
                if (blocks.get(j) != null && asksModel(blocks.get(j), children)) first = j;
            }
            return new ModelSteps(blocks, children, first);
        }

        /**
         * What {@code name} stands for in block {@code i}, when the run quotes it as text a model wrote, as the
         * words a finding says it with; null otherwise.
         */
        String sourceOf(int i, String name, boolean own) {
            if (first < 0) return null;
            Source source = null;
            // Whether the name is an output whose step the block list does not tell
            boolean untold = false;
            Matcher byName = BLOCK_OUTPUT.matcher(name);
            Matcher byIndex = STEP_OUTPUT.matcher(name);
            if (byName.matches()) {
                source = named(blocks, children, byName.group(1));
            } else if ("previous_output".equals(name)) {
                source = own && i > 0 && blocks.get(i - 1) != null ? new Source(blocks.get(i - 1), i - 1) : null;
                untold = !own;   // in a nested block it may be the output of the step nested before it
            } else if (byIndex.matches()) {
                int index = Integer.parseInt(byIndex.group(1));
                source = atStepIndex(blocks, index, i);
                untold = source == null && drawnBefore(blocks, Math.min(index, own ? i : i + 1));
            }
            if (source == null) {
                // The run quotes it when it was recorded after a model was asked: possible from the first AI step on
                boolean afterModel = first < i || (first == i && !own);
                return untold && afterModel ? "an output whose step the workflow does not tell, in a run that asks"
                        + " a model before it (the AI step " + label(blocks.get(first)) + "), so it may hold what the"
                        + " model wrote" : null;
            }
            if (asksModel(source.block(), children)) return "the output of the AI step " + label(source.block());
            if (source.position() > first || source.afterNestedModel()) {
                return "the output of the step " + label(source.block()) + ", which runs after the AI step "
                        + label(blocks.get(first)) + " and may hold what the model wrote";
            }
            return null;
        }

        private static String label(BlockDefinition block) {
            return block.getName() != null && !block.getName().isBlank() ? block.getName() : block.getType();
        }
    }

    /** The block of that name: a top-level one first, else a nested one. */
    private static Source named(List<BlockDefinition> blocks, WorkflowResolver children, String name) {
        for (int j = 0; j < blocks.size(); j++) {
            if (blocks.get(j) != null && name.equals(blocks.get(j).getName())) return new Source(blocks.get(j), j);
        }
        List<Source> nested = new ArrayList<>();
        for (int j = 0; j < blocks.size(); j++) {
            if (blocks.get(j) == null) continue;
            int position = j;
            // A switch runs its nested blocks once, in the order written; any other wrapper may run them again
            boolean inOrder = "switch".equals(blocks.get(j).getType());
            boolean[] modelBefore = {!inOrder && asksModel(blocks.get(j), children)};
            NestedBlocks.visit(blocks.get(j).getConfig(), inner -> {
                BlockDefinition block = BlockDefinition.builder().type(inner.type()).name(inner.name())
                        .config(inner.config() != null ? inner.config() : Map.of()).build();
                if (name.equals(inner.name())) nested.add(new Source(block, position, modelBefore[0]));
                if (inner.type() != null && asksModel(block, children)) modelBefore[0] = true;
            });
        }
        return nested.isEmpty() ? null : nested.getFirst();
    }

    /** Whether a block before position {@code end} draws further step indices, so the numbers after it are not known. */
    private static boolean drawnBefore(List<BlockDefinition> blocks, int end) {
        for (int j = 0; j < Math.min(end, blocks.size()); j++) {
            if (blocks.get(j) == null || INDEX_DRAWING.contains(blocks.get(j).getType())) return true;
        }
        return false;
    }

    /** The block at position {@code index}, before {@code i}, while no block before it draws further indices. */
    private static Source atStepIndex(List<BlockDefinition> blocks, int index, int i) {
        if (index >= i) return null;
        for (int j = 0; j < index; j++) {
            if (blocks.get(j) == null || INDEX_DRAWING.contains(blocks.get(j).getType())) return null;
        }
        return blocks.get(index) == null ? null : new Source(blocks.get(index), index);
    }

    /** Whether a run of the block asks a model: the block writes AI text itself, or its child workflow does. */
    private static boolean asksModel(BlockDefinition block, WorkflowResolver children) {
        return writesAiText(block) || childAsksModel(block, children);
    }

    /**
     * Whether the workflow the block runs by a literal {@code workflow_id}, or one that workflow reaches, has a block
     * that writes AI text. A reference with a placeholder or one that names no workflow is not followed, and neither
     * is any without a resolver: the run quotes such a child's output when the child asked a model.
     */
    private static boolean childAsksModel(BlockDefinition block, WorkflowResolver children) {
        String ref = children == null ? null : ChildWorkflows.literalRef(block.getType(), block.getConfig());
        if (ref == null) return false;
        WorkflowDefinition child;
        try {
            child = children.resolve(ref);
        } catch (RuntimeException e) {
            return false;   // a reference that names no workflow fails at run time, with its own message
        }
        if (child == null) return false;
        for (ChildWorkflows.Reached reached : ChildWorkflows.reached(child, children.forChild(child))) {
            for (BlockDefinition inner : reached.definition().blocks()) {
                if (inner != null && writesAiText(inner)) return true;
            }
        }
        return false;
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

    private static void quoted(ModelSteps model, List<ShellText> texts, int i, String name,
                               List<AnalyzerFinding> out) {
        Set<String> severe = new LinkedHashSet<>();
        Set<String> other = new LinkedHashSet<>();
        for (ShellText text : texts) {
            for (Placeholder placeholder : ShellQuoteScanner.scan(text.code())) {
                if (placeholder.quote() == Quote.NONE) continue;
                String shown = "{{ " + placeholder.name() + " }}";
                // What a model wrote is quoted by every run, so inside the author's quotes it always breaks out
                if (outsideText(placeholder.name())
                        || model.sourceOf(i, placeholder.name().strip(), text.own()) != null) {
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
        final Set<String> modelText = new LinkedHashSet<>();
        final Set<String> modelSources = new LinkedHashSet<>();
    }

    private static void heredoc(ModelSteps model, List<ShellText> texts, int i, String name,
                                List<AnalyzerFinding> out) {
        HeredocHits hits = new HeredocHits();
        for (ShellText text : texts) {
            for (Heredoc heredoc : ShellQuoteScanner.read(text.code()).heredocs()) {
                if (heredoc.placeholders().isEmpty()) continue;
                String reader = HeredocReader.sourceReader(heredoc.command(), heredoc.rest());
                for (String placeholder : heredoc.placeholders()) {
                    String shown = "{{ " + placeholder + " }}";
                    String modelSource = model.sourceOf(i, placeholder.strip(), text.own());
                    boolean free = outsideText(placeholder) || OUTPUT.matcher(placeholder).matches();
                    if (free && reader != null) {
                        hits.source.add(shown);
                        hits.readers.add(reader);
                    } else if (free && !heredoc.quoted()) {
                        boolean severe = outsideText(placeholder) || modelSource != null;
                        (severe ? hits.expandedSevere : hits.expandedOther).add(shown);
                        hits.delimiters.add(heredoc.delimiter());
                    } else if (modelSource != null) {
                        hits.modelText.add(shown);
                        hits.modelSources.add(modelSource);
                    } else if (text.escaped()) {
                        hits.escaped.add(shown);
                    }
                }
            }
        }
        if (!hits.source.isEmpty()) out.add(programSource(hits, i, name));
        if (!hits.expandedSevere.isEmpty() || !hits.expandedOther.isEmpty()) out.add(shellExpanded(hits, i, name));
        if (!hits.modelText.isEmpty()) out.add(modelTextInBody(hits, i, name));
        if (!hits.escaped.isEmpty()) out.add(quotesInBody(hits, i, name));
    }

    private static AnalyzerFinding modelTextInBody(HeredocHits hits, int i, String name) {
        String names = String.join(", ", hits.modelText);
        return AnalyzerFinding.of("heredoc-placeholder", Severity.ERROR, i, name,
                "puts " + names + " into the body of a heredoc, " + String.join(", ", hits.modelSources) + " — a run"
                        + " quotes what a model wrote wherever it stands in shell code, so the quotes land in the"
                        + " text, and a line of the value that equals the delimiter ends the body and the rest runs"
                        + " as commands; Traceworks refuses the workflow. Correct: pass the value outside the body"
                        + " with shell_escape_variables: true, e.g. printf '%s\\n' " + hits.modelText.iterator().next()
                        + " > file instead of cat <<'EOF'");
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
