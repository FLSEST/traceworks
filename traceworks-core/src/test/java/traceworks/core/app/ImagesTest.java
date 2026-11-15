package traceworks.core.app;

import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.BuiltInImageSource;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.PackageImage;
import traceworks.core.port.PackageLoader;
import traceworks.core.testing.FakeContainerRuntime;
import traceworks.core.testing.MapWorkflowResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImagesTest {

    private static final String DIGEST_A = "aaaaaaaaaaaa" + "0".repeat(52);
    private static final String DIGEST_B = "bbbbbbbbbbbb" + "0".repeat(52);

    private final FakeContainerRuntime runtime = new FakeContainerRuntime();
    private final List<String> contextsOpened = new ArrayList<>();
    private final List<String> contextsClosed = new ArrayList<>();

    private final BuiltInImageSource builtIns = new BuiltInImageSource() {
        @Override
        public List<PackageImage> all() {
            return List.of(new PackageImage("shell", null, DIGEST_A, "FROM x"),
                    new PackageImage("claude", null, DIGEST_B, "FROM x"),
                    new PackageImage("pi", null, DIGEST_B, "FROM x"),
                    new PackageImage("egress", null, DIGEST_B, "FROM x"));
        }

        @Override
        public BuildContext context(PackageImage image) {
            contextsOpened.add(image.name());
            return new BuildContext() {
                @Override
                public String directory() {
                    return "/context/" + image.name();
                }

                @Override
                public void close() {
                    contextsClosed.add(image.name());
                }
            };
        }
    };

    private final Images images = new Images(runtime, builtIns, loaderOf(location -> {
        throw new UnsupportedOperationException();
    }));

    private static PackageLoader loaderOf(java.util.function.Function<Path, LoadedPackage> load) {
        return new PackageLoader() {
            @Override
            public LoadedPackage load(Path location) {
                return load.apply(location);
            }

            @Override
            public List<Path> packagesIn(Path directory) {
                return List.of();
            }

            @Override
            public java.util.Optional<String> idOf(Path packageDirectory) {
                return java.util.Optional.empty();
            }
        };
    }

    private static BlockDefinition block(String type, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(type);
        b.setConfig(new HashMap<>(config));
        return b;
    }

    private static LoadedPackage pkg(String provider, Map<String, PackageImage> packageImages,
                                     BlockDefinition... blocks) {
        WorkflowDefinition definition = new WorkflowDefinition("pkg-id", "p", null, Arrays.asList(blocks), null, null,
                provider, null, null, null, null, "traceworks-workflow", 1);
        return new LoadedPackage(Path.of("/pkg"), definition, new byte[0], "d", null, null, packageImages);
    }

    private static Map<String, PackageImage> packageImages(String... names) {
        Map<String, PackageImage> map = new LinkedHashMap<>();
        for (String name : names) {
            map.put(name, new PackageImage(name, "/pkg/images/" + name, DIGEST_A, "FROM x"));
        }
        return map;
    }

    @Test
    void builtInTags_followTheDirectoryDigest() {
        assertThat(Images.builtInTag(new PackageImage("shell", null, DIGEST_A, "")))
                .isEqualTo("traceworks/shell:aaaaaaaaaaaa");
        assertThat(Images.builtInTag(new PackageImage("shell", null, DIGEST_B, "")))
                .isEqualTo("traceworks/shell:bbbbbbbbbbbb").matches("traceworks/shell:[0-9a-f]{12}");
    }

    @Test
    void packageImageTag_namesThePackageId() {
        assertThat(Images.packageTag("pkg-id", new PackageImage("tools", "/x", DIGEST_B, "")))
                .isEqualTo("traceworks-pkg/pkg-id/tools:bbbbbbbbbbbb");
    }

    @Test
    void build_withoutPackage_buildsTheBuiltInImages_inOrder_shellFirst() {
        Images.Built report = images.build(null);
        List<String> lines = report.lines();

        assertThat(runtime.built()).containsExactly("traceworks/shell:aaaaaaaaaaaa", "traceworks/claude:bbbbbbbbbbbb",
                "traceworks/pi:bbbbbbbbbbbb", "traceworks/egress:bbbbbbbbbbbb");
        assertThat(lines).containsExactly("built traceworks/shell:aaaaaaaaaaaa", "built traceworks/claude:bbbbbbbbbbbb",
                "built traceworks/pi:bbbbbbbbbbbb", "built traceworks/egress:bbbbbbbbbbbb");
        assertThat(contextsOpened).containsExactly("shell", "claude", "pi", "egress");
        assertThat(contextsClosed).isEqualTo(contextsOpened);
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void build_withPackage_buildsTheShellImage_itsImages_andPullsLiteralRegistryImages_once() {
        Images withPackage = new Images(runtime, builtIns, loaderOf(location -> pkg(null, packageImages("tools", "lint"),
                block("shell", Map.of("container_image", "alpine:3")),
                block("shell", Map.of("container_image", "./images/tools")),
                block("switch", Map.of("cases", List.of(Map.of("blocks", List.of(Map.of("type", "shell",
                        "config", Map.of("container_image", "alpine:3")))))
                )),
                block("shell", Map.of("container_image", "{{image}}")),
                block("shell", Map.of("command", "true")))));

        List<String> lines = withPackage.build(Path.of("/pkg")).lines();

        assertThat(runtime.built()).containsExactly("traceworks/shell:aaaaaaaaaaaa",
                "traceworks-pkg/pkg-id/tools:aaaaaaaaaaaa", "traceworks-pkg/pkg-id/lint:aaaaaaaaaaaa");
        assertThat(runtime.pulled()).containsExactly("alpine:3");
        assertThat(lines).containsExactly("built traceworks/shell:aaaaaaaaaaaa",
                "built traceworks-pkg/pkg-id/tools:aaaaaaaaaaaa", "built traceworks-pkg/pkg-id/lint:aaaaaaaaaaaa",
                "pulled alpine:3");
        assertThat(contextsOpened).as("no AI step: only the shell image of the built-in ones").containsExactly("shell");
    }

    @Test
    void build_reportsEveryRuntimeFailure_andStillBuildsTheOthers() {
        traceworks.core.port.ContainerRuntime failing =
                org.mockito.Mockito.mock(traceworks.core.port.ContainerRuntime.class);
        try {
            org.mockito.Mockito.doThrow(new java.io.IOException("docker build failed: no space left"))
                    .when(failing).build(org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyString());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }

        Images.Built report = new Images(failing, builtIns, loaderOf(l -> null)).build(null);

        assertThat(report.lines()).isEmpty();
        assertThat(report.failures()).hasSize(4).allSatisfy(f -> assertThat(f).contains("no space left"));
        assertThat(report.failures().getFirst()).startsWith("failed to build traceworks/shell:aaaaaaaaaaaa: ");
    }

    /** One image that fails to build is reported, and the images after it are built all the same. */
    @Test
    void build_oneFailure_isReported_andTheImagesAfterItAreBuilt() throws java.io.IOException {
        traceworks.core.port.ContainerRuntime failing =
                org.mockito.Mockito.spy(new FakeContainerRuntime());
        org.mockito.Mockito.doThrow(new java.io.IOException("npm: network unreachable")).when(failing)
                .build(org.mockito.ArgumentMatchers.eq("traceworks/claude:bbbbbbbbbbbb"),
                        org.mockito.ArgumentMatchers.anyString());

        Images.Built report = new Images(failing, builtIns, loaderOf(l -> null)).build(null);

        assertThat(report.lines()).containsExactly("built traceworks/shell:aaaaaaaaaaaa",
                "built traceworks/pi:bbbbbbbbbbbb", "built traceworks/egress:bbbbbbbbbbbb");
        assertThat(report.failures()).containsExactly("failed to build traceworks/claude:bbbbbbbbbbbb: npm: network"
                + " unreachable");
    }

    /**
     * With a package, the built-in images it uses: {@code shell}, which every container run needs, the image of
     * each AI provider its steps and its literal children's use, and with any of them the egress proxy of an AI step;
     * and the registry images the children name.
     */
    @Test
    void build_withPackage_buildsOnlyTheBuiltInImagesThePackageAndItsLiteralChildrenUse() {
        WorkflowDefinition child = new WorkflowDefinition("wf-child", "c", null, List.of(
                block("simple", Map.of("command", "hi", "provider", "PI")),
                block("shell", Map.of("container_image", "busybox:1"))), null, null, null, null, null, null, null,
                "traceworks-workflow", 1);
        Images withPackage = new Images(runtime, builtIns, loaderOf(location -> location.normalize().equals(
                Path.of("/pkg/child")) ? new LoadedPackage(Path.of("/pkg/child"), child, new byte[0], "d", null, null)
                : pkg(null, Map.of(), block("simple", Map.of("command", "hi")),
                        block("sub-workflow", Map.of("workflow_id", "./child")))));

        Images.Built report = withPackage.build(Path.of("/pkg"));

        assertThat(report.lines()).containsExactly("built traceworks/shell:aaaaaaaaaaaa",
                "built traceworks/claude:bbbbbbbbbbbb", "built traceworks/pi:bbbbbbbbbbbb",
                "built traceworks/egress:bbbbbbbbbbbb", "pulled busybox:1");
        assertThat(contextsOpened).containsExactly("shell", "claude", "pi", "egress");
    }

    /** A package without an AI step needs no egress proxy: it is not built for it. */
    @Test
    void build_withPackageWithoutAnAiStep_doesNotBuildTheEgressImage() {
        Images withPackage = new Images(runtime, builtIns, loaderOf(location -> pkg(null, Map.of(),
                block("shell", Map.of("command", "true")))));

        assertThat(withPackage.build(Path.of("/pkg")).lines()).containsExactly("built traceworks/shell:aaaaaaaaaaaa");
    }

    /** A directory under images/ with a Dockerfile but a name that is no package image name is named, as a failure. */
    @Test
    void build_namesAnImageDirectoryWhoseNameIsNotValid() {
        LoadedPackage invalid = new LoadedPackage(Path.of("/pkg"), pkg(null, Map.of()).definition(), new byte[0], "d",
                null, null, packageImages("tools"), List.of("MyTool"));
        Images withPackage = new Images(runtime, builtIns, loaderOf(location -> invalid));

        Images.Built report = withPackage.build(Path.of("/pkg"));

        assertThat(report.lines()).contains("built traceworks-pkg/pkg-id/tools:aaaaaaaaaaaa");
        assertThat(report.failures()).singleElement().satisfies(f -> assertThat(f).contains("images/MyTool",
                "\"MyTool\" is not a valid package image name ([a-z0-9][a-z0-9._-]*)"));
    }

    @Test
    void missing_ofAnInvalidPackageImageName_saysSo_notThatTheDockerfileIsMissing() {
        runtime.present("traceworks/shell:aaaaaaaaaaaa", "sha256:1");

        assertThat(images.missing(pkg(null, Map.of(), block("shell", Map.of("container_image", "./images/MyTool"))),
                true, null)).singleElement().satisfies(l -> assertThat(l).isEqualTo("image ./images/MyTool:"
                + " \"MyTool\" is not a valid package image name ([a-z0-9][a-z0-9._-]*)"));
    }

    @Test
    void missing_listsEachMissingImage_withTheCommandThatProvidesIt() {
        LoadedPackage p = pkg(null, packageImages("tools"),
                block("shell", Map.of("container_image", "alpine:3")),
                block("shell", Map.of("container_image", "./images/tools")),
                block("shell", Map.of("container_image", "./images/ghost")),
                block("switch", Map.of("cases", List.of(Map.of("blocks", List.of(Map.of("type", "shell",
                        "config", Map.of("container_image", "busybox")))))
                )),
                block("shell", Map.of("container_image", "{{image}}")));

        List<String> lines = images.missing(p, true, null);

        assertThat(lines).hasSize(5);
        assertThat(lines).anyMatch(l -> l.contains("traceworks/shell:aaaaaaaaaaaa")
                && l.contains("traceworks images build"));
        assertThat(lines).anyMatch(l -> l.contains("alpine:3") && l.contains("traceworks images build /pkg"));
        assertThat(lines).anyMatch(l -> l.contains("busybox"));
        assertThat(lines).anyMatch(l -> l.contains("traceworks-pkg/pkg-id/tools:aaaaaaaaaaaa")
                && l.contains("traceworks images build /pkg"));
        assertThat(lines).anyMatch(l -> l.contains("./images/ghost") && l.contains("images/ghost"));
        assertThat(lines).noneMatch(l -> l.contains("{{"));
    }

    private static WorkflowDefinition workflow(String id, BlockDefinition... blocks) {
        return new WorkflowDefinition(id, id, null, Arrays.asList(blocks), null, null, null, null, null, null, null,
                "traceworks-workflow", 1);
    }

    /**
     * The literal children count too: their registry images must be there, and a package image named in a child of
     * another package is refused, since the run would resolve it against its own package. A child in the run's own
     * package uses the run's package images.
     */
    @Test
    void missing_looksIntoTheLiteralChildren_andRefusesAPackageImageOfAnotherPackage() {
        runtime.present("traceworks/shell:aaaaaaaaaaaa", "sha256:1")
                .present("traceworks-pkg/pkg-id/tools:aaaaaaaaaaaa", "sha256:2");
        MapWorkflowResolver children = new MapWorkflowResolver();
        children.addFromAnotherPackage("./other", workflow("wf-other",
                block("shell", Map.of("container_image", "./images/tools")),
                block("shell", Map.of("container_image", "busybox:1"))));
        children.add("./same", workflow("wf-same",
                block("shell", Map.of("container_image", "./images/tools"))));
        LoadedPackage p = pkg(null, packageImages("tools"),
                block("sub-workflow", Map.of("workflow_id", "./other")),
                block("foreach", Map.of("workflow_id", "./same", "items", "a,b")));

        List<String> lines = images.missing(p, true, children);

        assertThat(lines).hasSize(2);
        assertThat(lines).anyMatch(l -> l.contains("./images/tools") && l.contains("wf-other")
                && l.contains("another package"));
        assertThat(lines).anyMatch(l -> l.contains("busybox:1") && l.contains("is missing"));
    }

    @Test
    void missing_countsTheShellImageOnlyInAContainerRun_andOnlyWhenAbsent() {
        LoadedPackage p = pkg(null, Map.of());

        assertThat(images.missing(p, false, null)).isEmpty();
        assertThat(images.missing(p, true, null)).hasSize(1);

        runtime.present("traceworks/shell:aaaaaaaaaaaa", "sha256:1");
        assertThat(images.missing(p, true, null)).isEmpty();
    }

    /** A lookup that could not tell (it timed out) is reported with its own reason, never as a missing image. */
    @Test
    void missing_aLookupThatCouldNotTell_isReportedAsThat_notAsAMissingImage() {
        traceworks.core.port.ContainerRuntime slow =
                org.mockito.Mockito.spy(new FakeContainerRuntime());
        String why = "the lookup of image traceworks/shell:aaaaaaaaaaaa failed: it timed out after 30 seconds";
        org.mockito.Mockito.doReturn(new traceworks.core.port.ContainerRuntime.ImageLookup(null, why))
                .when(slow).lookup("traceworks/shell:aaaaaaaaaaaa");

        List<String> lines = new Images(slow, builtIns, loaderOf(l -> null)).missing(pkg(null, Map.of()), true, null);

        assertThat(lines).containsExactly(why);
    }

    private List<String> missingAiImages(String workflowProvider, BlockDefinition... blocks) {
        runtime.present("traceworks/shell:aaaaaaaaaaaa", "sha256:1");
        runtime.present("traceworks/egress:bbbbbbbbbbbb", "sha256:e");
        return images.missing(pkg(workflowProvider, Map.of(), blocks), false, null);
    }

    @Test
    void missing_anAiStepWithoutAnyProvider_needsTheDefaultProvidersImage() {
        assertThat(missingAiImages(null, block("simple", Map.of("command", "hi")))).singleElement()
                .satisfies(l -> assertThat(l).contains("traceworks/claude:bbbbbbbbbbbb"));
        assertThat(missingAiImages(null, block("shell", Map.of("command", "true")))).isEmpty();
    }

    /**
     * A run with an AI step needs the egress proxy, the only door of its AI containers, whichever image the CLI runs
     * in; not when the operator opened the AI network, and not for a run without an AI step.
     */
    @Test
    void missing_anAiStep_needsTheEgressImage_unlessTheAiNetworkIsOpen() {
        runtime.present("traceworks/shell:aaaaaaaaaaaa", "sha256:1");
        runtime.present("traceworks/claude:bbbbbbbbbbbb", "sha256:c");
        LoadedPackage ai = pkg(null, Map.of(), block("simple", Map.of("command", "hi")));
        Images open = new Images(runtime, builtIns, loaderOf(l -> null), List.of(), Map.of(),
                new traceworks.core.port.AiNetwork(true, Map.of()));

        assertThat(images.missing(ai, true, null)).containsExactly(
                "image traceworks/egress:bbbbbbbbbbbb is missing: run 'traceworks images build'");
        assertThat(open.missing(ai, true, null)).isEmpty();
        assertThat(images.missing(pkg(null, Map.of(), block("shell", Map.of("command", "true"))), true, null))
                .isEmpty();
        runtime.present("traceworks/egress:bbbbbbbbbbbb", "sha256:e");
        assertThat(images.missing(ai, true, null)).isEmpty();
    }

    /** An {@code ai_images} entry is checked in place of the built-in image of that CLI, with its own hint. */
    @Test
    void missing_anOperatorsAiImage_isCheckedInsteadOfTheBuiltInOne() {
        Images operator = new Images(runtime, builtIns, loaderOf(l -> null), List.of(),
                Map.of("claude", "registry.example/claude:7"));
        runtime.present("traceworks/shell:aaaaaaaaaaaa", "sha256:1");
        runtime.present("traceworks/egress:bbbbbbbbbbbb", "sha256:e");
        LoadedPackage p = pkg(null, Map.of(), block("simple", Map.of("command", "hi")),
                block("agentic", Map.of("goal", "g", "provider", "pi")));

        List<String> lines = operator.missing(p, false, null);

        assertThat(lines).hasSize(2);
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("image registry.example/claude:7 is missing")
                .contains("claude in ai_images of traceworks.json").contains("never builds or pulls")
                .doesNotContain("traceworks images build"));
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("traceworks/pi:bbbbbbbbbbbb")
                .contains("traceworks images build"));
        assertThat(lines).noneMatch(l -> l.contains("traceworks/claude:"));
        runtime.present("registry.example/claude:7", "sha256:2");
        assertThat(operator.missing(p, false, null)).singleElement().satisfies(l -> assertThat(l).contains("pi"));
    }

    @Test
    void missing_anOperatorsAiImage_isNotAskedForWhenNoStepUsesItsCli() {
        Images operator = new Images(runtime, builtIns, loaderOf(l -> null), List.of(),
                Map.of("claude", "registry.example/claude:7"));
        runtime.present("traceworks/shell:aaaaaaaaaaaa", "sha256:1");

        assertThat(operator.missing(pkg(null, Map.of(), block("shell", Map.of("command", "true"))), true, null))
                .isEmpty();
    }

    @Test
    void missing_anAiStepWithoutProvider_usesTheWorkflowsProvider() {
        assertThat(missingAiImages("PI", block("agentic", Map.of("goal", "g")))).singleElement()
                .satisfies(l -> assertThat(l).contains("traceworks/pi:bbbbbbbbbbbb"));
    }

    @Test
    void missing_aBlocksOwnProviderWins() {
        assertThat(missingAiImages("pi", block("simple", Map.of("command", "hi", "provider", "Claude"))))
                .singleElement().satisfies(l -> assertThat(l).contains("traceworks/claude:bbbbbbbbbbbb"));
    }

    @Test
    void packageId_isLowercasedForTheTag() {
        Images withPackage = new Images(runtime, builtIns, loaderOf(location -> new LoadedPackage(Path.of("/pkg"),
                new WorkflowDefinition("ABCDEF12-0000", "p", null, List.of(), null, null, null, null, null, null, null,
                        "traceworks-workflow", 1), new byte[0], "d", null, null, packageImages("tools"))));

        assertThat(withPackage.build(Path.of("/pkg")).lines()).contains(
                "built traceworks-pkg/abcdef12-0000/tools:aaaaaaaaaaaa");
    }

    @Test
    void packageId_withAColonOrASpace_isRefused() {
        for (String id : List.of("my:pkg", "my pkg", "a@b", "")) {
            LoadedPackage bad = new LoadedPackage(Path.of("/pkg"), new WorkflowDefinition(id, "p", null, List.of(), null,
                    null, null, null, null, null, null, "traceworks-workflow", 1), new byte[0], "d", null, null,
                    packageImages("tools"));
            Images withBad = new Images(runtime, builtIns, loaderOf(location -> bad));

            assertThatThrownBy(() -> withBad.build(Path.of("/pkg"))).isInstanceOf(RunRefusedException.class)
                    .hasMessageContaining("[a-z0-9][a-z0-9._-]*");
            assertThatThrownBy(() -> withBad.catalog(bad)).isInstanceOf(RunRefusedException.class);
            assertThat(withBad.missing(bad, false, null)).singleElement().satisfies(l -> assertThat(l)
                    .contains("[a-z0-9][a-z0-9._-]*"));
        }
        assertThat(runtime.built()).isEmpty();
    }
}
