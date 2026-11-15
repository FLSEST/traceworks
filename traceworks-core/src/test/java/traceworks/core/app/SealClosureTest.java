package traceworks.core.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SealClosureTest {

    private static final Path MAIN = Path.of("/pkg/main");
    private static final Path LIB = Path.of("/lib/tools");
    private static final Path OTHER = Path.of("/lib/other");
    private static final String PINNED = "registry.example/tool@sha256:" + "a".repeat(64);

    private final AppFixture fx = new AppFixture();

    SealClosureTest() {
        fx.packages.put(MAIN, """
                {"id": "wf-main", "name": "Main", "blocks": [
                  {"type": "sub-workflow", "name": "tools", "config": {"workflow_id": "../../lib/tools"}},
                  {"type": "shell", "name": "say", "config": {"command": "echo"}}
                ]}""");
        fx.packages.put(LIB, """
                {"id": "wf-tools", "name": "Tools", "blocks": [
                  {"type": "shell", "name": "t", "config": {"command": "echo"}}
                ]}""");
        fx.packages.put(OTHER, """
                {"id": "wf-other", "name": "Other", "blocks": [
                  {"type": "shell", "name": "o", "config": {"command": "echo"}}
                ]}""");
    }

    private SealClosure closure(Path pkg) {
        return SealClosure.of(fx.packages.load(pkg), List.of(), fx.packages);
    }

    @Test
    void theDigest_changesWithAFileOfThePackage_andWithAFileOfAChildPackage() {
        String before = closure(MAIN).digest();
        assertThat(before).matches("[0-9a-f]{64}").isNotEqualTo(fx.packages.load(MAIN).digest());
        assertThat(closure(MAIN).violations()).isEmpty();
        assertThat(closure(MAIN).digest()).isEqualTo(before);

        fx.packages.script(LIB, "echo changed");
        String childChanged = closure(MAIN).digest();
        fx.packages.script(MAIN, "echo changed");

        assertThat(childChanged).isNotEqualTo(before);
        assertThat(closure(MAIN).digest()).isNotEqualTo(before).isNotEqualTo(childChanged);
    }

    @Test
    void aPackageThatIsNotReached_doesNotCount() {
        String before = closure(MAIN).digest();

        fx.packages.script(OTHER, "echo changed");

        assertThat(closure(MAIN).digest()).isEqualTo(before);
    }

    @Test
    void aChildInsideThePackage_isCoveredByThePackagesOwnDigest_andIsWalked() {
        // AppFixture.PARENT calls ./child, a directory of the package
        SealClosure parent = closure(AppFixture.PARENT);
        fx.packages.put(Path.of("/pkg/alone"), """
                {"id": "wf-alone", "name": "Alone", "blocks": [
                  {"type": "shell", "name": "s", "config": {"command": "echo"}}]}""");

        assertThat(parent.violations()).isEmpty();
        assertThat(parent.children()).isEmpty();
        assertThat(closure(MAIN).children()).containsExactly("wf-tools");
        assertThat(closure(Path.of("/pkg/alone")).children()).isEmpty();
    }

    @Test
    void theOrderOfTheBlocks_doesNotChangeTheDigest_andAChildOfAChildCounts() {
        fx.packages.put(Path.of("/pkg/ab"), """
                {"id": "wf-x", "name": "X", "blocks": [
                  {"type": "sub-workflow", "name": "a", "config": {"workflow_id": "../../lib/tools"}},
                  {"type": "foreach", "name": "b", "config": {"items": "1", "workflow_id": "../../lib/other"}}]}""");
        fx.packages.put(Path.of("/pkg/ba"), """
                {"id": "wf-x", "name": "X", "blocks": [
                  {"type": "sub-workflow", "name": "a", "config": {"workflow_id": "../../lib/tools"}},
                  {"type": "foreach", "name": "b", "config": {"items": "1", "workflow_id": "../../lib/other"}}]}""");
        assertThat(closure(Path.of("/pkg/ab")).children()).containsExactly("wf-other", "wf-tools");
        assertThat(closure(Path.of("/pkg/ab")).digest()).isEqualTo(closure(Path.of("/pkg/ba")).digest());

        // tools now calls other: main reaches other through tools
        fx.packages.put(LIB, """
                {"id": "wf-tools", "name": "Tools", "blocks": [
                  {"type": "sub-workflow", "name": "o", "config": {"workflow_id": "../other"}}]}""");
        String before = closure(MAIN).digest();
        fx.packages.script(OTHER, "echo changed");

        assertThat(closure(MAIN).children()).containsExactly("wf-other", "wf-tools");
        assertThat(closure(MAIN).digest()).isNotEqualTo(before);
    }

    @Test
    void workflowsThatCallEachOther_endTheWalk() {
        fx.packages.put(LIB, """
                {"id": "wf-tools", "name": "Tools", "blocks": [
                  {"type": "sub-workflow", "name": "back", "config": {"workflow_id": "../../pkg/main"}}]}""");

        assertThat(closure(MAIN).violations()).isEmpty();
        assertThat(closure(MAIN).children()).containsExactly("wf-tools");
    }

    @Test
    void aChildInANestedBlock_counts() {
        fx.packages.put(Path.of("/pkg/switch"), """
                {"id": "wf-switch", "name": "Switch", "blocks": [
                  {"type": "switch", "name": "sw", "config": {"value": "a", "cases": [
                    {"value": "a", "blocks": [
                      {"type": "sub-workflow", "name": "t", "config": {"workflow_id": "../../lib/tools"}}]}]}}]}""");

        assertThat(closure(Path.of("/pkg/switch")).children()).containsExactly("wf-tools");
    }

    @Test
    void whatASealCannotPin_isAViolation() {
        fx.packages.put(Path.of("/pkg/dynamic"), """
                {"id": "wf-d", "name": "D", "blocks": [
                  {"type": "sub-workflow", "name": "any", "config": {"workflow_id": "{{ which }}"}},
                  {"type": "sub-workflow", "name": "gone", "config": {"workflow_id": "../nowhere"}},
                  {"type": "shell", "name": "floating", "config": {"command": "x", "container_image": "alpine:3"}},
                  {"type": "shell", "name": "chosen", "config": {"command": "x", "container_image": "{{ image }}"}},
                  {"type": "shell", "name": "pinned", "config": {"command": "x", "container_image": "%s"}},
                  {"type": "shell", "name": "own", "config": {"command": "x", "container_image": "./images/tool"}}
                ]}""".formatted(PINNED));
        fx.packages.image(Path.of("/pkg/dynamic"), "tool", "FROM debian:12\nRUN true\n");
        fx.packages.image(Path.of("/pkg/dynamic"), "fine", "FROM debian@sha256:" + "b".repeat(64) + "\n");

        List<String> violations = closure(Path.of("/pkg/dynamic")).violations();

        assertThat(violations).hasSize(5);
        assertThat(violations).anyMatch(v -> v.contains("'any'") && v.contains("{{ which }}"));
        assertThat(violations).anyMatch(v -> v.contains("'gone'") && v.contains("../nowhere"));
        assertThat(violations).anyMatch(v -> v.contains("'floating'") && v.contains("alpine:3") && v.contains("@sha256:"));
        assertThat(violations).anyMatch(v -> v.contains("'chosen'") && v.contains("{{ image }}"));
        assertThat(violations).anyMatch(v -> v.contains("images/tool/Dockerfile") && v.contains("debian:12"));
    }

    @Test
    void aViolationInAChildPackage_counts() {
        fx.packages.put(LIB, """
                {"id": "wf-tools", "name": "Tools", "blocks": [
                  {"type": "shell", "name": "t", "config": {"command": "x", "container_image": "alpine"}}]}""");

        assertThat(closure(MAIN).violations()).singleElement().asString().contains("wf-tools").contains("alpine");
    }

    /** The digest leaves the top-level .git/ out, so a child there would be covered by nothing. */
    @Test
    void aChildUnderThePackagesGitDirectory_isAViolation() {
        Path hidden = Path.of("/pkg/hidden");
        fx.packages.put(hidden, """
                {"id": "wf-hidden", "name": "Hidden", "blocks": [
                  {"type": "sub-workflow", "name": "inside", "config": {"workflow_id": "./.git/child"}},
                  {"type": "sub-workflow", "name": "fine", "config": {"workflow_id": "./sub/child"}}]}""");
        fx.packages.put(hidden.resolve(".git/child"), """
                {"id": "wf-git-child", "name": "C", "blocks": [{"type": "shell", "name": "s", "config": {"command": "x"}}]}""");
        fx.packages.put(hidden.resolve("sub/child"), """
                {"id": "wf-sub-child", "name": "C", "blocks": [{"type": "shell", "name": "s", "config": {"command": "x"}}]}""");

        assertThat(closure(hidden).violations()).singleElement().asString()
                .contains("'inside'").contains(".git").contains("digest");
    }

    /** The seal file of a child is left out of the child's own digest, so the caller's seal must cover it. */
    @Test
    void theSealFileOfAChildPackage_isCoveredLikeAnyOtherFile() {
        String without = closure(MAIN).digest();

        fx.sealStore.writeSeal(LIB, "free content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String with = closure(MAIN).digest();
        fx.sealStore.writeSeal(LIB, "other content".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(with).isNotEqualTo(without);
        assertThat(closure(MAIN).digest()).isNotEqualTo(with).isNotEqualTo(without);
        // the package's own seal file stays out: it carries the signature over this digest
        fx.sealStore.writeSeal(MAIN, "own seal".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(closure(MAIN).digest()).isEqualTo(closure(MAIN).digest());
        fx.sealStore.removeSeal(LIB);
        assertThat(closure(MAIN).digest()).isEqualTo(without);
    }

    @Test
    void anImageIsPinned_onlyByADigestAtItsEnd() {
        Path pkg = Path.of("/pkg/pins");
        fx.packages.put(pkg, """
                {"id": "wf-pins", "name": "P", "blocks": [
                  {"type": "shell", "name": "fragment", "config": {"command": "x", "container_image": "img:latest#@sha256:"}},
                  {"type": "shell", "name": "short", "config": {"command": "x", "container_image": "img@sha256:abc"}},
                  {"type": "shell", "name": "tail", "config": {"command": "x", "container_image": "img@sha256:%s:latest"}},
                  {"type": "shell", "name": "good", "config": {"command": "x", "container_image": "%s"}}]}"""
                .formatted("a".repeat(64), PINNED));

        assertThat(closure(pkg).violations()).hasSize(3)
                .anyMatch(v -> v.contains("'fragment'")).anyMatch(v -> v.contains("'short'"))
                .anyMatch(v -> v.contains("'tail'"));
    }

    @Test
    void everyImageADockerfileNames_mustBePinned_notOnlyItsBase() {
        Path pkg = Path.of("/pkg/docker");
        String digest = "@sha256:" + "c".repeat(64);
        fx.packages.put(pkg, """
                {"id": "wf-docker", "name": "D", "blocks": [{"type": "shell", "name": "s", "config": {"command": "x"}}]}""");
        fx.packages.image(pkg, "tool", "FROM debian" + digest + " AS build\n"
                + "FROM debian" + digest + "\n"
                + "COPY --from=build /a /a\n"
                + "COPY --from=0 /b /b\n"
                + "COPY --chown=1:1 --from=alpine:3 /c /c\n"
                + "COPY --from=busybox" + digest + " /d /d\n"
                + "RUN --mount=type=bind,from=golang:1.22,target=/go true\n"
                + "RUN --mount=type=cache,target=/x \\\n    --mount=type=bind,from=node:20,target=/n true\n"
                + "RUN --mount=type=bind,from=build,target=/s --mount=type=bind,from=rust" + digest + ",target=/r true\n"
                + "FROM debian:12#" + digest.substring(0, 20) + "\n");

        assertThat(closure(pkg).violations()).hasSize(4)
                .anyMatch(v -> v.contains("alpine:3") && v.contains("COPY --from"))
                .anyMatch(v -> v.contains("golang:1.22") && v.contains("RUN --mount"))
                .anyMatch(v -> v.contains("node:20"))
                .anyMatch(v -> v.contains("debian:12#"));
    }
}
