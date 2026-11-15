#!/usr/bin/env python3
"""The report step of the functionality self-audit.

It takes the measured findings of the fact step and the answers of the AI, checks every quote of every AI finding
against the files, counts, sets the gate and prints the audit result envelope the register reads. The AI decides
none of that: a finding of the AI counts only when its doc_quote is in the section it judged and its code_quote is
a line of the file it names.

The gate follows the items: every section that could be judged is one, and this script says in what state each is
after the run. GREEN when every item is settled (clean or decided), RED while an open finding of high severity
stands, YELLOW while an item is open or not judged, or something was measured or left unmeasured.

The register and the gate take the findings a verdict rates medium or high. A new one it rates low is listed in the
report of the run and nowhere else: not in the envelope, not in findings.jsonl. Its section counts as judged. An open
finding the register holds is answered as before, whatever its severity.

    report.py --root DIR --facts FILE --sections FILE --verdicts FILE|- --out FILE

prints the gate, the report and the envelope, and writes the report to --out and the findings as data to
findings.jsonl next to it. The envelope lists every item of the audit (each section that could be judged) and says
which of them this run judged; a section without a verdict, because its call failed or its answer was no JSON, is an
item not judged and leaves the snapshot full. The envelope is a partial snapshot only when something was left
unmeasured. Python 3 standard library only; it reads the workspace and writes only those two files.
"""
import argparse
import hashlib
import json
import os
import re
import sys

sys.dont_write_bytecode = True   # the package directory is read-only in a run, and a build must not change it

import known as known_rows

RULE = "docs-contradict-code"
SEVERITIES = {"high": "HIGH", "medium": "MEDIUM", "low": "LOW"}
ORDER = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3}
# A quote shorter than this proves nothing: "}" is a line of every file
MIN_DOC_QUOTE = 20
MIN_CODE_QUOTE = 8
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_SHOWN = 300
# A model may name the file as the container shows it
WORKSPACE_PREFIXES = ("/workspace/", "./")


def collapse(text):
    """The text with every run of white space as one space, trimmed: how a quote and a text are compared."""
    return re.sub(r"\s+", " ", str(text)).strip()


def shown(text, limit=MAX_SHOWN):
    """Text of a model or of a file for the report: one line, no control character, no comment marker, cut."""
    line = "".join(c if c.isprintable() else " " for c in collapse(text))
    line = collapse(line).replace("<!--", "<! --").replace("-->", "-- >")
    return line if len(line) <= limit else line[:limit] + "..."


def first_object(text, key):
    """The first JSON object in the text that has the key, or None."""
    decoder = json.JSONDecoder()
    at = text.find("{")
    tries = 0
    while at >= 0 and tries < 500:
        tries += 1
        try:
            value, _ = decoder.raw_decode(text, at)
            if isinstance(value, dict) and key in value:
                return value
        except (ValueError, RecursionError):
            pass
        at = text.find("{", at + 1)
    return None


def read_code_file(root, name):
    """The lines of a file of the workspace the model names, or the reason why it cannot be a place for a quote."""
    if not isinstance(name, str) or not name.strip():
        return None, None, "no file named"
    rel = name.strip()
    for prefix in WORKSPACE_PREFIXES:
        while rel.startswith(prefix):
            rel = rel[len(prefix):]
    if os.path.isabs(rel) or "\x00" in rel:
        return None, rel, "the file is not a path inside the workspace"
    real_root = os.path.realpath(root)
    real = os.path.realpath(os.path.join(real_root, rel))
    if real != real_root and not real.startswith(real_root + os.sep):
        return None, rel, "the file is not inside the workspace"
    rel = os.path.relpath(real, real_root).replace(os.sep, "/")
    if not os.path.isfile(real):
        return None, rel, "no such file in the workspace"
    if rel.lower().endswith(".md"):
        return None, rel, "the file is a document, not code"
    try:
        if os.path.getsize(real) > MAX_FILE_BYTES:
            return None, rel, "the file is larger than 4 MiB"
        with open(real, encoding="utf-8", errors="replace") as f:
            return f.read().split("\n"), rel, None
    except OSError as e:
        return None, rel, "the file cannot be read: " + e.__class__.__name__


def check(root, section, entry):
    """One finding of the AI against the section and the file: a dict with "problem" None when the evidence holds."""
    finding = {"section": section["id"], "item": section.get("item"), "doc": section["doc"],
               "heading": section["heading"],
               "start": section["start"], "end": section["end"], "problem": None, "doc_quote": "", "code_quote": "",
               "file": "", "line": 0, "issue": "", "severity": "", "claimed": ""}
    if not isinstance(entry, dict):
        finding["problem"] = "the finding is no JSON object"
        return finding
    finding["issue"] = entry.get("issue") if isinstance(entry.get("issue"), str) else ""
    finding["claimed"] = str(entry.get("severity", "")).strip().lower()
    doc_quote = entry.get("doc_quote")
    code_quote = entry.get("code_quote")
    finding["doc_quote"] = collapse(doc_quote) if isinstance(doc_quote, str) else ""
    finding["code_quote"] = code_quote.strip() if isinstance(code_quote, str) else ""
    finding["file"] = entry.get("file") if isinstance(entry.get("file"), str) else ""

    problems = []
    if finding["claimed"] in SEVERITIES:
        finding["severity"] = SEVERITIES[finding["claimed"]]
    else:
        problems.append("the severity is none of high, medium, low")
    if len(finding["doc_quote"]) < MIN_DOC_QUOTE:
        problems.append("the doc_quote is shorter than " + str(MIN_DOC_QUOTE) + " characters")
    elif finding["doc_quote"] not in collapse(section["text"]):
        problems.append("the doc_quote is not in the section")
    lines, rel, why = read_code_file(root, entry.get("file"))
    if rel:
        finding["file"] = rel
    if why:
        problems.append(why)
    elif len(finding["code_quote"]) < MIN_CODE_QUOTE:
        problems.append("the code_quote is shorter than " + str(MIN_CODE_QUOTE) + " characters")
    elif "\n" in finding["code_quote"]:
        problems.append("the code_quote is more than one line")
    else:
        for number, line in enumerate(lines, 1):
            if line.strip() == finding["code_quote"]:
                finding["line"] = number
                break
        if not finding["line"]:
            problems.append("the code_quote is not a line of " + rel)
    finding["problem"] = "; ".join(problems) if problems else None
    return finding


def judge(root, section_lines, verdicts):
    """The answers of the foreach against the sections: verified findings, unverified ones, sections not judged,
    what was answered about the open findings, and the new findings rated low, which stay in the report."""
    sections = [json.loads(line) for line in section_lines]
    # The foreach writes "[<item>]: " before each answer; an answer runs to the next marker
    markers, counts, places = [], [], []
    for line in section_lines:
        marker = "[" + line + "]: "
        first = verdicts.find(marker)
        count = 0
        at = first
        while at >= 0:
            count += 1
            places.append(at)
            at = verdicts.find(marker, at + 1)
        markers.append(first)
        counts.append(count)
    places.sort()

    verified, unverified, not_judged, asked_about, low = [], [], [], [], []
    for i, section in enumerate(sections):
        if markers[i] < 0:
            not_judged.append((section["id"], "no answer in the verdicts: the loop did not reach the section"))
            continue
        if counts[i] > 1:
            not_judged.append((section["id"], "its marker occurs " + str(counts[i])
                               + " times in the verdicts: an answer copied it"))
            continue
        start = markers[i] + len(section_lines[i]) + 4
        later = [p for p in places if p > markers[i]]
        answer = verdicts[start:max(start, later[0]) if later else len(verdicts)]
        verdict = first_object(answer, "section")
        if verdict is None:
            not_judged.append((section["id"], "no JSON answer with a \"section\": " + shown(answer, 120)))
            continue
        if verdict.get("section") != section["id"]:
            not_judged.append((section["id"], "the answer is for section " + shown(verdict.get("section"), 120)))
            continue
        if not isinstance(verdict.get("findings"), list):
            not_judged.append((section["id"], "the answer has no findings list"))
            continue
        for entry in verdict["findings"]:
            finding = check(root, section, entry)
            # A new finding rated low is not for the register: its section is judged, and it stays in the report
            (unverified if finding["problem"] else low if finding["severity"] == "LOW" else verified).append(finding)
        # The open findings the register knows of this section: each needs an answer of its own, by its id
        answers = {}
        for entry in verdict.get("known") if isinstance(verdict.get("known"), list) else []:
            if isinstance(entry, dict):
                answers.setdefault(str(entry.get("id", "")).strip(), entry)
        for known in section.get("known", []):
            answer = answers.get(known["id"])
            status = str(answer.get("status", "")).strip().lower() if answer else ""
            asked = {"section": section["id"], "item": section.get("item"), "id": known["id"],
                     "rule": known["rule"], "subject": known["subject"], "severity": known.get("severity", ""),
                     "answer": "none", "reason": ""}
            if status == "holds":
                # Stated again under the subject the register knows it by, after the check a new finding gets
                finding = check(root, section, answer)
                finding["known_subject"] = known["subject"]
                (unverified if finding["problem"] else verified).append(finding)
                asked["answer"] = "none" if finding["problem"] else "holds"
            elif status == "gone":
                asked["answer"] = "gone"
                asked["reason"] = answer.get("reason") if isinstance(answer.get("reason"), str) else ""
            asked_about.append(asked)
    return sections, verified, unverified, not_judged, asked_about, low


def settle(asked_about, verified, known_gone, doubtful=()):
    """What became of the open findings of earlier runs: (gone, not answered).

    A finding is stated when a verified finding has its subject. It is gone when every part of its section that was
    asked about it said so, or when the fact step found its section no longer in the document. Otherwise nothing
    was said: it is neither stated nor gone, and the register keeps it open.

    "Gone" from an answer that also held a finding that did not verify (its section is in `doubtful`) is not taken.
    The register would resolve the finding and keep the earlier verdict of the section, with the digest the section
    still has: the section would read decided, and no run would ask about it again. The finding stays open instead,
    and the next run asks."""
    stated = {subject_of(f) for f in verified}
    by_subject = {}
    for asked in asked_about:
        if asked["answer"] == "gone" and asked["item"] in doubtful:
            asked["answer"] = "gone, not taken"
        by_subject.setdefault(asked["subject"], []).append(asked)
    gone, not_answered = [], []
    for subject, answers in by_subject.items():
        if subject in stated:
            continue
        if all(a["answer"] == "gone" for a in answers):
            gone.append({"ruleKey": answers[0]["rule"], "subject": subject, "section": answers[0]["section"],
                         "reason": "; ".join(a["reason"] for a in answers if a["reason"])})
        else:
            not_answered += [a for a in answers if a["answer"] != "gone"]
    for orphan in known_gone:
        if orphan.get("subject") not in stated:
            gone.append({"ruleKey": orphan.get("ruleKey"), "subject": orphan.get("subject"), "section": "",
                         "reason": "the section is no longer in the document"})
    return gone, not_answered


def subject_of(finding):
    """The register's subject of a contradiction: the section and a digest of the quoted sentence. A finding that
    answers for a known one keeps the subject the register knows, whatever sentence it quotes now."""
    if finding.get("known_subject"):
        return finding["known_subject"]
    digest = hashlib.sha256(finding["doc_quote"].encode("utf-8")).hexdigest()[:12]
    return finding["doc"] + "#" + finding["heading"] + ":" + digest


def evidence_of(f):
    """The evidence the envelope gives a contradiction: the section and the two quotes, text of the files."""
    return ("func_section: " + f["section"] + "; the document: \"" + shown(f["doc_quote"], 800) + "\"; the code, "
            + f["file"] + ": \"" + shown(f["code_quote"], 800) + "\"")


def envelope_findings(measured, verified):
    """The findings of the envelope: every measured one, then every verified one of the AI, each subject once.
    No free text of a model is in it: the issue stays in the report; the quotes are text of the files. A finding of
    the AI names the item of the section whose verdict stated it."""
    findings = [dict(f) for f in measured]
    seen = set()
    for f in verified:
        subject = subject_of(f)
        if subject in seen:
            continue
        seen.add(subject)
        findings.append({
            "ruleKey": RULE,
            "subject": subject,
            "severity": f["severity"],
            "title": f["doc"] + ", \"" + f["heading"] + "\": the code contradicts \"" + shown(f["doc_quote"], 120)
                     + "\"",
            "description": "Section " + f["section"] + " (lines " + str(f["start"]) + " to " + str(f["end"])
                           + " of " + f["doc"] + ") against " + f["file"] + ", line " + str(f["line"])
                           + ". What the model says is wrong is in functionality-report.md of the run.",
            "evidence": evidence_of(f),
        })
        if f.get("item"):
            findings[-1]["item"] = f["item"]
    return findings


def findings_data(measured, verified):
    """findings.jsonl: one JSON object per finding of the envelope, with the same ruleKey and subject, so the two
    join. A measured finding has what the fact step wrote; a contradiction has the two quotes this script checked
    and, under "model", the issue as the model wrote it; "item" is its section as the item of the envelope, and
    "number" the id the section has in this run's report. The register never reads this file: the record of the run
    keeps it, and `traceworks findings functionality show` reads it from there."""
    entries = [{"ruleKey": f.get("ruleKey"), "subject": f.get("subject"), "severity": f.get("severity"),
                "source": "measured",
                "details": [{"description": f.get("description") or f.get("title"), "evidence": f.get("evidence")}]}
               for f in measured]
    seen = set()
    for f in verified:
        subject = subject_of(f)
        if subject in seen:
            continue
        seen.add(subject)
        entries.append({"ruleKey": RULE, "subject": subject, "severity": f["severity"],
                        "item": f.get("item") or f["section"], "number": f["section"], "source": "model",
                        "details": [{"file": f["file"], "line": f["line"], "quote": f["code_quote"], "doc": f["doc"],
                                     "doc_quote": f["doc_quote"], "model": {"issue": f["issue"]}}]})
    return entries


def envelope_items(facts, sections, not_judged, unverified):
    """Every item of the audit for the envelope: each section that could be judged, with the digest the fact step
    gave it, and whether this run read a verdict for it. A section that was left out, got no place or whose call
    failed is listed as not judged: the register then keeps what it knew of it (docs/registers.md, "Items"). So is
    a section whose answer held a finding that did not verify: the register never sees that finding, the section
    would read clean and leave the loop, and the model saw something there. The next run asks again."""
    without = {section_id for section_id, _ in not_judged} | {f["section"] for f in unverified}
    answered = {section.get("item") for section in sections if section["id"] not in without}
    return listed_items([(item, {"name": item["name"], "digest": item["digest"], "judged": item["name"] in answered})
                         for item in facts.get("items", [])])


ITEMS_MAX = 1000  # what the register takes in one report (docs/registers.md, "Items")


def listed_items(pairs):
    """The items for the envelope, and whether the list is the whole one. The register takes at most ITEMS_MAX
    items in a report and refuses a longer list as a whole. So a longer list is sent in part, in its own order:
    first the items of this run (a finding names one of them), then the ones that wait for a verdict, then settled
    ones, which the register knows already. A list in part vouches for nothing: no item leaves the register."""
    if len(pairs) <= ITEMS_MAX:
        return [out for _, out in pairs], True
    order = sorted(range(len(pairs)),
                   key=lambda i: (not pairs[i][0].get("chosen"), bool(pairs[i][0].get("settled")), i))
    return [pairs[i][1] for i in sorted(order[:ITEMS_MAX])], False


def item_counts(facts):
    """The items of the fact step, counted: all, the ones the fact step left out as settled (the register calls
    them clean or decided, and they did not change), and the ones that need a verdict and got no place. A clean
    section that changed and got no place waits; it is not settled."""
    items = facts.get("items", [])
    left_out = [item for item in items if item.get("settled")]
    return {"all": len(items), "settled": len(left_out),
            "clean": sum(1 for item in left_out if item.get("state") == "clean"),
            "decided": sum(1 for item in left_out if item.get("state") == "decided"),
            "waiting": len(facts.get("sections_cut", []))}


HIGH = ("HIGH", "CRITICAL")


def item_states(facts, sections, verified, unverified, not_judged, asked_about, not_answered):
    """The state of every item after this run, counted, and whether an open finding of high severity stands.

    Every finding the run states opens its item and counts, also one the register holds a decision for. With open
    rows of the register's findings missing (`known_missing_open` of the facts), an item the register calls open
    stays open, whatever was shown of it and whatever was answered.

    Settled: an item the fact step left out (the register calls it clean or decided, and it did not change), and
    one judged in this run with nothing that goes to the register: clean, or decided when a person decided a
    finding of it or an answer said that one is gone. Open: a new finding of medium or high, an open finding of
    the register that holds, or one that got no answer; also an item with an open finding that got no verdict.
    Not judged: no place under the cap, a failed call, or an answer with a finding that did not verify.

    An open high finding stands when a verified finding is high, when an answer leaves a high one of the register
    unanswered, and when the section of one got no verdict at all."""
    without = {section_id for section_id, _ in not_judged}
    answered = {section.get("item") for section in sections if section["id"] not in without}
    doubtful = {f["item"] for f in unverified}
    open_now = {f["item"] for f in verified} | {a["item"] for a in not_answered}
    resolved = {a["item"] for a in asked_about if a["answer"] == "gone"}
    counts = {"clean": 0, "decided": 0, "open": 0, "not judged": 0}
    high = any(f["severity"] in HIGH for f in verified) or any(a["severity"] in HIGH for a in not_answered)
    unseen = bool(facts.get("known_missing_open", facts.get("known_missing")))
    for item in facts.get("items", []):
        name = item["name"]
        if item.get("settled"):
            state = "decided" if item.get("state") == "decided" else "clean"
        elif name in open_now or unseen and item.get("state") == "open":
            state = "open"
        elif name not in answered:
            state = "open" if item.get("open") else "not judged"
            high = high or bool(item.get("open_high"))
        elif name in doubtful:
            state = "not judged"
        else:
            state = "decided" if item.get("decided") or name in resolved else "clean"
        counts[state] += 1
    return counts, high


def settled_text(counts):
    """The number that can reach its goal, in the wording every self-audit uses."""
    total = sum(counts.values())
    return (str(counts["clean"] + counts["decided"]) + " of " + plural(total, "item") + " settled ("
            + str(counts["clean"]) + " clean, " + str(counts["decided"]) + " decided; " + str(counts["open"])
            + " open, " + str(counts["not judged"]) + " not judged)")


def gate_of(counted, not_measured, states, high_stands, unowned=(), missing=0):
    """RED while an open finding of high severity stands, or a counted finding is high; YELLOW while an item is open
    or not judged, for a counted finding, for anything not measured, when there is no item at all, and when the
    register left findings out of what it handed the run; GREEN when every item is settled."""
    # An open finding of the register that no item carries: no run asks about it, so no number of settled items says
    # anything about it. A high one stands like any other
    if high_stands or any(f["severity"] in HIGH for f in counted) or any(u.get("severity") in HIGH for u in unowned):
        return "RED"
    if (counted or not_measured or unowned or missing or states["open"] or states["not judged"]
            or not sum(states.values())):
        return "YELLOW"
    return "GREEN"


def markdown(facts, sections, measured, verified, unverified, not_judged, gate, description, complete, low=(),
             states=None):
    counts = facts.get("counts", {})
    md = ["# Functionality self-audit", "",
          "Does Traceworks do what its documentation says? A deterministic step compared the documentation with the"
          " code and chose the sections; an AI judged one section per call and could read the workspace; this step"
          " checked every quote. A finding of the AI counts only when its quote of the document is in the section"
          " and its quote of the code is a line of the file it names.", "",
          "**Gate: " + gate + "** (" + description + ")", ""]
    md += ["## What was measured", ""]
    for name in sorted(counts):
        md.append("- " + name.replace("_", " ") + ": " + str(counts[name]))
    md.append("- sections judged: " + str(len(sections) - len(not_judged)) + " of " + str(len(sections)))
    items = item_counts(facts)
    cut = facts.get("sections_cut", [])
    md.append("- items: " + plural(items["all"], "section") + " that could be judged; " + str(items["settled"])
              + " settled and unchanged since " + ("its" if items["settled"] == 1 else "their") + " verdict ("
              + str(items["clean"]) + " clean, " + str(items["decided"]) + " decided), not judged again; "
              + str(len(sections)) + " chosen for a verdict, " + str(items["waiting"]) + " more "
              + ("waits" if items["waiting"] == 1 else "wait") + " for a later run")
    if states:
        md.append("- items after this run: " + settled_text(states))
    if items["all"] > ITEMS_MAX:
        md.append("- the register takes at most " + str(ITEMS_MAX) + " items in a report: " + str(ITEMS_MAX) + " of "
                  + str(items["all"]) + " are listed, the ones of this run first, and no item leaves the register by"
                  " this report")
    md.append("")
    gaps = ["NOT MEASURED: " + shown(n.get("what")) + " (" + shown(n.get("why")) + ")"
            for n in facts.get("not_measured", [])]
    if gaps:
        md += ["## Not measured", ""] + ["- " + g for g in gaps] + [""]
        if not complete:
            md += ["The envelope of this report is no full snapshot (`snapshotComplete` is false), so the register"
                   " does not apply it: it would resolve findings this run never checked.", ""]
    if not_judged:
        md += ["## Sections not judged (" + str(len(not_judged)) + ")", "",
               "No verdict that could be read. The envelope lists every one of them as not judged, the register keeps"
               " what it knows of it, and the next run asks again.", ""]
        md += ["- " + shown(s) + ": " + shown(why) for s, why in not_judged] + [""]
    if cut:
        md += ["## Sections that wait for a later run (" + str(len(cut)) + ")", "",
               "They need a verdict (never judged, changed since, or with an open finding), and the places of this"
               " run, max_sections of the rule file, were taken. The next run starts with them.", ""]
        md += ["- " + shown(c) for c in cut] + [""]

    md += ["## Measured findings (" + str(len(measured)) + ")", ""]
    if not measured:
        md += ["None: where it was measured, the documentation and the code agree on commands, options, exit codes,"
               " example paths and block types.", ""]
    for f in measured:
        md.append("- **" + shown(f["ruleKey"]) + "** `" + shown(f["subject"]).replace("`", "'") + "` ("
                  + shown(f["severity"]) + "): " + shown(f["title"]) + ". " + shown(f.get("description", ""), 600))
    if measured:
        md.append("")

    md += ["## Verified contradictions (" + str(len(verified)) + ")", ""]
    if not verified:
        md += ["None.", ""]
    for f in sorted(verified, key=lambda f: (ORDER[f["severity"]], f["section"])):
        md += ["- **" + f["severity"] + "**, section " + shown(f["section"]) + " (" + shown(f["doc"]) + ", lines "
               + str(f["start"]) + " to " + str(f["end"]) + "), register subject `"
               + shown(subject_of(f)).replace("`", "'") + "`",
               "  - the document says: \"" + shown(f["doc_quote"], 800) + "\"",
               "  - the code, " + shown(f["file"]) + ", line " + str(f["line"]) + ": \"" + shown(f["code_quote"], 800)
               + "\"",
               "  - what the model says is wrong (its words, not checked): " + shown(f["issue"], 600)]
    if verified:
        md.append("")

    if low:
        md += ["## Low findings, in this report only (" + str(len(low)) + ")", "",
               "The verdict rated each of them low: a difference that misleads without changing what a user does. They"
               " passed the same check as the others. They are not in the register and not in findings.jsonl, they do"
               " not count for the gate, and no later run is asked about them.", ""]
        for f in low:
            md += ["- section " + shown(f["section"]) + " (" + shown(f["doc"]) + ", lines " + str(f["start"]) + " to "
                   + str(f["end"]) + ")",
                   "  - the document says: \"" + shown(f["doc_quote"], 800) + "\"",
                   "  - the code, " + shown(f["file"]) + ", line " + str(f["line"]) + ": \""
                   + shown(f["code_quote"], 800) + "\"",
                   "  - what the model says is wrong (its words, not checked): " + shown(f["issue"], 600)]
        md.append("")

    md += ["## Unverified findings (" + str(len(unverified)) + ")", ""]
    if unverified:
        md += ["Their evidence does not hold, so they do not count and are not in the register.", ""]
    else:
        md += ["None.", ""]
    for f in unverified:
        md += ["- section " + shown(f["section"]) + ", claimed " + (shown(f["claimed"], 20) or "no severity") + ": "
               + shown(f["problem"]),
               "  - the document, as quoted: \"" + shown(f["doc_quote"], 400) + "\"",
               "  - the code, as quoted, " + (shown(f["file"]) or "no file") + ": \"" + shown(f["code_quote"], 400)
               + "\"",
               "  - what the model says is wrong: " + shown(f["issue"], 600)]
    if unverified:
        md.append("")
    return "\n".join(md)


def plural(count, one):
    return str(count) + " " + (one if count == 1 else one + "s")


def known_markdown(gone, not_answered, unowned=(), missing=0, decided=()):
    """What became of the open findings of earlier runs, for the end of the report; empty when there were none."""
    md = []
    if decided:
        md += ["", "## Findings the register holds a decision for (" + str(len(decided)) + ")", "",
               "A person accepted or dismissed a finding of the same rule and subject as each of these, and this run"
               " states it again. A decision covers the severity it was made on: when the register takes this report,"
               " a finding stated above that severity is open again, and one stated at or below it keeps its"
               " decision, unless a person reopens it (`traceworks findings functionality reopen <id>`). Each line"
               " says which; with `\"decision_on_higher_severity\": \"keep\"` in the operator's `traceworks.json`,"
               " which a run does not see, the register keeps every decision. Whether this is what was decided, or"
               " something else under the same name, is not told here: the gate counts each of them in this run, and"
               " its section is open.", ""]
        md += ["- `" + shown(d["subject"]).replace("`", "'") + "` (" + shown(d["status"], 30) + ")"
               + d.get("register", "") for d in decided]
    if missing:
        md += ["", "## Findings of the register this run did not see (" + str(missing) + ")", "",
               "The register handed this run its findings in a block that holds at most 250 rows or 60 000 bytes, and"
               " left " + str(missing) + " out. An open finding can be among them, so no verdict was asked about it."
               " The gate is not GREEN over that, and a section the register calls open stays open. Decide or fix"
               " findings until the block holds them all.", ""]
    if unowned:
        md += ["", "## Open findings that no item carries (" + str(len(unowned)) + ")", "",
               "Open in the register, and no section of this run is what they are about, nor could the fact step tell"
               " that it is gone, so no run asks about them. A person must decide them: `traceworks findings"
               " functionality dismiss <id>` or `accept <id>`.", ""]
        md += ["- " + shown(u.get("severity"), 20) + " `" + shown(u.get("subject")).replace("`", "'") + "`"
               for u in unowned]
    if gone:
        md += ["", "## Earlier findings that are gone (" + str(len(gone)) + ")", "",
               "Open in the register, checked in this run and no longer there. The register resolves them. A reason"
               " is the model's (its words, not checked) unless it names the document.", ""]
        md += ["- `" + shown(g["subject"]).replace("`", "'") + "`: " + (shown(g["reason"], 600) or "no reason given")
               for g in gone]
    if not_answered:
        md += ["", "## Open findings without an answer (" + str(len(not_answered)) + ")", "",
               "Open in the register, and the answer for their section neither showed that they still hold nor said"
               " that they are gone. They stay open.", ""]
        md += ["- " + shown(a["section"]) + " " + shown(a["id"]) + " `" + shown(a["subject"]).replace("`", "'") + "`"
               + (": the answer said that it is gone; that is not taken, since the same answer held a finding that"
                  " did not verify" if a["answer"] == "gone, not taken" else "") for a in not_answered]
    return "\n".join(md)


def main(argv):
    parser = argparse.ArgumentParser(prog="report.py", description="The report of the functionality self-audit.")
    parser.add_argument("--root", required=True, help="the workspace the quotes are checked against")
    parser.add_argument("--facts", required=True, help="facts.json of the fact step")
    parser.add_argument("--sections", required=True, help="sections.jsonl of the fact step")
    parser.add_argument("--verdicts", required=True, help="the output of the foreach; - for standard input")
    parser.add_argument("--out", required=True, help="the file the report goes to")
    args = parser.parse_args(argv)

    with open(args.facts, encoding="utf-8") as f:
        facts = json.load(f)
    with open(args.sections, encoding="utf-8") as f:
        section_lines = [line for line in f.read().split("\n") if line.strip()]
    if args.verdicts == "-":
        verdicts = sys.stdin.buffer.read().decode("utf-8", errors="replace")
    else:
        with open(args.verdicts, encoding="utf-8", errors="replace") as f:
            verdicts = f.read()

    sections, verified, unverified, not_judged, asked_about, low = judge(args.root, section_lines, verdicts)
    measured = facts.get("findings", [])
    not_measured = facts.get("not_measured", [])
    findings = envelope_findings(measured, verified)
    gone, not_answered = settle(asked_about, verified, facts.get("known_gone", []),
                                {f["item"] for f in unverified})
    states, high_stands = item_states(facts, sections, verified, unverified, not_judged, asked_about, not_answered)
    # Stated findings the register holds a decision for: counted like the others, and named in the report
    decided = known_rows.decided_stated(verified, sections, lambda f: "docs-contradict-code", subject_of)
    unowned = facts.get("known_unowned", [])
    missing = facts.get("known_missing", 0)
    gate = gate_of(findings, not_measured, states, high_stands, unowned, missing)
    high = sum(1 for f in findings if f["severity"] in HIGH)
    description = (str(len(measured)) + " measured findings, " + str(len(findings) - len(measured))
                   + " verified contradictions (" + str(high) + " high), "
                   + (str(len(low)) + " low in this report only, " if low else "") + str(len(unverified))
                   + " unverified, " + str(len(sections) - len(not_judged)) + " of " + str(len(sections))
                   + " sections judged, " + str(len(not_measured)) + " not measured")
    if not_answered:
        description += ", " + str(len(not_answered)) + " open findings of earlier runs not answered"
    if unowned:
        description += ", " + plural(len(unowned), "open finding") + " of the register that no item carries"
    if missing:
        description += ", " + plural(missing, "finding") + " of the register not shown to this run"
    description += "; " + settled_text(states)
    # A section without a verdict is an item not judged, which the envelope says; only what was not measured makes
    # the snapshot partial, since a measured finding that was not looked for would be resolved by its absence
    complete = not not_measured
    report = markdown(facts, sections, measured, verified, unverified, not_judged, gate, description, complete, low,
                      states)
    report += known_markdown(gone, not_answered, unowned, missing, decided)

    with open(args.out, "w", encoding="utf-8", errors="replace") as f:
        f.write(report + "\n")
    # The findings as data, next to the report: the scratch directory is recorded, so the run keeps both
    with open(os.path.join(os.path.dirname(args.out), "findings.jsonl"), "w", encoding="utf-8",
              errors="replace") as f:
        for entry in findings_data(measured, verified):
            f.write(json.dumps(entry, ensure_ascii=True) + "\n")

    # The gate first: the first gate line of an output counts, and no quoted text comes before it
    out = ["gate_status: " + gate, "gate_category: functionality", "gate_description: " + description, "", report,
           ""]
    envelope = {"snapshotComplete": complete, "expectedFindings": len(findings), "findings": findings}
    if gone:
        # The open findings this run checked and found gone: only this resolves a model's finding in the register.
        # The rule and the subject only: why it is gone are a model's words, and stay in the report
        envelope["gone"] = [{"ruleKey": g["ruleKey"], "subject": g["subject"]} for g in gone]
    if isinstance(facts.get("items"), list):
        # Every item, and whether this run judged it. expectedItems vouches that the list is the whole one; a list
        # in part goes without it, and then no item leaves the register
        items, items_complete = envelope_items(facts, sections, not_judged, unverified)
        if items_complete:
            envelope["expectedItems"] = len(items)
        envelope["items"] = items
    # "<" and ">" as escapes: no quote of a file can end the comment the envelope is in
    body = json.dumps(envelope, ensure_ascii=True).replace("<", "\\u003c").replace(">", "\\u003e")
    out.append("<!-- traceworks:audit-result:v1 " + body + " -->")
    sys.stdout.write("\n".join(out) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
