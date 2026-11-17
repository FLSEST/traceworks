#!/usr/bin/env python3
"""The fact step of the functionality self-audit: the documentation against the code, read as text.

It compares what docs/cli.md says with what the CLI sources declare (commands, options, exit codes), the
example packages with the documents that name them, and the block types the documents name with the executors
that are registered. Every difference is a finding. What it could not measure it says, as a line
"NOT MEASURED: <what> (<why>)", and never stays silent about.

It also chooses what the AI judges: one item per section of docs/cli.md, in sections.jsonl. Every section that
could be judged is an item of the audit, with a digest of what a verdict about it sees. The register hands back what
it knows of the items ({{ audit_items }}); a section it calls clean or decided, with the digest it has now, is not
judged again (docs/registers.md, "Items").

    facts.py --root DIR --rules FILE --out DIR [--known FILE] [--items FILE]

writes DIR/facts.json and DIR/sections.jsonl and prints a fact sheet. With --known FILE (the text of
{{ audit_findings }}), each section also gets the open and the decided findings the register knows of it; with
--items FILE (the text of {{ audit_items }}), the sections that are settled are left out. Python 3 standard library
only; it reads the workspace and writes only below --out.
"""
import argparse
import fnmatch
import hashlib
import json
import os
import re
import secrets
import sys

import items as register_items
import known

SKIPPED_DIRS = {"target", "node_modules"}
MAX_FILE_BYTES = 4 * 1024 * 1024
NAME = r"[a-z][a-z0-9-]*"
OPTION_IN_TEXT = re.compile(r"(?<![\w-])--(" + NAME + r")")
EXAMPLE_PATH = re.compile(r"(?<![\w./-])examples/[A-Za-z0-9_./-]*")
BLOCK_IN_TEXT = re.compile(r"`(" + NAME + r")` blocks?\b")

SEVERITY = {
    "undocumented-command": "MEDIUM",
    "unknown-command-in-docs": "MEDIUM",
    "undocumented-option": "LOW",
    "unknown-option-in-docs": "MEDIUM",
    "exit-code-mismatch": "MEDIUM",
    "example-not-mentioned": "LOW",
    "missing-example-path": "LOW",
    "undocumented-block-type": "LOW",
    "unknown-block-type-in-docs": "LOW",
}


# ---------------------------------------------------------------------------------------------------------------
# The rule file
# ---------------------------------------------------------------------------------------------------------------

DEFAULT_RULES = {
    "cli_doc": ["docs/cli.md"],
    "commands_heading": ["Commands"],
    "exit_codes_heading": ["Exit codes"],
    "exit_codes_class": ["ExitCodes"],
    "doc": [],
    "block_doc": [],
    "max_sections": ["40"],
    "max_section_chars": ["12000"],
    "verdict_version": ["1"],
    "foreign_option": [],
    "not_a_block_type": [],
    "example_exclude": [],
}
SINGLE = {"cli_doc", "commands_heading", "exit_codes_heading", "exit_codes_class", "max_sections",
          "max_section_chars", "verdict_version"}


def read_rules(path):
    """The rule file: one "key value" per line; "#" starts a comment line. An unknown key is an error."""
    rules = {key: list(value) for key, value in DEFAULT_RULES.items()}
    given = set()
    with open(path, encoding="utf-8") as f:
        for number, raw in enumerate(f, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            key, _, value = line.partition(" ")
            value = value.strip()
            if key not in rules:
                raise SystemExit(f"facts: {path}, line {number}: unknown key '{key}'")
            if not value:
                raise SystemExit(f"facts: {path}, line {number}: '{key}' needs a value")
            if key in SINGLE:
                if key in given:
                    raise SystemExit(f"facts: {path}, line {number}: '{key}' is given twice")
                rules[key] = [value]
            else:
                rules[key].append(value)
            given.add(key)
    for value in rules["foreign_option"]:
        if not re.fullmatch(NAME + r"( [A-Za-z_][\w.-]*)?", value):
            raise SystemExit(f"facts: {path}: 'foreign_option' takes an option's name, or the name and one word"
                             f" the line must hold before it, got '{value}'")
    for key in ("max_sections", "max_section_chars", "verdict_version"):
        if not re.fullmatch(r"[1-9][0-9]{0,6}", rules[key][0]):
            raise SystemExit(f"facts: {path}: '{key}' takes a positive number, got '{rules[key][0]}'")
    return rules


# ---------------------------------------------------------------------------------------------------------------
# Files
# ---------------------------------------------------------------------------------------------------------------

def read_text(path):
    """The text of a regular file that is no symbolic link, or None."""
    try:
        if os.path.islink(path) or not os.path.isfile(path) or os.path.getsize(path) > MAX_FILE_BYTES:
            return None
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return None


def walk(root, top="."):
    """Every file below root/top as a path relative to root, sorted; no hidden directory, no build output, no link."""
    found = []
    start = os.path.normpath(os.path.join(root, top))
    for folder, dirs, files in os.walk(start, followlinks=False):
        dirs[:] = sorted(d for d in dirs if not d.startswith(".") and d not in SKIPPED_DIRS
                         and not os.path.islink(os.path.join(folder, d)))
        for name in sorted(files):
            full = os.path.join(folder, name)
            if not os.path.islink(full):
                found.append(os.path.relpath(full, root).replace(os.sep, "/"))
    return found


# ---------------------------------------------------------------------------------------------------------------
# Java, as tokens: enough to read a declaration without being fooled by a comment or a string
# ---------------------------------------------------------------------------------------------------------------

def java_tokens(text):
    """The tokens of Java source as (kind, value): "id", "str" (the value without its quotes), "num", "sym"."""
    tokens = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c.isspace():
            i += 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end < 0 else end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            while end > 0 and text[end - 1] == "\\":
                end = text.find('"""', end + 1)
            tokens.append(("str", text[i + 3:end if end >= 0 else n]))
            i = n if end < 0 else end + 3
        elif c == '"' or c == "'":
            j = i + 1
            value = []
            while j < n and text[j] != c and text[j] != "\n":
                if text[j] == "\\" and j + 1 < n:
                    value.append({"n": "\n", "t": "\t"}.get(text[j + 1], text[j + 1]))
                    j += 2
                else:
                    value.append(text[j])
                    j += 1
            tokens.append(("str" if c == '"' else "chr", "".join(value)))
            i = j + 1
        elif c.isalpha() or c in "_$":
            j = i + 1
            while j < n and (text[j].isalnum() or text[j] in "_$"):
                j += 1
            tokens.append(("id", text[i:j]))
            i = j
        elif c.isdigit():
            j = i + 1
            while j < n and (text[j].isalnum() or text[j] in "._"):
                j += 1
            tokens.append(("num", text[i:j]))
            i = j
        else:
            tokens.append(("sym", c))
            i += 1
    return tokens


def top_level_class(tokens):
    """The name of the first class, interface, record or enum the tokens declare, or None."""
    for at in range(len(tokens) - 1):
        if tokens[at] in (("id", "class"), ("id", "interface"), ("id", "record"), ("id", "enum")) \
                and tokens[at + 1][0] == "id" and (at == 0 or tokens[at - 1] != ("sym", ".")):
            return tokens[at + 1][1]
    return None


def statement_end(tokens, start):
    """The index of the ";" that ends the statement at start, at the bracket depth of start."""
    depth = 0
    for at in range(start, len(tokens)):
        kind, value = tokens[at]
        if kind != "sym":
            continue
        if value in "([{":
            depth += 1
        elif value in ")]}":
            depth -= 1
            if depth < 0:
                return at
        elif value == ";" and depth == 0:
            return at
    return len(tokens)


def is_call(tokens, at, receiver, method):
    """Whether tokens[at:] is "<receiver> . <method> (" (receiver None: ". <method> (")."""
    head = [] if receiver is None else [("id", receiver)]
    want = head + [("sym", "."), ("id", method), ("sym", "(")]
    return tokens[at:at + len(want)] == want


class Sources:
    """The Java files below every src/main/java of the workspace, with their tokens."""

    def __init__(self, root):
        self.files = {}
        for rel in walk(root):
            if rel.endswith(".java") and "/src/main/java/" in "/" + rel:
                text = read_text(os.path.join(root, rel))
                if text is not None:
                    self.files[rel] = java_tokens(text)
        self.by_class = {}
        for rel, tokens in self.files.items():
            name = top_level_class(tokens)
            if name:
                self.by_class.setdefault(name, []).append(rel)

    def file_of(self, class_name):
        """The one file that declares the class, or None when none or several do."""
        files = self.by_class.get(class_name, [])
        return files[0] if len(files) == 1 else None


# ---------------------------------------------------------------------------------------------------------------
# What the code declares
# ---------------------------------------------------------------------------------------------------------------

def command_specs(sources, not_measured):
    """Every command a source file declares with Spec.of("<name>", ...): name -> {file, class, options}."""
    specs = {}
    for rel, tokens in sorted(sources.files.items()):
        for at in range(len(tokens)):
            if not is_call(tokens, at, "Spec", "of"):
                continue
            first = tokens[at + 4] if at + 4 < len(tokens) else ("sym", "")
            if first[0] != "str":
                # A declaration of Spec.of itself, "Spec of(String name, ...)", is no call with a dot before "of"
                not_measured.append(("the command of " + rel, "the first argument of Spec.of is no string literal"))
                continue
            options = set()
            end = statement_end(tokens, at)
            for inner in range(at, end):
                for method in ("flag", "option", "repeated"):
                    if is_call(tokens, inner, None, method):
                        name = tokens[inner + 3] if inner + 3 < end else ("sym", "")
                        if name[0] == "str":
                            options.add(name[1])
                        else:
                            not_measured.append(("an option of the command '" + first[1] + "'",
                                                 "an argument of ." + method + "( in " + rel
                                                 + " is no string literal"))
            if first[1] in specs:
                not_measured.append(("the command '" + first[1] + "'", "declared twice: " + specs[first[1]]["file"]
                                     + " and " + rel))
                continue
            specs[first[1]] = {"file": rel, "class": top_level_class(tokens), "options": sorted(options)}
    return specs


def main_file(sources, specs):
    """The file with "static void main(" that creates the most command classes: the one that dispatches them."""
    classes = {spec["class"] for spec in specs.values() if spec["class"]}
    best, best_count = None, 0
    for rel, tokens in sorted(sources.files.items()):
        if not any(tokens[at:at + 3] == [("id", "void"), ("id", "main"), ("sym", "(")] for at in range(len(tokens))):
            continue
        created = {tokens[at + 1][1] for at in range(len(tokens) - 1)
                   if tokens[at] == ("id", "new") and tokens[at + 1][0] == "id"}
        if len(created & classes) > best_count:
            best, best_count = rel, len(created & classes)
    return best


def dispatched(sources, specs, main):
    """The names of the commands whose class the main file creates."""
    tokens = sources.files[main]
    created = {tokens[at + 1][1] for at in range(len(tokens) - 1)
               if tokens[at] == ("id", "new") and tokens[at + 1][0] == "id"}
    return {name for name, spec in specs.items() if spec["class"] in created}


def global_options(sources, main):
    """The options every command takes: each "--name" a string literal of the main file or of the file that declares
    Spec starts with."""
    files = [main] if main else []
    for rel, tokens in sources.files.items():
        if any(tokens[at:at + 2] == [("id", "class"), ("id", "Spec")] for at in range(len(tokens))):
            files.append(rel)
    found = set()
    for rel in files:
        for kind, value in sources.files[rel]:
            match = re.match(r"--(" + NAME + r")", value) if kind == "str" else None
            if match:
                found.add(match.group(1))
    return found


def exit_codes_in_code(sources, class_name, not_measured):
    """The constants "static final int NAME = N;" of the exit code class: number -> name."""
    rel = sources.file_of(class_name)
    if rel is None:
        not_measured.append(("the exit codes of the code", "no single class " + class_name
                             + " below a src/main/java"))
        return None, None
    tokens = sources.files[rel]
    codes = {}
    for at in range(len(tokens) - 5):
        if tokens[at:at + 3] == [("id", "static"), ("id", "final"), ("id", "int")] and tokens[at + 3][0] == "id" \
                and tokens[at + 4] == ("sym", "=") and tokens[at + 5][0] == "num" \
                and tokens[at + 6:at + 7] == [("sym", ";")] and tokens[at + 5][1].isdigit():
            codes.setdefault(int(tokens[at + 5][1]), tokens[at + 3][1])
    if not codes:
        not_measured.append(("the exit codes of the code", rel + " declares no 'static final int NAME = N;'"))
        return None, rel
    return codes, rel


def block_types_in_code(sources, not_measured):
    """The block types of the executors the registry creates, type -> file: what each one's supportedTypes() returns.
    The second value says whether every executor could be read."""
    registry = None
    for rel, tokens in sorted(sources.files.items()):
        if any(tokens[at:at + 2] == [("id", "new"), ("id", "BlockTypeRegistry")] for at in range(len(tokens))):
            registry = rel
            break
    if registry is None:
        not_measured.append(("the block types of the code", "no source file creates a BlockTypeRegistry"))
        return None, False
    tokens = sources.files[registry]
    executors = sorted({tokens[at + 1][1] for at in range(len(tokens) - 1)
                        if tokens[at] == ("id", "new") and tokens[at + 1][0] == "id"
                        and tokens[at + 1][1] != "BlockTypeRegistry" and tokens[at + 1][1][0].isupper()
                        and tokens[at + 2:at + 3] == [("sym", "(")]})
    types = {}
    complete = True
    for executor in executors:
        rel = sources.file_of(executor)
        if rel is None:
            not_measured.append(("the block types of " + executor, "no single source file declares the class"))
            complete = False
            continue
        own = sources.files[rel]
        constants = {own[at + 1][1]: own[at + 3][1] for at in range(len(own) - 3)
                     if own[at] == ("id", "String") and own[at + 1][0] == "id" and own[at + 2] == ("sym", "=")
                     and own[at + 3][0] == "str" and own[at + 4:at + 5] == [("sym", ";")]}
        found = None
        for at in range(len(own) - 2):
            if own[at] == ("id", "supportedTypes") and own[at + 1] == ("sym", "(") and own[at + 2] == ("sym", ")"):
                found = set()
                body = at + 3
                while body < len(own) and own[body] != ("sym", "{"):
                    body += 1
                end = statement_end(own, body + 1)
                for kind, value in own[body + 1:end]:
                    if kind == "str":
                        found.add(value)
                    elif kind == "id" and value in constants:
                        found.add(constants[value])
                break
        if not found:
            not_measured.append(("the block types of " + executor, "supportedTypes() in " + rel
                                 + " names no string literal or constant of the file"))
            complete = False
            continue
        for name in found:
            types[name] = rel
    return types, complete


# ---------------------------------------------------------------------------------------------------------------
# What the documents say
# ---------------------------------------------------------------------------------------------------------------

class Section:
    def __init__(self, level, heading, start):
        self.level = level
        self.heading = heading
        self.start = start      # the line of the heading, 1-based
        self.end = start        # the last line, inclusive
        self.lines = []         # the lines after the heading


def markdown_sections(text):
    """The sections of a markdown text: every heading outside a code fence starts one that ends before the next."""
    sections = []
    fence = None
    for number, line in enumerate(text.split("\n"), 1):
        stripped = line.lstrip()
        marker = stripped[:3]
        if fence is None and marker in ("```", "~~~"):
            fence = marker
        elif fence is not None and stripped.startswith(fence):
            fence = None
        else:
            heading = re.match(r"(#{1,6})\s+(.*?)\s*#*\s*$", line) if fence is None else None
            if heading:
                sections.append(Section(len(heading.group(1)), heading.group(2).strip(), number))
                continue
        if sections:
            sections[-1].lines.append(line)
            sections[-1].end = number
    return sections


def heading_commands(heading):
    """The commands a heading such as "approve and answer" names, or None when it holds another word."""
    parts = [p.strip().strip("`") for p in re.split(r"\s+and\s+|\s*,\s*", heading) if p.strip()]
    if parts and all(re.fullmatch(NAME, p) for p in parts):
        return parts
    return None


def foreign_options(values):
    """The foreign_option lines of the rule file: option -> the words one of which must stand right before it, or
    None for an option that is another program's wherever it stands."""
    foreign = {}
    for value in values:
        option, _, word = value.partition(" ")
        if not word:
            foreign[option] = None
        elif foreign.get(option, set()) is not None:
            foreign.setdefault(option, set()).add(word)
    return foreign


def unknown_options(text, every_option, foreign):
    """The options a text names that no command has and that are not another program's where they stand, sorted."""
    unknown = set()
    for line in text.split("\n"):
        for match in OPTION_IN_TEXT.finditer(line):
            option = match.group(1)
            if option in every_option:
                continue
            if option in foreign:
                words = foreign[option]
                before = line[:match.start()]
                # The word stands right before the option: only a backtick, a quote, "[" or white space between
                if words is None or any(re.search(r"(?<![\w-])" + re.escape(word) + r"[`'\"\[\s]*$", before)
                                        for word in words):
                    continue
            unknown.add(option)
    return sorted(unknown)


def slug(heading):
    return re.sub(r"[^a-z0-9]+", "-", heading.lower()).strip("-") or "section"


# ---------------------------------------------------------------------------------------------------------------
# The checks
# ---------------------------------------------------------------------------------------------------------------

class Audit:
    def __init__(self, root, rules):
        self.root = root
        self.rules = rules
        self.findings = []
        self.not_measured = []
        self.counts = {}
        self.every = []         # every section that could be judged, in the order of the document
        self.headings = []
        self.sections = []      # the ones this run judges
        self.sections_cut = []  # the ones that need a verdict and got no place
        self.items = []
        self.known_unowned = []  # open findings of the register that no section carries and that are not gone

    def finding(self, rule, subject, title, description):
        self.findings.append({
            "ruleKey": rule, "subject": subject, "severity": SEVERITY[rule], "title": title,
            "description": description, "evidence": "func_" + rule.replace("-", "_") + ": " + subject,
        })

    # -- commands and options ------------------------------------------------------------------------------------

    def commands_and_options(self, sources, cli_doc, doc_sections):
        specs = command_specs(sources, self.not_measured)
        main = main_file(sources, specs) if specs else None
        if not specs or main is None:
            self.not_measured.append(("commands and options", "no command declared with Spec.of(\"<name>\", ...)"
                                      if not specs else "no file with a main method creates a command class"))
            return {}
        in_code = dispatched(sources, specs, main)
        for name in sorted(set(specs) - in_code):
            self.not_measured.append(("the command '" + name + "'", specs[name]["file"] + " declares it, and "
                                      + main + " does not create its class: it counts as not dispatched"))
        globals_ = global_options(sources, main)
        self.counts["commands_in_code"] = len(in_code)
        self.counts["options_in_code"] = sum(len(specs[name]["options"]) for name in in_code)

        if doc_sections is None:
            return specs
        wanted = self.rules["commands_heading"][0]
        top = next((i for i, s in enumerate(doc_sections) if s.level == 2 and s.heading == wanted), None)
        if top is None:
            self.not_measured.append(("commands and options", cli_doc + " has no section '## " + wanted + "'"))
            return specs
        documented = {}
        for section in doc_sections[top + 1:]:
            if section.level <= 2:
                break
            if section.level != 3:
                continue
            names = heading_commands(section.heading)
            if names is None:
                self.not_measured.append(("the section '" + section.heading + "' of " + cli_doc,
                                          "its heading is not a command, or commands joined by 'and'"))
                continue
            for name in names:
                documented.setdefault(name, []).append(section)
        # A level-3 section holds its deeper sections too
        texts = {}
        for i, section in enumerate(doc_sections):
            if section.level == 3:
                lines = list(section.lines)
                for deeper in doc_sections[i + 1:]:
                    if deeper.level <= 3:
                        break
                    lines.append(deeper.heading)
                    lines.extend(deeper.lines)
                texts[id(section)] = "\n".join(lines)
        self.counts["commands_in_docs"] = len(documented)

        for name in sorted(in_code - set(documented)):
            self.finding("undocumented-command", name, "The command '" + name + "' has no section in " + cli_doc,
                         specs[name]["file"] + " declares the command, and no '###' heading under '## " + wanted
                         + "' names it.")
        for name in sorted(set(documented) - in_code):
            self.finding("unknown-command-in-docs", name, cli_doc + " describes a command '" + name
                         + "' that the command line does not have",
                         "The heading '" + documented[name][0].heading + "' (line "
                         + str(documented[name][0].start) + ") names it; " + main + " dispatches no such command.")

        foreign = foreign_options(self.rules["foreign_option"])
        every_option = set(globals_)
        for name in in_code:
            every_option.update(specs[name]["options"])
        seen_unknown = set()
        for name in sorted(in_code & set(documented)):
            named = set()
            for section in documented[name]:
                named.update(OPTION_IN_TEXT.findall(texts[id(section)]))
            for option in specs[name]["options"]:
                if option not in named:
                    self.finding("undocumented-option", name + " --" + option, "The option --" + option + " of '"
                                 + name + "' is not in its section of " + cli_doc,
                                 specs[name]["file"] + " declares it; the section '" + documented[name][0].heading
                                 + "' does not name it.")
            for section in documented[name]:
                # An option of another command may be named in passing: only one no command has is unknown
                for option in unknown_options(texts[id(section)], every_option, foreign):
                    subject = "/".join(heading_commands(section.heading)) + " --" + option
                    if subject not in seen_unknown:
                        seen_unknown.add(subject)
                        self.finding("unknown-option-in-docs", subject, "The section '" + section.heading + "' of "
                                     + cli_doc + " names --" + option + ", which no command takes",
                                     "No Spec of a dispatched command declares it, it is no global option ("
                                     + ", ".join("--" + g for g in sorted(globals_)) + "), and the rule file does"
                                     " not list it as the option of another tool (foreign_option).")
        return specs

    # -- exit codes ----------------------------------------------------------------------------------------------

    def exit_codes(self, sources, cli_doc, doc_sections):
        codes, rel = exit_codes_in_code(sources, self.rules["exit_codes_class"][0], self.not_measured)
        if codes is not None:
            self.counts["exit_codes_in_code"] = len(codes)
        if doc_sections is None:
            return
        wanted = self.rules["exit_codes_heading"][0]
        section = next((s for s in doc_sections if s.level == 2 and s.heading == wanted), None)
        in_docs = {}
        if section is not None:
            for offset, line in enumerate(section.lines, 1):
                row = re.match(r"\s*\|\s*`?(\d+)`?\s*\|", line)
                if row:
                    in_docs.setdefault(int(row.group(1)), section.start + offset)
        if not in_docs:
            self.not_measured.append(("the exit codes of the documentation", cli_doc + " has no table under '## "
                                      + wanted + "'"))
            return
        self.counts["exit_codes_in_docs"] = len(in_docs)
        if codes is None:
            return
        for code in sorted(set(codes) - set(in_docs)):
            self.finding("exit-code-mismatch", "exit " + str(code), "Exit code " + str(code) + " (" + codes[code]
                         + ") is not in the table of " + cli_doc, rel + " declares " + codes[code] + " = "
                         + str(code) + "; the table under '## " + wanted + "' has no row for it.")
        for code in sorted(set(in_docs) - set(codes)):
            self.finding("exit-code-mismatch", "exit " + str(code), cli_doc + " documents exit code " + str(code)
                         + ", which the code does not declare", "Line " + str(in_docs[code]) + " of " + cli_doc
                         + " has the row; " + rel + " declares no constant with this value.")

    # -- examples ------------------------------------------------------------------------------------------------

    def documents(self):
        """The documents: README.md, docs/*.md, every README.md below examples/, and the "doc" globs of the rules."""
        files = walk(self.root)
        patterns = ["README.md", "docs/*.md", "examples/*README.md"] + self.rules["doc"]
        docs = []
        for rel in files:
            if not rel.endswith(".md"):
                continue
            for pattern in patterns:
                # fnmatch's "*" crosses "/", which is wanted for examples/ and not for docs/
                if fnmatch.fnmatchcase(rel, pattern) and (pattern != "docs/*.md" or rel.count("/") == 1):
                    docs.append(rel)
                    break
        return docs

    def examples(self):
        if not os.path.isdir(os.path.join(self.root, "examples")):
            self.not_measured.append(("the example packages", "the workspace has no directory examples/"))
            return
        excluded = self.rules["example_exclude"]
        packages = sorted(os.path.dirname(rel) for rel in walk(self.root, "examples")
                          if os.path.basename(rel) == "workflow.json"
                          and not any(fnmatch.fnmatchcase(os.path.dirname(rel), p) for p in excluded))
        docs = self.documents()
        self.counts["example_packages"] = len(packages)
        if not docs:
            self.not_measured.append(("whether the documentation names the example packages",
                                      "no document found: no README.md, no docs/*.md, no README.md below examples/"))
            return
        named = {}
        for doc in docs:
            text = read_text(os.path.join(self.root, doc))
            if text is None:
                self.not_measured.append(("the example paths of " + doc, "the file cannot be read"))
                continue
            for number, line in enumerate(text.split("\n"), 1):
                for match in EXAMPLE_PATH.finditer(line):
                    path = match.group(0).rstrip("./-")
                    named.setdefault(path, []).append((doc, number))
        self.counts["example_paths_in_docs"] = len(named)
        for package in packages:
            # Its own README does not count: a package only its own README names is not found by a reader
            elsewhere = [doc for path, places in named.items() for doc, _ in places
                         if (path == package or path.startswith(package + "/"))
                         and os.path.dirname(doc) != package]
            if not elsewhere:
                self.finding("example-not-mentioned", package, "No document names the example package " + package,
                             "It holds a workflow.json, and none of the " + str(len(docs)) + " documents (README.md,"
                             " docs/*.md, the README.md files below examples/; its own README aside) names its"
                             " path.")
        for path in sorted(named):
            if not os.path.lexists(os.path.join(self.root, path)):
                doc, number = named[path][0]
                self.finding("missing-example-path", path, doc + " names " + path + ", which does not exist",
                             "; ".join(d + ", line " + str(n) for d, n in named[path][:5])
                             + (" and " + str(len(named[path]) - 5) + " more" if len(named[path]) > 5 else ""))

    # -- block types ---------------------------------------------------------------------------------------------

    def block_types(self, sources, cli_doc):
        # A side that was read in part proves nothing about what it lacks: no finding is drawn from it
        types, every_executor = block_types_in_code(sources, self.not_measured)
        if types is None:
            return
        self.counts["block_types_in_code"] = len(types)
        docs = [cli_doc] + [d for d in self.rules["block_doc"] if d != cli_doc]
        texts = {}
        for doc in docs:
            text = read_text(os.path.join(self.root, doc))
            if text is None:
                self.not_measured.append(("the block types of " + doc, "the file does not exist or cannot be read"))
            else:
                texts[doc] = text
        if not texts:
            return
        not_types = set(self.rules["not_a_block_type"])
        for name in sorted(types if len(texts) == len(docs) else []):
            if not any("`" + name + "`" in text for text in texts.values()):
                self.finding("undocumented-block-type", name, "The block type '" + name + "' is in none of "
                             + ", ".join(sorted(texts)), types[name] + " runs it; no document names it as `"
                             + name + "`.")
        named = {}
        for doc, text in sorted(texts.items()):
            for number, line in enumerate(text.split("\n"), 1):
                for match in BLOCK_IN_TEXT.finditer(line):
                    named.setdefault(match.group(1), (doc, number))
        self.counts["block_types_in_docs"] = len(set(named) - not_types)
        for name in sorted(set(named) - set(types) - not_types if every_executor else []):
            doc, number = named[name]
            self.finding("unknown-block-type-in-docs", name, doc + " names a `" + name
                         + "` block, and no executor runs that type", "Line " + str(number) + " of " + doc
                         + "; the registered types are " + ", ".join(sorted(types)) + ".")

    # -- the sections the AI judges ------------------------------------------------------------------------------

    def find_sections(self, cli_doc, doc_sections, specs):
        """One item per '###' section under the commands heading and per other '##' section, cut into parts at
        empty lines when it is longer than max_section_chars. Which of them this run judges is chosen later, once
        each has what the register knows of it (choose)."""
        limit = int(self.rules["max_section_chars"][0])
        wanted = self.rules["commands_heading"][0]
        chosen = []
        i = 0
        while i < len(doc_sections):
            section = doc_sections[i]
            if section.level == 2 and section.heading == wanted:
                i += 1
                while i < len(doc_sections) and doc_sections[i].level > 2:
                    if doc_sections[i].level == 3:
                        chosen.append([doc_sections[i]])
                    elif chosen and chosen[-1][0].level == 3:
                        chosen[-1].append(doc_sections[i])
                    i += 1
            elif section.level == 2:
                group = [section]
                i += 1
                while i < len(doc_sections) and doc_sections[i].level > 2:
                    group.append(doc_sections[i])
                    i += 1
                chosen.append(group)
            else:
                i += 1
        mark = secrets.token_hex(8)
        fence = secrets.token_hex(8)
        used = set()
        items = []
        for group in chosen:
            head = group[0]
            lines = []
            for member in group:
                lines.append("#" * member.level + " " + member.heading)
                lines.extend(member.lines)
            while lines and not lines[-1].strip():
                lines.pop()
            base = slug(head.heading)
            while base in used:
                base += "-x"
            used.add(base)
            commands = heading_commands(head.heading) if head.level == 3 else None
            files = sorted({specs[c]["file"] for c in (commands or []) if c in specs})
            where = ("The command is declared in " + " and ".join(files) + "." if files
                     else "No single source file belongs to this section: search the sources for the words it uses.")
            parts = split_parts(lines, head.start, limit)
            for number, (start, end, text) in enumerate(parts, 1):
                items.append({
                    "id": os.path.basename(cli_doc) + "#" + base + ("" if len(parts) == 1 else "/" + str(number)),
                    "doc": cli_doc, "heading": head.heading, "part": number, "parts": len(parts),
                    "start": start, "end": end, "where": where, "files": files, "text": text, "fence": fence,
                    "mark": mark,
                })
        name_items(items)
        # Every section there is, also one that is not judged in this run: a finding of such a section is not gone
        self.headings = sorted({(item["doc"], item["heading"]) for item in items})
        self.every = items

    def choose(self, known_items):
        """Chooses the sections this run judges, after each got the open findings the register knows of it.

        Every section is an item. One the register calls clean or decided, with the digest it has now, is settled
        and not judged, unless the register holds an open finding of it. The places, at most max_sections, go first
        to the sections that changed or were never judged, in the order of the document, and then to the sections
        with an open finding that did not change: so every section is judged once before one is asked about twice."""
        version = int(self.rules["verdict_version"][0])
        cap = int(self.rules["max_sections"][0])
        changed, open_unchanged = [], []
        for section in self.every:
            section["digest"] = item_digest(self.root, section, version)
            row = known_items.get(register_items.shown(section["item"]))
            state = row["state"] if row else ""
            same = bool(row) and row["digest"] == section["digest"]
            # A finding of a run before items existed names no item, so its section may read clean: the finding decides
            has_open = bool(section.get("known"))
            entry = {"name": section["item"], "digest": section["digest"], "chosen": False, "state": state,
                     "settled": same and state in register_items.SETTLED and not has_open}
            # For the report, which says in what state the item is after the run: what the register holds of it
            entry.update(known.counts(section))
            self.items.append(entry)
            if entry["settled"]:
                continue
            (open_unchanged if same and (state == "open" or has_open) else changed).append((section, entry))
        to_judge = changed + open_unchanged
        for _, entry in to_judge[:cap]:
            entry["chosen"] = True
        self.sections = [section for section, _ in to_judge[:cap]]
        self.sections_cut = [section["id"] for section, _ in to_judge[cap:]]
        self.counts["sections"] = len(self.sections)
        self.counts["sections_cut"] = len(self.sections_cut)

    # -- everything ----------------------------------------------------------------------------------------------

    def run(self):
        sources = Sources(self.root)
        self.counts["java_files"] = len(sources.files)
        if not sources.files:
            self.not_measured.append(("the code", "no Java file below a src/main/java of the workspace"))
        cli_doc = self.rules["cli_doc"][0]
        text = read_text(os.path.join(self.root, cli_doc))
        doc_sections = None
        if text is None:
            self.not_measured.append(("the documentation of the command line", cli_doc
                                      + " does not exist or cannot be read"))
            self.not_measured.append(("the sections the AI judges", cli_doc + " does not exist or cannot be read"))
        else:
            doc_sections = markdown_sections(text)
        specs = {}
        if sources.files:
            specs = self.commands_and_options(sources, cli_doc, doc_sections)
            self.exit_codes(sources, cli_doc, doc_sections)
            self.block_types(sources, cli_doc)
        self.examples()
        if doc_sections is not None:
            self.find_sections(cli_doc, doc_sections, specs)
            if not self.every:
                self.not_measured.append(("the sections the AI judges", cli_doc + " has no '##' section"))
        self.findings.sort(key=lambda f: (f["ruleKey"], f["subject"]))


def split_parts(lines, first_line, limit):
    """Cuts the lines of a section into parts of at most limit characters, at empty lines outside a code fence where
    there is one; each part as (first line, last line, text)."""
    parts = []
    current, start, size = [], first_line, 0
    fence = None
    last_break = None      # the index in current after which a cut is clean
    for line in lines:
        if current and size + len(line) + 1 > limit:
            cut = last_break if last_break else len(current)
            parts.append((start, start + cut - 1, "\n".join(current[:cut]).strip("\n")))
            current = current[cut:]
            start += cut
            size = sum(len(l) + 1 for l in current)
            last_break = None
        current.append(line)
        size += len(line) + 1
        stripped = line.lstrip()
        if fence is None and stripped[:3] in ("```", "~~~"):
            fence = stripped[:3]
        elif fence is not None and stripped.startswith(fence):
            fence = None
        elif fence is None and not stripped:
            last_break = len(current)
    if any(l.strip() for l in current):
        parts.append((start, start + len(current) - 1, "\n".join(current).strip("\n")))
    return parts


def one_line(text):
    return re.sub(r"\s+", " ", str(text)).strip()


# ---------------------------------------------------------------------------------------------------------------
# The sections as items of the audit (docs/registers.md, "Items")
# ---------------------------------------------------------------------------------------------------------------

# The most characters the register takes for a name
NAME_LIMIT = 300


def name_items(sections):
    """Gives each section its name as an item of the audit: `<doc>#<heading>`, as a finding's subject starts, with
    `/<part>` for a section that is judged in parts, and `~2`, `~3` for a further section of the same name. The
    heading names a section better than its place does: a section that moves keeps its name."""
    seen, taken = {}, set()
    for section in sections:
        name = section["doc"] + "#" + section["heading"]
        if section["parts"] > 1:
            name += "/" + str(section["part"])
        # Counted on the name as the register gets it: two headings that differ in their blanks only, or in a control
        # character, are one name once fitted, and the register refuses a list that holds a name twice
        name = fitted(name)
        seen[name] = seen.get(name, 0) + 1
        item = name if seen[name] == 1 else fitted(name + "~" + str(seen[name]))
        while item in taken:
            seen[name] += 1
            item = fitted(name + "~" + str(seen[name]))
        taken.add(item)
        section["item"] = item


def fitted(name):
    """A name the register takes: on one line, a control character as `?`, and a name over NAME_LIMIT characters
    cut, with a digest of the whole at its end. The register counts as Java does, a character beyond the basic
    plane as two."""
    whole = name
    name = "".join("?" if ord(c) < 0x20 or 0x7f <= ord(c) < 0xa0 or c in "\u2028\u2029" else c
                   for c in one_line(name))
    if len(name.encode("utf-16-le", errors="replace")) // 2 <= NAME_LIMIT:
        return name
    kept, size = [], 0
    for c in name:
        size += 2 if ord(c) > 0xffff else 1
        if size > NAME_LIMIT - 17:
            break
        kept.append(c)
    return "".join(kept) + "~" + hashlib.sha256(whole.encode("utf-8", errors="replace")).hexdigest()[:16]


def item_digest(root, section, version):
    """What a verdict about the section sees, as 16 hex digits: the version of the question, the document and the
    heading, which part it is, the text, where the prompt says to start, and every file named there, by path and
    content. Not its lines in the document, so a section that only moved keeps its digest, and not the frame of the
    run. A file that cannot be read counts by its path alone."""
    digest = hashlib.sha256(("verdict_version " + str(version) + "\n").encode("utf-8"))
    for seen in (section["doc"], section["heading"], str(section["part"]) + "/" + str(section["parts"]),
                 section["where"], section["text"]):
        digest.update(seen.encode("utf-8", errors="replace") + b"\0")
    for rel in section["files"]:
        digest.update(rel.encode("utf-8", errors="replace") + b"\0")
        try:
            with open(os.path.join(root, rel), "rb") as f:
                digest.update(f.read())
        except OSError:
            pass
        digest.update(b"\0")
    return digest.hexdigest()[:16]


JUDGED_RULE = "docs-contradict-code"
QUOTED = re.compile(r'the document: "(.*?)(?:"; the code, |' + known.CUT + r'$|$)')


def attach_known(audit, rows):
    """Sorts what the register knows to the sections, and returns what belongs to none.

    A contradiction's subject is `<doc>#<heading>:<digest of the quoted sentence>`, so it belongs to the section with
    that heading: to the parts of it that hold the quoted sentence, as far as the register shows it, and to every
    part when none does. Each part gets the open ones to answer for and the decided ones not to report again. An
    open finding whose section is no longer in the document is returned: it cannot hold as it was stated,
    so the report names it as gone."""
    rows = [row for row in rows if row["rule"] == JUDGED_RULE]
    of_item = {id(item): [] for item in audit.every}
    gone = []
    for row in rows:
        heading = next(((doc, heading) for doc, heading in audit.headings
                        if re.fullmatch(re.escape(known.shown(doc + "#" + heading + ":")) + "[0-9a-f]{12}",
                                        row["subject"])), None)
        if heading is None:
            # Without a section list nothing was measured, and a cut subject could be any section's
            # and a decided finding stays decided until its review date brings it back as one that is asked about
            if audit.headings and not known.is_cut(row) and row["status"] in known.ASKED:
                gone.append({"ruleKey": row["rule"], "subject": row["subject"]})
            elif row["status"] in known.ASKED:
                # No section carries it, and nothing says that it is gone: for a person, and for the gate
                audit.known_unowned.append({"ruleKey": row["rule"], "subject": row["subject"],
                                            "severity": row["severity"]})
            continue
        parts = [item for item in audit.every if (item["doc"], item["heading"]) == heading]
        quoted = QUOTED.search(row["evidence"])
        sentence = quoted.group(1).strip() if quoted else ""
        holding = [item for item in parts if sentence and sentence in known.shown(item["text"])]
        for item in holding or parts:
            of_item[id(item)].append(row)
    for item in audit.every:
        known.attach(item, of_item[id(item)])
    return gone


def main(argv):
    parser = argparse.ArgumentParser(prog="facts.py", description="The facts of the functionality self-audit.")
    parser.add_argument("--known", help="a file with the text of {{ audit_findings }}: what the register knows")
    parser.add_argument("--items", help="a file with the text of {{ audit_items }}: the items the register knows")
    parser.add_argument("--root", required=True, help="the workspace: a Traceworks checkout")
    parser.add_argument("--rules", required=True, help="the rule file")
    parser.add_argument("--out", required=True, help="the directory facts.json and sections.jsonl go to")
    args = parser.parse_args(argv)

    audit = Audit(os.path.abspath(args.root), read_rules(args.rules))
    audit.run()
    known_text = (read_text(args.known) or "") if args.known else ""
    known_gone = attach_known(audit, known.parse(known_text))
    audit.choose(register_items.parse(read_text(args.items) or "") if args.items else {})

    with open(os.path.join(args.out, "sections.jsonl"), "w", encoding="utf-8") as f:
        for item in audit.sections:
            f.write(json.dumps(item, ensure_ascii=True) + "\n")
    not_measured = [{"what": what, "why": why} for what, why in audit.not_measured]
    with open(os.path.join(args.out, "facts.json"), "w", encoding="utf-8") as f:
        json.dump({"findings": audit.findings, "not_measured": not_measured, "counts": audit.counts,
                   "sections_cut": audit.sections_cut, "items": audit.items, "known_gone": known_gone,
                   "known_unowned": audit.known_unowned,
                   # How many findings {{ audit_findings }} left out: nothing is concluded from a row not there
                   "known_missing": known.missing(known_text),
                   # How many of them are open by the block's own count
                   "known_missing_open": known.missing_open(known_text)}, f,
                  indent=1, sort_keys=True)
        f.write("\n")

    print("## FACT SHEET functionality")
    for name in sorted(audit.counts):
        print("FACT func_" + name + ": " + str(audit.counts[name]))
    print("FACT func_items: " + str(len(audit.items)))
    print("FACT func_items_settled: " + str(sum(1 for item in audit.items if item["settled"])))
    print("FACT func_findings: " + str(len(audit.findings)))
    print("FACT func_not_measured: " + str(len(audit.not_measured)))
    for finding in audit.findings:
        print("HIT " + finding["evidence"].split(": ", 1)[0] + ": " + one_line(finding["subject"]) + " ("
              + one_line(finding["title"]) + ")")
    for item in audit.sections:
        print("HIT func_section: " + item["id"] + " (" + item["doc"] + ", lines " + str(item["start"]) + " to "
              + str(item["end"]) + ")")
    for cut in audit.sections_cut:
        print("NOT MEASURED: the section " + one_line(cut) + " (beyond max_sections of the rule file)")
    for what, why in audit.not_measured:
        print("NOT MEASURED: " + one_line(what) + " (" + one_line(why) + ")")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
