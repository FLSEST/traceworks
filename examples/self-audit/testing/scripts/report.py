#!/usr/bin/env python3
"""The report of the testing self-audit: checks the AI's findings, counts, sets the gate, writes the envelope.

The measured findings come from the facts step as they are. An AI finding (a change of one line that no test would
notice) counts only when its evidence holds: the line it quotes is a line of code of the class it was asked about,
compared after trimming, and it names a change and a reason. Every other AI finding is counted and shown as
unverified, and is not in the envelope. No model sets the gate or a count.

The register and the gate take the findings a verdict rates medium or high. A new one it rates low is listed in the
report of the run and nowhere else: not in the envelope, not in findings.jsonl. Its class counts as judged. An open
finding the register holds is answered as before, whatever its severity.

The verdicts are the output of the `foreach` over the lines of candidates.jsonl: each item as `[<item>]: ` followed
by its answer. A line holds a token of the run that no prompt shows, so no answer can write the marker of another
class; a marker that occurs twice leaves its class not judged.

The gate follows the items: every class that could be judged is one, and this script says in what state each is
after the run. GREEN when every item is settled (clean or decided), RED while an open finding of high severity
stands, YELLOW while an item is open or not judged, or something was measured or left unmeasured.

Standard output: the gate lines first, then the report as markdown, then the audit result envelope. The envelope
lists every item of the audit (each class that could be judged) and says which of them this run judged; a class
without a verdict, because its call failed or its answer was no JSON, is an item not judged and leaves the snapshot
full. The envelope is a partial snapshot only when something was left unmeasured.

With --out, the report goes to that file too, and the findings as data to findings.jsonl next to it.

usage: report.py --rules FILE --root DIR --facts FILE --candidates FILE --verdicts FILE|- [--out FILE]
"""

import sys

sys.dont_write_bytecode = True   # the package directory is read-only in a run, and a build must not change it

import argparse
import hashlib
import json
import os
import re

import known as register_known
from javatext import Rules, mask, one_line

SEVERITIES = {'low': 'LOW', 'medium': 'MEDIUM', 'high': 'HIGH'}
RULES_IN_ORDER = ('class-without-test', 'test-without-assertion', 'disabled-test', 'command-without-integration-test')
RULE_TEXT = {
    'class-without-test': 'Classes with logic that no test names',
    'test-without-assertion': 'Tests that assert nothing',
    'disabled-test': 'Tests that are disabled or skip themselves',
    'command-without-integration-test': 'Commands no integration test runs',
}


def code(text, limit=300):
    """Text as a code span of markdown, on one line."""
    text = one_line(text, limit)
    if '`' not in text:
        return f'`{text}`'
    return f'`` {text} ``'


def split_answers(candidate_lines, verdicts):
    """The answer of each candidate, or why it has none: (answer, None) or (None, reason)."""
    markers = ['[' + line + ']: ' for line in candidate_lines]
    occurrences = []
    first, counts = [], []
    for marker in markers:
        positions = [m.start() for m in re.finditer(re.escape(marker), verdicts)]
        first.append(positions[0] if positions else -1)
        counts.append(len(positions))
        occurrences += positions
    occurrences.sort()
    answers = []
    for marker, start, count in zip(markers, first, counts):
        if start < 0:
            answers.append((None, 'no answer in the verdicts: the loop did not reach the class'))
        elif count > 1:
            answers.append((None, f'its marker occurs {count} times in the verdicts: an answer copied it'))
        else:
            later = [p for p in occurrences if p > start]
            answers.append((verdicts[start + len(marker):later[0] if later else len(verdicts)], None))
    return answers


def parse_answer(answer, class_name):
    """The last JSON object of the answer that names the class and has a list of findings; None without one."""
    decoder = json.JSONDecoder()
    found = None
    at = answer.find('{')
    while at >= 0:
        try:
            value, end = decoder.raw_decode(answer, at)
        except (ValueError, RecursionError):
            at = answer.find('{', at + 1)
            continue
        if isinstance(value, dict) and value.get('class') == class_name and isinstance(value.get('findings'), list):
            found = value
        at = answer.find('{', end)
    return found


class ClassFile:
    """The lines of a candidate's file, trimmed, and which of them hold code."""

    def __init__(self, root, rel):
        self.problem = None
        self.lines, self.is_code = [], []
        if os.path.isabs(rel) or '..' in rel.split('/'):
            self.problem = 'its path leaves the workspace'
            return
        try:
            with open(os.path.join(root, rel), encoding='utf-8', newline='') as handle:
                text = handle.read()
        except (OSError, UnicodeDecodeError):
            self.problem = 'the file of the class cannot be read'
            return
        self.lines = [line.strip() for line in text.split('\n')]
        self.is_code = [bool(line.strip()) for line in mask(text)[0].split('\n')]

    def lines_of(self, quote):
        """The numbers of the lines of code that equal the quote."""
        return [number for number, (line, is_code) in enumerate(zip(self.lines, self.is_code), 1)
                if is_code and line == quote]


def normalized_path(value):
    path = str(value).strip()
    for prefix in ('/workspace/', './'):
        if path.startswith(prefix):
            path = path[len(prefix):]
    return path


def check_finding(raw, candidate, source):
    """One finding of an answer: (verified finding, None) or (None, why its evidence does not hold)."""
    if not isinstance(raw, dict):
        return None, 'the finding is no JSON object'
    quote = raw.get('line_quote')
    if not isinstance(quote, str) or not quote.strip():
        return None, 'it quotes no line'
    quote = quote.strip()
    file = normalized_path(raw.get('file', ''))
    if file != candidate['file']:
        return None, f'it names the file {code(file, 160)}, not the file of the class'
    if source.problem:
        return None, source.problem
    if not re.search(r'\w', quote):
        return None, 'the quoted line holds no letter and no digit, so it names no line'
    numbers = source.lines_of(quote)
    if not numbers:
        return None, 'the quoted line is not a line of code of the file'
    mutation = raw.get('mutation')
    if not isinstance(mutation, str) or not mutation.strip():
        return None, 'it gives no changed line'
    if mutation.strip() == quote:
        return None, 'the changed line equals the quoted line'
    why = raw.get('why_uncaught')
    if not isinstance(why, str) or not why.strip():
        return None, 'it does not say why no test notices'
    severity = SEVERITIES.get(str(raw.get('severity', '')).strip().lower())
    if severity is None:
        return None, f'its severity is none of low, medium, high: {code(raw.get("severity", ""), 40)}'
    return {'candidate': candidate['id'], 'class': candidate['class'], 'item': candidate['item'],
            'file': candidate['file'], 'quote': quote,
            'lines': numbers, 'mutation': mutation.strip(), 'why': why.strip(), 'severity': severity,
            'subject': candidate['item'] + '#' + hashlib.sha256(quote.encode('utf-8')).hexdigest()[:12]}, None


MAX_TRIED = 3


def tried_of(parsed):
    """What a verdict without a finding says it tried: up to three changes, each with the test said to catch it.
    They are a model's words and are shown as such; nothing here checks them."""
    tried = []
    for entry in parsed.get('tried') if isinstance(parsed.get('tried'), list) else []:
        if isinstance(entry, dict) and isinstance(entry.get('line_quote'), str) \
                and isinstance(entry.get('caught_by'), str):
            tried.append((entry['line_quote'], entry['caught_by']))
    return tried[:MAX_TRIED]


def judge(candidates, candidate_lines, verdicts, root, max_per_class):
    """Checks every answer: the verified findings, the unverified ones, the classes not judged and those judged,
    what was answered about the open findings, and the new findings rated low, which stay in the report.
    A judged class is (candidate, verified findings of its own, what the verdict says it tried, its low findings)."""
    verified, unverified, not_judged, judged, asked_about, low = [], [], [], [], [], []
    seen = set()
    for candidate, (answer, missing) in zip(candidates, split_answers(candidate_lines, verdicts)):
        if missing:
            not_judged.append((candidate, missing))
            continue
        parsed = parse_answer(answer, candidate['class'])
        if parsed is None:
            not_judged.append((candidate, 'no JSON answer that names the class and has a list "findings": '
                               + code(answer.strip() or '(empty)', 120)))
            continue
        source = ClassFile(root, candidate['file'])
        own = lows = 0
        for raw in parsed['findings']:
            finding, problem = check_finding(raw, candidate, source)
            quote = one_line(raw.get('line_quote', ''), 200) if isinstance(raw, dict) else ''
            if finding and finding['subject'] in seen:
                finding, problem = None, 'the same line of the class is quoted twice; the first one counts'
            elif finding and finding['severity'] != 'LOW' and own >= max_per_class:
                # The cap is on what goes to the register: a low finding does not use it up
                finding, problem = None, f'more than {max_per_class} findings for one class; the first ones count'
            if finding and finding['severity'] == 'LOW':
                # Not for the register: the class is judged, and this finding is in the report of the run only.
                # As many as the cap are listed; a further one is counted, and is no reason to doubt the answer
                seen.add(finding['subject'])
                lows += 1
                low.append(dict(finding, listed=lows <= max_per_class))
            elif finding:
                seen.add(finding['subject'])
                own += 1
                verified.append(finding)
            else:
                unverified.append({'candidate': candidate['id'], 'class': candidate['class'],
                                   'item': candidate['item'], 'quote': quote, 'problem': problem})
        # The open findings the register knows of this class: each needs an answer of its own, by its id
        answers = {}
        for entry in parsed.get('known') if isinstance(parsed.get('known'), list) else []:
            if isinstance(entry, dict):
                answers.setdefault(str(entry.get('id', '')).strip(), entry)
        for known in candidate.get('known', []):
            answer = answers.get(known['id'])
            status = str(answer.get('status', '')).strip().lower() if answer else ''
            asked = {'candidate': candidate['id'], 'class': candidate['class'], 'item': candidate['item'],
                     'id': known['id'], 'rule': known['rule'], 'subject': known['subject'],
                     'severity': known.get('severity', ''), 'answer': 'none', 'reason': ''}
            if status == 'holds':
                # Stated again under the subject the register knows it by, after the check a new finding gets
                finding, problem = check_finding(answer, candidate, source)
                if finding:
                    asked['answer'] = 'holds'
                    if known['subject'] not in seen:
                        finding['subject'] = known['subject']
                        seen.add(known['subject'])
                        verified.append(finding)
                else:
                    unverified.append({'candidate': candidate['id'], 'class': candidate['class'],
                                       'item': candidate['item'], 'quote': one_line(answer.get('line_quote', ''), 200), 'problem': problem})
            elif status == 'gone':
                asked['answer'] = 'gone'
                asked['reason'] = answer.get('reason') if isinstance(answer.get('reason'), str) else ''
            asked_about.append(asked)
        judged.append((candidate, own, tried_of(parsed) if not parsed['findings'] else [], lows))
    return verified, unverified, not_judged, judged, asked_about, low


def settle(asked_about, verified, known_gone, doubtful=()):
    """What became of the open findings of earlier runs: (gone, not answered).

    A finding is stated when a verified finding has its subject. It is gone when the answer for its class said so,
    or when the facts step found its class or its line no longer there. Otherwise nothing was said: it is neither
    stated nor gone, and the register keeps it open.

    "Gone" from an answer that also held a finding that did not verify (its class is in `doubtful`) is not taken.
    The register would resolve the finding and keep the earlier verdict of the class, with the digest the class
    still has: the class would read decided, and no run would ask about it again. The finding stays open instead,
    and the next run asks."""
    stated = {f['subject'] for f in verified}
    gone, not_answered = [], []
    for asked in asked_about:
        if asked['subject'] in stated:
            continue
        if asked['answer'] == 'gone' and asked['item'] in doubtful:
            not_answered.append(dict(asked, held=True))
        elif asked['answer'] == 'gone':
            gone.append({'ruleKey': asked['rule'], 'subject': asked['subject'], 'reason': asked['reason'],
                         'model': True})
        else:
            not_answered.append(asked)
    for orphan in known_gone:
        if orphan.get('subject') not in stated:
            gone.append({'ruleKey': orphan.get('ruleKey'), 'subject': orphan.get('subject'),
                         'reason': orphan.get('reason', ''), 'model': False})
    return gone, not_answered


def known_markdown(gone, not_answered, unowned=(), missing=0, decided=()):
    """What became of the open findings of earlier runs, for the end of the report; empty when there were none."""
    md = []
    if decided:
        md += ['', f'## Findings the register holds a decision for ({len(decided)})', '',
               'A person accepted or dismissed a finding of the same rule and subject as each of these, and this run '
               'states it again. A decision covers the severity it was made on: when the register takes this report, '
               'a finding stated above that severity is open again, and one stated at or below it keeps its '
               'decision, unless a person reopens it (`traceworks findings testing reopen <id>`). Each line says '
               'which; with `"decision_on_higher_severity": "keep"` in the operator\'s `traceworks.json`, which a '
               'run does not see, the register keeps every decision. Whether this is what was decided, or something '
               'else under the same name, is not told here: the gate counts each of them in this run, and its class '
               'is open.', '']
        md += [f"- {code(d['subject'])} ({one_line(d['status'], 30)}){d.get('register', '')}" for d in decided]
    if missing:
        md += ['', f'## Findings of the register this run did not see ({missing})', '',
               f'The register handed this run its findings in a block that holds at most 250 rows or 60 000 bytes, '
               f'and left {missing} out. An open finding can be among them, so no verdict was asked about it. The '
               'gate is not GREEN over that, and a class the register calls open stays open. Decide or fix findings '
               'until the block holds them all.', '']
    if unowned:
        md += ['', f'## Open findings that no item carries ({len(unowned)})', '',
               'Open in the register, and what they are about is still there but is no item of the audit any more, '
               'so no run asks about them and none names them as gone. A person must decide them: '
               '`traceworks findings testing dismiss <id>` or `accept <id>`.', '']
        md += [f"- {one_line(u.get('severity', ''), 20)} {code(u.get('subject', ''))}" for u in unowned]
    if gone:
        md += ['', f'## Earlier findings that are gone ({len(gone)})', '',
               'Open in the register, checked in this run and no longer there. The register resolves them.', '']
        md += [f"- {code(g['subject'])}: " + (('the verdict says (its words, not checked): ' if g['model'] else '')
                                             + (one_line(g['reason'], 600) or 'no reason given')) for g in gone]
    if not_answered:
        md += ['', f'## Open findings without an answer ({len(not_answered)})', '',
               'Open in the register, and the answer for their class neither showed that they still hold nor said '
               'that they are gone. They stay open.', '']
        md += [f"- {a['candidate']} {one_line(a['id'], 20)} {code(a['subject'])}"
               + (': the answer said that it is gone; that is not taken, since the same answer held a finding that '
                  'did not verify' if a.get('held') else '') for a in not_answered]
    return '\n'.join(md)


def envelope_findings(facts, verified):
    findings = []
    for measured in facts['findings']:
        findings.append({
            'ruleKey': measured['rule'], 'subject': measured['subject'], 'severity': measured['severity'],
            'title': one_line(measured['title'], 300), 'description': one_line(measured['description'], 2000),
            'evidence': one_line(measured['evidence'], 1000), 'tags': ['measured']})
    for finding in verified:
        simple = finding['class'].rsplit('.', 1)[-1]
        findings.append({
            'ruleKey': 'uncaught-mutation', 'subject': finding['subject'], 'severity': finding['severity'],
            'title': one_line(f"{simple}: no test notices a change of the line {finding['quote']}", 300),
            'description': one_line(
                f"Line {finding['lines'][0]} of {finding['file']}. The change: {finding['mutation']} Why no test "
                f"notices, as the verdict says: {finding['why']} To check it: make the change and run the tests of "
                'the module. Green means the gap is real; red refutes the finding.', 2000),
            'evidence': one_line(f"candidate: {finding['file']} | {finding['quote']}", 1000),
            'tags': ['ai-verdict'], 'item': finding['item']})
    return findings


def findings_data(facts, verified):
    """findings.jsonl: one JSON object per finding of the envelope, with the same ruleKey and subject, so the two
    join. A measured finding has what the facts step wrote; an uncaught change has the line this script found and,
    under "model", the change and why no test notices it, as the model wrote them; "item" is its class, the item of
    the envelope, and "number" the running number the class has in this run's report. The register never reads this
    file: the record of the run keeps it, and `traceworks findings testing show` reads it from there."""
    entries = []
    for measured in facts['findings']:
        entries.append({
            'ruleKey': measured['rule'], 'subject': measured['subject'], 'severity': measured['severity'],
            'source': 'measured',
            'details': [{'file': measured.get('file'), 'line': measured.get('line'),
                         'description': measured['description'], 'evidence': measured['evidence']}]})
    for finding in verified:
        entries.append({
            'ruleKey': 'uncaught-mutation', 'subject': finding['subject'], 'severity': finding['severity'],
            'item': finding['item'], 'number': finding['candidate'], 'source': 'model',
            'details': [{'file': finding['file'], 'line': finding['lines'][0], 'quote': finding['quote'],
                         'model': {'mutation': finding['mutation'], 'why_uncaught': finding['why']}}]})
    return entries


def envelope_items(facts, judged, unverified=()):
    """Every item of the audit for the envelope: each class that could be judged, with the digest the facts step
    gave it, and whether this run read a verdict for it. A class that was left out, cut by the cap or whose call
    failed is listed as not judged: the register then keeps what it knew of it (docs/registers.md, "Items"). So is
    a class whose answer held a finding that did not verify: the register never sees that finding, the class would
    read clean and leave the loop, and the model saw something there. The next run asks again."""
    doubtful = {u['item'] for u in unverified}
    answered = {entry[0]['item'] for entry in judged} - doubtful
    return listed_items([(item, {'name': item['name'], 'digest': item['digest'], 'judged': item['name'] in answered})
                         for item in facts.get('items', [])])


ITEMS_MAX = 1000  # what the register takes in one report (docs/registers.md, "Items")


def listed_items(pairs):
    """The items for the envelope, and whether the list is the whole one. The register takes at most ITEMS_MAX
    items in a report and refuses a longer list as a whole. So a longer list is sent in part, in its own order:
    first the items of this run (a finding names one of them), then the ones that wait for a verdict, then settled
    ones, which the register knows already. A list in part vouches for nothing: no item leaves the register."""
    if len(pairs) <= ITEMS_MAX:
        return [out for _, out in pairs], True
    order = sorted(range(len(pairs)), key=lambda i: (not pairs[i][0].get('chosen'), bool(pairs[i][0].get('settled')), i))
    return [pairs[i][1] for i in sorted(order[:ITEMS_MAX])], False


def envelope(findings, complete, gone=(), items=None, items_complete=True):
    """The audit result envelope v1. `<` and `>` are written as escapes, so no text in it can end the comment.
    `gone` names the open findings this run checked and found gone, by rule and subject only: a model's finding is
    resolved in the register only then (docs/registers.md, "Reconciliation"). `items` is the list of items, and
    `expectedItems` vouches that it is the whole one; a list in part goes without it."""
    content = {'snapshotComplete': complete, 'expectedFindings': len(findings), 'findings': findings}
    if gone:
        content['gone'] = [{'ruleKey': g['ruleKey'], 'subject': g['subject']} for g in gone]
    if items is not None:
        if items_complete:
            content['expectedItems'] = len(items)
        content['items'] = items
    body = json.dumps(content)
    return '<!-- traceworks:audit-result:v1 ' + body.replace('<', '\\u003c').replace('>', '\\u003e') + ' -->'


def plural(count, one, many=None):
    return f'{count} {one if count == 1 else (many or one + "s")}'


def item_counts(facts):
    """The items of the facts step, counted: all, the ones the facts step left out as settled (the register calls
    them clean or decided, and they did not change), and the ones that need a verdict and got no place. A clean
    class that changed and got no place waits; it is not settled."""
    items = facts.get('items', [])
    left_out = [item for item in items if item.get('settled')]
    return {'all': len(items), 'settled': len(left_out),
            'clean': sum(1 for item in left_out if item['state'] == 'clean'),
            'decided': sum(1 for item in left_out if item['state'] == 'decided'),
            'waiting': len(facts['cut'])}


HIGH = ('HIGH', 'CRITICAL')


def item_states(facts, verified, unverified, judged, asked_about, not_answered):
    """The state of every item after this run, counted, and whether an open finding of high severity stands.

    Every finding the run states opens its item and counts, also one the register holds a decision for. With open
    rows of the register's findings missing (`known_missing_open` of the facts), an item the register calls open
    stays open, whatever was shown of it and whatever was answered.

    Settled: an item the facts step left out (the register calls it clean or decided, and it did not change), and
    one judged in this run with nothing that goes to the register: clean, or decided when a person decided a
    finding of it or an answer said that one is gone. Open: a new finding of medium or high, an open finding of
    the register that holds, or one that got no answer; also an item with an open finding that got no verdict.
    Not judged: no place under the cap, a failed call, or an answer with a finding that did not verify.

    An open high finding stands when a verified finding is high, when an answer leaves a high one of the register
    unanswered, and when the class of one got no verdict at all."""
    answered = {entry[0]['item'] for entry in judged}
    doubtful = {u['item'] for u in unverified}
    open_now = {f['item'] for f in verified} | {a['item'] for a in not_answered}
    resolved = {a['item'] for a in asked_about if a['answer'] == 'gone'} - doubtful
    counts = {'clean': 0, 'decided': 0, 'open': 0, 'not judged': 0}
    high = any(f['severity'] in HIGH for f in verified) or any(a['severity'] in HIGH for a in not_answered)
    unseen = bool(facts.get('known_missing_open', facts.get('known_missing')))
    for item in facts.get('items', []):
        name = item['name']
        if item.get('settled'):
            state = 'decided' if item.get('state') == 'decided' else 'clean'
        elif name in open_now or unseen and item.get('state') == 'open':
            state = 'open'
        elif name not in answered:
            state = 'open' if item.get('open') else 'not judged'
            high = high or bool(item.get('open_high'))
        elif name in doubtful:
            state = 'not judged'
        else:
            state = 'decided' if item.get('decided') or name in resolved else 'clean'
        counts[state] += 1
    return counts, high


def settled_text(counts):
    """The number that can reach its goal, in the wording every self-audit uses."""
    total = sum(counts.values())
    return (f"{counts['clean'] + counts['decided']} of {plural(total, 'item')} settled ({counts['clean']} clean, "
            f"{counts['decided']} decided; {counts['open']} open, {counts['not judged']} not judged)")


def gate_of(facts, verified, unverified, not_judged, not_answered, low, states, high_stands):
    measured = len(facts['findings'])
    high = sum(1 for f in verified if f['severity'] == 'HIGH')
    total = len(facts['candidates'])
    description = (f"{plural(measured, 'measured finding')}; {plural(len(verified), 'uncaught change')} verified "
                   f"({high} high), {f'{len(low)} low in this report only, ' if low else ''}"
                   f"{len(unverified)} unverified; {total - len(not_judged)} of "
                   f"{plural(total, 'class', 'classes')} judged; {len(facts['not_measured'])} not measured")
    if not_answered:
        description += f"; {plural(len(not_answered), 'open finding')} of an earlier run not answered"
    # An open finding of the register that no item carries: no run asks about it, so no number of settled items
    # says anything about it. A high one stands like any other
    unowned = facts.get('known_unowned', [])
    if unowned:
        description += f"; {plural(len(unowned), 'open finding')} of the register that no item carries"
    missing = facts.get('known_missing', 0)
    if missing:
        description += f"; {plural(missing, 'finding')} of the register not shown to this run"
    description += '; ' + settled_text(states)
    if high_stands or any(u.get('severity') in HIGH for u in unowned):
        return 'RED', description
    if measured or facts['not_measured'] or states['open'] or states['not judged'] or unowned or missing:
        return 'YELLOW', description
    return 'GREEN', description


def markdown(facts, verified, unverified, not_judged, judged, gate, description, complete, low=(), states=None):
    counts = facts['counts']
    items = item_counts(facts)
    md = ['# Testing self-audit', '', f'**Gate:** {gate}: {description}', '',
          'Would the tests notice when the code breaks? A deterministic step measured the test inventory and chose '
          'the classes; an AI named, per class, a change of one line that no test would notice; this report checked '
          'every quoted line against the file.', '',
          '## What was measured', '',
          f"- main source roots: {', '.join(code(r) for r in facts['roots']['main']) or 'none'}",
          f"- test source roots: {', '.join(code(r) for r in facts['roots']['test']) or 'none'}",
          f"- {counts['main_classes']} top-level types in {counts['main_files']} main files; "
          f"{counts['test_methods']} test methods in {counts['test_files']} test files",
          f"- {counts['commands_run']} of {counts['commands_declared']} commands are run by an integration test",
          f"- items: {plural(items['all'], 'class', 'classes')} that could be judged; {items['settled']} settled and "
          f"unchanged since {'its' if items['settled'] == 1 else 'their'} verdict ({items['clean']} clean, "
          f"{items['decided']} decided), not judged again; {len(facts['candidates'])} chosen for a verdict, "
          f"{items['waiting']} more {'waits' if items['waiting'] == 1 else 'wait'} for a later run"]
    if states:
        md.append('- items after this run: ' + settled_text(states))
    if items['all'] > ITEMS_MAX:
        md.append(f"- the register takes at most {ITEMS_MAX} items in a report: {ITEMS_MAX} of {items['all']} are "
                  'listed, the ones of this run first, and no item leaves the register by this report')
    md.append('')
    if facts['not_measured']:
        md += ['## Not measured', '', 'What the audit could not measure. It keeps the gate from GREEN, and the '
               'register is not fed, because a measured finding that was not looked for would count as resolved.', '']
        md += [f"NOT MEASURED: {one_line(n['what'])} ({one_line(n['why'])})" for n in facts['not_measured']]
        md.append('')

    md += [f"## Measured findings ({len(facts['findings'])})", '']
    if not facts['findings']:
        md += ['None.', '']
    for rule in RULES_IN_ORDER:
        of_rule = [f for f in facts['findings'] if f['rule'] == rule]
        if not of_rule:
            continue
        md += [f'### {RULE_TEXT[rule]} ({len(of_rule)}, `{rule}`)', '']
        for finding in of_rule:
            evidence = one_line(finding['evidence'], 400).split(': ', 1)[-1]
            md.append(f"- {finding['severity']} {code(finding['subject'])}: {one_line(finding['title'], 200)} "
                      f"({code(finding['file'])}:{finding['line']}; {evidence})")
        md.append('')

    md += [f'## Uncaught changes, verified ({len(verified)})', '',
           'Each is a change of one line that the AI says no test notices. The quoted line is in the file; whether '
           'the tests really stay green is checked in triage: make the change and run the tests of the module.', '']
    if not verified:
        md += ['None.', '']
    for finding in verified:
        where = 'line ' + str(finding['lines'][0]) if len(finding['lines']) == 1 else (
            'lines ' + ', '.join(map(str, finding['lines'])) + ' (the quoted line occurs more than once)')
        md += [f"- {finding['severity']} {finding['candidate']} {code(finding['class'])}, {code(finding['file'])} "
               f"{where}, subject {code(finding['subject'])}",
               f"  - the line: {code(finding['quote'])}",
               f"  - the change: {code(finding['mutation'])}",
               f"  - why no test notices: {one_line(finding['why'], 600)}"]
    if verified:
        md.append('')

    if low:
        md += [f'## Low findings, in this report only ({len(low)})', '',
               'The verdict rated each of them low: an edge case or an error path. They passed the same check as the '
               'others. They are not in the register and not in findings.jsonl, they do not count for the gate, and '
               'no later run is asked about them.', '']
        for finding in (f for f in low if f.get('listed', True)):
            md += [f"- {finding['candidate']} {code(finding['class'])}, {code(finding['file'])} line "
                   f"{finding['lines'][0]}",
                   f"  - the line: {code(finding['quote'])}",
                   f"  - the change: {code(finding['mutation'])}",
                   f"  - why no test notices: {one_line(finding['why'], 600)}"]
        more = {}
        for finding in (f for f in low if not f.get('listed', True)):
            more.setdefault((finding['candidate'], finding['class']), []).append(finding)
        listed_most = max((sum(1 for f in low if f['candidate'] == c and f.get('listed', True)) for c, _ in more),
                          default=0)
        md += [f"- {candidate} {code(class_)}: {plural(len(rest), 'more low finding')}, not listed: a class lists at "
               f"most {listed_most}" for (candidate, class_), rest in more.items()]
        md.append('')

    md += [f'## Unverified AI findings ({len(unverified)})', '',
           'Their evidence does not hold, so they do not count and are not in the register.', '']
    if not unverified:
        md += ['None.', '']
    md += [f"- {u['candidate']} {code(u['class'])}: {u['problem']}" + (f"; quoted: {code(u['quote'], 200)}"
                                                                      if u['quote'] else '') for u in unverified]
    if unverified:
        md.append('')

    if not_judged:
        md += [f'## Classes not judged ({len(not_judged)})', '']
        md += [f"- {c['id']} {code(c['class'])}: {reason}" for c, reason in not_judged]
        md.append('')
    md += [f'## Classes judged ({len(judged)})', '']
    md += [f"- {c['id']} {code(c['class'])} (score {c['score']:g}; tests: "
           f"{', '.join(code(t.rsplit('/', 1)[-1]) for t in c['tests']) or 'none'}): "
           f"{plural(count, 'verified finding')}" + (f', {lows} low' if lows else '') for c, count, _, lows in judged]
    md.append('')
    with_reasons = [(c, tried) for c, _, tried, _ in judged if tried]
    if with_reasons:
        md += [f'## Classes judged without a finding, and what the verdict tried ({len(with_reasons)})', '',
               "The model's words, not checked: the changes it considered and the test it says catches each.", '']
        for c, tried in with_reasons:
            md.append(f"- {c['id']} {code(c['class'])}")
            md += [f'  - {code(quote, 200)}: caught by {code(caught, 200)}' for quote, caught in tried]
        md.append('')
    if facts['cut']:
        md += [f"## Classes that wait for a later run ({len(facts['cut'])})", '',
               'They need a verdict (never judged, changed since, or with an open finding), and the places of this '
               'run, the cap of the rule file, were taken. The next run starts with them. The highest scores first.',
               '']
        md += [f"- {code(c['class'])} (score {c['score']:g})" for c in facts['cut'][:40]]
        if len(facts['cut']) > 40:
            md.append(f"- and {len(facts['cut']) - 40} more (testing-facts.json names them)")
        md.append('')
    without = len(not_judged)
    md += ['## The register', '',
           ('The envelope below this report is a full snapshot.'
            + (f" {plural(without, 'class', 'classes')} got no verdict: the envelope lists "
               f"{'it' if without == 1 else 'them'} as not judged, and the register keeps what it knows of "
               f"{'it' if without == 1 else 'them'}." if without else '')) if complete else
           'The envelope below this report is **not** a full snapshot (something was not measured), so the register '
           'rejects it and no finding changes.', '']
    return '\n'.join(md)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--rules', required=True)
    parser.add_argument('--root', default='.')
    parser.add_argument('--facts', required=True)
    parser.add_argument('--candidates', required=True)
    parser.add_argument('--verdicts', required=True)
    parser.add_argument('--out')
    args = parser.parse_args()
    rules = Rules(args.rules)
    with open(args.facts, encoding='utf-8') as handle:
        facts = json.load(handle)
    with open(args.candidates, encoding='utf-8') as handle:
        candidate_lines = [line.rstrip('\n') for line in handle if line.strip()]
    candidates = [json.loads(line) for line in candidate_lines]
    if args.verdicts == '-':
        verdicts = sys.stdin.buffer.read().decode('utf-8', errors='replace')
    else:
        with open(args.verdicts, encoding='utf-8', errors='replace') as handle:
            verdicts = handle.read()

    verified, unverified, not_judged, judged, asked_about, low = judge(
        candidates, candidate_lines, verdicts, args.root, rules.number('max_findings_per_class'))
    gone, not_answered = settle(asked_about, verified, facts.get('known_gone', []), {u['item'] for u in unverified})
    states, high_stands = item_states(facts, verified, unverified, judged, asked_about, not_answered)
    # Stated findings the register holds a decision for: counted like the others, and named in the report
    decided = register_known.decided_stated(verified, candidates, lambda f: 'uncaught-mutation',
                                            lambda f: f['subject'])
    gate, description = gate_of(facts, verified, unverified, not_judged, not_answered, low, states, high_stands)
    # A class without a verdict is an item not judged, which the envelope says; only what was not measured makes
    # the snapshot partial, since a measured finding that was not looked for would be resolved by its absence
    complete = not facts['not_measured']
    report = markdown(facts, verified, unverified, not_judged, judged, gate, description, complete, low, states)
    report += known_markdown(gone, not_answered, facts.get('known_unowned', []), facts.get('known_missing', 0), decided)
    if args.out:
        with open(args.out, 'w', encoding='utf-8') as handle:
            handle.write(report + '\n')
        # The findings as data, next to the report: the scratch directory is recorded, so the run keeps both
        with open(os.path.join(os.path.dirname(args.out), 'findings.jsonl'), 'w', encoding='utf-8',
                  errors='replace') as handle:
            for entry in findings_data(facts, verified):
                handle.write(json.dumps(entry) + '\n')
    print(f'gate_status: {gate}')
    print('gate_category: testing')
    print(f'gate_description: {description}')
    print()
    print(report)
    items, items_complete = envelope_items(facts, judged, unverified)
    print(envelope(envelope_findings(facts, verified), complete, gone, items, items_complete))


if __name__ == '__main__':
    main()
