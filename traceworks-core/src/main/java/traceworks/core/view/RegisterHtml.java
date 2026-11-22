package traceworks.core.view;

import traceworks.core.port.BlobStore;
import traceworks.core.record.ChainReport;
import traceworks.core.register.Finding;
import traceworks.core.register.FindingStatus;
import traceworks.core.register.RegisterEvent;
import traceworks.core.register.RegisterState;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a register as one self-contained HTML page, {@code report.html}: the findings by status, the history of
 * the log, the verification line. No scripts, nothing loaded, every text escaped; the same state gives the same bytes.
 */
public final class RegisterHtml {

    /** How many lines of the log the history shows, newest first. */
    static final int HISTORY_LINES = 200;

    private static final List<FindingStatus> ORDER = List.of(FindingStatus.OPEN, FindingStatus.AWAITING_VERIFICATION,
            FindingStatus.ACCEPTED_RISK, FindingStatus.DISMISSED, FindingStatus.RESOLVED);

    private RegisterHtml() {
    }

    /**
     * @param history the register's log, oldest first
     * @param chain   the verification of the register
     * @param version the version of the renderer
     */
    public static String render(RegisterState state, List<RegisterEvent> history, BlobStore blobs, ChainReport chain,
                                String version) {
        StringBuilder out = new StringBuilder(8192);
        out.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n")
                .append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>Register ").append(Html.line(name(state))).append("</title>\n<style>").append(RunHtml.CSS)
                .append(".unverified{color:var(--yellow)}.finding{border:1px solid var(--line);border-left-width:4px;"
                        + "background:var(--card);padding:.5rem 1rem;margin:.5rem 0;border-radius:4px}"
                        + ".finding.open{border-left-color:var(--red)}.finding.awaiting_verification{border-left-color:var(--yellow)}"
                        + ".finding.accepted_risk,.finding.dismissed{border-left-color:var(--muted)}"
                        + ".finding.resolved{border-left-color:var(--green)}\n")
                .append("</style></head><body>\n");
        header(out, state, version);
        verification(out, chain);
        findings(out, state);
        items(out, state);
        history(out, history);
        out.append("</body></html>\n");
        return out.toString();
    }

    /** The register by its key and its project: {@code hello of shop}; the key alone for one from before projects. */
    private static String name(RegisterState state) {
        return state.project() == null ? state.key() : state.key() + " of " + state.project();
    }

    private static void header(StringBuilder out, RegisterState state, String version) {
        out.append("<h1>").append(Html.line(name(state)));
        if (state.category() != null) {
            out.append(" <small>").append(Html.line(state.category())).append("</small>");
        }
        out.append("</h1>\n<table>\n");
        row(out, "project", state.project());   // only for a register that names one
        row(out, "workflow", state.workflowId());
        if (state.lastReport() != null) {
            row(out, "last report", "run " + state.lastReport().runId() + (state.lastReport().finishedAt() == null ? ""
                    : " at " + state.lastReport().finishedAt()) + (state.lastReport().valid() ? " (applied)" : " (rejected)"));
        }
        row(out, "reports applied", String.valueOf(state.validReports()));
        Map<FindingStatus, Integer> counts = new EnumMap<>(FindingStatus.class);
        state.findings().forEach(f -> counts.merge(f.status(), 1, Integer::sum));
        StringBuilder summary = new StringBuilder();
        for (FindingStatus status : ORDER) {
            if (counts.containsKey(status)) {
                summary.append(summary.isEmpty() ? "" : " · ").append(status).append(' ').append(counts.get(status));
            }
        }
        row(out, "findings", summary.isEmpty() ? "no findings yet" : summary.toString());
        if (!state.items().isEmpty()) {
            row(out, "items", traceworks.core.register.ItemsText.summary(state));
        }
        row(out, "renderer", version);
        out.append("</table>\n");
    }

    private static void row(StringBuilder out, String label, String value) {
        if (value != null) {
            out.append("<tr><th>").append(Html.line(label)).append("</th><td>").append(Html.line(value))
                    .append("</td></tr>\n");
        }
    }

    private static void verification(StringBuilder out, ChainReport chain) {
        if (chain.intact()) {
            out.append("<p class=\"verification\">chain intact, ").append(chain.events()).append(" events, head ")
                    .append(Html.line(chain.head())).append("</p>\n");
        } else {
            out.append("<div class=\"verification problem\"><p>chain NOT intact, ").append(chain.events())
                    .append(" events read</p><ul>\n");
            chain.problems().forEach(p -> out.append("<li>").append(Html.line(p)).append("</li>\n"));
            out.append("</ul></div>\n");
        }
    }

    private static void findings(StringBuilder out, RegisterState state) {
        if (state.findings().isEmpty()) {
            out.append("<p class=\"muted\">no findings yet</p>\n");
            return;
        }
        for (FindingStatus status : ORDER) {
            List<Finding> of = state.findings().stream().filter(f -> f.status() == status).toList();
            if (of.isEmpty()) {
                continue;
            }
            boolean collapsed = status == FindingStatus.RESOLVED;
            out.append(collapsed ? "<details><summary>" : "<h2>").append(status).append(" (").append(of.size())
                    .append(")").append(collapsed ? "</summary>\n" : "</h2>\n");
            for (Finding f : of) {
                finding(out, f);
            }
            if (collapsed) {
                out.append("</details>\n");
            }
        }
    }

    private static void finding(StringBuilder out, Finding f) {
        out.append("<div class=\"finding ").append(f.status().name().toLowerCase(java.util.Locale.ROOT))
                .append("\" id=\"").append(Html.line(f.id())).append("\">\n<p><code>").append(Html.line(f.ruleKey()))
                .append("</code> <code>").append(Html.line(f.subject())).append("</code> · <strong>")
                .append(f.severity()).append("</strong> · ").append(Html.line(f.title()))
                .append(" <small>").append(Html.line(f.id()), 0, 8).append("</small></p>\n");
        if (f.description() != null) {
            out.append("<p>").append(Html.text(Html.cut(f.description(), Content.LIMIT))).append("</p>\n");
        }
        if (f.evidence() != null) {
            out.append("<pre>").append(Html.text(Html.cut(f.evidence(), Content.LIMIT))).append("</pre>\n");
        }
        if (f.unverifiedEvidence()) {
            out.append("<p><span class=\"unverified\">unverified evidence</span>: it names no fact a step of the run measured</p>\n");
        }
        out.append("<p class=\"muted\">first seen run ").append(Html.line(f.firstSeen().runId())).append(" at ")
                .append(f.firstSeen().at()).append(", last seen run ").append(Html.line(f.lastSeen().runId()))
                .append(" at ").append(f.lastSeen().at()).append(", ").append(f.occurrences()).append(" occurrence")
                .append(f.occurrences() == 1 ? "" : "s").append(", ").append(f.regressions()).append(" regression")
                .append(f.regressions() == 1 ? "" : "s");
        if (f.resolvedAt() != null) {
            out.append(", resolved at ").append(f.resolvedAt());
        }
        if (!f.tags().isEmpty()) {
            out.append(", tags ").append(Html.line(String.join(", ", f.tags())));
        }
        out.append("</p>\n");
        if (f.disposition() != null) {
            Finding.DispositionRecord d = f.disposition();
            out.append("<p>").append(d.kind() == FindingStatus.ACCEPTED_RISK ? "accepted" : "dismissed").append(" by ")
                    .append(Html.line(d.actor() == null ? "?" : d.actor()));
            if (d.agent() != null) {
                // The actor ran the command; an agent that named itself decided under that actor
                out.append(Html.line(traceworks.core.register.DecisionAgent.through(d.agent())));
            }
            out.append(" at ").append(d.at());
            if (d.until() != null) {
                out.append(", review by ").append(d.until());
            }
            String madeOn = traceworks.core.register.Disposition.madeOn(f);
            if (madeOn != null) {
                out.append(", ").append(madeOn);
            }
            if (f.status() != d.kind()) {
                out.append("; not in force: the finding is ").append(f.status());
            }
            out.append(": ").append(Html.line(d.note() == null ? "(no note)" : d.note())).append("</p>\n");
        }
        out.append("</div>\n");
    }

    /** The items the audit judges, each with its state, closed by default: the findings are what a reader came for. */
    private static void items(StringBuilder out, RegisterState state) {
        if (state.items().isEmpty()) {
            return;
        }
        out.append("<details><summary>Items (").append(state.items().size()).append(")</summary>\n<table>")
                .append("<tr><th>state</th><th>verdict digest</th><th>item</th><th>last verdict in run</th></tr>\n");
        for (traceworks.core.register.Item item
                : traceworks.core.register.ItemsText.listed(state)) {
            out.append("<tr><td>").append(traceworks.core.register.ItemState.of(item,
                            state.findings()).word())
                    .append("</td><td>").append(Html.line(item.verdictDigest() == null ? "-" : item.verdictDigest()))
                    .append("</td><td>").append(Html.line(item.name()))
                    .append("</td><td>").append(Html.line(item.verdict() == null ? "-" : item.verdict().runId()))
                    .append("</td></tr>\n");
        }
        out.append("</table></details>\n");
    }

    private static void history(StringBuilder out, List<RegisterEvent> history) {
        if (history.isEmpty()) {
            return;
        }
        out.append("<h2>History</h2>\n<table><tr><th>seq</th><th>time</th><th>event</th><th>what</th></tr>\n");
        int from = Math.max(0, history.size() - HISTORY_LINES);
        for (int i = history.size() - 1; i >= from; i--) {
            RegisterEvent e = history.get(i);
            out.append("<tr><td>").append(e.seq()).append("</td><td>").append(e.at()).append("</td><td>")
                    .append(Html.line(e.type())).append("</td><td>");
            StringBuilder what = new StringBuilder();
            for (String field : List.of("run_id", "id", "reason", "actor", "agent", "note", "counts", "workflow_id")) {
                if (e.fields().has(field) && !e.fields().get(field).isNull()) {
                    String value = e.fields().get(field).isTextual() ? e.fields().get(field).asText()
                            : e.fields().get(field).toString();
                    if (field.equals("id") && value.length() > 8) {
                        value = value.substring(0, 8);
                    }
                    what.append(what.isEmpty() ? "" : " · ").append(field).append(' ').append(value);
                }
            }
            out.append(Html.line(what.toString())).append("</td></tr>\n");
        }
        out.append("</table>\n");
    }
}
