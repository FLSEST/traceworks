"""Reads Java sources as text, for the testing self-audit: a small scanner, no parser.

The scanner blanks comments and the contents of string and character literals, so a word in a comment or in a string
is never taken for code. Everything else works on that masked text with brace matching and regular expressions:
the types of a file, its method declarations, the calls of a body, and the declaration an annotation belongs to.
Positions and line numbers of the masked text are those of the file.

Also here, because both scripts need them: the rule file and the helpers that make text safe to print.
"""

import bisect
import re

KEYWORDS = frozenset("""
abstract assert boolean break byte case catch char class const continue default do double else enum extends final
finally float for goto if implements import instanceof int interface long native new package private protected public
return short static strictfp super switch synchronized this throw throws transient try void volatile while var yield
record sealed permits non true false null
""".split())

# Words that stand before a call or a block, never before the name of a method declaration
_NOT_BEFORE_DECLARATION = frozenset("""
new return else throw record class interface enum case yield assert instanceof do extends implements throws import
package goto
""".split())

_TOKEN = re.compile(
    r'//[^\n]*'
    r'|/\*.*?(?:\*/|\Z)'
    r'|"""(?:\\.|[^\\])*?(?:"""|\Z)'
    r'|"(?:\\.|[^"\\\n])*(?:"|(?=\n)|\Z)'
    r"|'(?:\\.|[^'\\\n])*(?:'|(?=\n)|\Z)",
    re.DOTALL)
_NOT_NEWLINE = re.compile(r'[^\n]')
_TYPE = re.compile(r'(?<![\w.@])(class|interface|enum|record)\s+([A-Za-z_]\w*)')
_CALL = re.compile(r'([A-Za-z_]\w*)\s*\(')
_BODY_AFTER_PARAMETERS = re.compile(r'\s*(?:throws\s+[\w.\s,<>?]+?)?\s*\{')
_ANNOTATION = re.compile(r'@([A-Za-z_][\w.]*)')
_HEADER_END = re.compile(r'[({;=]')
_PACKAGE = re.compile(r'^\s*package\s+([\w.]+)\s*;', re.MULTILINE)
_PLAIN_IMPORT = re.compile(r'^[ \t]*import\s+(?!static\b)[^;]*;', re.MULTILINE)
_STATIC_IMPORT = re.compile(r'^[ \t]*import\s+static\s+([\w.]+?)\.(?:\w+|\*)\s*;', re.MULTILINE)
_BRANCH = re.compile(r'\b(?:if|for|while|case|catch)\b|&&|\|\||\?(?!\s*(?:[>,)\]]|extends\b|super\b))')
_IDENTIFIER = re.compile(r'[A-Za-z_]\w*')


def mask(text):
    """The text with comments and literal contents blanked, and the string literals as (start, end, value)."""
    strings = []

    def blank(match):
        token = match.group()
        if token.startswith('/'):
            return _NOT_NEWLINE.sub(' ', token)
        quote = '"""' if token.startswith('"""') else token[0]
        closed = len(token) >= 2 * len(quote) and token.endswith(quote)
        inner = token[len(quote):len(token) - len(quote)] if closed else token[len(quote):]
        if quote != "'":
            strings.append((match.start(), match.end(), inner))
        return quote[0] + _NOT_NEWLINE.sub(' ', token[1:len(token) - 1]) + (quote[0] if len(token) > 1 else '')

    code = _TOKEN.sub(blank, text)
    return code, strings


def _pairs(code):
    """Each opening brace and parenthesis with its closing one; None when they do not match up."""
    stack = []
    pairs = {}
    for match in re.finditer(r'[(){}]', code):
        char = match.group()
        if char in '({':
            stack.append((char, match.start()))
            continue
        if not stack:
            return None
        opened, at = stack.pop()
        if (opened == '(') != (char == ')'):
            return None
        pairs[at] = match.start()
    return None if stack else pairs


class Type:
    def __init__(self, kind, name, start, body_open, body_close, extends):
        self.kind, self.name, self.start = kind, name, start
        self.body_open, self.body_close, self.extends = body_open, body_close, extends
        self.parent = None

    def chain(self):
        return (self.parent.chain() if self.parent else []) + [self.name]


class Method:
    def __init__(self, source, name, start, params_open, body_open, body_close):
        self.source, self.name, self.start = source, name, start
        self.params_open, self.body_open, self.body_close = params_open, body_open, body_close


class JavaFile:
    """One Java file: its masked code, types and method declarations. `problem` says why it could not be read."""

    def __init__(self, rel, text):
        self.rel = rel
        self.text = text
        self.problem = None
        self.code, self.strings = mask(text)
        self._line_starts = [0] + [m.end() for m in re.finditer(r'\n', text)]
        found = _PACKAGE.search(self.code)
        self.package = found.group(1) if found else ''
        self.types, self.methods = [], []
        self.pairs = _pairs(self.code)
        if self.pairs is None:
            self.problem = 'its braces and parentheses do not match up'
            return
        self._find_types()
        self._find_methods()
        self._by_params = {m.params_open: m for m in self.methods}
        self._by_name = {}
        for method in self.methods:
            self._by_name.setdefault(method.name, []).append(method)

    def line_of(self, position):
        return bisect.bisect_right(self._line_starts, position)

    def _find_types(self):
        code = self.code
        for match in _TYPE.finditer(code):
            kind, name = match.group(1), match.group(2)
            if name in KEYWORDS:
                continue
            at = match.end()
            if kind == 'record':
                after = code[at:at + 200].lstrip()[:1]
                if after not in ('(', '<'):
                    continue   # a variable called record
            body_open = None
            while at < len(code):
                char = code[at]
                if char == '(':
                    at = self.pairs[at]
                elif char == '{':
                    body_open = at
                    break
                elif char in ';=)}':
                    break
                at += 1
            if body_open is None:
                continue
            header = code[match.end():body_open]
            extends = re.search(r'\bextends\s+([\w.]+)', header) if kind == 'class' else None
            self.types.append(Type(kind, name, match.start(), body_open, self.pairs[body_open],
                                   extends.group(1).rsplit('.', 1)[-1] if extends else None))
        for inner in self.types:
            for outer in self.types:
                if outer is not inner and outer.body_open < inner.start < outer.body_close:
                    if inner.parent is None or outer.body_open > inner.parent.body_open:
                        inner.parent = outer

    def _find_methods(self):
        code = self.code
        for match in _CALL.finditer(code):
            name = match.group(1)
            if name in KEYWORDS:
                continue
            params_open = match.end() - 1
            body = _BODY_AFTER_PARAMETERS.match(code, self.pairs[params_open] + 1)
            if not body:
                continue
            at = match.start() - 1
            while at >= 0 and code[at].isspace():
                at -= 1
            if at >= 0:
                char = code[at]
                if char.isalnum() or char == '_':
                    word_end = at + 1
                    while at >= 0 and (code[at].isalnum() or code[at] == '_'):
                        at -= 1
                    if code[at + 1:word_end] in _NOT_BEFORE_DECLARATION:
                        continue
                elif char not in '>]{};)':
                    continue
            body_open = body.end() - 1
            self.methods.append(Method(self, name, match.start(), params_open, body_open, self.pairs[body_open]))

    def top_level_types(self):
        return [t for t in self.types if t.parent is None]

    def enclosing_type(self, position):
        best = None
        for candidate in self.types:
            if candidate.body_open < position < candidate.body_close:
                if best is None or candidate.body_open > best.body_open:
                    best = candidate
        return best

    def qualified(self, type_):
        """The name of a type with its package and the types around it, such as p.Outer.Inner."""
        names = '.'.join(type_.chain())
        return self.package + '.' + names if self.package else names

    def enclosing_method(self, position):
        best = None
        for method in self.methods:
            if method.body_open < position < method.body_close:
                if best is None or method.body_open > best.body_open:
                    best = method
        return best

    def methods_named(self, name):
        return self._by_name.get(name, [])

    def calls(self, start, end):
        """The calls between two positions as (receiver, name): the receiver is None for a plain call, an
        identifier for `receiver.name(`, and '?' when the call is made on the value of an expression."""
        code = self.code
        found = []
        for match in _CALL.finditer(code, start, end):
            name = match.group(1)
            if name in KEYWORDS:
                continue
            at = match.start() - 1
            while at >= 0 and code[at].isspace():
                at -= 1
            receiver = None
            if at >= 0 and code[at] == '.':
                at -= 1
                while at >= 0 and code[at].isspace():
                    at -= 1
                word_end = at + 1
                while at >= 0 and (code[at].isalnum() or code[at] == '_'):
                    at -= 1
                receiver = code[at + 1:word_end] or '?'
            elif at >= 0 and (code[at].isalnum() or code[at] == '_'):
                word_end = at + 1
                while at >= 0 and (code[at].isalnum() or code[at] == '_'):
                    at -= 1
                if code[at + 1:word_end] == 'new':
                    continue   # a constructor
            found.append((receiver, name))
        return found

    def annotated(self, names):
        """Every use of one of the annotations: (annotation, position, its arguments' range or None, the kind of
        what it stands on ('type', 'method' or None), and that Type or Method)."""
        code = self.code
        found = []
        for match in _ANNOTATION.finditer(code):
            simple = match.group(1).rsplit('.', 1)[-1]
            if simple not in names or (match.start() > 0 and (code[match.start() - 1].isalnum()
                                                              or code[match.start() - 1] in '_"')):
                continue
            at, arguments = self._after_arguments(match.end())
            while True:
                more = _ANNOTATION.match(code, self._skip_space(at))
                if not more or more.group(1) == 'interface':
                    break
                at, _ = self._after_arguments(more.end())
            end = _HEADER_END.search(code, at)
            kind, target = None, None
            if end:
                declared = _TYPE.search(code, at, end.start())
                if declared:
                    target = next((t for t in self.types if t.start == declared.start()), None)
                    kind = 'type' if target else None
                elif end.group() == '(':
                    target = self._by_params.get(end.start())
                    kind = 'method' if target else None
            found.append((simple, match.start(), arguments, kind, target))
        return found

    def _skip_space(self, at):
        while at < len(self.code) and self.code[at].isspace():
            at += 1
        return at

    def _after_arguments(self, at):
        start = self._skip_space(at)
        if start < len(self.code) and self.code[start] == '(':
            return self.pairs[start] + 1, (start, self.pairs[start])
        return at, None

    def strings_between(self, start, end):
        return [value for (from_, to, value) in self.strings if start <= from_ and to <= end + 1]

    def first_string_argument(self, params_open):
        """The first string literal among the arguments of a call, not inside a nested call; None without one."""
        close = self.pairs[params_open]
        for from_, to, value in self.strings:
            if from_ <= params_open:
                continue
            if from_ >= close:
                break
            depth = 0
            for char in self.code[params_open + 1:from_]:
                depth += char in '({'
                depth -= char in ')}'
            if depth == 0:
                return value
        return None

    def ncloc(self, start, end):
        """The lines between two positions that hold code."""
        return sum(1 for line in self.code[start:end + 1].split('\n') if line.strip())

    def branches(self, start, end):
        return len(_BRANCH.findall(self.code, start, end))

    def code_without_imports(self):
        return _PLAIN_IMPORT.sub('', _PACKAGE.sub('', self.code, count=1))

    def static_import_types(self):
        return {name.rsplit('.', 1)[-1] for name in _STATIC_IMPORT.findall(self.code)}

    def identifiers(self):
        return _IDENTIFIER.findall(self.code_without_imports())


class Rules:
    """The rule file: one `name value ...` per line, `#` starts a comment line. A name may occur several times."""

    def __init__(self, path):
        self.path = path
        self.entries = {}
        with open(path, encoding='utf-8') as handle:
            for number, line in enumerate(handle, 1):
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                parts = line.split(None, 1)
                if len(parts) < 2:
                    raise SystemExit(f'{path}:{number}: "{parts[0]}" has no value')
                self.entries.setdefault(parts[0], []).append((number, parts[1].strip()))

    def texts(self, name):
        """Every value of the name, each the rest of its line."""
        return [value for _, value in self.entries.get(name, [])]

    def words(self, name):
        """Every word of every line of the name."""
        return [word for value in self.texts(name) for word in value.split()]

    def text(self, name):
        values = self.entries.get(name)
        if not values:
            raise SystemExit(f'{self.path}: "{name}" is missing')
        if len(values) > 1:
            raise SystemExit(f'{self.path}:{values[1][0]}: "{name}" is given twice')
        return values[0][1]

    def number(self, name):
        value = self.text(name)
        try:
            return float(value) if '.' in value else int(value)
        except ValueError:
            raise SystemExit(f'{self.path}: "{name}" must be a number, not "{value}"')

    def pattern(self, name):
        """The words of the name as one regular expression that matches a whole word."""
        words = self.words(name)
        if not words:
            raise SystemExit(f'{self.path}: "{name}" is missing')
        try:
            return re.compile('(?:' + '|'.join(words) + r')\Z')
        except re.error as error:
            raise SystemExit(f'{self.path}: "{name}" is no regular expression: {error}')

    def layers(self):
        """The `layer <weight> <prefix>` lines as (prefix, weight), the longest prefix first."""
        layers = []
        for number, value in self.entries.get('layer', []):
            parts = value.split()
            try:
                layers.append((parts[1], float(parts[0])))
            except (IndexError, ValueError):
                raise SystemExit(f'{self.path}:{number}: a layer is "layer <weight> <package or class>"')
        return sorted(layers, key=lambda layer: -len(layer[0]))

    def severity(self, rule):
        for number, value in self.entries.get('severity', []):
            parts = value.split()
            if len(parts) != 2 or parts[1] not in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'):
                raise SystemExit(f'{self.path}:{number}: a severity is "severity <rule> LOW|MEDIUM|HIGH|CRITICAL"')
            if parts[0] == rule:
                return parts[1]
        raise SystemExit(f'{self.path}: no severity for "{rule}"')


def layer_of(qualified_name, layers):
    """The (prefix, weight) of the longest prefix that names the class or a package around it; ('', 1.0) else."""
    for prefix, weight in layers:
        if qualified_name == prefix or qualified_name.startswith(prefix + '.'):
            return prefix, weight
    return '', 1.0


_CONTROL = re.compile(r'[\x00-\x1f\x7f-\x9f  ]')


def one_line(text, limit=400):
    """Text from the workspace or from a model on one line: no control character, no start of an HTML comment, cut
    at the limit. A line of the report or of the fact sheet can then never be started by such text."""
    text = ' '.join(_CONTROL.sub(' ', str(text)).split()).replace('<!--', '<! --')
    return text if len(text) <= limit else text[:limit - 3] + '...'


def printable(path):
    """Whether a path can be named on a line of its own: no control character in it."""
    return not _CONTROL.search(path)
