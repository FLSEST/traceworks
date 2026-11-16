# Testing audit, rating again

The same question and the same measurement as the [testing audit](../testing/README.md): would the tests notice
when the code changes, answered by mutation testing with [PIT](https://pitest.org). What differs is the rating. The
testing audit rates a Java package from zero whenever it changed, and a model that rates the same gap twice does not
always say the same: three full runs on the same code gave 8, 14 and 11 `HIGH`. This audit rates again **from the
earlier verdict**: a rating stays until something that a script can check speaks against it.

```
traceworks images build examples/audit/testing-again
traceworks run examples/audit/testing-again
traceworks findings audit.testing-again
```

| Step | Image | Network | What it does |
|---|---|---|---|
| `fetch` | `./images/maven` | yes | as in the testing audit: fills a Maven repository in the tool's cache folder |
| `measure` | `./images/maven` | no | as in the testing audit: runs PIT offline on a copy of the sources |
| `findings` | built-in `shell` | no | maps PIT's CSV to one entry per method, and adds from the register folder of the run (`{{ register_dir }}`) the earlier verdict of each method: its severity, its reason, the kinds of its gaps then, and what a person decided |
| `rate` | `claude` | provider only | AI calls of at most twenty methods, only for methods that changed or were never rated. A changed method comes with its earlier severity and reason and with what changed |
| `report` | built-in `shell` | no | takes the answers, looks each ground up in the workspace, and writes the gate, the findings and the list of methods |

## How a method is rated

- **Not changed: not asked.** The methods are the items of the register (docs/registers.md, "Items"). The digest of
  a method is its name, the mutators of its unnoticed changes without line numbers, and the decision on it. A method
  with the digest of its last verdict keeps its severity and its reason, and no model sees it.
- **Changed: asked, from the earlier verdict.** The model gets the earlier severity and reason, and which mutators
  were added or went away. It may keep the rating; that needs nothing. To change it, the answer gives a ground, and
  `scripts/report.sh` looks it up in the workspace:

  | Change | Ground in the answer | What the script checks |
  |---|---|---|
  | lower | `"ground": {"test": "<TestClass>#<testMethod>"}`, the test that now notices it | a file `<TestClass>.java` below a `src/test` folder holds the method |
  | lower | `"ground": {"line": "<path>:<line>"}`, the caller or reader that shows nothing breaks | the file is in the workspace, and the line names the method |
  | raise | `"ground": {"line": "<path>:<line>"}`, the place that breaks | the file is in the workspace, and the line is not blank |

  A change whose ground holds is taken, and the finding keeps the ground (`Ground: ...` in its description). Any
  other change is not taken: the earlier severity stays, and the report lists the change with the reason. Nothing is
  taken on the model's word alone.
- **Never rated: rated fresh,** as in the testing audit. Without an answer the method is `MEDIUM` from ten unnoticed
  changes on, else `LOW`, and is asked about again.
- **Rated low and changed later.** A method rated `low` is no finding, so the register keeps no reason for it. Its
  earlier rating is `LOW`; when it was a finding before, `resolved.jsonl` of the register folder still has that
  rating and reason, and the model gets both. Raising it needs a ground like any other change.
- **A person's decision** is part of the digest: a method somebody accepted or dismissed is asked about once more,
  with the note, and then rests.

The report says how many methods kept their rating without being asked, how many were asked, how many changes were
taken and how many were not. The findings, the gate and the inputs are those of the testing audit; read its README
for them.

## What a ground is worth

A ground says where to look. The script checks that the test or the line exists, not that it proves the rating:
a line that names the method may be a comment. That is on purpose the small step: a rating no longer moves because a
model worded a close call differently, and every change that was taken names a place a person can open. Whether the
place holds is the person's to judge, in the triage of the finding.

## What is shared with the testing audit, and what is a copy

Three files are copies of the testing audit's, byte for byte: `images/maven/Dockerfile`, `scripts/pit.sh` and
`scripts/methods.sh` (the mapping of PIT's CSV). Change them there and copy them here. They are copies because a run
resolves `./images/` against the package it was started with: a child workflow of another package may not name a
package image (docs/workflow-format.md), and it runs with the paths of the run, so `/package` is not its own
package. The cache folder belongs to the image of one package too, so this audit measures the sources again even
when the testing audit measured them.

`scripts/findings.sh` and `scripts/report.sh` are this audit's own. The register is its own as well
(`audit.testing-again`): a register belongs to one workflow, and the two audits rate differently, so each keeps its
own findings and decisions.

## Limits

- A register holds at most 1000 items in a report, so at most 1000 methods with an unnoticed change.
- The first run rates every method fresh. From then on the ratings are as stable as the code.
- The kinds of a method's gaps are kept with its finding. For a method rated `low` they are not, so the model is told
  that the method changed, not how.
