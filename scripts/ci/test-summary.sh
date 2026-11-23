#!/usr/bin/env bash
# Prints what the tests of a build came to, as a Markdown table per module, from the Surefire reports that Maven
# left in the target folders. The CI appends it to the page of the run (GITHUB_STEP_SUMMARY), so the numbers are
# there without opening the log. Usage: test-summary.sh "<title>" [<root of the build>]
set -euo pipefail

title="$1"
root="${2:-.}"

echo "### $title"
echo
# Hidden folders are left out: they hold no module. The numbers are attributes of the <testsuite> element, the
# first element of a report, so awk reads each file up to that line.
table=$(find "$root" -name '.?*' -prune -o -path '*/target/surefire-reports/TEST-*.xml' -print0 | sort -z |
  xargs -0 -r awk -v root="$root/" '
    function number(name,   found) {
      return match($0, " " name "=\"[0-9]+\"") ? substr($0, RSTART + length(name) + 3, RLENGTH - length(name) - 4) : 0
    }
    /<testsuite / {
      module = FILENAME; sub("^" root, "", module); sub("/target/.*", "", module)
      if (!(module in tests)) order[++n] = module
      tests[module] += number("tests"); failed[module] += number("failures")
      errors[module] += number("errors"); skipped[module] += number("skipped")
      nextfile
    }
    END {
      print "| Module | Tests | Failed | Errors | Skipped |"
      print "|---|---:|---:|---:|---:|"
      for (i = 1; i <= n; i++) { m = order[i]
        printf "| %s | %d | %d | %d | %d |\n", m, tests[m], failed[m], errors[m], skipped[m]
        t += tests[m]; f += failed[m]; e += errors[m]; s += skipped[m] }
      printf "| **All** | **%d** | **%d** | **%d** | **%d** |\n", t, f, e, s
    }')
# Without a report awk is not started at all
printf '%s\n' "${table:-No test report was written: the build ended before its first test.}"
