# Mutation testing

Would the tests notice when the code changes? This audit answers with mutation testing: [PIT](https://pitest.org)
changes the compiled code in one place at a time and runs the tests that reach the line. A change no test detects is a
gap in the tests.

```
traceworks images build examples/mutation-testing
traceworks run examples/mutation-testing
traceworks findings mutation-testing
```

| Step | Image | Network | What it does |
|---|---|---|---|
| `fetch` | `./images/maven` | yes | builds a copy of the sources and fills a Maven repository in the tool's cache folder |
| `measure` | `./images/maven` | no | builds the copy again offline and runs PIT on each module; the tests of the project run here |
| `findings` | built-in `shell` | no | maps PIT's CSV to one entry per method and the fact sheet with `awk` and `jq`, and adds from the register folder of the run what the register knew: the last rating of each method with its reason, and what a person decided on it |
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
- **What the next run knows:** the ratings, their reasons and the decisions come from the register folder of the run
  (`{{ register_dir }}`, docs/registers.md, "The register folder of a run"): `findings.jsonl` holds every finding
  whole, so no number of findings makes a run forget. The reason of a rating is the first line of the finding's
  description (`Rating: ...`); the evidence is the measured fact alone. A method rated `low` is no finding, so the
  register keeps no reason for it: that is enough here, because a package that changed is rated from zero.
- **A second audit rates again from the earlier verdict:** this audit rates a changed package from zero, and a
  model does not rate the same gap the same way every time.
  [`mutation-testing-grounded`](../mutation-testing-grounded/README.md) measures the same and rates from the earlier
  verdict: the method is its unit, an unchanged method is not asked about, and the rating of a changed method moves
  only when the answer names a test or a calling line (a ground) that a script finds in the workspace. That shows that the place exists, not that the rating is right. It has a register of its
  own. Use this one for a fresh look, that one for ratings that do not move without a named place.
- **Inputs:** `modules` (a comma list of Maven modules), `classes` (a PIT glob of the classes to change and of the
  tests to run) and `threads`. A run with other `modules` or `classes` states another snapshot, so the register
  resolves what the smaller run did not look at: keep them the same from run to run.
- **Run time:** PIT runs the tests once per change. A whole project takes hours; the operator's `container_limits` in
  `traceworks.json` say how many CPUs the step gets.
- **The project is not changed.** PIT needs its JUnit 5 plugin in the pom; `scripts/pit.sh` adds it to the copy.
- **Measured once per state of the sources:** the result is kept in the cache folder under a digest of every
  `pom.xml`, every file below a `src` folder and the `.mvn` folder, at any depth, of `scripts/pit.sh` (PIT's version
  and settings), of `images/maven/Dockerfile` (Maven and the JDK) and of `classes`, and a run with the same digest
  takes it from there (the step says so). A file outside these that the tests read, such as test data next to a
  module's `src` folder, is not in the digest: after changing one, empty the folder `pit` of the step's cache folder.
- **Left out:** a test that is red in the image without any change (it needs a program the image lacks) does not
  count: add the program to `images/maven/Dockerfile`. PIT runs only the tests of the module it changes.
- The Maven repository in the cache folder (`<home>/cache/tools/maven`) is written by the step with network and read
  by the step without.
