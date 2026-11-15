#!/usr/bin/env python3
"""The facts of the testing self-audit: what can be measured about the tests without running them.

It reads the Java sources below the workspace as text (scripts/javatext.py) and states four kinds of findings:
a class of the main sources with logic that no test names, a test that asserts nothing, a test that is disabled or
skips itself, and a command of the command line that no integration test runs. It also chooses the classes an AI
judges afterwards, by size, branches and layer, and writes one line per class to candidates.jsonl.

Every class that could be judged is an item of the audit, with a digest of what a verdict about it sees. The register
hands back what it knows of the items ({{ audit_items }}); a class it calls clean or decided, with the digest it has
now, is not judged again, and its place goes to the next class (docs/registers.md, "Items").

What it could not measure it says as `NOT MEASURED: <what> (<why>)`. Thresholds, the cap and the weights are in the
rule file. Standard output is a fact sheet, which the register checks the findings' evidence against.

usage: facts.py --rules FILE --root DIR --facts FILE --candidates FILE [--known FILE] [--items FILE]
"""

import sys

sys.dont_write_bytecode = True   # the package directory is read-only in a run, and a build must not change it

import argparse
import collections
import hashlib
import json
import os
import re
import secrets

import items as register_items
import known
from javatext import JavaFile, Rules, layer_of, one_line, printable

MAX_DEPTH = 10


def glob_regex(pattern):
    """A path pattern as a regular expression: `*` is one directory name or a part of it, `**` any directories."""
    parts = []
    for part in pattern.strip('/').split('/'):
        if part == '**':
            parts.append('(?:[^/]+/)*')
        else:
            parts.append(''.join('[^/]*' if char == '*' else re.escape(char) for char in part) + '/')
    return re.compile(''.join(parts).rstrip('/') + r'/?\Z')


class Sources:
    """The Java files below the roots the rule file names, read once."""

    def __init__(self, root, rules, not_measured):
        self.root = root
        self.main_roots, self.test_roots = [], []
        self.main, self.test = [], []
        self.outside = collections.Counter()
        main_patterns = [glob_regex(p) for p in rules.words('main_root')]
        test_patterns = [glob_regex(p) for p in rules.words('test_root')]
        skip = set(rules.words('skip_dir'))
        for directory, names, files in os.walk(root):
            rel = os.path.relpath(directory, root).replace(os.sep, '/')
            rel = '' if rel == '.' else rel
            kept = []
            for name in sorted(names):
                path = f'{rel}/{name}' if rel else name
                if name in skip or name.startswith('.'):
                    continue
                if os.path.islink(os.path.join(directory, name)):
                    not_measured.append((f'the directory {one_line(path)}', 'a symbolic link, not followed'))
                    continue
                if any(p.match(path) for p in test_patterns):
                    self.test_roots.append(path)
                    self._read(path, self.test, not_measured)
                elif any(p.match(path) for p in main_patterns):
                    self.main_roots.append(path)
                    self._read(path, self.main, not_measured)
                elif path.count('/') < MAX_DEPTH:
                    kept.append(name)
            names[:] = kept
            for name in files:
                if name.endswith('.java'):
                    self.outside[rel or '.'] += 1

    def _read(self, root_rel, into, not_measured):
        for directory, names, files in os.walk(os.path.join(self.root, root_rel)):
            names[:] = sorted(n for n in names if not os.path.islink(os.path.join(directory, n)))
            for name in sorted(files):
                if not name.endswith('.java'):
                    continue
                full = os.path.join(directory, name)
                rel = os.path.relpath(full, self.root).replace(os.sep, '/')
                if not printable(rel):
                    not_measured.append((f'the file {one_line(rel)}', 'a control character in its name'))
                    continue
                if os.path.islink(full):
                    not_measured.append((f'the file {rel}', 'a symbolic link, not followed'))
                    continue
                try:
                    with open(full, encoding='utf-8', newline='') as handle:
                        text = handle.read()
                except (OSError, UnicodeDecodeError) as error:
                    not_measured.append((f'the file {rel}', 'not UTF-8' if isinstance(error, UnicodeDecodeError)
                                         else f'cannot be read: {error.strerror}'))
                    continue
                source = JavaFile(rel, text)
                if source.problem:
                    not_measured.append((f'the file {rel}', source.problem))
                    continue
                into.append(source)


class TestIndex:
    """What the test sources declare and name: types by simple name, the identifiers of every file, and which
    method asserts, also through a helper."""

    def __init__(self, files, rules):
        self.files = files
        self.assertion = rules.pattern('assertion_call')
        self.declaring = collections.defaultdict(list)
        for source in files:
            for type_ in source.types:
                self.declaring[type_.name].append(source)
        self.mentions = {source.rel: collections.Counter(source.identifiers()) for source in files}
        self._asserts = {}
        self._variable_types = {}

    def naming(self, name):
        """The test files that name the identifier in code, the ones that name it most first."""
        found = [(count[name], rel) for rel, count in self.mentions.items() if name in count]
        return [rel for _, rel in sorted(found, key=lambda item: (-item[0], item[1]))]

    def _with_ancestors(self, sources):
        seen, queue = [], list(sources)
        while queue:
            source = queue.pop(0)
            if source in seen:
                continue
            seen.append(source)
            for type_ in source.types:
                if type_.extends:
                    queue.extend(self.declaring.get(type_.extends, []))
        return seen

    def _type_of_variable(self, source, name):
        key = (source.rel, name)
        if key not in self._variable_types:
            declared = re.search(r'\b([A-Z]\w*)(?:\s*<[^;(){}=]*>)?(?:\s*\[\s*\])?\s+' + re.escape(name) + r'\b(?!\s*\()',
                                 source.code)
            created = re.search(r'\b' + re.escape(name) + r'\s*=\s*new\s+([A-Z]\w*)', source.code)
            found = declared or created
            self._variable_types[key] = found.group(1) if found else None
        return self._variable_types[key]

    def _helpers(self, source, receiver, name):
        """The methods of the test sources a call may reach: of the file itself, of the classes its classes extend
        and of its static imports for a plain call; of the type the receiver names, or is declared with, else."""
        if receiver is None or receiver in ('this', 'super'):
            origins = [source] + [s for t in source.static_import_types() for s in self.declaring.get(t, [])]
        elif receiver == '?':
            return []
        elif receiver[:1].isupper():
            origins = self.declaring.get(receiver, [])
        else:
            type_ = self._type_of_variable(source, receiver)
            origins = self.declaring.get(type_, []) if type_ else []
        return [m for origin in self._with_ancestors(origins) for m in origin.methods_named(name)]

    def asserts(self, method):
        """Whether the method's body holds an assertion call, or calls a helper of the test sources that does."""
        key = (method.source.rel, method.body_open)
        if key in self._asserts:
            return self._asserts[key]
        self._asserts[key] = False   # a helper that calls itself adds nothing
        calls = method.source.calls(method.body_open, method.body_close)
        result = any(self.assertion.match(name) for _, name in calls)
        if not result:
            for receiver, name in dict.fromkeys(calls):
                if any(self.asserts(helper) for helper in self._helpers(method.source, receiver, name)
                       if helper is not method):
                    result = True
                    break
        self._asserts[key] = result
        return result


def finding(rule, subject, severity, title, description, evidence, file, line):
    return {'rule': rule, 'subject': subject, 'severity': severity, 'title': title, 'description': description,
            'evidence': evidence, 'file': file, 'line': line}


def measured_classes(sources, rules):
    """Every top-level type of the main sources with its size, branches and layer."""
    layers = rules.layers()
    classes = []
    for source in sources.main:
        for type_ in source.top_level_types():
            name = source.qualified(type_)
            prefix, weight = layer_of(name, layers)
            classes.append({
                'class': name, 'simple': type_.name, 'kind': type_.kind, 'file': source.rel,
                'line': source.line_of(type_.start), 'lines': source.text.count('\n') + 1,
                'ncloc': source.ncloc(type_.start, type_.body_close),
                'branches': source.branches(type_.body_open, type_.body_close),
                'layer': prefix, 'weight': weight})
    classes.sort(key=lambda c: (c['class'], c['file']))
    return classes


# The register takes a name of 300 characters, and shows a subject of 300 whole. The subject of an uncaught change
# is the name, a `#` and 12 hex digits, so a name gets 13 characters less
NAME_LIMIT = 300 - 13


def fitted(name):
    """A name the register takes: on one line, a control character as `?`, and a name over NAME_LIMIT characters
    cut, with a digest of the whole at its end. The register counts as Java does, a character beyond the basic
    plane as two."""
    whole = name
    name = ''.join('?' if ord(c) < 0x20 or 0x7f <= ord(c) < 0xa0 or c in '\u2028\u2029' else c
                   for c in ' '.join(name.split()))
    if len(name.encode('utf-16-le', errors='replace')) // 2 <= NAME_LIMIT:
        return name
    kept, size = [], 0
    for c in name:
        size += 2 if ord(c) > 0xffff else 1
        if size > NAME_LIMIT - 17:
            break
        kept.append(c)
    return ''.join(kept) + '~' + hashlib.sha256(whole.encode('utf-8', errors='replace')).hexdigest()[:16]


def source_root(file, roots):
    """The source root a file lies in: the longest root that is a directory above it."""
    above = [root for root in roots if file.startswith(root.rstrip('/') + '/')]
    return max(above, key=len) if above else ''


def name_items(classes, roots, known_items, rows, root, version):
    """Gives every class the name it has as an item of the audit. The register wants every name once.

    A class whose name no other class has gets that name. When several source roots hold a class of one name, one
    of them keeps the plain name and each other is named by its source root, `<class>~<root>`: a name that does not
    depend on the order of the files. The plain name stays with the class the register knows by it, so a second
    class that appears does not take the findings of the first: the class whose file the register's findings of
    that name quote; without one, the class whose digest the register's item holds; without one, the first in
    the order of the files, where nothing in the register depends on the choice."""
    by_name = {}
    for class_ in classes:
        by_name.setdefault(class_['class'], []).append(class_)
    taken = set()
    for name, group in by_name.items():
        keeper = group[0]
        if len(group) > 1:
            plain = register_items.shown(fitted(name))
            quoted = set()
            for row in rows:
                subject = re.fullmatch(r'(.+)#([0-9a-f]{12})', row['subject'])
                if row['rule'] == JUDGED_RULE and subject and subject.group(1) == plain:
                    # The evidence is "candidate: <file> | <the line>", with the register's mask for the pipe
                    quoted.add(row['evidence'].split(' \u00a6 ', 1)[0].removeprefix('candidate: ').strip())
            by_file = [c for c in group if c['file'] in quoted]
            known_digest = known_items.get(plain, {}).get('digest')
            by_digest = [c for c in group if known_digest and item_digest(root, c, version) == known_digest]
            keeper = by_file[0] if len(by_file) == 1 else by_digest[0] if len(by_digest) == 1 else group[0]
        for class_ in [keeper] + [c for c in group if c is not keeper]:
            wanted = fitted(name) if class_ is keeper else fitted(f"{name}~{source_root(class_['file'], roots)}")
            item, number = wanted, 1
            while item in taken:
                number += 1
                item = fitted(f'{wanted}~{number}')
            taken.add(item)
            class_['item'] = item


def tests_of(class_, index, suffixes):
    """The test files of a class: those of a type called <Name><suffix> first, then the files that name it."""
    own = [s.rel for suffix in suffixes for s in index.declaring.get(class_['simple'] + suffix, [])]
    return list(dict.fromkeys(own + index.naming(class_['simple'])))


def classes_without_test(classes, rules):
    min_ncloc, min_branches = rules.number('class_min_ncloc'), rules.number('class_min_branches')
    high, low = rules.severity('class-without-test'), rules.severity('class-without-test-outside-layers')
    found = []
    for class_ in classes:
        if class_['tests'] or class_['ncloc'] < min_ncloc or class_['branches'] < min_branches:
            continue
        measure = f"{class_['file']} ({class_['ncloc']} lines, {class_['branches']} branches)"
        found.append(finding(
            'class-without-test', class_['class'], high if class_['weight'] > 1 else low,
            f"No test names {class_['simple']}: {class_['ncloc']} lines of code, {class_['branches']} branches",
            f"No type of the test sources is called {class_['simple']} with one of the test suffixes, and no test "
            f"file names {class_['simple']} in its code. Tests may still reach it through another class. The rule "
            f"counts a class from {min_ncloc} lines of code and {min_branches} branches.",
            'class_without_test: ' + measure, class_['file'], class_['line']))
    return found


def test_methods(sources, rules, not_measured):
    """The test methods of the test sources as (file, method), each once."""
    annotations = set(rules.words('test_annotation'))
    found = {}
    for source in sources.test:
        for name, position, _, kind, target in source.annotated(annotations):
            if kind != 'method':
                not_measured.append((f'the @{name} at {source.rel}:{source.line_of(position)}',
                                     'no method declaration follows it that the scanner can read'))
                continue
            found[(source.rel, target.body_open)] = (source, target)
    return list(found.values())


def method_subject(source, method):
    type_ = source.enclosing_type(method.start)
    return (source.qualified(type_) if type_ else source.rel) + '#' + method.name


def tests_without_assertion(tests, index, rules):
    severity = rules.severity('test-without-assertion')
    words = ', '.join(rules.words('assertion_call'))
    found = []
    for source, method in tests:
        if index.asserts(method):
            continue
        subject = method_subject(source, method)
        line = source.line_of(method.start)
        found.append(finding(
            'test-without-assertion', subject, severity,
            f'{subject.rsplit(".", 1)[-1]} asserts nothing',
            f'The body of the test calls no method whose name matches one of: {words}; and no helper of the test '
            'sources it calls does. It fails only when the code under test throws.',
            f'test_without_assertion: {subject} ({source.rel})', source.rel, line))
    return found


def disabled_tests(sources, rules, may_skip):
    """The tests that are switched off. A test that skips itself on an assumption is a finding too, unless the rule
    file says ``assumption_report fact``: then it is only counted (appended to ``may_skip``)."""
    severity = rules.severity('disabled-test')
    assumption_is_finding = 'fact' not in rules.words('assumption_report')
    annotations = set(rules.words('disabled_annotation'))
    assumption = rules.pattern('assumption_call')
    found = {}
    for source in sources.test:
        for name, position, arguments, kind, target in source.annotated(annotations):
            if kind == 'type':
                subject = source.qualified(target)
            elif kind == 'method':
                subject = method_subject(source, target)
            else:
                subject = f'{source.rel}#@{name}'
            reasons = source.strings_between(*arguments) if arguments else []
            reason = one_line(' '.join(reasons), 200) if reasons else 'no reason given'
            found.setdefault(subject, finding(
                'disabled-test', subject, severity, f'{subject.rsplit(".", 1)[-1]} is disabled (@{name})',
                f'@{name} keeps the test from running. Its reason: {reason}',
                f'disabled_test: {subject} ({source.rel}) @{name}: {reason}', source.rel, source.line_of(position)))
        for method in source.methods:
            for match in re.finditer(r'([A-Za-z_]\w*)\s*\(', source.code[method.body_open:method.body_close]):
                if not assumption.match(match.group(1)):
                    continue
                position = method.body_open + match.start()
                inner = source.enclosing_method(position)
                if inner is not method:
                    continue   # counted with the method that holds it directly
                subject = method_subject(source, method)
                if not assumption_is_finding:
                    if subject not in may_skip:
                        may_skip.append(subject)
                    continue
                line = source.line_of(position)
                reason = one_line(source.text.split('\n')[line - 1], 200)
                found.setdefault(subject, finding(
                    'disabled-test', subject, severity,
                    f'{subject.rsplit(".", 1)[-1]} skips itself when an assumption does not hold',
                    f'An assumption ends the test as skipped, not failed, when it does not hold: {reason}',
                    f'disabled_test: {subject} ({source.rel}) assumption: {reason}', source.rel, line))
    return [found[subject] for subject in sorted(found)]


def commands_without_integration_test(root, sources, rules, not_measured):
    """The commands the command line declares against the commands the integration tests run."""
    rule = 'command-without-integration-test'
    declared_in, tests_in = rules.text('command_sources').strip('/'), rules.text('command_tests').strip('/')
    try:
        declaration = re.compile(rules.text('command_declared'))
    except re.error as error:
        raise SystemExit(f'{rules.path}: "command_declared" is no regular expression: {error}')
    runners = set(rules.words('command_runner'))
    if not os.path.isdir(os.path.join(root, declared_in)):
        not_measured.append((rule, f'no directory {declared_in}, where the rule file says the commands are declared'))
        return [], [], []
    if not os.path.isdir(os.path.join(root, tests_in)):
        not_measured.append((rule, f'no directory {tests_in}, where the rule file says the integration tests are'))
        return [], [], []
    declared = {}
    for source in sources.main:
        if source.rel.startswith(declared_in + '/'):
            for match in declaration.finditer(source.text):
                if source.code[match.start()] != ' ':   # not in a comment
                    declared.setdefault(match.group(1), (source.rel, source.line_of(match.start())))
    if not declared:
        not_measured.append((rule, f'no command declaration found below {declared_in} with the pattern of the rule file'))
        return [], [], []
    run = set()
    for source in sources.test:
        if source.rel.startswith(tests_in + '/'):
            for match in re.finditer(r'([A-Za-z_]\w*)\s*\(', source.code):
                if match.group(1) in runners:
                    first = source.first_string_argument(match.end() - 1)
                    if first is not None:
                        run.add(first)
    severity = rules.severity(rule)
    found = []
    for name in sorted(set(declared) - run):
        file, line = declared[name]
        found.append(finding(
            rule, name, severity, f'no integration test runs the command "{name}"',
            f'No call of {", ".join(sorted(runners))}(...) below {tests_in} has "{name}" as its first string '
            f'argument. The command is declared in {file}.',
            f'command_without_integration_test: {name} ({file})', file, line))
    return found, sorted(declared), sorted(run & set(declared))


def numbered(lines, first=1):
    width = len(str(first + len(lines) - 1))
    return '\n'.join(f'{str(first + i).rjust(width)}| {line}' for i, line in enumerate(lines))


def item_digest(root, class_, version):
    """What a verdict about the class sees, as 16 hex digits: the version of the question, the file of the class,
    and every test file that names it, by path and content. A file that cannot be read counts by its path alone."""
    digest = hashlib.sha256(f'verdict_version {version}\n'.encode('utf-8'))
    for rel in [class_['file']] + sorted(class_['tests']):
        digest.update(rel.encode('utf-8', errors='replace') + b'\0')
        try:
            with open(os.path.join(root, rel), 'rb') as handle:
                digest.update(handle.read())
        except OSError:
            pass
        digest.update(b'\0')
    return digest.hexdigest()[:16]


def classes_with_open_findings(rows):
    """The classes the register holds an open uncaught change of, read from the findings themselves. A finding of a
    run before items existed names no item, so the register may call its class clean: the finding decides."""
    names = set()
    for row in rows:
        subject = re.fullmatch(r'(.+)#([0-9a-f]{12})', row['subject'])
        if row['rule'] == JUDGED_RULE and row['status'] in known.ASKED and subject:
            names.add(subject.group(1))
    return names


def choose_candidates(classes, sources, rules, root, known_items, open_classes=frozenset()):
    """The items of the audit, the classes of them this run judges, and those that need a verdict and got no place.

    An item is a class with a test where a missed bug costs something. One the register calls clean or decided, with
    the digest it has now, is settled and not judged, unless the register holds an open finding of it. The places, at most the cap, go first to the classes that
    changed or were never judged, by score, and then to the classes with an open finding that did not change: so
    every class is judged once before one is asked about twice."""
    cap, min_ncloc = rules.number('candidates_max'), rules.number('candidate_min_ncloc')
    version = rules.number('verdict_version')
    per_branch, max_lines = rules.number('score_per_branch'), rules.number('max_code_lines')
    max_tests = rules.number('max_tests_listed')
    by_file = {source.rel: source for source in sources.main}
    eligible = []
    for class_ in classes:
        if class_['tests'] and class_['ncloc'] >= min_ncloc and class_['kind'] != 'interface':
            class_['score'] = round((class_['ncloc'] + per_branch * class_['branches']) * class_['weight'], 1)
            eligible.append(class_)
    eligible.sort(key=lambda c: (-c['score'], c['class']))
    changed, open_unchanged = [], []
    for class_ in eligible:
        class_['digest'] = item_digest(root, class_, version)
        row = known_items.get(register_items.shown(class_['item']))
        class_['state'] = row['state'] if row else ''
        same = bool(row) and row['digest'] == class_['digest']
        has_open = register_items.shown(class_['item']) in open_classes
        if same and row['state'] in register_items.SETTLED and not has_open:
            class_['settled'] = True
            continue
        (open_unchanged if same and (row['state'] == 'open' or has_open) else changed).append(class_)
    to_judge = changed + open_unchanged
    for class_ in to_judge[:cap]:
        class_['chosen'] = True
    fence = secrets.token_hex(8)
    chosen = []
    for number, class_ in enumerate(to_judge[:cap], 1):
        lines = by_file[class_['file']].text.split('\n')
        if lines and lines[-1] == '':
            lines.pop()
        lines = [line.rstrip('\r') for line in lines]
        shown = lines[:max_lines]
        tests = class_['tests'][:max_tests]
        layer = (f"it is in the layer {class_['layer']}, which the rule file weighs {class_['weight']:g} times"
                 if class_['layer'] else 'it is in no layer the rule file weighs')
        chosen.append({
            'id': f'C{number:02d}', 'token': secrets.token_hex(16), 'fence': fence,
            'class': class_['class'], 'item': class_['item'], 'digest': class_['digest'],
            'file': class_['file'], 'ncloc': class_['ncloc'], 'lines': len(lines),
            'branches': class_['branches'], 'score': class_['score'], 'layer': class_['layer'],
            'why': f"{class_['ncloc']} lines of code and {class_['branches']} branches; {layer}; score "
                   f"{class_['score']:g}",
            'tests': tests,
            'tests_text': '\n'.join(f'- {test}' for test in tests)
                          + (f"\n({len(class_['tests']) - len(tests)} more test files name the class)"
                             if len(class_['tests']) > len(tests) else ''),
            'shown_text': 'The whole file.' if len(shown) == len(lines)
                          else f'The first {len(shown)} of its {len(lines)} lines; read the rest of the file yourself.',
            'code': numbered(shown)})
    cut = [{'class': c['class'], 'file': c['file'], 'score': c['score']} for c in to_judge[cap:]]
    items = [{'name': c['item'], 'digest': c['digest'], 'chosen': c.get('chosen', False), 'state': c['state'],
              'settled': c.get('settled', False)} for c in eligible]
    return chosen, cut, items


JUDGED_RULE = 'uncaught-mutation'


def has_line(root, rel, quote):
    """Whether a line of the file, trimmed, equals the quote; True when the file cannot be read, so nothing is
    concluded from a file nobody could look at."""
    try:
        with open(os.path.join(root, rel), encoding='utf-8', newline='') as handle:
            return any(line.strip() == quote for line in handle.read().split('\n'))
    except (OSError, UnicodeDecodeError):
        return True


def attach_known(candidates, classes, rows, root, items=(), unowned=None):
    """Sorts what the register knows to the classes the AI judges, and returns what cannot hold any more.

    An uncaught change's subject is `<class>#<digest of the quoted line>`. Each chosen class gets its open findings
    to answer for and its decided ones not to report again. An open finding is returned as gone, with the reason,
    when its class is no longer in the sources, or when the line it quotes is no longer a line of the file: the
    digest in the subject proves that the register shows the line whole and unchanged, and only then is that
    concluded. An open finding of a class that exists but is not judged in this run is in neither list: nothing is
    said about it, and the register keeps it open.

    Every item also gets what the report needs to say in what state it is after the run: how many open findings
    the register holds of it, how many of them are high, and how many a person decided.

    An open finding that no item carries goes into `unowned`: its class is still in the sources but is no item any
    more (no test names it now, or it is too small), or its subject cannot be read. No run asks about it and none
    names it as gone, so the report lists it for a person, and the gate does not go green over it."""
    unowned = [] if unowned is None else unowned
    files = {c['item']: c['file'] for c in classes}
    of_class = {c['item']: [] for c in candidates}
    of_class.update({item['name']: [] for item in items})
    gone = []
    for row in rows:
        subject = re.fullmatch(r'(.+)#([0-9a-f]{12})', row['subject'])
        if row['rule'] != JUDGED_RULE:
            continue
        if not subject:
            if row['status'] in known.ASKED:
                unowned.append({'ruleKey': row['rule'], 'subject': row['subject'], 'severity': row['severity']})
            continue
        name, digest = subject.groups()
        if row['status'] in known.ASKED and files:
            # The evidence is "candidate: <file> | <the line>", with the register's mask for the pipe
            quote = row['evidence'].split(' \u00a6 ', 1)[-1].replace('\u00a6', '|')
            whole = hashlib.sha256(quote.encode('utf-8')).hexdigest()[:12] == digest
            reason = ('the class is no longer in the sources' if name not in files
                      else 'the quoted line is no longer a line of the file'
                      if whole and not has_line(root, files[name], quote) else None)
            if reason:
                gone.append({'ruleKey': row['rule'], 'subject': row['subject'], 'reason': reason})
                continue
        if name in of_class:
            of_class[name].append(row)
        elif row['status'] in known.ASKED:
            unowned.append({'ruleKey': row['rule'], 'subject': row['subject'], 'severity': row['severity']})
    for candidate in candidates:
        known.attach(candidate, of_class[candidate['item']])
    for item in items:
        holder = {}
        known.attach(holder, of_class[item['name']])
        item.update(known.counts(holder))
    return gone


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--known', help='a file with the text of {{ audit_findings }}: what the register knows')
    parser.add_argument('--items', help='a file with the text of {{ audit_items }}: the items the register knows')
    parser.add_argument('--rules', required=True)
    parser.add_argument('--root', default='.')
    parser.add_argument('--facts', required=True)
    parser.add_argument('--candidates', required=True)
    args = parser.parse_args()
    rules = Rules(args.rules)

    not_measured = []
    sources = Sources(args.root, rules, not_measured)
    index = TestIndex(sources.test, rules)
    suffixes = rules.words('test_suffix')
    classes = measured_classes(sources, rules)
    for class_ in classes:
        class_['tests'] = tests_of(class_, index, suffixes)

    findings = []
    tests = []
    may_skip = []
    if not sources.main_roots:
        not_measured.append(('class-without-test and the classes to judge',
                             'no main source root below the workspace: ' + ', '.join(rules.words('main_root'))))
    if not sources.test_roots:
        not_measured.append(('class-without-test, test-without-assertion, disabled-test and the classes to judge',
                             'no test source root below the workspace: ' + ', '.join(rules.words('test_root'))))
    else:
        findings += classes_without_test(classes, rules)
        tests = test_methods(sources, rules, not_measured)
        findings += tests_without_assertion(tests, index, rules)
        findings += disabled_tests(sources, rules, may_skip)
    command_findings, declared, run = commands_without_integration_test(args.root, sources, rules, not_measured)
    findings += command_findings
    outside = sum(sources.outside.values())
    if outside:
        where = ', '.join(one_line(d) for d in sorted(sources.outside)[:8]) + (', ...' if len(sources.outside) > 8 else '')
        not_measured.append(('Java files outside the source roots of the rule file', f'{outside}, in {where}'))
    known_items = {}
    if args.items:
        with open(args.items, encoding='utf-8', errors='replace') as handle:
            known_items = register_items.parse(handle.read())
    rows = []
    if args.known:
        with open(args.known, encoding='utf-8', errors='replace') as handle:
            rows = known.parse(handle.read())
    name_items(classes, sources.main_roots, known_items, rows, args.root, rules.number('verdict_version'))
    known_missing = known_missing_open = 0
    if args.known:
        with open(args.known, encoding='utf-8', errors='replace') as handle:
            known_text = handle.read()
        known_missing, known_missing_open = known.missing(known_text), known.missing_open(known_text)
    candidates, cut, items = (choose_candidates(classes, sources, rules, args.root, known_items,
                                                classes_with_open_findings(rows))
                              if sources.test_roots else ([], [], []))
    findings.sort(key=lambda f: (f['rule'], f['subject']))
    known_unowned = []
    known_gone = attach_known(candidates, classes, rows, args.root, items, known_unowned)

    with open(args.candidates, 'w', encoding='utf-8') as handle:
        for candidate in candidates:
            handle.write(json.dumps(candidate) + '\n')
    counts = collections.Counter(f['rule'] for f in findings)
    facts = {
        'format': 1,
        'roots': {'main': sources.main_roots, 'test': sources.test_roots},
        'counts': {'main_files': len(sources.main), 'main_classes': len(classes), 'test_files': len(sources.test),
                   'test_methods': len(tests), 'commands_declared': len(declared), 'commands_run': len(run)},
        'commands': {'declared': declared, 'run_by_an_integration_test': run},
        'findings': findings,
        'not_measured': [{'what': what, 'why': why} for what, why in not_measured],
        'candidates': [{k: c[k] for k in ('id', 'class', 'file', 'ncloc', 'branches', 'score', 'layer', 'tests')}
                       for c in candidates],
        'cut': cut,
        'items': items,
        'known_gone': known_gone,
        'known_unowned': known_unowned,
        # How many findings {{ audit_findings }} left out: nothing is concluded from a row that is not there
        'known_missing': known_missing,
        # How many of them are open by the block's own count: with any, an item the register calls open stays open
        'known_missing_open': known_missing_open,
    }
    with open(args.facts, 'w', encoding='utf-8') as handle:
        json.dump(facts, handle, indent=1)
        handle.write('\n')

    out = ['## FACT SHEET testing']
    out += [f'FACT {name}: {value}' for name, value in facts['counts'].items()]
    for rule in ('class-without-test', 'test-without-assertion', 'disabled-test', 'command-without-integration-test'):
        out.append(f"FACT {rule.replace('-', '_')}_count: {counts[rule]}")
    out.append(f'FACT tests_that_may_skip_count: {len(may_skip)}')
    out.append(f'FACT items_count: {len(items)}')
    out.append(f"FACT items_settled_count: {len(items) - len(candidates) - len(cut)}")
    out.append(f'FACT candidates_count: {len(candidates)}')
    out.append(f'FACT candidates_cut_count: {len(cut)}')
    out += ['HIT ' + one_line(f['evidence'], 600) for f in findings]
    out += [f"HIT candidate: {c['file']} ({c['id']}, {c['class']}, score {c['score']:g})" for c in candidates]
    out += [f'NOT MEASURED: {one_line(what)} ({one_line(why)})' for what, why in not_measured]
    print('\n'.join(out))


if __name__ == '__main__':
    main()
