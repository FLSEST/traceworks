package traceworks.selfaudit.architecture;

import traceworks.selfaudit.architecture.RuleFile.Allow;
import traceworks.selfaudit.architecture.RuleFile.Call;
import traceworks.selfaudit.architecture.RuleFile.Io;
import traceworks.selfaudit.architecture.RuleFile.Layer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleFileTest {

    @Test
    void parse_readsLayersAndIoRulesWithThePrefix() {
        RuleFile rules = RuleFile.parse("""
                # comment
                prefix io.github.x

                layer inner
                  why  Inner knows nothing of outer.
                  from core.port core.model
                  from core.util
                  not  core.app core.blocks.*

                io core_io
                  why   No I/O in the core.
                  in    core.*
                  type  java.nio.file.Files java.net.http.*
                  call  java.lang.System#getenv
                  allow core.app.Version reads its version, as documented
                """);

        assertThat(rules.prefix()).isEqualTo("io.github.x");
        assertThat(rules.layers()).containsExactly(new Layer("inner", "Inner knows nothing of outer.",
                java.util.List.of("core.port", "core.model", "core.util"), java.util.List.of("core.app", "core.blocks.*")));
        assertThat(rules.ios()).containsExactly(new Io("core_io", "No I/O in the core.", java.util.List.of("core.*"),
                java.util.List.of("java.nio.file.Files", "java.net.http.*"),
                java.util.List.of(new Call("java.lang.System", "getenv")),
                java.util.List.of(new Allow("core.app.Version", "reads its version, as documented"))));
        assertThat(rules.ids()).containsExactly("inner", "core_io");
        assertThat(rules.relative("io.github.x.core.app")).isEqualTo("core.app");
        assertThat(rules.relative("io.github.xy.core")).isEqualTo("io.github.xy.core");
    }

    @Test
    void aPattern_withAStar_matchesThePackageAndBelow() {
        assertThat(RuleFile.matches("core.*", "core")).isTrue();
        assertThat(RuleFile.matches("core.*", "core.app")).isTrue();
        assertThat(RuleFile.matches("core.*", "corex")).isFalse();
        assertThat(RuleFile.matches("core.app", "core.app")).isTrue();
        assertThat(RuleFile.matches("core.app", "core.app.sub")).isFalse();
    }

    @Test
    void anError_namesItsLine() {
        assertRefused("from a", "line 1: 'from' before the first rule");
        assertRefused("layer a\n  why x\n  from a\n  not b\nlayer a\n  why y\n  from a\n  not b", "line 5: rule a twice");
        assertRefused("layer Bad", "line 1: 'Bad' is no rule id");
        assertRefused("layer a\n  from a\n  not b", "rule a has no why");
        assertRefused("layer a\n  why x\n  from a", "layer a needs a from and a not line");
        assertRefused("io a\n  why x\n  type java.io.File", "io a needs an in line");
        assertRefused("io a\n  why x\n  in core", "io a has no type or call line");
        assertRefused("layer a\n  why x\n  why y", "line 3: a second why");
        assertRefused("layer a\n  why x\n  type java.io.File", "line 3: 'type' does not belong in a layer rule");
        assertRefused("io a\n  why x\n  from core", "line 3: 'from' does not belong in a io rule");
        assertRefused("io a\n  why x\n  in core\n  call java.lang.System", "line 4: 'java.lang.System' is no <type>#<method>");
        assertRefused("io a\n  why x\n  in core\n  allow core.A", "line 4: an allow needs a reason after the class");
        assertRefused("layer a\n  why x\n  from 9.x", "line 3: '9.x' is no package");
        assertRefused("layer a\n  why x\n  jump a", "line 3: unknown line 'jump'");
        assertRefused("layer a\n  why", "line 2: 'why' needs a value");
        assertRefused("prefix a\nprefix b", "line 2: a second prefix");
        assertRefused("layer a\n  why x\n  from a\n  not b\nprefix c", "line 5: the prefix comes before the first rule");
        assertRefused("", "no rule");
    }

    @Test
    void thePackagesRuleFile_parses() throws Exception {
        RuleFile rules = RuleFile.parse(Files.readString(Path.of("../architecture/rules/architecture.rules")));

        assertThat(rules.prefix()).isEqualTo("traceworks");
        assertThat(rules.layers()).isNotEmpty();
        assertThat(rules.ios()).singleElement().satisfies(io -> assertThat(io.allowed()).isNotEmpty());
    }

    private static void assertRefused(String text, String message) {
        assertThatThrownBy(() -> RuleFile.parse(text)).as(text)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }
}
