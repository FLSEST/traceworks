"""The items of the audit as the register knows them, as a run gets them in {{ audit_items }}, read back into rows.

An item is what one verdict is about: a class in the testing package, a section of the documentation in the
functionality package. The register keeps for each item the digest of what its last verdict saw, and says whether it
is open, not judged, decided or clean (docs/registers.md, "Items"). The facts step reads the rows to leave out an
item that is settled and has not changed since.

A row is `state | verdict digest | item`, with `-` for no verdict. The register writes every name on one line, with
blanks collapsed and `|` masked as `¦`; a name is compared here the way the register shows it. Anything that is no
row gives none: the head of the block, the text of a first run, and the literal placeholder where nothing sets the
variable (an older Traceworks). Then no item is known and every item is judged.

The same file is in the functionality and the testing package: each package carries what it runs. Python 3 standard
library only.
"""
import re

STATES = ("open", "not judged", "decided", "clean")
SETTLED = ("decided", "clean")


def parse(audit_items):
    """The rows of the block by the name the register shows: {"state": ..., "digest": ...}."""
    rows = {}
    for line in audit_items.split("\n"):
        cells = line.strip().split(" | ")
        if len(cells) != 3 or cells[0] not in STATES:
            continue
        rows[cells[2]] = {"state": cells[0], "digest": "" if cells[1] == "-" else cells[1]}
    return rows


def shown(name):
    """A name as the register shows it in a row: blanks collapsed, and `|` as the masked `¦`."""
    return re.sub(r"\s+", " ", name.strip()).replace("|", "¦")
