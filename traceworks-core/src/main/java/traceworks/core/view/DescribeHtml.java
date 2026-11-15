package traceworks.core.view;

import traceworks.core.describe.DescriptionText;
import traceworks.core.describe.WorkflowDescription;
import traceworks.core.describe.WorkflowDescription.Block;
import traceworks.core.describe.WorkflowDescription.Line;
import traceworks.core.describe.WorkflowDescription.Section;

import java.util.List;

/**
 * Renders the description of a workflow package as one self-contained HTML page, as {@link RunHtml} renders a run:
 * the same style, no JavaScript, no external resources, every text escaped. The page is a pure function of its
 * inputs, so the same package gives the same bytes.
 */
public final class DescribeHtml {

    private DescribeHtml() {
    }

    /**
     * @param version the version of the renderer, printed in the header
     */
    public static String render(WorkflowDescription described, String version) {
        String title = described.name() != null ? described.name() : described.id() != null ? described.id()
                : "(no name)";
        StringBuilder out = new StringBuilder(8192);
        out.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Workflow ").append(Html.line(title)).append("</title>\n<style>").append(RunHtml.CSS)
                .append("</style></head><body>\n");
        out.append("<h1>").append(Html.line(title)).append(" <small>").append(Html.line(described.id()))
                .append("</small></h1>\n");
        out.append("<p class=\"muted\">What this workflow will do, read from its package. Nothing ran.</p>\n");
        if (described.description() != null) {
            out.append("<p>").append(Html.text(described.description())).append("</p>\n");
        }
        out.append("<table>\n");
        row(out, "package", described.packageDir());
        if (described.commit() != null) {
            row(out, "commit", described.commit()
                    + (described.uncommittedChanges() ? " (with uncommitted changes)" : ""));
        }
        row(out, "digest", described.digest());
        row(out, "requires", described.requires().isEmpty() ? "nothing" : String.join(", ", described.requires()));
        if (!described.aiDefaults().isEmpty()) {
            row(out, "AI", String.join(", ", described.aiDefaults()));
        }
        row(out, "instructions", described.instructions());
        if (described.audit() != null) {
            row(out, "audit", DescriptionText.audit(described.audit()));
        }
        row(out, "renderer", version);
        out.append("</table>\n");

        out.append("<h2>Inputs</h2>\n");
        if (described.inputs().isEmpty()) {
            out.append("<p class=\"muted\">none</p>\n");
        } else {
            out.append("<table><tr><th>input</th><th>default</th></tr>\n");
            described.inputs().forEach((name, value) -> out.append("<tr><td>").append(Html.line(name))
                    .append("</td><td><code>").append(Html.line(value)).append("</code></td></tr>\n"));
            out.append("</table>\n");
        }

        if (!described.modelChoices().isEmpty()) {
            out.append("<h2>Model choices</h2>\n");
            out.append("<table><tr><th>choice</th><th>option</th><th>provider</th><th>model</th><th>used by</th>"
                    + "</tr>\n");
            for (WorkflowDescription.ModelChoice choice : described.modelChoices()) {
                boolean first = true;
                for (WorkflowDescription.ModelChoice.Option option : choice.options()) {
                    out.append("<tr><td>");
                    if (first) {
                        out.append(Html.line(choice.name())).append("<br><small>pick with <code>")
                                .append(Html.line(choice.pickedWith())).append("</code></small>");
                    }
                    out.append("</td><td>").append(Html.line(option.name()))
                            .append(option.name().equals(choice.defaultOption()) ? " <small>(the default)</small>"
                                    : "")
                            .append("</td><td>").append(Html.line(option.provider())).append("</td><td><code>")
                            .append(Html.line(option.model())).append("</code></td><td>")
                            .append(first ? Html.line(choice.usedBy().isEmpty() ? "no block"
                                    : String.join(", ", choice.usedBy())) : "")
                            .append("</td></tr>\n");
                    first = false;
                }
            }
            out.append("</table>\n");
        }

        out.append("<h2>Blocks</h2>\n");
        if (described.blocks().isEmpty()) {
            out.append("<p class=\"muted\">none</p>\n");
        } else {
            blocks(out, described.blocks());
        }
        out.append("<p><strong>").append(Html.line(described.counts().sentence())).append("</strong></p>\n");
        out.append("<p class=\"muted\">").append(Html.line(WorkflowDescription.OPERATOR_NOTE)).append("</p>\n");
        out.append("<p class=\"muted\">").append(Html.line(WorkflowDescription.CHECK_NOTE)).append("</p>\n");
        out.append("</body></html>\n");
        return out.toString();
    }

    /** One table per block list; the blocks a block holds or starts follow its row, set in. */
    private static void blocks(StringBuilder out, List<Block> blocks) {
        out.append("<table><tr><th>#</th><th>block</th><th>type</th><th>what</th><th>may touch</th></tr>\n");
        for (Block block : blocks) {
            out.append("<tr><td>").append(block.outline().position() < 0 ? "" : block.outline().position())
                    .append("</td><td>")
                    .append(block.outline().name() == null ? "<em>unnamed</em>" : Html.line(block.outline().name()))
                    .append("</td><td>").append(Html.line(block.outline().shownType())).append("</td><td>")
                    .append(block.outline().what() == null ? "" : "<code>" + Html.line(block.outline().what())
                            + "</code>")
                    .append("</td><td>");
            for (Line line : block.lines()) {
                out.append("<div><span class=\"badge").append(line.label().equals(Line.AI) ? " ai" : "")
                        .append("\">").append(Html.line(line.label())).append("</span> ")
                        .append(Html.line(String.join(", ", line.facts()))).append("</div>");
            }
            out.append("</td></tr>\n");
            for (Section section : block.sections()) {
                out.append("<tr><td></td><td colspan=\"4\"><div class=\"child\"><p>")
                        .append(Html.line(DescriptionText.heading(section))).append("</p>\n");
                if (!section.blocks().isEmpty()) {
                    blocks(out, section.blocks());
                }
                out.append("</div></td></tr>\n");
            }
        }
        out.append("</table>\n");
    }

    private static void row(StringBuilder out, String label, String value) {
        if (value != null) {
            out.append("<tr><th>").append(Html.line(label)).append("</th><td>").append(Html.line(value))
                    .append("</td></tr>\n");
        }
    }
}
