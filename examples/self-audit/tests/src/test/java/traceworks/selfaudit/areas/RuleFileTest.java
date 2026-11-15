package traceworks.selfaudit.areas;

import traceworks.selfaudit.areas.RuleFile.Argument;
import traceworks.selfaudit.areas.RuleFile.Matcher;
import traceworks.selfaudit.areas.RuleFile.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleFileTest {

    @Test
    void parse_readsRulesWithTheirMatchersAndScopes() {
        RuleFile rules = RuleFile.parse("""
                # comment
                rule process_start
                  why  Starts a process.
                  new  java.lang.ProcessBuilder
                  call java.lang.Runtime#exec
                  in   traceworks.adapters

                rule redos
                  why  A regex from data.
                  call java.util.regex.Pattern#compile nonconstant
                  call java.lang.String#replaceAll nonconstant 0
                  call java.util.regex.Pattern#compile constant \\.[*+].*\\.[*+]
                  call java.io.PrintStream#*
                  literal ^--network
                """);

        assertThat(rules.rules()).extracting(Rule::id).containsExactly("process_start", "redos");
        Rule process = rules.rules().getFirst();
        assertThat(process.why()).isEqualTo("Starts a process.");
        assertThat(process.packages()).containsExactly("traceworks.adapters");
        assertThat(process.matchers()).containsExactly(
                new Matcher.New("java.lang.ProcessBuilder"),
                new Matcher.Call("java.lang.Runtime", "exec", Argument.ANY));
        List<Matcher> redos = rules.rules().get(1).matchers();
        assertThat(redos.get(0)).isEqualTo(
                new Matcher.Call("java.util.regex.Pattern", "compile", Argument.nonconstant(-1)));
        assertThat(redos.get(1)).isEqualTo(
                new Matcher.Call("java.lang.String", "replaceAll", Argument.nonconstant(0)));
        assertThat(((Matcher.Call) redos.get(2)).argument().pattern().pattern()).isEqualTo("\\.[*+].*\\.[*+]");
        assertThat(((Matcher.Call) redos.get(3)).method()).isEqualTo("*");
        assertThat(((Matcher.Literal) redos.get(4)).pattern().pattern()).isEqualTo("^--network");
        assertThat(rules.rules().get(1).packages()).isEmpty();
    }

    @Test
    void anError_namesItsLine() {
        assertRefused("new java.lang.ProcessBuilder", "line 1: 'new' before the first rule");
        assertRefused("rule a\n  why x\n  new java.lang.X\nrule a\n  why y\n  new java.lang.Y", "line 4: rule a twice");
        assertRefused("rule Bad\n  why x", "line 1: 'Bad' is no rule id");
        assertRefused("rule a\n  new java.lang.X", "rule a has no why");
        assertRefused("rule a\n  why x", "rule a has no new, call or literal line");
        assertRefused("rule a\n  why x\n  why y", "line 3: a second why");
        assertRefused("rule a\n  why x\n  call java.lang.Runtime", "line 3: 'java.lang.Runtime' is no <type>#<method>");
        assertRefused("rule a\n  why x\n  call 1x#y", "line 3: '1x#y' is no <type>#<method>");
        assertRefused("rule a\n  why x\n  call a.B#c sometimes", "line 3: after the method only");
        assertRefused("rule a\n  why x\n  literal (", "line 3: '(' is no regular expression");
        assertRefused("rule a\n  why x\n  call a.B#c constant", "line 3: 'constant' needs a regular expression");
        assertRefused("rule a\n  why x\n  call a.B#c nonconstant x", "line 3: 'nonconstant' takes an argument index");
        assertRefused("rule a\n  why x\n  call a.B#c nonconstant 0 1", "line 3: 'nonconstant' takes an argument index");
        assertRefused("rule a\n  why x\n  in 9.x", "line 3: '9.x' is no package");
        assertRefused("rule a\n  why x\n  jump a", "line 3: unknown line 'jump'");
        assertRefused("rule a\n  why", "line 2: 'why' needs a value");
        assertRefused("", "no rule");
    }

    private static void assertRefused(String text, String message) {
        assertThatThrownBy(() -> RuleFile.parse(text)).as(text)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    /** The register refuses a report whose rule key its catalog does not name, so the two are kept in step. */
    @Test
    void theCatalogOfThePackage_namesEveryRuleOfTheRuleFile_andOther() throws Exception {
        java.nio.file.Path pkg = java.nio.file.Path.of("../security");
        RuleFile rules = RuleFile.parse(java.nio.file.Files.readString(pkg.resolve("rules/security.rules")));
        Object catalog = Json.parse(java.nio.file.Files.readString(pkg.resolve("catalog.json")));

        java.util.Set<String> expected = new java.util.TreeSet<>();
        rules.rules().forEach(rule -> expected.add(rule.id()));
        expected.add("other");
        java.util.Set<String> named = new java.util.TreeSet<>();
        ((java.util.Map<?, ?>) ((java.util.Map<?, ?>) catalog).get("rules")).keySet()
                .forEach(key -> named.add(String.valueOf(key)));
        assertThat(named).isEqualTo(expected);
    }
}
