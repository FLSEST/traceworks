package traceworks.core.analyzer;

import traceworks.core.analyzer.AnalyzerFinding.Severity;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.WorkflowDefinition;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Rules on the {@code workspace} setting of a definition ({@code metadata.workspace}, or {@code workspace} in the
 * first block's config). Traceworks makes no clone for a workflow: a run works in the workspace the operator gives
 * it, and the operator isolates a run with {@code run --source}. Of the setting, only {@code mode} decides anything:
 * {@code project} is accepted and changes nothing, {@code isolated} refuses the workflow
 * ({@code unsupported-workspace-isolation} in {@link TraceworksRules}, the run admission), and any other value is
 * {@code unknown-workspace-mode}. Nothing else of the setting is read; these rules report what would not do what it
 * says.
 * <ul>
 *   <li>{@code legacy-install-npm} ({@code WARNING}): {@code workspace.install_npm} asks for an {@code npm ci} that
 *       no block runs; Traceworks does not run it. The replacement is a shell step {@code npm ci} at the start.</li>
 *   <li>{@code unknown-workspace-mode} ({@code ERROR}): a {@code mode} other than {@code project} and
 *       {@code isolated}.</li>
 *   <li>{@code workspace-not-on-first-block} ({@code ERROR}): {@code workspace} on a later block, where it is not
 *       read: a {@code mode: isolated} there does not even refuse the workflow.</li>
 *   <li>{@code build-in-project-path} ({@code WARNING}): a definition with {@code mode: isolated}, which is refused
 *       anyway, changes into {@code {{ project_path }}} ({@code cd}, {@code pushd}). Reading from it or
 *       {@code git -C {{ project_path }}} is not reported.</li>
 * </ul>
 */
final class WorkspaceRules {

    private static final Pattern CD_PROJECT = Pattern.compile("(?<![\\w-])(?:cd|pushd)\\s+[\"']?\\{\\{\\s*project_path\\s*}}");

    private WorkspaceRules() {}

    static void apply(WorkflowDefinition workflow, List<AnalyzerFinding> findings) {
        List<BlockDefinition> blocks = workflow.blocks();
        Map<String, Object> metadata = workflow.metadata();
        BlockDefinition firstBlock = blocks.getFirst();
        Object own = metadata.get("workspace");
        Object first = workspaceOf(firstBlock);
        String firstName = firstBlock == null ? null : firstBlock.getName();
        if (installsNpm(own)) findings.add(legacyInstallNpm(-1, null, "metadata.workspace"));
        if (installsNpm(first)) findings.add(legacyInstallNpm(0, firstName, "workspace"));
        unknownMode(own, -1, null, "metadata.workspace", findings);
        unknownMode(first, 0, firstName, "workspace", findings);
        for (int i = 1; i < blocks.size(); i++) {
            if (workspaceOf(blocks.get(i)) instanceof Map<?, ?>) findings.add(notOnFirstBlock(i, blocks.get(i).getName()));
        }
        if (!workflow.requestsIsolatedWorkspace()) return;
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) continue;
            if (BlockText.allStrings(block).stream().anyMatch(text -> CD_PROJECT.matcher(text).find())) {
                findings.add(buildInProjectPath(i, block.getName()));
            }
        }
    }

    private static Object workspaceOf(BlockDefinition block) {
        return block != null && block.getConfig() != null ? block.getConfig().get("workspace") : null;
    }

    private static boolean installsNpm(Object workspace) {
        return workspace instanceof Map<?, ?> config && Boolean.TRUE.equals(config.get("install_npm"));
    }

    private static void unknownMode(Object workspace, int index, String name, String where, List<AnalyzerFinding> findings) {
        if (!(workspace instanceof Map<?, ?> config) || config.get("mode") == null
                || List.of("isolated", "project").contains(config.get("mode"))) return;
        findings.add(AnalyzerFinding.of("unknown-workspace-mode", Severity.ERROR, index, name,
                where + ".mode \"" + config.get("mode") + "\" does not exist."
                        + " Traceworks accepts project (the run works in its workspace as it is) and refuses"
                        + " isolated (the operator isolates a run with run --source)."));
    }

    private static AnalyzerFinding notOnFirstBlock(int index, String name) {
        return AnalyzerFinding.of("workspace-not-on-first-block", Severity.ERROR, index, name,
                "workspace is set on block " + (index + 1) + ", but it is only read on the first block or in "
                        + "metadata.workspace — so the run is not isolated. Move the setting to the first block or "
                        + "to metadata.workspace.");
    }

    private static AnalyzerFinding buildInProjectPath(int index, String name) {
        return AnalyzerFinding.of("build-in-project-path", Severity.WARNING, index, name,
                "changes into {{ project_path }} although the run is isolated — building and changing then happen in "
                        + "the original folder instead of its own clone. Use {{ execution_path }}; only read "
                        + "{{ project_path }} or hand over on purpose (git -C).");
    }

    private static AnalyzerFinding legacyInstallNpm(int index, String name, String where) {
        return AnalyzerFinding.of("legacy-install-npm", Severity.WARNING, index, name,
                where + ".install_npm asks for an npm ci hidden in the workspace preparation, at fixed places and "
                        + "outside the block model — Traceworks does not run it. Add a shell step running npm ci "
                        + "where the package-lock.json lives as the first block instead (the workspace setting moves "
                        + "to that block), and remove install_npm.");
    }
}
