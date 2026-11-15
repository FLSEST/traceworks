package traceworks.selfaudit.areas;

import traceworks.selfaudit.areas.Areas.Area;
import traceworks.selfaudit.areas.Finder.Hit;
import traceworks.selfaudit.areas.Finder.Member;
import traceworks.selfaudit.areas.Finder.Result;
import traceworks.selfaudit.areas.Finder.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class AreasTest {

    private static final RuleFile RULES = RuleFile.parse("""
            rule process_start
              why Starts a process.
              new java.lang.ProcessBuilder
            rule env_pass
              why Passes the environment.
              call java.lang.ProcessBuilder#environment
            """);

    private static SourceFile file(String path, int lines) {
        return new SourceFile(path, IntStream.rangeClosed(1, lines).mapToObj(i -> "code " + i).toList());
    }

    private static Hit hit(String rule, String file, int line, Member member) {
        return new Hit(rule, file, line, "what " + line, "type", member);
    }

    @Test
    void theHitsOfOneMember_makeOneArea_withItsRulesInFileOrder() {
        Member run = new Member("A.run()", 3, 6, false);
        Result result = new Result(List.of(file("b/A.java", 8)),
                List.of(hit("env_pass", "b/A.java", 4, run), hit("process_start", "b/A.java", 5, run)), List.of(), 0);

        List<Area> areas = Areas.of(result, RULES, 160, 15);

        assertThat(areas).singleElement().satisfies(a -> {
            assertThat(a.id()).isEqualTo("A001");
            assertThat(a.file()).isEqualTo("b/A.java");
            assertThat(a.member()).isEqualTo("A.run()");
            assertThat(a.subject()).isEqualTo("b/A.java#A.run()");
            assertThat(a.start()).isEqualTo(3);
            assertThat(a.end()).isEqualTo(6);
            assertThat(a.rules()).containsExactly("process_start", "env_pass");
            assertThat(a.why()).isEqualTo("process_start: Starts a process.\nenv_pass: Passes the environment.");
            assertThat(a.code()).isEqualTo("3| code 3\n4| code 4\n5| code 5\n6| code 6");
            assertThat(a.hitsText()).isEqualTo(
                    "line 4: what 4 (env_pass)\nline 5: what 5 (process_start)");
        });
    }

    @Test
    void areas_areOrderedByFileThenLine_andNumbered() {
        Member late = new Member("B.late()", 20, 22, false);
        Member early = new Member("B.early()", 2, 4, false);
        Member other = new Member("A.f()", 5, 5, false);
        Result result = new Result(List.of(file("a/A.java", 9), file("b/B.java", 30)),
                List.of(hit("process_start", "b/B.java", 21, late), hit("process_start", "b/B.java", 3, early),
                        hit("process_start", "a/A.java", 5, other)), List.of(), 0);

        assertThat(Areas.of(result, RULES, 10, 15)).extracting(Area::id, Area::subject).containsExactly(
                tuple("A001", "a/A.java#A.f()"), tuple("A002", "b/B.java#B.early()"),
                tuple("A003", "b/B.java#B.late()"));
    }

    /** Areas of one file that fit the limit together become one, with the code between them as context. */
    @Test
    void neighbouringAreasOfAFile_areMerged_whileTheyFitTheLimit() {
        Member f = new Member("A.f()", 3, 6, false);
        Member g = new Member("A.g()", 8, 10, false);
        Member far = new Member("A.far()", 300, 305, false);
        Result result = new Result(List.of(file("A.java", 310), file("B.java", 9)),
                List.of(hit("env_pass", "A.java", 4, f), hit("process_start", "A.java", 9, g),
                        hit("process_start", "A.java", 301, far),
                        hit("process_start", "B.java", 2, new Member("B.h()", 1, 3, false))), List.of(), 0);

        List<Area> areas = Areas.of(result, RULES, 160, 15);

        assertThat(areas).extracting(Area::id, Area::member, Area::start, Area::end, Area::rules).containsExactly(
                tuple("A001", "A.f(), A.g()", 3, 10, List.of("process_start", "env_pass")),
                tuple("A002", "A.far()", 300, 305, List.of("process_start")),
                tuple("A003", "B.h()", 1, 3, List.of("process_start")));
        assertThat(areas.getFirst().hits()).extracting(Hit::line).containsExactly(4, 9);
        assertThat(areas.getFirst().code()).startsWith("3| code 3\n").endsWith("\n10| code 10");
    }

    /** A member longer than the limit is cut into windows around its hits; close hits share one. */
    @Test
    void aLongMember_isCutIntoWindowsAroundItsHits() {
        Member huge = new Member("A.huge()", 10, 400, false);
        Result result = new Result(List.of(file("A.java", 410)),
                List.of(hit("process_start", "A.java", 12, huge), hit("process_start", "A.java", 30, huge),
                        hit("env_pass", "A.java", 200, huge)), List.of(), 0);

        List<Area> areas = Areas.of(result, RULES, 160, 15);

        assertThat(areas).extracting(Area::start, Area::end, Area::rules).containsExactly(
                tuple(10, 45, List.of("process_start")), tuple(185, 215, List.of("env_pass")));
        assertThat(areas).extracting(Area::member).containsOnly("A.huge()");
    }

    @Test
    void aClassLevelHit_getsItsContext_clippedToTheFile() {
        Result result = new Result(List.of(file("A.java", 5)),
                List.of(hit("process_start", "A.java", 2, new Member("A", 2, 2, true))), List.of(), 0);

        assertThat(Areas.of(result, RULES, 160, 15)).singleElement()
                .satisfies(a -> assertThat(List.of(a.start(), a.end())).containsExactly(1, 5));
    }

    @Test
    void windowsOfDenseHits_stayWithinTheLimit() {
        Member huge = new Member("A.huge()", 1, 1000, false);
        List<Hit> hits = new ArrayList<>();
        for (int line = 1; line <= 1000; line += 10) hits.add(hit("process_start", "A.java", line, huge));
        Result result = new Result(List.of(file("A.java", 1000)), hits, List.of(), 0);

        List<Area> areas = Areas.of(result, RULES, 160, 15);

        assertThat(areas).allSatisfy(a -> assertThat(a.end() - a.start() + 1).isLessThanOrEqualTo(160));
        assertThat(areas.stream().mapToInt(a -> a.hits().size()).sum()).isEqualTo(100);
    }

    @Test
    void anArea_asJson_holdsWhatThePromptAndTheReportNeed() {
        Member run = new Member("A.run()", 1, 2, false);
        Result result = new Result(List.of(file("A.java", 2)),
                List.of(hit("process_start", "A.java", 2, run)), List.of(), 0);

        Map<String, Object> json = Areas.of(result, RULES, 160, 15).getFirst().toJson("t0k3n", "f3nc3");

        assertThat(json).containsOnlyKeys("id", "file", "member", "subject", "start", "end", "rules", "hits",
                "hits_text", "why", "code", "token", "fence");
        assertThat(json).containsEntry("token", "t0k3n").containsEntry("fence", "f3nc3");
        assertThat(json.get("hits")).isEqualTo(
                List.of(Map.of("rule", "process_start", "line", 2L, "what", "what 2", "resolved", "type")));
        assertThat(Json.write(json)).doesNotContain("\n");
    }
}
