package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.testing.MapWorkflowResolver;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PackageImageRulesTest {

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static WorkflowDefinition workflow(String id, BlockDefinition... blocks) {
        return new WorkflowDefinition(id, id, null, List.of(blocks), null, null, null, null, null, null,
                List.of("package.images"), null, null);
    }

    private static List<AnalyzerFinding> of(List<AnalyzerFinding> findings, String rule) {
        return findings.stream().filter(f -> f.rule().equals(rule)).toList();
    }

    private static BlockDefinition scan() {
        return block("shell", "scan", Map.of("command", "scan", "container_image", "./images/tools"));
    }

    @Test
    void aChildOfAnotherPackage_thatNamesAPackageImage_isAnError_atTheBlockThatRunsIt() {
        MapWorkflowResolver children = new MapWorkflowResolver()
                .addFromAnotherPackage("./other", workflow("wf-other", scan()))
                .add("./same", workflow("wf-same", scan()));
        WorkflowDefinition parent = workflow("parent",
                block("shell", "prepare", Map.of("command", "true", "container_image", "./images/tools")),
                block("sub-workflow", "other", Map.of("workflow_id", "./other")),
                block("foreach", "same", Map.of("workflow_id", "./same", "items", "a")));

        List<AnalyzerFinding> findings = of(new WorkflowAnalyzer().analyze(parent, children), "child-package-image");

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.ERROR);
            assertThat(f.blockIndex()).isEqualTo(1);
            assertThat(f.detail()).contains("./other", "wf-other", "another package", "Block 1 \"scan\"",
                    "./images/tools");
        });
    }

    @Test
    void aGrandchildOfAnotherPackage_isReportedAtTheBlockThatLeadsToIt() {
        MapWorkflowResolver children = new MapWorkflowResolver()
                .add("./same", workflow("wf-same", block("sub-workflow", "deeper", Map.of("workflow_id", "./deep"))))
                .addFromAnotherPackage("./deep", workflow("wf-deep", scan()));
        WorkflowDefinition parent = workflow("parent", block("sub-workflow", "same", Map.of("workflow_id", "./same")));

        assertThat(of(new WorkflowAnalyzer().analyze(parent, children), "child-package-image")).singleElement()
                .satisfies(f -> {
                    assertThat(f.blockIndex()).isZero();
                    assertThat(f.detail()).contains("wf-deep", "./images/tools");
                });
    }

    /** A directory under images/ with a Dockerfile whose name no ./images/ reference can name is a warning. */
    @Test
    void anImageDirectoryWhoseNameIsNotValid_isAWarning() {
        List<AnalyzerFinding> findings = of(new WorkflowAnalyzer().analyze(workflow("parent", scan()), null,
                List.of(), List.of("MyTool", "two words")), "invalid-image-name");

        assertThat(findings).hasSize(2).allSatisfy(f -> {
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.WARNING);
            assertThat(f.blockIndex()).isEqualTo(-1);
            assertThat(f.detail()).contains("is not a valid package image name ([a-z0-9][a-z0-9._-]*)",
                    "rename the directory");
        });
        assertThat(findings.getFirst().detail()).contains("images/MyTool", "\"MyTool\"", "for example to mytool");
        assertThat(findings.get(1).detail()).doesNotContain("for example");
    }

    @Test
    void withoutAResolver_orForAPlaceholderReference_nothingIsReported() {
        MapWorkflowResolver children = new MapWorkflowResolver()
                .addFromAnotherPackage("./other", workflow("wf-other", scan()));
        WorkflowDefinition parent = workflow("parent",
                block("sub-workflow", "other", Map.of("workflow_id", "{{ which }}")));

        assertThat(of(new WorkflowAnalyzer().analyze(parent, children), "child-package-image")).isEmpty();
        assertThat(of(new WorkflowAnalyzer().analyze(workflow("parent",
                block("sub-workflow", "other", Map.of("workflow_id", "./other")))), "child-package-image")).isEmpty();
    }

    @Nested
    class UnpinnedBaseImage {

        private static final String PIN = "@sha256:" + "ab".repeat(32);

        private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();

        private static WorkflowDefinition withoutBlocks() {
            return new WorkflowDefinition("id-1", "test", null, List.of(), null, null, null, null, null, null, null,
                    "traceworks-workflow", 1);
        }

        private List<AnalyzerFinding> check(String dockerfile) {
            traceworks.core.port.PackageImage image = new traceworks.core.port.PackageImage(
                    "tools", "/pkg/images/tools", "a".repeat(64), dockerfile);
            return analyzer.analyze(withoutBlocks(), null, List.of(image)).stream()
                    .filter(f -> f.rule().equals("unpinned-base-image")).toList();
        }

        private List<AnalyzerFinding> describedBy(String dockerfile) {
            traceworks.core.port.PackageImage image = new traceworks.core.port.PackageImage("tools", "/pkg/images/tools", "a".repeat(64), dockerfile);
            return analyzer.analyze(withoutBlocks(), null, List.of(image)).stream()
                    .filter(f -> f.rule().equals("image-without-description")).toList();
        }

        /** A reader of the record should see what an image holds; a name like images/tools says nothing. */
        @Test
        void aDockerfileWithoutTheDescriptionLabel_isAWarning_namingTheLabel() {
            assertThat(describedBy("FROM debian:bookworm-slim@sha256:" + "0".repeat(64) + "\nRUN true\n"))
                    .singleElement().satisfies(f -> {
                        assertThat(f.severity()).isEqualTo(Severity.WARNING);
                        assertThat(f.detail()).contains("images/tools/Dockerfile", "org.opencontainers.image.description",
                                "what the image holds");
                    });
            assertThat(describedBy("FROM debian:bookworm-slim@sha256:" + "0".repeat(64)
                    + "\nLABEL org.opencontainers.image.description=\"bash and jq\"\n")).isEmpty();
        }

        @Test
        void unpinnedBaseImage_isAWarning() {
            assertThat(check("FROM node:22-slim\nRUN true\n")).singleElement().satisfies(f -> {
                assertThat(f.severity()).isEqualTo(Severity.WARNING);
                assertThat(f.blockIndex()).isEqualTo(-1);
                assertThat(f.detail()).contains("images/tools").contains("node:22-slim").contains("@sha256:");
            });
            assertThat(check("FROM node:22-slim" + PIN + "\nRUN true\n")).isEmpty();
        }

        @Test
        void aStageNameAndScratchAndEarlierStagesAreNotBaseImages() {
            assertThat(check("FROM node:22-slim" + PIN + " AS build\nFROM build AS final\nFROM scratch\n")).isEmpty();
            assertThat(check("FROM --platform=linux/amd64 node:22\n")).hasSize(1);
            assertThat(check("# FROM node:22\nfrom alpine:3 as x\n")).hasSize(1);
        }

        /**
         * A Dockerfile is a package's file, and {@code check} reads it. The engine matches a repeated group with one
         * stack frame per repetition unless the group gives nothing back, so a FROM line with thousands of flags
         * overflowed the stack. Run on a small stack, so the test does not depend on the one of the build.
         */
        @Test
        void aFromLineWithThousandsOfFlags_isReadWithoutAStackOverflow() throws InterruptedException {
            check("FROM node:22-slim\n");   // everything the analyzer loads once is loaded on the full stack
            String dockerfile = "FROM" + " --x".repeat(5000) + " debian:bookworm-slim AS base\nRUN true\n";
            java.util.concurrent.atomic.AtomicReference<Object> result =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread smallStack = new Thread(null, () -> {
                try {
                    result.set(check(dockerfile));
                } catch (Throwable t) {
                    result.set(t);
                }
            }, "rules-small-stack", 256 * 1024);
            smallStack.start();
            smallStack.join(30_000);

            assertThat(smallStack.isAlive()).isFalse();
            assertThat(result.get()).isInstanceOf(List.class);
            assertThat(check(dockerfile)).singleElement()
                    .satisfies(f -> assertThat(f.detail()).contains("FROM debian:bookworm-slim without a digest"));
        }

        /** What the pattern reads is the same as before its flags stopped giving back. */
        @Test
        void flagsAndStageNames_areReadAsBefore() {
            assertThat(check("from --platform=linux/amd64 --x=y  node:22 as b\n")).singleElement()
                    .satisfies(f -> assertThat(f.detail()).contains("FROM node:22 without"));
            assertThat(check("FROM --platform=linux/amd64 node:22" + PIN + " AS build\nFROM build\n")).isEmpty();
            assertThat(check("FROM --platform=$BUILDPLATFORM node:22 AS build # the builder\n")).hasSize(1);
        }
    }
}
