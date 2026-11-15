package traceworks.core.describe;

import traceworks.core.describe.WorkflowDescription.Audit;
import traceworks.core.describe.WorkflowDescription.Block;
import traceworks.core.describe.WorkflowDescription.Line;
import traceworks.core.describe.WorkflowDescription.Section;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A {@link WorkflowDescription} as lines of text. Every text of the package stands on the line it belongs to: its
 * line breaks and other white space become one space, so a name or a command cannot add a line of its own. What a
 * terminal must not get (control characters) is for the caller to replace.
 */
public final class DescriptionText {

    private DescriptionText() {
    }

    /** The lines, without line ends. */
    public static List<String> lines(WorkflowDescription described) {
        List<String> out = new ArrayList<>();
        String id = described.id() == null ? null : one(described.id());
        out.add("Workflow  " + (described.name() == null ? id == null ? "(no name)" : id
                : one(described.name()) + (id == null ? "" : " (" + id + ")")));
        if (described.description() != null) {
            out.add("  " + one(described.description()));
        }
        out.add("Package   " + one(described.packageDir()));
        out.add("Digest    " + one(described.digest()));
        if (described.commit() != null) {
            out.add("Commit    " + one(described.commit())
                    + (described.uncommittedChanges() ? " (with uncommitted changes)" : ""));
        }
        out.add("");
        if (described.inputs().isEmpty()) {
            out.add("Inputs    none");
        } else {
            out.add("Inputs");
            described.inputs().forEach((name, value) -> out.add("  " + one(name) + " = " + one(value)));
        }
        if (!described.modelChoices().isEmpty()) {
            out.add("Model choices");
            for (WorkflowDescription.ModelChoice choice : described.modelChoices()) {
                out.add("  " + one(choice.name()) + "  pick with " + one(choice.pickedWith()) + "; without it: "
                        + one(choice.defaultOption()));
                for (WorkflowDescription.ModelChoice.Option option : choice.options()) {
                    out.add("    " + one(option.name()) + "  " + one(option.provider()) + " " + one(option.model())
                            + (option.name().equals(choice.defaultOption()) ? "  (the default)" : ""));
                }
                out.add("    used by " + (choice.usedBy().isEmpty() ? "no block"
                        : choice.usedBy().stream().map(DescriptionText::one).collect(Collectors.joining(", "))));
            }
        }
        out.add("Requires  " + (described.requires().isEmpty() ? "nothing"
                : described.requires().stream().map(DescriptionText::one).collect(Collectors.joining(", "))));
        if (!described.aiDefaults().isEmpty()) {
            out.add("AI        " + facts(described.aiDefaults()));
        }
        if (described.instructions() != null) {
            out.add("Instructions  " + one(described.instructions()));
        }
        if (described.audit() != null) {
            out.add("Audit     " + audit(described.audit()));
        }
        out.add("");
        if (described.blocks().isEmpty()) {
            out.add("Blocks    none");
        } else {
            out.add("Blocks");
            blocks(out, described.blocks(), "  ");
        }
        out.add("");
        out.add(described.counts().sentence());
        out.add(WorkflowDescription.OPERATOR_NOTE);
        out.add(WorkflowDescription.CHECK_NOTE);
        return out;
    }

    /** The audit block on one line: the register it feeds, or what is wrong with it. */
    public static String audit(Audit audit) {
        if (!audit.problems().isEmpty()) {
            return "invalid: " + facts(audit.problems());
        }
        return "register " + one(audit.key()) + ", category " + one(audit.category()) + ", report block "
                + one(audit.reportBlock());
    }

    /** A section's heading: its label, and its note when it has one. */
    public static String heading(Section section) {
        String label = one(section.label());
        if (section.blocks().isEmpty()) {
            return label + ": " + (section.note() == null ? "no blocks" : one(section.note()));
        }
        return section.note() == null ? label + ":" : label + " (" + one(section.note()) + "):";
    }

    private static void blocks(List<String> out, List<Block> blocks, String indent) {
        for (Block block : blocks) {
            String position = block.outline().position() < 0 ? "-" : String.valueOf(block.outline().position());
            out.add((indent + String.format("%-3s %-24s %s", position, block.outline().name() == null ? "(unnamed)"
                    : one(block.outline().name()), one(block.outline().shownType()))).stripTrailing());
            String below = indent + "    ";
            if (block.outline().what() != null) {
                out.add(below + one(block.outline().what()));
            }
            for (Line line : block.lines()) {
                out.add(below + line.label() + ": " + facts(line.facts()));
            }
            for (Section section : block.sections()) {
                out.add(below + heading(section));
                blocks(out, section.blocks(), below + "  ");
            }
        }
    }

    private static String facts(List<String> facts) {
        return facts.stream().map(DescriptionText::one).collect(Collectors.joining(", "));
    }

    /** The text on one line: its white space runs, line breaks included, as one space each. */
    static String one(String text) {
        return text == null ? "" : text.strip().replaceAll("[\\s\\u0085\\u2028\\u2029]+", " ");
    }
}
