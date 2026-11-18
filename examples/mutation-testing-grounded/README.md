# Mutation testing, grounded

This is the variant of [`mutation-testing`](../mutation-testing/README.md) in which a rating moves only on a ground:
a test or a line of code, named in the model's answer, that a script finds in the workspace. Hence the name. The
question and the measurement are the same: would the tests notice when the code changes, answered by mutation testing
with [PIT](https://pitest.org). What differs is the rating. `mutation-testing` rates a Java package from zero whenever
it changed, and a model that rates the same gap twice does not always say the same: three full runs on the same code
gave 8, 14 and 11 `HIGH`. This audit rates again **from the earlier verdict**: a method that did not change is not
rated again, and the rating of a changed method moves only when the answer names a place in the workspace that a
script finds.

```
traceworks images build examples/mutation-testing-grounded
traceworks run examples/mutation-testing-grounded --workspace ~/src/my-project \
    --input modules=core,service --input classes='com.example.*'
traceworks findings mutation-testing-grounded
```

The inputs, and what the project needs, are those of `mutation-testing` ("What the project needs" in its README): a
run without `modules` or `classes` stops in its first step and says what to pass. Traceworks runs it on itself from
the root of its repository with
`--input modules=traceworks-cli,traceworks-adapters,traceworks-core --input classes='traceworks.*'`.

| Step | Image | Network | What it does |
|---|---|---|---|
| `fetch` | `./images/maven` | yes | as in `mutation-testing`: fills a Maven repository in the tool's cache folder |
| `measure` | `./images/maven` | no | as in `mutation-testing`: runs PIT offline on a copy of the sources |
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
  | lower | `"ground": {"test": "<TestClass>#<testMethod>"}`, the test that now notices it | a file `<TestClass>.java` below a `src/test` folder has a line of code that declares the method, `void <testMethod>(`, and the file holds the simple name of the rated method's class |
  | lower | `"ground": {"line": "<path>:<line>"}`, the caller or reader that shows nothing breaks | the file is a `.java` file below a `src/main` folder of the workspace and not the file of the method's own class, and the line is a line of code that calls or references the method: `<name>(` or `::<name>`, for a constructor `new <Class>` or `<Class>::new` |
  | raise | `"ground": {"line": "<path>:<line>"}`, the place that breaks | the file is a `.java` file below a `src/main` folder of the workspace, and the line is a line of code |

  A line of code is not blank and does not begin with `//`, `*` or `/*`. Nothing below a `target` folder counts,
  and nothing outside the workspace. Declaration and call are told apart by the file, not by the line: a line in
  the file of the method's own class is never a caller, also when it calls the method.
- **A change whose ground holds is taken,** and the finding keeps the ground (`Ground: ...` in its description).
- **A lowering that is not taken is final:** the higher severity stands, the report lists it with the reason, and
  the method is not asked about until it changes again.
- **A raise that is not taken is asked about again** in the next run, and so is a method without an answer. The
  method keeps its severity for now and the report lists it. A method that stays `LOW` is no finding, so nobody
  would look at it; a mistyped path must not end the question.
- **Never rated: rated fresh,** as in `mutation-testing`. Without an answer the method is `MEDIUM` from ten unnoticed
  changes on, else `LOW`, and is asked about again.
- **Rated low and changed later.** A method rated `low` is no finding, so the register keeps no reason for it. Its
  earlier rating is `LOW`. When it was a finding before, the register folder still has that rating and reason
  (`resolved.jsonl`, or `findings.jsonl` for a finding somebody decided on, which the register keeps for three
  reports after the report left it out), and the model gets both. Raising it needs a ground like any other change.
- **A person's decision** is part of the digest: a method somebody accepted or dismissed is asked about once more,
  with the note. After that it is asked about when its gaps change, like every method.

The report says how many methods kept their rating without being asked, how many were asked, how many changes were
taken and how many were not. The findings, the gate and the inputs are those of `mutation-testing`; read its README
for them.

## What a ground is worth

A ground that holds exists and names the method. That is all a script can tell: it does not show that the rating
is right. A test that names the class need not test the method, a line that calls the method need not show that
nothing breaks, and any line of code passes for a raise. What the rule buys is smaller: a rating does not move
because a model worded a close call differently, and every change that was taken names a place a person can open.
Whether the place holds is the person's to judge, in the triage of the finding. A test method that is not `void`,
a caller in the method's own class and a caller in Kotlin or Groovy are grounds the script does not take.

## What is shared with `mutation-testing`, and what is a copy

Three files are copies of those of `mutation-testing`, byte for byte: `images/maven/Dockerfile`, `scripts/pit.sh` and
`scripts/methods.sh` (the mapping of PIT's CSV). Change them there and copy them here. They are copies because a run
resolves `./images/` against the package it was started with: a child workflow of another package may not name a
package image (docs/workflow-format.md), and it runs with the paths of the run, so `/package` is not its own
package. The cache folder belongs to the image of one package too, so this audit measures the sources again even
when `mutation-testing` measured them.

`scripts/findings.sh` and `scripts/report.sh` are this audit's own. The register is its own as well
(`mutation-testing-grounded`): a register belongs to one workflow, and the two audits rate differently, so each
keeps its own findings and decisions.

## Two real runs

The audit ran twice on Traceworks itself on 2026-11-18, one run after the other on the same commit, with
`modules=traceworks-cli,traceworks-adapters,traceworks-core`, `classes='traceworks.*'`, Docker 29.1.3, PIT 1.30.2
and `claude-opus-5-5` as the model.

| | First run | Second run |
|---|---|---|
| Changes PIT made | 16029 | 16029 |
| Changes no test noticed | 1600, in 740 methods | 1600, in 741 methods |
| Methods the model was asked about | 740 | 24 |
| Methods that kept their rating without being asked | 0 | 717 |
| Ratings that moved | no earlier rating | 0 |
| Findings in the register (rated above low) | 175 | 187, 19 of them high |
| Time for PIT | 4 h 34 min | 4 h 10 min |
| Time for the model | 1 h 25 min | 2 min |

What the two runs show: without a change of the code no rating moved, and the second run asked the model about
24 methods instead of 740.

What they also show:

- **22 methods had no rating after the first run.** The model was asked about them and the report held no rating
  for them (20 of one package, 2 of another). They were not judged, and the second run asked again. The 12
  further findings of the second run are among these methods and the one method that was new.
- **PIT does not measure the same twice.** 10 of the 16029 changes had another result in the second run, all of
  them a time-out or a memory error on one side. For two methods that changed the gaps, so they were asked about
  again: one kept its rating, one was new.
- **The second run measured again,** although the sources of the project were the same. The digest of the kept
  result counts every file below a `src` folder in the workspace, and this workspace held other checkouts of the
  repository (git worktrees in a folder git ignores), one of which changed between the runs. See the limits of
  `mutation-testing`.
- **Nobody has checked the findings yet.** The 187 findings are what a model rated above low. How many of them a
  person would call a gap is not known.

## Limits

- **At most 1000 methods with an unnoticed change.** A register takes at most 1000 items in a report and rejects a
  larger one as a whole, so nothing would be kept. The `findings` step fails before any call of a model and says
  so: narrow `classes` or `modules`, and keep them the same from run to run.
- The first run rates every method fresh. From then on a rating moves only for a method that changed, and then
  only on a ground; it is not more right than the first rating was.
- The kinds of a method's gaps are kept with its finding. For a method rated `low` they are not, so the model is told
  that the method changed, not how.
