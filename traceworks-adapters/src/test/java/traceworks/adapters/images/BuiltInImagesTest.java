package traceworks.adapters.images;

import traceworks.adapters.pkg.ImageDirectories;
import traceworks.adapters.pkg.PackageDigest;
import traceworks.core.analyzer.AnalyzerFinding;
import traceworks.core.analyzer.WorkflowAnalyzer;
import traceworks.core.app.Images;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.BuiltInImageSource.BuildContext;
import traceworks.core.port.PackageImage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInImagesTest {

    /** The module directory is the working directory of Surefire. */
    private static final Path REPOSITORY_IMAGES = Path.of("../images").toAbsolutePath().normalize();

    private final BuiltInImages builtIns = new BuiltInImages();

    @Test
    void all_isTheFourImages_shellFirst_withTheDigestOfTheRepositoryDirectory() {
        List<PackageImage> all = builtIns.all();

        assertThat(all).extracting(PackageImage::name).containsExactly("shell", "claude", "pi", "egress");
        for (PackageImage image : all) {
            assertThat(image.digest()).isEqualTo(PackageDigest.of(REPOSITORY_IMAGES.resolve(image.name())));
            assertThat(image.directory()).isNull();
            assertThat(image.dockerfile()).startsWith("#").contains("FROM ");
            assertThat(Images.builtInTag(image)).matches("traceworks/" + image.name() + ":[0-9a-f]{12}");
        }
    }

    @Test
    void context_writesTheFilesAndRemovesThemOnClose() throws IOException {
        PackageImage shell = builtIns.all().getFirst();
        Path directory;
        try (BuildContext context = builtIns.context(shell)) {
            directory = Path.of(context.directory());
            assertThat(directory.resolve("Dockerfile")).hasContent(shell.dockerfile());
            assertThat(PackageDigest.of(directory)).isEqualTo(shell.digest());
        }
        assertThat(directory).doesNotExist();
    }

    @Test
    void repositoryDockerfiles_pinTheirBasesByDigest() throws IOException {
        var images = ImageDirectories.in(REPOSITORY_IMAGES);
        assertThat(images.keySet()).containsExactlyInAnyOrder("shell", "claude", "pi", "egress");

        WorkflowDefinition empty = new WorkflowDefinition("id", "n", null, List.of(), null, null, null, null, null, null,
                null, "traceworks-workflow", 1);
        List<AnalyzerFinding> findings = new WorkflowAnalyzer().analyze(empty, null, images.values());

        assertThat(findings).extracting(AnalyzerFinding::rule).doesNotContain("unpinned-base-image");
        for (PackageImage image : images.values()) {
            assertThat(image.dockerfile()).contains("@sha256:").doesNotContainPattern("(?m)^\\s*USER\\b");
            assertThat(Files.isRegularFile(REPOSITORY_IMAGES.resolve(image.name()).resolve("Dockerfile"))).isTrue();
        }
    }

    /** The proxy's image carries its source, which the build compiles: the context holds it. */
    @Test
    void theEgressImage_carriesTheSourceOfTheProxy() throws IOException {
        PackageImage egress = builtIns.all().getLast();
        try (BuildContext context = builtIns.context(egress)) {
            assertThat(Path.of(context.directory()).resolve("src/traceworks/egress/Egress.java"))
                    .isRegularFile();
            assertThat(PackageDigest.of(Path.of(context.directory()))).isEqualTo(egress.digest());
        }
        assertThat(egress.dockerfile()).contains("COPY src ", "ENTRYPOINT [\"java\"");
    }
}
