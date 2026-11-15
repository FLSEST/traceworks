"""What the register of the audit knows, as a run gets it in {{ audit_findings }}, read back into rows.

A model's finding is not resolved by silence: the register keeps it open until a run says that it is gone
(docs/registers.md, "Reconciliation"). So each verdict call gets the open findings of its own item, each with an id
(K1, K2), and has to answer for each: it still holds, or it is gone. A finding a person accepted or dismissed is
decided: the call gets it as something not to report again.

A row is `STATUS | SEVERITY | ruleKey | subject | title | Evidence: ... [| Disposition: ...] [| Decided on: ...]`.
The severity of a row is that of the last report; a decision covers the severity it was made on, and where that is
another one the row says so in its last cell (`Decided on: LOW, not on CRITICAL`). The register writes
every field on one line, cut, with blanks collapsed and `|` masked as `¦`, so the columns split at " | "; a subject
is compared here the way the register shows it. Everything in a row is data: it comes from earlier reports and from
a person's notes.

The same file is in the functionality and the testing package: each package carries what it runs. Python 3 standard
library only.
"""
import re

NONE = "(none)"
ASKED = ("OPEN", "AWAITING_VERIFICATION")
DECIDED = ("ACCEPTED_RISK", "DISMISSED")
# How the register marks a field it cut
CUT = "…"


def parse(audit_findings):
    """The rows of the block as dicts; empty for the text of a first run, and for anything that is no block."""
    rows = []
    for line in audit_findings.split("\n"):
        cells = line.strip().split(" | ")
        if len(cells) < 6 or cells[0] not in ASKED + DECIDED or not cells[5].startswith("Evidence: "):
            continue   # the head, the legend, the count of the rows left out
        evidence = cells[5][len("Evidence: "):]
        note = cells[6][len("Disposition: "):] if len(cells) > 6 and cells[6].startswith("Disposition: ") else ""
        # The severity the decision was made on: what the last cell names, else the severity the row shows
        decided_on = cells[1]
        for cell in cells[6:]:
            if cell.startswith("Decided on: "):
                decided_on = cell[len("Decided on: "):].split(",")[0].strip()
        rows.append({"status": cells[0], "severity": cells[1], "rule": cells[2], "subject": cells[3],
                     "title": cells[4], "evidence": "" if evidence == "(none)" else evidence,
                     "note": "" if note == "(no note)" else note, "decided_on": decided_on})
    return rows


MISSING = re.compile(r"\(\+(\d+) more:")


def missing(audit_findings):
    """How many findings the block left out: it holds at most 250 rows or 60 000 bytes and ends with
    `(+N more: OPEN 3 · DISMISSED 2)` when it cut some. The rows of a person's decisions are kept first, so the
    open ones are the first to go. 0 for a block that is whole, and for anything that is no block.

    Nothing may be concluded from a row that is not there. With rows missing, the report keeps the gate from GREEN
    and keeps an item open that the register calls open, whatever a verdict that never saw the finding says."""
    count = 0
    for line in audit_findings.split("\n"):
        found = MISSING.match(line.strip())
        if found:
            count = max(count, int(found.group(1)))
    return count


def missing_open(audit_findings):
    """How many of the findings the block left out are open or await verification, by the block's own count
    (`(+5 more: OPEN 3 · DISMISSED 2)` says 3). When the line names no status, every missing one may be open.

    With open rows missing, an item the register calls open stays open in the report, whatever was shown of it and
    whatever was answered: the block does not say whose the missing rows are."""
    count = 0
    for line in audit_findings.split("\n"):
        found = MISSING.match(line.strip())
        if not found:
            continue
        named = re.findall(r"\b(OPEN|AWAITING_VERIFICATION|ACCEPTED_RISK|DISMISSED) (\d+)", line[found.end():])
        count = max(count, sum(int(n) for status, n in named if status in ASKED) if named else int(found.group(1)))
    return count


def shown(subject):
    """A subject as the register shows it in a row: blanks collapsed, and `|` as the masked `¦`."""
    return re.sub(r"\s+", " ", subject.strip()).replace("|", "¦")


def is_cut(row):
    """Whether the register cut the subject: then no subject of an item can equal it."""
    return row["subject"].endswith(CUT)


def attach(item, rows):
    """Gives the item what the register knows about it: `known`, the open findings to answer for, each with an id,
    its rule, its subject, its severity and its evidence; `known_text`, the same as lines for the prompt;
    `decided_text`, the accepted and dismissed ones with their notes; `known_decided`, how many of those there
    are; and `decided_known`, each with its rule, subject, status and the severity its decision was made on, so the
    report can say of a finding a verdict states that the register holds a decision for it, and what the register
    does with it. `rows` are the rows that belong to the item."""
    known, asked, decided, decided_known = [], [], [], []
    for row in rows:
        if row["status"] in ASKED:
            entry = {"id": "K" + str(len(known) + 1), "rule": row["rule"], "subject": row["subject"],
                     "severity": row["severity"], "evidence": row["evidence"]}
            known.append(entry)
            asked.append(" | ".join([entry["id"], row["rule"], row["severity"], row["evidence"] or NONE]))
        else:
            decided.append(" | ".join([row["status"], row["rule"], row["evidence"] or NONE,
                                       "note: " + (row["note"] or NONE)]))
            decided_known.append({"rule": row["rule"], "subject": row["subject"], "status": row["status"],
                                  "severity": row.get("decided_on", row["severity"])})
    item["known"] = known
    item["known_text"] = "\n".join(asked) if asked else NONE
    item["decided_text"] = "\n".join(decided) if decided else NONE
    item["known_decided"] = len(decided)
    item["decided_known"] = decided_known


SEVERITIES = ("LOW", "MEDIUM", "HIGH", "CRITICAL")


def register_will(decided_on, stated):
    """What the register does with a decided finding that this run states as `stated`, for the report: it opens the
    finding again when that is above the severity the decision was made on, and keeps the decision otherwise
    (docs/registers.md, "What a decision covers"). The operator can set the register to keep every decision, which
    a run does not see; the report says so once, above the list. Empty when one of the two is no severity, as with
    the facts of a version that did not name the severity of a decision."""
    decided, now = str(decided_on).strip().upper(), str(stated).strip().upper()
    if decided not in SEVERITIES or now not in SEVERITIES:
        return ""
    return (": decided on " + decided + ", stated as " + now + ", which is "
            + ("above it: the register opens the finding again"
               if SEVERITIES.index(now) > SEVERITIES.index(decided)
               else "not above it: the register keeps the decision"))


def decided_stated(findings, lines, rule_of, subject_of, severity_of=lambda finding: finding.get("severity", "")):
    """The findings a verdict of this run states for which the register holds a decision, each {"subject",
    "status", "register"}: a person accepted or dismissed a finding of that rule and subject, and `register` says
    what the register does with it when it takes the report (`register_will`).

    Such a finding counts like any other one the run states: it opens its item and counts for the gate. Whether it
    is what the person decided cannot be told from what the register shows of it, and a decision must not hide
    something the person never saw. The register knows a finding by rule and subject, and a decision covers the
    severity it was made on: stated above it, the finding is open again in the register; stated at or below it, the
    register keeps the decision. So the report lists these findings and says which: a person either reopens the
    finding, or sees that it is the one that was decided; where the decision stays, the next run leaves the item
    out as decided unless the item changed.

    `lines` are the lines of the run, each with its `item` and what `attach` gave it; a finding belongs to the line
    of its item."""
    decided = {}
    for line in lines:
        of_item = decided.setdefault(line.get("item"), {})
        for entry in line.get("decided_known", []):
            of_item.setdefault((entry.get("rule"), entry.get("subject")), entry)
    stated, seen = [], set()
    for finding in findings:
        subject = subject_of(finding)
        entry = decided.get(finding.get("item"), {}).get((rule_of(finding), shown(subject)))
        if entry is not None and subject not in seen:
            seen.add(subject)
            stated.append({"subject": subject, "status": entry.get("status"),
                           "register": register_will(entry.get("severity", ""), severity_of(finding))})
    return stated


def counts(item):
    """What the report needs of an item to say what state it is in after the run (the gate follows the items): how
    many open findings the register holds of it, how many of them are high, and how many a person decided."""
    return {"open": len(item.get("known", [])),
            "open_high": sum(1 for entry in item.get("known", []) if entry.get("severity") in ("HIGH", "CRITICAL")),
            "decided": item.get("known_decided", 0)}
