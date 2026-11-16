package traceworks.core.app;

import traceworks.core.engine.AllowedModels;
import traceworks.core.context.OutputBudget;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.ContainerLimits;

import traceworks.core.port.AiCli;
import traceworks.core.port.AiNetwork;
import traceworks.core.port.LocalModels;
import traceworks.core.container.ImageCatalog;
import traceworks.core.register.DecisionOnHigherSeverity;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The operator's settings from {@code <home>/traceworks.json}.
 *
 * @param allowHostExecution whether the operator allows host runs, the fallback where no container runtime works:
 *                           shell steps without an image then run on the host instead of in a container
 * @param actor              who runs workflows on this machine, or {@code null} for {@code user@host}
 * @param packagePath        directories whose immediate subdirectories are searched for workflow ids
 * @param containerRuntime   {@code podman} or {@code docker}; {@code null} takes the first of them that works
 * @param containerLimits    the memory, pids and CPU limits of every container
 * @param outputLimitMib     the most a runner keeps of each stream of every command, in MiB
 *                           ({@code output_limit_mib}): from {@value #MIN_OUTPUT_LIMIT_MIB} to
 *                           {@value #MAX_OUTPUT_LIMIT_MIB}, {@value #DEFAULT_OUTPUT_LIMIT_MIB} by default. Only the
 *                           operator sets it; no workflow can raise it
 * @param aiImages           the image each AI CLI runs in instead of its built-in one ({@code ai_images}), by the
 *                           CLI's id in lower case ({@code claude}, {@code pi}); empty for the built-in images. A
 *                           value is a reference ({@link #aiImageProblem}), which Traceworks never builds or pulls.
 *                           Only the operator sets it; no workflow can
 * @param runOutputBudgetMib how much output text a run holds in memory at most, in MiB
 *                           ({@code run_output_budget_mib}, {@link OutputBudget}): from
 *                           {@value #MIN_RUN_OUTPUT_BUDGET_MIB} to {@value #MAX_RUN_OUTPUT_BUDGET_MIB}, and at least
 *                           twice the output limit, since one step can hold its stdout and its stderr up to the limit
 *                           each. By default {@link #defaultRunOutputBudgetMib}. Only the operator sets it; no workflow
 *                           can
 * @param hooks              the operator's hooks; {@link Hooks#NONE} without the key
 * @param secretNames        the secrets a run may take from the environment ({@code secret_names}); {@code null}
 *                           and an empty list allow none. Values from {@code credentials.json} are not
 *                           affected, and neither are the AI providers' credentials
 * @param aiNetwork          where the containers of the AI CLIs can connect ({@code ai_network}, {@code ai_hosts});
 *                           absent is {@link AiNetwork#RESTRICTED}: a call reaches its provider's hosts only. Only
 *                           the operator sets it; no workflow can
 * @param seals              the operator's settings for seals ({@code require_seal}, {@code seal_immutable},
 *                           {@code signing_key}, {@code allowed_signers}); absent is {@link SealSettings#DEFAULTS}
 * @param decisionOnHigherSeverity what a register does with a finding a person accepted or dismissed when a report
 *                           states it with a severity above the one the decision was made on
 *                           ({@code decision_on_higher_severity}): {@code reopen}, the default, or {@code keep}. Only
 *                           the operator sets it: a decision is the operator's, so no package can widen what one
 *                           covers
 * @param allowedModels      the provider and model pairs that may run in this home ({@code allowed_models}); a run
 *                           with an AI step outside them is refused before it starts. By default
 *                           {@link AllowedModels#ANY}: no restriction. No workflow can set it
 * @param localModels        the model servers of the operator's own ({@code local_models}): a {@code PI} step whose
 *                           model names one of them runs against that server and reaches nothing else. By default
 *                           {@link LocalModels#NONE}. No workflow can set it
 * @throws IllegalArgumentException for an output limit or a budget outside its range, a budget below twice the output
 *                                  limit, an AI CLI that does not exist, or an image reference that cannot name one
 */
public record OperatorSettings(boolean allowHostExecution, String actor, List<Path> packagePath,
                               String containerRuntime, ContainerLimits containerLimits, int outputLimitMib,
                               Map<String, String> aiImages, int runOutputBudgetMib, List<String> secretNames,
                               Hooks hooks, AiNetwork aiNetwork, SealSettings seals,
                               DecisionOnHigherSeverity decisionOnHigherSeverity, AllowedModels allowedModels,
                               LocalModels localModels) {

    /** Settings without {@code local_models}: no model server of the operator's. */
    public OperatorSettings(boolean allowHostExecution, String actor, List<Path> packagePath,
                            String containerRuntime, ContainerLimits containerLimits, int outputLimitMib,
                            Map<String, String> aiImages, int runOutputBudgetMib, List<String> secretNames,
                            Hooks hooks, AiNetwork aiNetwork, SealSettings seals,
                            DecisionOnHigherSeverity decisionOnHigherSeverity, AllowedModels allowedModels) {
        this(allowHostExecution, actor, packagePath, containerRuntime, containerLimits, outputLimitMib, aiImages,
                runOutputBudgetMib, secretNames, hooks, aiNetwork, seals, decisionOnHigherSeverity, allowedModels,
                LocalModels.NONE);
    }

    /** These settings, with {@code local} as the model servers of the operator's. */
    public OperatorSettings withLocalModels(LocalModels local) {
        return new OperatorSettings(allowHostExecution, actor, packagePath, containerRuntime, containerLimits,
                outputLimitMib, aiImages, runOutputBudgetMib, secretNames, hooks, aiNetwork, seals,
                decisionOnHigherSeverity, allowedModels, local);
    }

    /** Settings without {@code allowed_models}: every model may run. */
    public OperatorSettings(boolean allowHostExecution, String actor, List<Path> packagePath,
                            String containerRuntime, ContainerLimits containerLimits, int outputLimitMib,
                            Map<String, String> aiImages, int runOutputBudgetMib, List<String> secretNames,
                            Hooks hooks, AiNetwork aiNetwork, SealSettings seals,
                            DecisionOnHigherSeverity decisionOnHigherSeverity) {
        this(allowHostExecution, actor, packagePath, containerRuntime, containerLimits, outputLimitMib, aiImages,
                runOutputBudgetMib, secretNames, hooks, aiNetwork, seals, decisionOnHigherSeverity,
                AllowedModels.ANY);
    }

    /**
     * The operator's hooks ({@code hooks} in {@code traceworks.json}).
     *
     * @param registerChanged the program and its arguments that get the diff of a register after an audit run on
     *                        its standard input; empty for none
     * @param timeoutSeconds  after how many seconds a hook is ended
     */
    public record Hooks(List<String> registerChanged, int timeoutSeconds) {
        public static final int DEFAULT_TIMEOUT_SECONDS = 60;
        public static final Hooks NONE = new Hooks(List.of(), DEFAULT_TIMEOUT_SECONDS);

        public Hooks {
            registerChanged = registerChanged == null ? List.of() : List.copyOf(registerChanged);
            if (timeoutSeconds < 1) {
                throw new IllegalArgumentException("a hook timeout is at least 1 second: " + timeoutSeconds);
            }
        }

        public boolean onRegisterChanged() {
            return !registerChanged.isEmpty();
        }
    }

    /** The output limit unless the operator sets one: {@link CommandRunner#OUTPUT_LIMIT_MIB}, 16 MiB. */
    public static final int DEFAULT_OUTPUT_LIMIT_MIB = CommandRunner.OUTPUT_LIMIT_MIB;
    /** The smallest output limit. */
    public static final int MIN_OUTPUT_LIMIT_MIB = 1;
    /** The largest output limit: 1 GiB of each stream, which Traceworks holds in memory. */
    public static final int MAX_OUTPUT_LIMIT_MIB = 1024;
    /** The smallest run output budget. */
    public static final int MIN_RUN_OUTPUT_BUDGET_MIB = 1;
    /** The largest run output budget: 64 GiB, far above any heap Traceworks runs in. */
    public static final int MAX_RUN_OUTPUT_BUDGET_MIB = 65536;

    public OperatorSettings {
        packagePath = packagePath == null ? List.of() : List.copyOf(packagePath);
        containerLimits = Objects.requireNonNullElse(containerLimits, ContainerLimits.DEFAULTS);
        if (outputLimitMib < MIN_OUTPUT_LIMIT_MIB || outputLimitMib > MAX_OUTPUT_LIMIT_MIB) {
            throw new IllegalArgumentException("output limit " + outputLimitMib + " MiB must be from "
                    + MIN_OUTPUT_LIMIT_MIB + " to " + MAX_OUTPUT_LIMIT_MIB);
        }
        if (runOutputBudgetMib < MIN_RUN_OUTPUT_BUDGET_MIB || runOutputBudgetMib > MAX_RUN_OUTPUT_BUDGET_MIB) {
            throw new IllegalArgumentException("run output budget " + runOutputBudgetMib + " MiB must be from "
                    + MIN_RUN_OUTPUT_BUDGET_MIB + " to " + MAX_RUN_OUTPUT_BUDGET_MIB);
        }
        if (runOutputBudgetMib < 2 * outputLimitMib) {
            throw new IllegalArgumentException("run output budget " + runOutputBudgetMib
                    + " MiB must be at least twice the output limit of " + outputLimitMib + " MiB");
        }
        Map<String, String> images = new TreeMap<>();
        if (aiImages != null) {
            aiImages.forEach((cli, image) -> {
                if (!aiCliIds().contains(cli)) {
                    throw new IllegalArgumentException("'" + cli + "' is no AI CLI Traceworks runs; the ones that can"
                            + " have an image are " + String.join(", ", aiCliIds()));
                }
                String problem = aiImageProblem(image);
                if (problem != null) {
                    throw new IllegalArgumentException("the image of " + cli + " " + problem);
                }
                images.put(cli, image);
            });
        }
        aiImages = Map.copyOf(images);
        secretNames = secretNames == null ? null : List.copyOf(new java.util.TreeSet<>(secretNames));
        hooks = hooks == null ? Hooks.NONE : hooks;
        aiNetwork = aiNetwork == null ? AiNetwork.RESTRICTED : aiNetwork;
        seals = seals == null ? SealSettings.DEFAULTS : seals;
        allowedModels = allowedModels == null ? AllowedModels.ANY : allowedModels;
        localModels = localModels == null ? LocalModels.NONE : localModels;
        decisionOnHigherSeverity = decisionOnHigherSeverity == null ? DecisionOnHigherSeverity.DEFAULT
                : decisionOnHigherSeverity;
        if (decisionOnHigherSeverity.word() == null) {
            throw new IllegalArgumentException("decision_on_higher_severity is reopen or keep, not "
                    + decisionOnHigherSeverity);
        }
    }

    /** With a decided finding that is open again when a report states it with a higher severity. */
    public OperatorSettings(boolean allowHostExecution, String actor, List<Path> packagePath,
                            String containerRuntime, ContainerLimits containerLimits, int outputLimitMib,
                            Map<String, String> aiImages, int runOutputBudgetMib, List<String> secretNames,
                            Hooks hooks, AiNetwork aiNetwork, SealSettings seals) {
        this(allowHostExecution, actor, packagePath, containerRuntime, containerLimits, outputLimitMib, aiImages,
                runOutputBudgetMib, secretNames, hooks, aiNetwork, seals, DecisionOnHigherSeverity.DEFAULT);
    }

    /** These settings, with {@code decision} as what happens to a decided finding stated with a higher severity. */
    public OperatorSettings withDecisionOnHigherSeverity(DecisionOnHigherSeverity decision) {
        return new OperatorSettings(allowHostExecution, actor, packagePath, containerRuntime, containerLimits,
                outputLimitMib, aiImages, runOutputBudgetMib, secretNames, hooks, aiNetwork, seals, decision,
                allowedModels, localModels);
    }

    /** These settings, with {@code allowed} as the models that may run in this home. */
    public OperatorSettings withAllowedModels(AllowedModels allowed) {
        return new OperatorSettings(allowHostExecution, actor, packagePath, containerRuntime, containerLimits,
                outputLimitMib, aiImages, runOutputBudgetMib, secretNames, hooks, aiNetwork, seals,
                decisionOnHigherSeverity, allowed, localModels);
    }

    /** Without settings for seals: no run needs one, and seals can be removed. */
    public OperatorSettings(boolean allowHostExecution, String actor, List<Path> packagePath,
                            String containerRuntime, ContainerLimits containerLimits, int outputLimitMib,
                            Map<String, String> aiImages, int runOutputBudgetMib, List<String> secretNames,
                            Hooks hooks, AiNetwork aiNetwork) {
        this(allowHostExecution, actor, packagePath, containerRuntime, containerLimits, outputLimitMib, aiImages,
                runOutputBudgetMib, secretNames, hooks, aiNetwork, SealSettings.DEFAULTS);
    }

    /** These settings, with {@code seals} as the operator's settings for seals. */
    public OperatorSettings withSeals(SealSettings seals) {
        return new OperatorSettings(allowHostExecution, actor, packagePath, containerRuntime, containerLimits,
                outputLimitMib, aiImages, runOutputBudgetMib, secretNames, hooks, aiNetwork, seals,
                decisionOnHigherSeverity, allowedModels, localModels);
    }

    /** With the restricted AI network: an AI call reaches its provider's hosts only. */
    public OperatorSettings(boolean allowHostExecution, String actor, List<Path> packagePath,
                            String containerRuntime, ContainerLimits containerLimits, int outputLimitMib,
                            Map<String, String> aiImages, int runOutputBudgetMib, List<String> secretNames,
                            Hooks hooks) {
        this(allowHostExecution, actor, packagePath, containerRuntime, containerLimits, outputLimitMib, aiImages,
                runOutputBudgetMib, secretNames, hooks, AiNetwork.RESTRICTED);
    }

    /** These settings, with {@code aiNetwork} as where the containers of the AI CLIs can connect. */
    public OperatorSettings withAiNetwork(AiNetwork aiNetwork) {
        return new OperatorSettings(allowHostExecution, actor, packagePath, containerRuntime, containerLimits,
                outputLimitMib, aiImages, runOutputBudgetMib, secretNames, hooks, aiNetwork, seals,
                decisionOnHigherSeverity, allowedModels, localModels);
    }

    /** Without hooks. */
    public OperatorSettings(boolean allowHostExecution, String actor, List<Path> packagePath,
                            String containerRuntime, ContainerLimits containerLimits, int outputLimitMib,
                            Map<String, String> aiImages, int runOutputBudgetMib, List<String> secretNames) {
        this(allowHostExecution, actor, packagePath, containerRuntime, containerLimits, outputLimitMib, aiImages,
                runOutputBudgetMib, secretNames, Hooks.NONE);
    }

    /** Without {@code secret_names}: every secret may come from the environment, as before the key existed. */
    public OperatorSettings(boolean allowHostExecution, String actor, List<Path> packagePath,
                            String containerRuntime, ContainerLimits containerLimits, int outputLimitMib,
                            Map<String, String> aiImages, int runOutputBudgetMib) {
        this(allowHostExecution, actor, packagePath, containerRuntime, containerLimits, outputLimitMib, aiImages,
                runOutputBudgetMib, null, Hooks.NONE);
    }

    /**
     * Whether a secret named {@code name} may take its value from the environment of Traceworks: only when
     * {@code secret_names} names it. Without the setting no name is allowed: a package nobody checked could otherwise
     * name any variable of the operator's shell as a step's secret and get its value in a container.
     * {@code credentials.json} is not affected.
     */
    public boolean secretFromEnvironmentAllowed(String name) {
        return secretNames != null && secretNames.contains(name);
    }

    /** The AI CLIs that can run in an image of the operator's, by their ids in lower case, sorted. */
    public static SortedSet<String> aiCliIds() {
        SortedSet<String> ids = new TreeSet<>();
        AiCli.CREDENTIALS.keySet().forEach(id -> ids.add(id.toLowerCase(Locale.ROOT)));
        return ids;
    }

    /**
     * Why {@code image} cannot be the image of an AI CLI; {@code null} when it can. It must be a reference for the
     * container runtime's command line: not blank, with no whitespace or control character, not starting with
     * {@code -} (it would be taken for an option), and not a package image ({@value ImageCatalog#PACKAGE_IMAGE_PREFIX},
     * which only a package's own steps can name).
     */
    public static String aiImageProblem(String image) {
        if (image == null || image.isBlank()) {
            return "must not be blank";
        }
        if (image.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            return "must not hold whitespace or control characters";
        }
        if (image.startsWith("-")) {
            return "must not start with '-'";
        }
        if (image.startsWith(ImageCatalog.PACKAGE_IMAGE_PREFIX)) {
            return "must not be a package image (" + ImageCatalog.PACKAGE_IMAGE_PREFIX + "...)";
        }
        return null;
    }

    /** With the default run output budget for the output limit, {@link #defaultRunOutputBudgetMib}. */
    public OperatorSettings(boolean allowHostExecution, String actor, List<Path> packagePath,
                            String containerRuntime, ContainerLimits containerLimits, int outputLimitMib,
                            Map<String, String> aiImages) {
        this(allowHostExecution, actor, packagePath, containerRuntime, containerLimits, outputLimitMib, aiImages,
                defaultRunOutputBudgetMib(outputLimitMib));
    }

    /**
     * The run output budget unless the operator sets one: {@value OutputBudget#DEFAULT_MIN_MIB} MiB, or
     * {@value OutputBudget#DEFAULT_OUTPUT_LIMITS} times the output limit when that is more, so that raising the output
     * limit does not make steps fail at the budget.
     */
    public static int defaultRunOutputBudgetMib(int outputLimitMib) {
        return OutputBudget.defaultMib(outputLimitMib);
    }

    /** With no replacement of the AI images. */
    public OperatorSettings(boolean allowHostExecution, String actor, List<Path> packagePath,
                            String containerRuntime, ContainerLimits containerLimits, int outputLimitMib) {
        this(allowHostExecution, actor, packagePath, containerRuntime, containerLimits, outputLimitMib, Map.of());
    }

    /** With the default output limit. */
    public OperatorSettings(boolean allowHostExecution, String actor, List<Path> packagePath,
                            String containerRuntime, ContainerLimits containerLimits) {
        this(allowHostExecution, actor, packagePath, containerRuntime, containerLimits, DEFAULT_OUTPUT_LIMIT_MIB);
    }

    /** With the container runtime found on the machine, the default container limits and output limit. */
    public OperatorSettings(boolean allowHostExecution, String actor, List<Path> packagePath) {
        this(allowHostExecution, actor, packagePath, null, ContainerLimits.DEFAULTS);
    }

    /** No host execution, no actor, an empty package path, the runtime found on the machine, the default limits. */
    public static OperatorSettings defaults() {
        return new OperatorSettings(false, null, List.of());
    }

    /** The output limit in bytes, as the command runners take it. */
    public int outputLimitBytes() {
        return outputLimitMib * 1024 * 1024;
    }

    /** The run output budget in bytes, as the engine takes it. */
    public long runOutputBudgetBytes() {
        return runOutputBudgetMib * 1024L * 1024L;
    }
}
