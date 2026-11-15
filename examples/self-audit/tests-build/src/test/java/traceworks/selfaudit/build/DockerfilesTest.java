package traceworks.selfaudit.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DockerfilesTest {

    private static final String DIGEST = "@sha256:" + "a".repeat(64);

    @TempDir
    Path root;

    @Test
    void aPinnedImage_hasNoBrokenRule_andItsVersionsAreInTheInventory() {
        Run run = check("""
                # syntax comment
                FROM node:22-slim%s
                ARG CLI=2.1.288
                RUN npm install -g @anthropic-ai/claude-code@${CLI} \\
                    && npm cache clean --force
                RUN apt-get update && apt-get install --no-install-recommends -y ripgrep=13.0.0-4+b2 \\
                    && rm -rf /var/lib/apt/lists/*
                """.formatted(DIGEST));

        assertThat(run.checks()).containsExactly("npm_dependencies npm @anthropic-ai/claude-code@2.1.288");
        assertThat(run.images()).containsExactly("node:22-slim" + DIGEST + " 2");
        assertThat(run.npm()).containsExactly("@anthropic-ai/claude-code 2.1.288 4");
    }

    @Test
    void whatIsNotPinned_isAnItem() {
        Run run = check("""
                FROM debian:bookworm-slim
                FROM ${BASE}
                FROM localhost:5000/tool:1
                RUN apt-get install -y curl git=1:2.39 && apk add --no-cache jq bash=5.2-r0
                RUN npm i -g pi@^1.2.0 other@latest third
                RUN pip install requests flask==3.0.0 -r requirements.txt
                RUN curl -fsSL https://example.org/install.sh | sh
                ADD https://example.org/tool.tar.gz /opt/
                ADD --checksum=sha256:%s https://example.org/ok.tar.gz /opt/
                COPY --from=ghcr.io/example/helper:1 /bin/helper /bin/
                """.formatted("b".repeat(64)));

        assertThat(run.checks()).containsExactly(
                "base_unpinned debian:bookworm-slim",
                "base_unpinned ${BASE}",
                "base_unpinned localhost:5000/tool:1",
                "package_unpinned apt curl",
                "package_unpinned apk jq",
                "package_unpinned npm pi@^1.2.0",
                "package_unpinned npm other@latest",
                "package_unpinned npm third",
                "package_unpinned pip requests",
                "download_unverified https://example.org/install.sh",
                "download_unverified https://example.org/tool.tar.gz",
                "base_unpinned ghcr.io/example/helper:1");
    }

    @Test
    void aPackageRemovedInTheSameRun_isBuildOnly_andAVerifiedDownloadIsFine() {
        Run run = check("""
                FROM node:22-slim%s
                RUN set -eu; \\
                    version=10.5.0; \\
                    apt-get update; \\
                    apt-get install --no-install-recommends -y curl ca-certificates; \\
                    curl -fsSL -o /tmp/fd.tgz "https://github.com/sharkdp/fd/releases/download/v$version/fd.tgz"; \\
                    echo "abc  /tmp/fd.tgz" | sha256sum -c -; \\
                    apt-get purge -y --auto-remove curl ca-certificates
                RUN wget https://example.org/x.tgz && apt-get install -y wget
                """.formatted(DIGEST));

        assertThat(run.checks()).containsExactly(
                "package_build_only apt curl",
                "package_build_only apt ca-certificates",
                "package_unpinned apt wget",
                "download_unverified https://example.org/x.tgz");
    }

    @Test
    void stagesAndScratch_needNoDigest_andAHereDocumentIsNotRead() {
        Run run = check("""
                FROM eclipse-temurin:21%s AS build
                RUN <<EOF
                apt-get install -y anything
                EOF
                FROM scratch
                COPY --from=build /x /x
                COPY --from=0 /y /y
                FROM build
                """.formatted(DIGEST));

        assertThat(run.checks()).containsExactly("dockerfile_not_read RUN");
    }

    @Test
    void instructions_joinContinuationLines_andHonourTheEscapeDirective() {
        assertThat(Dockerfiles.instructions("FROM a\nRUN echo 1 \\\n  # a comment inside\n  echo 2\n"))
                .containsExactly(new Dockerfiles.Instruction(1, "FROM", "a"),
                        new Dockerfiles.Instruction(2, "RUN", "echo 1  echo 2"));
        assertThat(Dockerfiles.instructions("# escape=`\nFROM a\nRUN echo 1 `\n  echo 2\n"))
                .containsExactly(new Dockerfiles.Instruction(2, "FROM", "a"),
                        new Dockerfiles.Instruction(3, "RUN", "echo 1  echo 2"));
    }

    @Test
    void check_readsOnlyTheDockerfilesOfTheRuleFile_andNoLink() throws IOException {
        write("images/a/Dockerfile", "FROM debian:12\n");
        write("other/Dockerfile", "FROM debian:12\n");
        write("images/target/Dockerfile", "FROM debian:12\n");
        Files.createDirectories(root.resolve("images/link"));
        Files.createSymbolicLink(root.resolve("images/link/Dockerfile"), root.resolve("images/a/Dockerfile"));
        write("images/bad/Dockerfile", "");
        Files.write(root.resolve("images/bad/Dockerfile"), new byte[] {(byte) 0xff, (byte) 0xfe});
        Facts facts = new Facts("offline");

        Dockerfiles.check(root, RuleFile.parse("dockerfile images/*/Dockerfile"), facts);

        assertThat(facts.items).extracting(i -> i.check() + " " + i.where() + " " + i.detail()).containsExactlyInAnyOrder(
                "base_unpinned images/a/Dockerfile:1 no @sha256: digest: the tag can point to other bytes tomorrow",
                "dockerfile_not_read images/link/Dockerfile a symbolic link or no regular file, not read",
                "dockerfile_not_read images/bad/Dockerfile not UTF-8");
        assertThat(facts.facts).containsEntry("dockerfiles", 1L);
    }

    @Test
    void commands_splitAtOperatorsOutsideQuotes() {
        assertThat(Dockerfiles.commands("a 'b && c' && d \"e;f\"; g | h"))
                .containsExactly(List.of("a", "b && c"), List.of("d", "e;f"), List.of("g"), List.of("h"));
    }

    private record Run(List<String> checks, List<String> images, List<String> npm) {
    }

    private static Run check(String dockerfile) {
        Facts facts = new Facts("offline");
        List<Map<String, Object>> images = new ArrayList<>();
        List<Map<String, Object>> npm = new ArrayList<>();
        Dockerfiles.check("images/x/Dockerfile", dockerfile, facts, images, npm);
        return new Run(facts.items.stream().map(i -> i.check() + " " + i.subject()).toList(),
                images.stream().map(i -> i.get("reference") + " " + i.get("line")).toList(),
                npm.stream().map(i -> i.get("name") + " " + i.get("version") + " " + i.get("line")).toList());
    }

    private void write(String file, String text) throws IOException {
        Path path = root.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }
}
