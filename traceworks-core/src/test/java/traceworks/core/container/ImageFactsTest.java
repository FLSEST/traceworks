package traceworks.core.container;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What a Dockerfile says about its image: the base it starts from and the description label. */
class ImageFactsTest {

    private static final String DOCKERFILE = """
            # The image of the architecture self-audit's deterministic steps.
            FROM eclipse-temurin:21-jdk-noble@sha256:06c1f157dacf6b68198bd9a4186dd4c34ef24e65d7bbbe2a0cf8e62571ad38c9
            LABEL org.opencontainers.image.description="the architecture tool on JDK 21: javac parses the sources"
            COPY src /opt/architecture/src
            """;

    @Test
    void facts_takeTheBaseFromTheFromLine_withoutItsDigest_andTheDescriptionLabel() {
        ImageFacts facts = ImageFacts.of("./images/architecture", DOCKERFILE);

        assertThat(facts.reference()).isEqualTo("./images/architecture");
        assertThat(facts.base()).isEqualTo("eclipse-temurin:21-jdk-noble");
        assertThat(facts.description()).isEqualTo("the architecture tool on JDK 21: javac parses the sources");
    }

    @Test
    void theLastFromLine_isTheBase_ofAMultiStageBuild() {
        ImageFacts facts = ImageFacts.of("./images/x", "FROM maven:3 AS build\nRUN mvn\nFROM debian:bookworm-slim\n");

        assertThat(facts.base()).isEqualTo("debian:bookworm-slim");
    }

    @Test
    void aLabelInSingleQuotes_orWithoutQuotes_orWithOtherLabels_isRead() {
        assertThat(ImageFacts.of("i", "FROM a\nLABEL org.opencontainers.image.description='one two'\n").description())
                .isEqualTo("one two");
        assertThat(ImageFacts.of("i", "FROM a\nLABEL org.opencontainers.image.description=plain\n").description())
                .isEqualTo("plain");
        assertThat(ImageFacts.of("i", "FROM a\nLABEL x=\"y\" org.opencontainers.image.description=\"d e\" z=1\n")
                .description()).isEqualTo("d e");
        assertThat(ImageFacts.of("i", "FROM a\nlabel ORG.opencontainers.image.description=\"case\"\n").description())
                .isEqualTo("case");
    }

    @Test
    void withoutALabel_orWithoutAFrom_theFactIsNull() {
        assertThat(ImageFacts.of("i", "FROM a\n").description()).isNull();
        assertThat(ImageFacts.of("i", "RUN true\n").base()).isNull();
        assertThat(ImageFacts.of("i", null)).isEqualTo(new ImageFacts("i", null, null));
    }

    /** A registry image has no Dockerfile in the package: nothing is known about it beyond its reference. */
    @Test
    void aRegistryImage_hasNoFacts() {
        assertThat(ImageFacts.of("alpine:3.20", null)).isEqualTo(new ImageFacts("alpine:3.20", null, null));
    }

    /** The viewer prints a description on one line, cut: a label that tries to be a page is kept short. */
    @Test
    void aLongDescription_isCutAt200Characters() {
        String description = ImageFacts.of("i", "FROM a\nLABEL org.opencontainers.image.description=\""
                + "x".repeat(300) + "\"\n").description();

        assertThat(description).hasSize(200).endsWith("…");
    }

    /**
     * A Dockerfile is a package's file. The engine matches a repeated group with one stack frame per repetition unless
     * the group gives nothing back, so a FROM line with thousands of flags, or a long quoted label, overflowed the
     * stack. Run on a small stack, so the test does not depend on the one of the build.
     */
    @Test
    void aLineBuiltToNestTheMatch_isReadWithoutAStackOverflow() throws InterruptedException {
        String flags = " --platform=linux/amd64".repeat(5000);
        String text = "a \\\" b ".repeat(2000);   // 14 KB, with escaped quotes
        String dockerfile = "FROM" + flags + " debian:bookworm-slim AS base\n"
                + "LABEL other=1 " + ImageFacts.DESCRIPTION_LABEL + "=\"" + text + "\" more='x'\n";
        java.util.concurrent.atomic.AtomicReference<Object> result = new java.util.concurrent.atomic.AtomicReference<>();
        Thread smallStack = new Thread(null, () -> {
            try {
                result.set(ImageFacts.of("./images/x", dockerfile));
            } catch (Throwable t) {
                result.set(t);
            }
        }, "image-facts-small-stack", 256 * 1024);
        smallStack.start();
        smallStack.join(30_000);

        assertThat(smallStack.isAlive()).isFalse();
        assertThat(result.get()).isInstanceOf(ImageFacts.class);
        ImageFacts facts = (ImageFacts) result.get();
        assertThat(facts.base()).isEqualTo("debian:bookworm-slim");
        assertThat(facts.description()).startsWith("a \" b a \" b").hasSize(ImageFacts.DESCRIPTION_LIMIT);
    }

    /** What the patterns read is the same as before they stopped giving back. */
    @Test
    void flagsQuotesAndBareValues_areReadAsBefore() {
        assertThat(ImageFacts.of("x", "from --platform=linux/amd64 --x=y  debian:12 as b").base()).isEqualTo("debian:12");
        assertThat(ImageFacts.of("x", "FROM debian:12").base()).isEqualTo("debian:12");
        assertThat(ImageFacts.of("x", "FROM").base()).isNull();
        assertThat(ImageFacts.of("x", "FROM a b c").base()).isNull();
        String label = "LABEL " + ImageFacts.DESCRIPTION_LABEL;
        assertThat(ImageFacts.of("x", label + "=\"say \\\"hi\\\" now\"").description()).isEqualTo("say \"hi\" now");
        assertThat(ImageFacts.of("x", label + "='single quoted'").description()).isEqualTo("single quoted");
        assertThat(ImageFacts.of("x", label + "=bare").description()).isEqualTo("bare");
        assertThat(ImageFacts.of("x", label + "=\"\"").description()).isNull();
        // A quote that never closes is a bare value up to the next space, as it was
        assertThat(ImageFacts.of("x", label + "=\"open ended").description()).isEqualTo("\"open");
    }

    /** A package writes its Dockerfile, so the base is bounded as the description is: it goes into every record. */
    @Test
    void aBaseOfAnyLength_isCutToTheLimitOfADescription() {
        ImageFacts facts = ImageFacts.of("x", "FROM registry.example/" + "a".repeat(5000) + ":1@sha256:abc");

        assertThat(facts.base()).hasSize(ImageFacts.DESCRIPTION_LIMIT).startsWith("registry.example/aaa")
                .endsWith("…");
    }
}
