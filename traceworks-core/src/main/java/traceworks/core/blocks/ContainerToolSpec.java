package traceworks.core.blocks;

import traceworks.core.model.BlockSettings;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tool-mode keys of a shell block: {@code container_args} instead of {@code command}, optionally SARIF as output
 * format. Read here in one method, so a check of the known config keys finds them in one place.
 *
 * @param network           whether the tool container gets network access ({@code network: true}); without it, it
 *                          has none
 * @param writableWorkspace whether the tool may write the workspace ({@code workspace_writable: true}); without it,
 *                          it sees the workspace read-only
 */
record ContainerToolSpec(String image, List<String> args, boolean sarif, String outputDir, String cacheDir,
                         String factPrefix, List<String> mounts, boolean mountDockerSocket, boolean network,
                         int timeoutSeconds, boolean writableWorkspace) {

    static final String DEFAULT_OUTPUT_DIR = "/out";

    /**
     * @throws traceworks.core.model.InvalidSettingException for a setting of the wrong type or out
     *                                                                        of range ({@link BlockSettings})
     */
    static ContainerToolSpec from(Map<String, Object> config, String blockName) {
        BlockSettings settings = BlockSettings.of("shell", config);
        String image = settings.text("container_image");
        // container_args that is no list: ContainerToolStep refuses it in its own words
        Object args = config.get("container_args");
        String outputDir = settings.text("output_dir");
        String cacheDir = settings.text("cache_dir");
        String prefix = settings.text("fact_prefix");
        return new ContainerToolSpec(
                image != null && !image.isBlank() ? image.strip() : null,
                args instanceof List<?> list ? list.stream().map(a -> a == null ? "" : a.toString()).toList() : null,
                "sarif".equals(settings.text("output_format")),
                outputDir != null && !outputDir.isBlank() ? outputDir.strip() : DEFAULT_OUTPUT_DIR,
                cacheDir != null && !cacheDir.isBlank() ? cacheDir.strip() : null,
                factPrefix(prefix != null && !prefix.isBlank() ? prefix : blockName),
                settings.texts("mounts", List.of()),
                settings.flag("mount_docker_socket"),
                settings.flag("network"),
                settings.number("timeout_seconds", ShellBlockExecutor.DEFAULT_TIMEOUT_SECONDS),
                settings.flag("workspace_writable"));
    }

    /** {@code [a-z0-9_]} only: the names must match the {@code FACT}/{@code HIT} grammar of the fact sheet. */
    static String factPrefix(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "tool" : s;
    }
}
