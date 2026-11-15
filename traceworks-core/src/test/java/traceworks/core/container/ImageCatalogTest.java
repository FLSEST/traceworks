package traceworks.core.container;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which image a {@code container_image} names, and the tags Traceworks builds its images under. */
class ImageCatalogTest {

    private static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private final ImageCatalog catalog = new ImageCatalog(Map.of("shell", "traceworks/shell:0123456789ab",
            "claude", "traceworks/claude:fedcba987654"), Map.of("tools", "traceworks-pkg/pkg-1/tools:abcdef012345"));

    @Test
    void noImage_isTheShellImage() {
        assertThat(catalog.reference(null)).isEqualTo("traceworks/shell:0123456789ab");
        assertThat(new ImageCatalog(Map.of(), Map.of()).reference(null)).as("not built yet").isNull();
    }

    @Test
    void packageImage_isItsPackageTag() {
        assertThat(catalog.reference("./images/tools")).isEqualTo("traceworks-pkg/pkg-1/tools:abcdef012345");
        assertThat(catalog.reference("./images/unknown")).as("not in the package").isNull();
        assertThat(catalog.reference("./images/../tools")).as("not an image name").isNull();
    }

    @Test
    void registryImage_isItself() {
        assertThat(catalog.reference("alpine@sha256:" + DIGEST)).isEqualTo("alpine@sha256:" + DIGEST);
        assertThat(catalog.reference("node:22-slim")).isEqualTo("node:22-slim");
    }

    @Test
    void builtIn_areTheShellImage_andTheBuiltInTags() {
        assertThat(catalog.builtIn(null)).isTrue();
        assertThat(catalog.builtIn("traceworks/claude:fedcba987654")).isTrue();
        assertThat(catalog.builtIn("./images/tools")).isFalse();
        assertThat(catalog.builtIn("alpine:3")).isFalse();
    }

    @Test
    void packageImage_areTheImagesDirectoryForm_andThePackageTags() {
        assertThat(catalog.packageImage("./images/tools")).isTrue();
        assertThat(catalog.packageImage("./images/unknown")).isTrue();
        assertThat(catalog.packageImage("traceworks-pkg/pkg-1/tools:abcdef012345")).isTrue();
        assertThat(catalog.packageImage(null)).isFalse();
        assertThat(catalog.packageImage("alpine:3")).isFalse();
    }

    @Test
    void packageImageName_isTheNameAfterTheImagesDirectory_whenItIsOne() {
        assertThat(ImageCatalog.validPackageImageName("./images/tools")).isTrue();
        assertThat(ImageCatalog.validPackageImageName("./images/a.b_c-1")).isTrue();
        for (String bad : new String[] {"./images/Tools", "./images/../x", "./images/", "./images/-x", "./images/a/b"}) {
            assertThat(ImageCatalog.validPackageImageName(bad)).as(bad).isFalse();
        }
    }

    @Test
    void tag_isTheRepositoryAndTheFirst12HexCharactersOfTheDirectoryDigest() {
        assertThat(ImageCatalog.tag("traceworks/shell", DIGEST)).isEqualTo("traceworks/shell:0123456789ab");
        assertThat(ImageCatalog.tag("traceworks-pkg/pkg-1/tools", "sha256:" + DIGEST))
                .isEqualTo("traceworks-pkg/pkg-1/tools:0123456789ab");
    }

    @Test
    void tag_refusesWhatIsNoDigest() {
        for (String bad : new String[] {"0123456789a", "0123456789AB", "xyz0123456789ab", ""}) {
            assertThatThrownBy(() -> ImageCatalog.tag("traceworks/shell", bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ImageCatalog.tag(" ", DIGEST)).isInstanceOf(IllegalArgumentException.class);
    }
}
