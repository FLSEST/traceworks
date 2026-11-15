package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.context.PlaceholderSyntax;
import traceworks.core.engine.StepOutputs;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.TriggerSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rules for the <b>wiring</b> of a definition: is generated output also read, and does every reference point to
 * something that exists?
 *
 * <p>The cases behind them: a shell block delivered 31 KB that no following block referenced, and three of four
 * blocks carried a placeholder that is never set outside a chain and therefore arrived as a literal in the prompt.
 * And a typo: {@code {{ block.y.outptu }}} and {@code {{ step_7_output }}} in a workflow of two blocks reached the
 * shell as text, and the run was green.
 */
final class WiringRules {

    private WiringRules() {}

    /** Only set inside a chain: exactly the keys the chain sets for every entry; alone the text stays literal. */
    static final Set<String> CHAIN_ONLY = Set.of("previous_chain_output", TriggerSource.CHAIN_RUN_CONTEXT_KEY);

    /** Only set in an audit workflow (valid {@code metadata.audit}); otherwise they stay literals. */
    private static final List<String> AUDIT_ONLY = List.of("audit_findings", "audit_items");

    /**
     * The block types whose step draws exactly one step index, so that the block after it has the next one. Every
     * other type (foreach, while, retry, conditional, switch, an unknown one) draws one further index per inner step,
     * item or iteration, which moves the index of every later block by a number only the run knows.
     */
    private static final Set<String> ONE_STEP = Set.of("shell", "simple", "agentic", "approval", "human-input",
            "compress", "compress-ai", "sub-workflow");

    /** The one property of a block that a placeholder can name. */
    private static final String OUTPUT = "output";

    /** Like {@link #apply(List, Map, Map, List)} for a definition without a default context. */
    static void apply(List<BlockDefinition> blocks, Map<String, Object> metadata, List<AnalyzerFinding> out) {
        apply(blocks, metadata, Map.of(), out);
    }

    /**
     * @param metadata       the definition's metadata; empty for one without, which is then no audit workflow
     * @param defaultContext the definition's default context: a variable it sets resolves under any name
     */
    static void apply(List<BlockDefinition> blocks, Map<String, Object> metadata, Map<String, Object> defaultContext,
                      List<AnalyzerFinding> out) {
        Set<String> names = BlockNameRules.allNames(blocks);
        List<String> texts = new ArrayList<>();
        for (BlockDefinition block : blocks) {
            texts.add(BlockText.joined(block));
        }

        unknownBlockReference(blocks, names, texts, out);
        unknownBlockProperty(blocks, names, texts, defaultContext, out);
        stepOutputOutOfRange(blocks, texts, out);
        chainOnlyPlaceholder(blocks, texts, out);
        auditOnlyPlaceholder(blocks, texts, metadata, out);
        unwiredOutput(blocks, texts, out);
    }

    /**
     * {@code block.<name>.output} pointing to a block the definition does not know. The resolver leaves such a
     * placeholder unchanged: the prompt receives the text itself instead of the output, mostly unnoticed because the
     * run technically goes through. A block of a {@code switch} case is known: it records its output under its name
     * like a top-level block.
     */
    private static void unknownBlockReference(List<BlockDefinition> blocks, Set<String> names,
                                              List<String> texts, List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size(); i++) {
            for (String key : PlaceholderSyntax.keysIn(texts.get(i))) {
                if (!key.startsWith("block.") || !key.endsWith(".output")) continue;
                String referenced = key.substring("block.".length(), key.length() - ".output".length());
                if (!names.contains(referenced)) {
                    out.add(AnalyzerFinding.of("unknown-block-reference", Severity.ERROR, i, nameOf(blocks.get(i)),
                            "references {{ " + key + " }}, but no block is named \"" + referenced + "\""));
                }
            }
        }
    }

    /**
     * {@code unknown-block-property}: {@code block.<name>.<property>} of a block the definition knows, where the
     * property is not {@code output}, the only one the resolver reads. The placeholder stays literal in the run. Of
     * two block names that fit ({@code a} and {@code a.b} for {@code block.a.b.exit}) the longer one counts. A
     * reference to a block the definition does not know is not judged here, and neither is a name the default context
     * sets as a variable, which resolves.
     */
    private static void unknownBlockProperty(List<BlockDefinition> blocks, Set<String> names, List<String> texts,
                                             Map<String, Object> defaultContext, List<AnalyzerFinding> out) {
        List<String> longestFirst = new ArrayList<>(names);
        longestFirst.sort(Comparator.comparingInt(String::length).reversed());
        for (int i = 0; i < blocks.size(); i++) {
            for (String key : new LinkedHashSet<>(PlaceholderSyntax.keysIn(texts.get(i)))) {
                if (!key.startsWith("block.") || key.endsWith("." + OUTPUT) || defaultContext.containsKey(key)) continue;
                String name = longestFirst.stream().filter(known -> key.startsWith("block." + known + "."))
                        .findFirst().orElse(null);
                if (name == null) continue;
                String property = key.substring("block.".length() + name.length() + 1);
                out.add(AnalyzerFinding.of("unknown-block-property", Severity.ERROR, i, nameOf(blocks.get(i)),
                        "references {{ " + key + " }}, but a block has no property \"" + property + "\" — the only"
                                + " one is " + OUTPUT + ", so the placeholder stays literal in the run; write {{ block."
                                + name + "." + OUTPUT + " }}"));
            }
        }
    }

    /**
     * {@code step-output-out-of-range}: {@code step_N_output} with an N no step of the workflow gets. The steps are
     * numbered from 0, one index per top-level block, and only a top-level block's output is kept under its index; so
     * in a workflow of two blocks the last one is {@code step_1_output}. Judged only while the indices are known:
     * when every block but the last draws exactly one index ({@link #ONE_STEP}). A block that draws further indices
     * as the last one moves no index a later block could name. A reference to a later block's index, which is not set
     * yet when the block runs, is not judged.
     */
    private static void stepOutputOutOfRange(List<BlockDefinition> blocks, List<String> texts,
                                             List<AnalyzerFinding> out) {
        int size = blocks.size();
        for (int i = 0; i < size - 1; i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null || block.getType() == null || !ONE_STEP.contains(block.getType())) return;
        }
        for (int i = 0; i < size; i++) {
            for (String key : new LinkedHashSet<>(PlaceholderSyntax.keysIn(texts.get(i)))) {
                if (!StepOutputs.isStepOutput(key) || stepIndex(key) < size) continue;
                out.add(AnalyzerFinding.of("step-output-out-of-range", Severity.ERROR, i, nameOf(blocks.get(i)),
                        "references {{ " + key + " }}, but the workflow has " + size + (size == 1 ? " block" : " blocks")
                                + " and its steps are numbered from 0: the last one is {{ "
                                + StepOutputs.name(size - 1) + " }}, so the placeholder stays literal in the run"));
            }
        }
    }

    /** The N of {@code step_N_output}; a number too long to be an index is beyond every workflow. */
    private static long stepIndex(String key) {
        String digits = key.substring("step_".length(), key.length() - "_output".length());
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    /** Chain variable in a definition that is not itself a chain entry. */
    private static void chainOnlyPlaceholder(List<BlockDefinition> blocks, List<String> texts,
                                             List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size(); i++) {
            for (String key : new LinkedHashSet<>(PlaceholderSyntax.keysIn(texts.get(i)))) {
                if (CHAIN_ONLY.contains(key)) {
                    out.add(AnalyzerFinding.of("chain-only-placeholder", Severity.WARNING, i, nameOf(blocks.get(i)),
                            "{{ " + key + " }} is only set inside a chain; when the workflow runs on its own, "
                                    + "the literal placeholder ends up in the prompt"));
                }
            }
        }
    }

    /**
     * {@code audit_findings} or {@code audit_items} in a definition without valid {@code metadata.audit}. Each exists only for an
     * audit workflow, decided as at run time ({@link #auditWorkflow}); otherwise, also as a sub-workflow of an audit
     * workflow, the text itself is in the prompt.
     */
    private static void auditOnlyPlaceholder(List<BlockDefinition> blocks, List<String> texts,
                                             Map<String, Object> metadata, List<AnalyzerFinding> out) {
        if (auditWorkflow(metadata)) return;
        for (int i = 0; i < blocks.size(); i++) {
            for (String name : AUDIT_ONLY) {
                if (PlaceholderSyntax.keysIn(texts.get(i)).contains(name)) {
                    out.add(AnalyzerFinding.of("audit-only-placeholder", Severity.WARNING, i, nameOf(blocks.get(i)),
                            "{{ " + name + " }} is only set in an audit workflow (metadata.audit with enabled, "
                                    + "key, category and reportBlock); here the literal placeholder ends up in the prompt"));
                }
            }
        }
    }

    /**
     * Whether the metadata carry an enabled, complete audit block: {@code enabled: true}, {@code key},
     * {@code category} and {@code reportBlock} set, {@code schemaVersion} 1 or absent. Only such a
     * workflow gets {@code audit_findings}.
     */
    static boolean auditWorkflow(Map<String, Object> metadata) {
        return traceworks.core.model.AuditConfig.of(metadata).isPresent();
    }

    /**
     * A block produces output that nobody reads. Every block except the last is checked: does the immediately
     * following one access {@code previous_output}, or does any later block name it through
     * {@code block.<name>.output} or {@code step_N_output}?
     *
     * <p>Only <b>AI blocks</b> are checked. A shell block whose output nobody reads often has a reason for exactly
     * that: it writes a file that the next block reads again. With an AI block, by contrast, an unread output is
     * paid-for text that nobody gets. Without this restriction the rule reported over a hundred hits on real
     * definitions, most of them legitimate shell chains, and a rule with that much noise gets switched off instead of
     * heeded.
     *
     * <p>Wrapper blocks (foreach, while, ...) are excluded too: their inner blocks pass outputs on to each other,
     * which looks like a dead output from the outside.
     */
    private static void unwiredOutput(List<BlockDefinition> blocks, List<String> texts,
                                      List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size() - 1; i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null || isWrapper(block) || !isAiBlock(block)) continue;

            String next = texts.get(i + 1);
            if (next.contains("previous_output")) continue;

            String byName = block.getName() == null || block.getName().isBlank()
                    ? null : "block." + block.getName() + ".output";
            String byIndex = StepOutputs.name(i);
            boolean readLater = false;
            for (int j = i + 1; j < blocks.size() && !readLater; j++) {
                String text = texts.get(j);
                readLater = text.contains(byIndex) || (byName != null && text.contains(byName));
            }
            if (!readLater) {
                out.add(AnalyzerFinding.of("unwired-output", Severity.WARNING, i, block.getName(),
                        "no later block reads the output of this block "
                                + "(neither previous_output nor " + byIndex
                                + (byName == null ? "" : " nor " + byName) + ")"));
            }
        }
    }

    /** Only here an unread output costs money instead of just a file access. */
    private static boolean isAiBlock(BlockDefinition block) {
        String type = block.getType() == null ? "" : block.getType();
        return switch (type) {
            case "simple", "agentic", "compress-ai" -> true;
            default -> false;
        };
    }

    /** Container blocks run their own wiring, which cannot be judged from the outside. */
    private static boolean isWrapper(BlockDefinition block) {
        String type = block.getType() == null ? "" : block.getType();
        return switch (type) {
            case "foreach", "while", "conditional", "switch", "retry", "sub-workflow" -> true;
            default -> false;
        };
    }

    private static String nameOf(BlockDefinition block) {
        return block == null ? null : block.getName();
    }
}
