package traceworks.core.record;

import traceworks.core.container.ImageFacts;
import traceworks.core.context.OutputBudget;
import traceworks.core.model.ModelPick;
import traceworks.core.model.TriggerSource;
import traceworks.core.port.AiNetwork;
import traceworks.core.port.LocalModels;
import traceworks.core.port.CommandRunner;

import java.util.List;
import java.util.Map;

/**
 * What the record says about the start of a run.
 *
 * @param packageDir    the directory of the workflow package
 * @param packageDigest the digest of the package's files
 * @param gitCommit     the commit of the package's repository; {@code null} when there is none
 * @param gitDirty      whether the package has uncommitted changes; {@code null} when there is no repository
 * @param workflowJson  the definition as it was run, stored as a blob
 * @param inputs        the inputs of the run, stored as a blob
 * @param execution        where the run runs its steps: {@code container}, or {@code host} for a host run
 * @param containerRuntime the container runtime the run uses; {@code null} for a host run that found none
 * @param outputLimitMib   the output limit of every command of the run, in MiB: the operator's
 *                         {@code output_limit_mib}
 * @param aiImages         the images the operator's {@code ai_images} replaces the built-in AI images with, by CLI id
 *                         in lower case; empty for none
 * @param workspace        the workspace directory
 * @param workspaceSource    the source the workspace is a checkout of ({@code --source}), without the credentials of a
 *                           URL; {@code null} for a run without one
 * @param workspaceSourceSha the commit the checkout is at; {@code null} for a run without one
 * @param inputsFrom         the run whose inputs this run took ({@code --inputs-from}); {@code null} for none
 * @param images             what each image the run may use holds ({@link ImageFacts}): the built-in images of
 *                           this distribution and the package's own, from their Dockerfiles
 * @param runOutputBudgetMib how much output text the run may hold in memory, in MiB: the operator's
 *                           {@code run_output_budget_mib}, or its default, the number in force
 * @param aiNetwork          where the containers of the AI CLIs can connect: the operator's {@code ai_network} and
 *                           {@code ai_hosts}; absent is {@link AiNetwork#RESTRICTED}
 * @param seal               the seal the package carried and the seal check accepted; {@code null} for a package
 *                           that is not sealed
 * @param modelChoices       the option the run picked of each model choice its workflow declares; empty for a
 *                           workflow without one, whose {@code run.started} then has no such field
 * @param register           what the register of an audit workflow handed the run: the head of its log and the files
 *                           of the run's register folder; {@code null} for a workflow that is no audit, whose
 *                           {@code run.started} then has no such field
 * @param localModels        the model servers of the operator's own ({@code local_models}); empty for a home
 *                           without one, whose {@code run.started} then has no such field
 * @param project            the key of the project the run works on, the operator's word; {@code null} for a run
 *                           that names none, whose {@code run.started} then has no such field
 * @param agent              the agent that asked for the start under the actor, by the name it gave itself;
 *                           {@code null} for a start no agent claims, whose {@code run.started} then has no such
 *                           field
 */
public record RunStart(String workflowId, String workflowName, String packageDir, String packageDigest,
                       String gitCommit, Boolean gitDirty, byte[] workflowJson, Map<String, Object> inputs,
                       TriggerSource trigger, String actor, String version, String execution,
                       Runtime containerRuntime, int outputLimitMib, Map<String, String> aiImages, String workspace,
                       String workspaceSource, String workspaceSourceSha, int runOutputBudgetMib, String inputsFrom,
                       List<ImageFacts> images, AiNetwork aiNetwork, SealView seal, List<ModelPick> modelChoices,
                       RegisterHandover register, LocalModels localModels, String project, String agent) {

    /** The start of a run that no agent asked for. */
    public RunStart(String workflowId, String workflowName, String packageDir, String packageDigest, String gitCommit,
                    Boolean gitDirty, byte[] workflowJson, Map<String, Object> inputs, TriggerSource trigger,
                    String actor, String version, String execution, Runtime containerRuntime, int outputLimitMib,
                    Map<String, String> aiImages, String workspace, String workspaceSource,
                    String workspaceSourceSha, int runOutputBudgetMib, String inputsFrom, List<ImageFacts> images,
                    AiNetwork aiNetwork, SealView seal, List<ModelPick> modelChoices, RegisterHandover register,
                    LocalModels localModels, String project) {
        this(workflowId, workflowName, packageDir, packageDigest, gitCommit, gitDirty, workflowJson, inputs, trigger,
                actor, version, execution, containerRuntime, outputLimitMib, aiImages, workspace, workspaceSource,
                workspaceSourceSha, runOutputBudgetMib, inputsFrom, images, aiNetwork, seal, modelChoices, register,
                localModels, project, null);
    }

    /** The start of a run that names no project. */
    public RunStart(String workflowId, String workflowName, String packageDir, String packageDigest, String gitCommit,
                    Boolean gitDirty, byte[] workflowJson, Map<String, Object> inputs, TriggerSource trigger,
                    String actor, String version, String execution, Runtime containerRuntime, int outputLimitMib,
                    Map<String, String> aiImages, String workspace, String workspaceSource,
                    String workspaceSourceSha, int runOutputBudgetMib, String inputsFrom, List<ImageFacts> images,
                    AiNetwork aiNetwork, SealView seal, List<ModelPick> modelChoices, RegisterHandover register,
                    LocalModels localModels) {
        this(workflowId, workflowName, packageDir, packageDigest, gitCommit, gitDirty, workflowJson, inputs, trigger,
                actor, version, execution, containerRuntime, outputLimitMib, aiImages, workspace, workspaceSource,
                workspaceSourceSha, runOutputBudgetMib, inputsFrom, images, aiNetwork, seal, modelChoices, register,
                localModels, null);
    }

    /** This start, asked for by {@code agent} under the actor; {@code null} for none. */
    public RunStart by(String agent) {
        return new RunStart(workflowId, workflowName, packageDir, packageDigest, gitCommit, gitDirty, workflowJson,
                inputs, trigger, actor, version, execution, containerRuntime, outputLimitMib, aiImages, workspace,
                workspaceSource, workspaceSourceSha, runOutputBudgetMib, inputsFrom, images, aiNetwork, seal,
                modelChoices, register, localModels, project, agent);
    }

    /** The start of a run in a home without a model server of the operator's. */
    public RunStart(String workflowId, String workflowName, String packageDir, String packageDigest, String gitCommit,
                    Boolean gitDirty, byte[] workflowJson, Map<String, Object> inputs, TriggerSource trigger,
                    String actor, String version, String execution, Runtime containerRuntime, int outputLimitMib,
                    Map<String, String> aiImages, String workspace, String workspaceSource,
                    String workspaceSourceSha, int runOutputBudgetMib, String inputsFrom, List<ImageFacts> images,
                    AiNetwork aiNetwork, SealView seal, List<ModelPick> modelChoices, RegisterHandover register) {
        this(workflowId, workflowName, packageDir, packageDigest, gitCommit, gitDirty, workflowJson, inputs, trigger,
                actor, version, execution, containerRuntime, outputLimitMib, aiImages, workspace, workspaceSource,
                workspaceSourceSha, runOutputBudgetMib, inputsFrom, images, aiNetwork, seal, modelChoices, register,
                LocalModels.NONE);
    }

    /** The start of a run of a workflow that is no audit. */
    public RunStart(String workflowId, String workflowName, String packageDir, String packageDigest, String gitCommit,
                    Boolean gitDirty, byte[] workflowJson, Map<String, Object> inputs, TriggerSource trigger,
                    String actor, String version, String execution, Runtime containerRuntime, int outputLimitMib,
                    Map<String, String> aiImages, String workspace, String workspaceSource,
                    String workspaceSourceSha, int runOutputBudgetMib, String inputsFrom, List<ImageFacts> images,
                    AiNetwork aiNetwork, SealView seal, List<ModelPick> modelChoices) {
        this(workflowId, workflowName, packageDir, packageDigest, gitCommit, gitDirty, workflowJson, inputs, trigger,
                actor, version, execution, containerRuntime, outputLimitMib, aiImages, workspace, workspaceSource,
                workspaceSourceSha, runOutputBudgetMib, inputsFrom, images, aiNetwork, seal, modelChoices, null);
    }

    /** The start of a run of a workflow without a model choice. */
    public RunStart(String workflowId, String workflowName, String packageDir, String packageDigest, String gitCommit,
                    Boolean gitDirty, byte[] workflowJson, Map<String, Object> inputs, TriggerSource trigger,
                    String actor, String version, String execution, Runtime containerRuntime, int outputLimitMib,
                    Map<String, String> aiImages, String workspace, String workspaceSource,
                    String workspaceSourceSha, int runOutputBudgetMib, String inputsFrom, List<ImageFacts> images,
                    AiNetwork aiNetwork, SealView seal) {
        this(workflowId, workflowName, packageDir, packageDigest, gitCommit, gitDirty, workflowJson, inputs, trigger,
                actor, version, execution, containerRuntime, outputLimitMib, aiImages, workspace, workspaceSource,
                workspaceSourceSha, runOutputBudgetMib, inputsFrom, images, aiNetwork, seal, List.of());
    }

    /** The start of a run of a package that is not sealed. */
    public RunStart(String workflowId, String workflowName, String packageDir, String packageDigest, String gitCommit,
                    Boolean gitDirty, byte[] workflowJson, Map<String, Object> inputs, TriggerSource trigger,
                    String actor, String version, String execution, Runtime containerRuntime, int outputLimitMib,
                    Map<String, String> aiImages, String workspace, String workspaceSource,
                    String workspaceSourceSha, int runOutputBudgetMib, String inputsFrom, List<ImageFacts> images,
                    AiNetwork aiNetwork) {
        this(workflowId, workflowName, packageDir, packageDigest, gitCommit, gitDirty, workflowJson, inputs, trigger,
                actor, version, execution, containerRuntime, outputLimitMib, aiImages, workspace, workspaceSource,
                workspaceSourceSha, runOutputBudgetMib, inputsFrom, images, aiNetwork, null);
    }

    public RunStart {
        aiNetwork = aiNetwork == null ? AiNetwork.RESTRICTED : aiNetwork;
        aiImages = aiImages == null ? Map.of() : Map.copyOf(aiImages);
        images = images == null ? List.of() : List.copyOf(images);
        modelChoices = modelChoices == null ? List.of() : List.copyOf(modelChoices);
        localModels = localModels == null ? LocalModels.NONE : localModels;
    }

    /** The start of a run with the restricted AI network, the default. */
    public RunStart(String workflowId, String workflowName, String packageDir, String packageDigest, String gitCommit,
                    Boolean gitDirty, byte[] workflowJson, Map<String, Object> inputs, TriggerSource trigger,
                    String actor, String version, String execution, Runtime containerRuntime, int outputLimitMib,
                    Map<String, String> aiImages, String workspace, String workspaceSource,
                    String workspaceSourceSha, int runOutputBudgetMib, String inputsFrom, List<ImageFacts> images) {
        this(workflowId, workflowName, packageDir, packageDigest, gitCommit, gitDirty, workflowJson, inputs, trigger,
                actor, version, execution, containerRuntime, outputLimitMib, aiImages, workspace, workspaceSource,
                workspaceSourceSha, runOutputBudgetMib, inputsFrom, images, AiNetwork.RESTRICTED);
    }

    /** The start of a run whose inputs came from no earlier run and that records no image facts (the tests). */
    public RunStart(String workflowId, String workflowName, String packageDir, String packageDigest, String gitCommit,
                    Boolean gitDirty, byte[] workflowJson, Map<String, Object> inputs, TriggerSource trigger,
                    String actor, String version, String execution, Runtime containerRuntime, int outputLimitMib,
                    Map<String, String> aiImages, String workspace, String workspaceSource,
                    String workspaceSourceSha, int runOutputBudgetMib) {
        this(workflowId, workflowName, packageDir, packageDigest, gitCommit, gitDirty, workflowJson, inputs, trigger,
                actor, version, execution, containerRuntime, outputLimitMib, aiImages, workspace, workspaceSource,
                workspaceSourceSha, runOutputBudgetMib, null, List.of());
    }

    /** The start of a run with the default output budget for its output limit. */
    public RunStart(String workflowId, String workflowName, String packageDir, String packageDigest, String gitCommit,
                    Boolean gitDirty, byte[] workflowJson, Map<String, Object> inputs, TriggerSource trigger,
                    String actor, String version, String execution, Runtime containerRuntime, int outputLimitMib,
                    Map<String, String> aiImages, String workspace, String workspaceSource,
                    String workspaceSourceSha) {
        this(workflowId, workflowName, packageDir, packageDigest, gitCommit, gitDirty, workflowJson, inputs, trigger,
                actor, version, execution, containerRuntime, outputLimitMib, aiImages, workspace, workspaceSource,
                workspaceSourceSha, OutputBudget.defaultMib(outputLimitMib));
    }

    /** The start of a run without replaced AI images. */
    public RunStart(String workflowId, String workflowName, String packageDir, String packageDigest, String gitCommit,
                    Boolean gitDirty, byte[] workflowJson, Map<String, Object> inputs, TriggerSource trigger,
                    String actor, String version, String execution, Runtime containerRuntime, int outputLimitMib,
                    String workspace, String workspaceSource, String workspaceSourceSha) {
        this(workflowId, workflowName, packageDir, packageDigest, gitCommit, gitDirty, workflowJson, inputs, trigger,
                actor, version, execution, containerRuntime, outputLimitMib, Map.of(), workspace, workspaceSource,
                workspaceSourceSha);
    }

    /** The start of a run with the default output limit. */
    public RunStart(String workflowId, String workflowName, String packageDir, String packageDigest, String gitCommit,
                    Boolean gitDirty, byte[] workflowJson, Map<String, Object> inputs, TriggerSource trigger,
                    String actor, String version, String execution, Runtime containerRuntime, String workspace,
                    String workspaceSource, String workspaceSourceSha) {
        this(workflowId, workflowName, packageDir, packageDigest, gitCommit, gitDirty, workflowJson, inputs, trigger,
                actor, version, execution, containerRuntime, CommandRunner.OUTPUT_LIMIT_MIB, workspace,
                workspaceSource, workspaceSourceSha);
    }

    /**
     * The start of a run with the default output limit that works in a workspace of its own choosing, no checkout of
     * a source.
     */
    public RunStart(String workflowId, String workflowName, String packageDir, String packageDigest, String gitCommit,
                    Boolean gitDirty, byte[] workflowJson, Map<String, Object> inputs, TriggerSource trigger,
                    String actor, String version, String execution, Runtime containerRuntime, String workspace) {
        this(workflowId, workflowName, packageDir, packageDigest, gitCommit, gitDirty, workflowJson, inputs, trigger,
                actor, version, execution, containerRuntime, workspace, null, null);
    }

    /**
     * A container runtime as the record names it.
     *
     * @param name    {@code docker} or {@code podman}
     * @param version the version it reported
     */
    public record Runtime(String name, String version) {}
}
