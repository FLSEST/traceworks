# Testing audit

Would the tests notice when the code changes? This audit answers with mutation testing: [PIT](https://pitest.org)
changes the compiled code in one place at a time and runs the tests that reach the line. A change no test detects is a
gap in the tests.

```
traceworks images build examples/audit/testing
traceworks run examples/audit/testing
traceworks findings audit.testing
```

| Step | Image | Network | What it does |
|---|---|---|---|
| `fetch` | `./images/maven` | yes | builds a copy of the sources and fills a Maven repository in the tool's cache folder |
| `measure` | `./images/maven` | no | builds the copy again offline and runs PIT on each module; the tests of the project run here |
| `findings` | built-in `shell` | no | maps PIT's CSV to one entry per method and the fact sheet, with what a person decided on it, with `awk` and `jq` |
| `rate` | `claude` | provider only | AI calls per Java package, twenty methods a call: reads the code and rates each method's gap `high`, `medium` or `low` with one sentence. A package whose gaps are what they were at its last rating is left out |
| `report` | built-in `shell` | no | writes the gate, the findings and the list of packages; a rating only sets the severity of a method that was measured |

- **Findings:** one per method (`class#method`) with a change that went unnoticed (`undetected-mutants`) and a
  rating above `low`, so a decision in the register covers that method and nothing else. The rating step gets each
  decision with its note. A method rated `low` is counted in the report and is no finding: the register holds what a
  person should decide on. The description names each place as `method:line` and PIT's mutator.
- **The gate** counts what is left to decide: RED with a `HIGH` finding nobody decided on, YELLOW with any other
  such finding, else GREEN. A decision covers the severity it was made on (docs/registers.md, "What a decision
  covers"): a method rated higher since counts again, unless the register kept the decision there
  (`decision_on_higher_severity`). The report sees the decisions the register held before the run, so what the
  register makes of this report (a decision it reopens, a review that is due) shows in the gate of the next run.
  The model sets the severity and so has a say in the gate; it can add no finding, and a method it rates `low` is
  none.
- **What is rated again:** the Java packages are the items of the register (docs/registers.md, "Items"). The digest
  of a package is its methods with the kinds of their unnoticed changes and the decisions on them, without line
  numbers. A package with the digest of its last rating is not rated: its methods keep the severity and the reason
  the register holds, and the others are `low`. A run on unchanged code after a full one makes no AI call. A new
  test, a changed method or a decision rates the one package again. A method without an answer in a package that was
  to be rated keeps its rating, or is `MEDIUM` from ten unnoticed changes on, else `LOW`, and its package is rated
  again next time.
- **The limit:** the next run learns the ratings from `{{ audit_findings }}`, which holds 250 rows and 60 000 bytes.
  The report shortens the reason it keeps with each finding so that all rows fit (the full reason is in the
  description of the run that rated). From about 190 findings above `low` on the list is cut, and a list that was
  cut rates every package again: decide on findings, or cover them with tests, to get below.
- **Inputs:** `modules` (a comma list of Maven modules), `classes` (a PIT glob of the classes to change and of the
  tests to run) and `threads`. A run with other `modules` or `classes` states another snapshot, so the register
  resolves what the smaller run did not look at: keep them the same from run to run.
- **Run time:** PIT runs the tests once per change. A whole project takes hours; the operator's `container_limits` in
  `traceworks.json` say how many CPUs the step gets.
- **The project is not changed.** PIT needs its JUnit 5 plugin in the pom; `scripts/pit.sh` adds it to the copy.
- **Measured once per state of the sources:** the result is kept in the cache folder under a digest of the poms, the
  `src` folders and `classes`, and a run on the same sources takes it from there (the step says so).
- **Left out:** a test that is red in the image without any change (it needs a program the image lacks) does not
  count: add the program to `images/maven/Dockerfile`. PIT runs only the tests of the module it changes.
- The Maven repository in the cache folder (`<home>/cache/tools/maven`) is written by the step with network and read
  by the step without.
