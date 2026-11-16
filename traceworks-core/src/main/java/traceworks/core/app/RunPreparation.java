package traceworks.core.app;

import traceworks.core.container.ContainerCommandRunner;
import traceworks.core.container.ImageCatalog;
import traceworks.core.container.RunPaths;
import traceworks.core.engine.ChildWorkflows;
import traceworks.core.engine.ModelChoices;
import traceworks.core.engine.NestedBlocks;
import traceworks.core.engine.RunAdmission;
import traceworks.core.engine.RunOptions;
import traceworks.core.engine.RunOutcome;
import traceworks.core.engine.SecretNames;
import traceworks.core.model.BlockDefinition;
import traceworks.core.model.ModelPick;
import traceworks.core.model.TriggerSource;
import traceworks.core.model.WorkflowDefinition;
import traceworks.core.port.AiCli;
import traceworks.core.port.Clock;
import traceworks.core.port.CommandRunner;
import traceworks.core.port.ContainerRuntime;
import traceworks.core.port.HostDirectories;
import traceworks.core.port.LoadedPackage;
import traceworks.core.port.RegisterStore;
import traceworks.core.port.PackageLoader;
import traceworks.core.port.StoredRun;
import traceworks.core.port.WorkflowResolver;
import traceworks.core.record.Redactor;
import traceworks.core.record.RunRecorder;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares a run for the engine, at its start and at its resume: the start check, then the run's directories and
 * the options the engine runs it with.
 *
 * <p>The start check comes before the run exists, and before a resume records anything. Its contributors add their
 * reasons to one {@link Preflight}, which refuses with all of them at once: first what the run needs of this machine
 * (a container runtime, its images, directories a container may mount), then a child workflow that names Codex as a
 * provider, a pick that is none of the options of a model choice and a model the operator's {@code allowed_models}
 * does not allow ({@link #checkModels}), then what an audit workflow gets from its register
 * ({@link AuditPlatformData}), then its secrets
 * ({@link Secrets}), then the credentials of its AI providers ({@link AiCredentials}). The values of both are read at
 * most once per check ({@link SecretSource}). A run that passes is {@link Prepared}: what it was checked with, and what it takes from the
 * check. Its directories and options come from that, so they are those of the run the check saw.
 */
final class RunPreparation {

    private final ContainerSupport containers;
    private final PackageLoader packages;
    private final OperatorSettings settings;
    private final List<AiCli> aiClis;
    private final Supplier<SecretValues> secrets;
    private final CommandRunner hostRunner;
    private final AuditPlatformData auditData;
    private final Clock clock;

    /** A replacement the redactor wrote, with the name of the secret: {@code [redacted:<NAME>]}. */
    static final Pattern REPLACEMENT = Pattern.compile(Pattern.quote(Redactor.MARKER) + "([A-Z_][A-Z0-9_]*)]");

    /**
     * @param containers the container runtime, the built-in images and the run's directories on this machine
     * @param aiClis     the AI CLIs a run's AI steps can use, one per provider id
     * @param secrets    where the values of secrets and AI credentials come from: asked at most once per start check,
     *                   and only for a run that declares a secret or uses an AI provider
     * @param hostRunner runs the commands of a host run that name no image, on the host
     */
    RunPreparation(ContainerSupport containers, PackageLoader packages, OperatorSettings settings, List<AiCli> aiClis,
                   Supplier<SecretValues> secrets, CommandRunner hostRunner, Clock clock, RegisterStore registers) {
        this.auditData = new AuditPlatformData(registers, clock);
        this.containers = containers;
        this.packages = packages;
        this.settings = settings;
        this.aiClis = aiClis;
        this.secrets = secrets;
        this.hostRunner = hostRunner;
        this.clock = clock;
    }

    /**
     * A run that passed its start check: what it was checked with, and what it takes from the check.
     *
     * @param target           where it runs its steps
     * @param runtime          the container runtime it was checked with; {@code null} for none
     * @param pkg              its package, with the definition it runs; its blocks' {@code workflow_id}s are resolved
     *                         from it
     * @param secrets          the value of every secret it declares, by name
     * @param redactor         redacts those and the credentials of its AI providers, from its record and its result
     * @param catalog          the tags it resolves images with
     * @param ai               its AI setup
     * @param workspace        the real path of the workspace it was checked with; {@code null} for none
     * @param packageDirectory the real path of its package directory
     * @param values           where the check read the values of its secrets and credentials from; {@code null} when
     *                         it declares no secret and uses no AI provider, so none were read
     * @param modelPicks       the option the run picked of each model choice its workflow declares; empty for a
     *                         workflow without one
     * @param register         the files the register of an audit workflow hands the run, read with the platform
     *                         data; {@code null} for a workflow that is no audit. A start writes them into the run's
     *                         register folder; a resume goes on with the folder the run has
     */
    record Prepared(RunTarget target, ContainerRuntime runtime, LoadedPackage pkg, Map<String, String> secrets,
                    Redactor redactor, ImageCatalog catalog, RunAi ai, String workspace, String packageDirectory,
                    SecretValues values, Map<String, String> platformData, List<ModelPick> modelPicks,
                    AuditPlatformData.Handed register) {

        /**
         * What the run's use case returns: the outcome as the record has it, its error and every gate value
         * redacted, since the engine's own outcome holds the real ones.
         */
        RunResult result(String runId, String location, RunOutcome outcome, RunResult.KeptCheckout kept,
                         RegisterOutcome register) {
            Map<String, Object> gate = new LinkedHashMap<>();
            outcome.gate().forEach((key, value) -> gate.put(redactor.redact(key), redactor.redactValue(value)));
            return new RunResult(runId, location, outcome.status(), gate, redactor.redact(outcome.error()),
                    List.of(), kept, register);
        }
    }

    /**
     * The start check of a run or a resume, and what the run takes from it.
     *
     * @param pkg       the package, with the definition the run runs
     * @param workspace the directory the steps work in; {@code null} for none
     * @param inputs    the inputs the run starts with, or started with: they pick the options of the workflow's model
     *                  choices, so the check asks for the image and the credential of what was picked, not of every
     *                  option; {@code null} for none
     * @param what      what is refused, the start of the message: {@code The run is refused}
     * @throws RunRefusedException naming every reason the check found
     */
    Prepared prepare(RunTarget target, ContainerRuntime runtime, LoadedPackage pkg, Path workspace,
                     Map<String, ?> inputs, String what) {
        Preflight preflight = new Preflight();
        WorkflowUses.Picks picks = WorkflowUses.Picks.of(pkg.definition(), inputs);
        List<ModelPick> modelPicks = checkModels(pkg.definition(), resolver(pkg), inputs, picks, preflight);
        // A refused pick first and alone: what the check would ask of the unpicked definition is beside the point
        preflight.check(what);
        Mounted mounted = checkMachine(target, runtime, pkg, workspace, resolver(pkg), picks, preflight);
        checkChildProviders(pkg.definition(), resolver(pkg), preflight);
        if (pkg.catalogJson() != null) {
            try {
                traceworks.core.register.Catalog.parse(pkg.catalogJson());
            } catch (IllegalArgumentException e) {
                preflight.refuse(e.getMessage());
            }
        }
        AuditPlatformData.Read register = auditData.platformData(pkg, preflight);
        Map<String, String> platformData = register.placeholders();
        SecretSource values = new SecretSource(secrets);
        Map<String, String> runSecrets = Secrets.check(pkg.definition(), resolver(pkg), values, preflight);
        Map<String, Map<String, String>> credentials = AiCredentials.check(pkg.definition(), resolver(pkg), aiClis,
                values, preflight, picks);
        preflight.check(what);
        Redactor redactor = new Redactor(redacted(runSecrets, credentials));
        ImageCatalog catalog = images(runtime).catalog(pkg);
        RunAi ai = RunAi.of(aiClis, catalog, credentials, settings.aiImages(), settings.aiNetwork());
        // The paths the check judged, not a second resolution: what replaced a directory since is not mounted
        return new Prepared(target, runtime, pkg, runSecrets, redactor, catalog, ai,
                mounted.workspace(), mounted.packageDirectory(), values.read(), platformData, modelPicks,
                register.register());
    }

    /**
     * The models of the run, before anything else of it is checked: a pick that is none of the options of a model
     * choice, of the run's workflow or of a child it reaches by a literal {@code workflow_id}, and an AI step whose
     * provider and model the operator's {@code allowed_models} does not allow, the picked options included. Both
     * refuse the run before it exists. The engine holds the same two against every run and child run when it starts
     * ({@link RunOptions#allowedModels}), so a child reached by a placeholder is judged too, at its step.
     *
     * @return the picks of the run's own workflow; empty when a pick is refused
     */
    private List<ModelPick> checkModels(WorkflowDefinition definition, WorkflowResolver children,
                                        Map<String, ?> inputs, WorkflowUses.Picks picks, Preflight preflight) {
        ModelChoices.Resolution own = ModelChoices.resolve(definition, inputs);
        boolean first = true;
        for (WorkflowDefinition reached : ChildWorkflows.reachable(definition, children)) {
            String where = first ? "" : "the child workflow " + reached.id() + ": ";
            ModelChoices.Resolution chosen = first ? own : ModelChoices.resolve(reached, startedWith(definition,
                    inputs));
            first = false;
            chosen.problems().forEach(problem -> preflight.refuse(where + problem));
            settings.allowedModels().refusals(chosen.definition())
                    .forEach(reason -> preflight.refuse(where + reason));
        }
        return own.picks();
    }

    /** The inputs a run of {@code root} starts with: its {@code default_context} under its inputs. */
    private static Map<String, Object> startedWith(WorkflowDefinition root, Map<String, ?> inputs) {
        Map<String, Object> started = new java.util.HashMap<>(root.defaultContext());
        if (inputs != null) {
            started.putAll(inputs);
        }
        return started;
    }

    /**
     * Only the redactor of a run of {@code pkg}, for a record written without a start check (an answer to a parked
     * run): the declared secrets and the credentials of the AI providers the run uses, with the values
     * {@code credentials.json} and the environment hold now. No machine, image or missing-value check: a name
     * without a value, or with one the {@link Redactor} refuses (a resume would refuse the run for it), adds nothing
     * to redact.
     *
     * @throws RunRefusedException if the values cannot be read ({@code credentials.json} cannot be used)
     */
    Redactor redactor(LoadedPackage pkg) {
        List<String> names = redactedNames(pkg);
        if (names.isEmpty()) {
            return new Redactor(Map.of());
        }
        SecretValues source = values();
        Map<String, String> values = new TreeMap<>();
        for (String name : names) {
            source.value(name).filter(value -> Redactor.problem(value) == null)
                    .ifPresent(value -> values.put(name, value));
        }
        return new Redactor(values);
    }

    /**
     * The names whose values a run of {@code pkg} redacts: the secrets it declares and the credentials of the AI
     * providers it uses.
     */
    List<String> redactedNames(LoadedPackage pkg) {
        return redactedNames(pkg.definition(), resolver(pkg));
    }

    /**
     * {@link #redactedNames(LoadedPackage)} of a definition whose package is not at hand.
     *
     * @param children finds the child workflows; {@code null} looks into none
     */
    List<String> redactedNames(WorkflowDefinition definition, WorkflowResolver children) {
        SortedSet<String> declared = SecretNames.declared(definition, children);
        List<String> names = new ArrayList<>(declared.stream().filter(n -> SecretNames.problem(n) == null).toList());
        for (String provider : WorkflowUses.providers(definition, children)) {
            for (AiCli cli : aiClis) {
                if (cli.id().toLowerCase(Locale.ROOT).equals(provider)) {
                    names.addAll(AiCli.credentialNamesOf(cli.id()));
                }
            }
        }
        return names;
    }

    /**
     * A path as the record holds it, made real again: each {@code [redacted:<NAME>]} in it is replaced by the value
     * {@code credentials.json} or the environment holds for NAME now. The record names a path redacted when a
     * secret's value occurs in it; a resume works at the real path.
     *
     * <p>Only a name the run redacts is replaced ({@link #refuseForeignReplacements}). This is for a path on this
     * machine, the workspace or the package directory, and never for the source of a clone: a value in a path stays
     * here, a value in a URL is sent to the host the URL names, and a record can be rewritten to name any host
     * ({@link Checkouts#restore}).
     *
     * @param what  what {@code recorded} is, for the refusal: {@code The workspace of run <id>}
     * @param names the names the run redacts; asked only when {@code recorded} holds a replacement
     * @return {@code recorded} itself when it holds no replacement ({@code null} for {@code null})
     * @throws RunRefusedException if a replacement is left that names no secret of the run with a value now, or the
     *                             values cannot be read ({@code credentials.json} cannot be used); it names the
     *                             path as the record does
     */
    String realPath(String what, String recorded, Supplier<? extends Collection<String>> names) {
        if (recorded == null || !recorded.contains(Redactor.MARKER)) {
            return recorded;
        }
        refuseForeignReplacements(what, recorded, names);
        SecretValues source = values();
        Matcher marker = REPLACEMENT.matcher(recorded);
        StringBuilder real = new StringBuilder();
        while (marker.find()) {
            String value = source.value(marker.group(1)).orElse(null);
            if (value == null) {
                throw new RunRefusedException(what + " (" + recorded + ") cannot be made real: " + marker.group()
                        + " has no value now");
            }
            marker.appendReplacement(real, Matcher.quoteReplacement(value));
        }
        marker.appendTail(real);
        return real.toString();
    }

    /**
     * Refuses a recorded path or source that holds a replacement the redactor of the run did not write. The record
     * of a run holds a replacement only for a name the run redacts: a start refuses a path or source that holds
     * {@value Redactor#MARKER} itself ({@link #refuseAReplacement}). So any other one is from a record that was
     * changed or made elsewhere. No value is read for this.
     *
     * @param what  what {@code recorded} is, for the refusal: {@code The source of run <id>}
     * @param names the names the run redacts; asked only when {@code recorded} holds a replacement
     * @throws RunRefusedException if a replacement names no secret of the run, or {@value Redactor#MARKER} stands
     *                             where no replacement is; it names {@code recorded} as the record does
     */
    void refuseForeignReplacements(String what, String recorded, Supplier<? extends Collection<String>> names) {
        if (recorded == null || !recorded.contains(Redactor.MARKER)) {
            return;
        }
        Collection<String> redacted = names.get();
        Matcher marker = REPLACEMENT.matcher(recorded);
        while (marker.find()) {
            if (!redacted.contains(marker.group(1))) {
                throw new RunRefusedException(what + " (" + recorded + ") cannot be made real: " + marker.group()
                        + " names no secret of the run, so the redactor did not write it");
            }
        }
        if (REPLACEMENT.matcher(recorded).replaceAll("").contains(Redactor.MARKER)) {
            throw new RunRefusedException(what + " (" + recorded + ") cannot be made real: it holds "
                    + Redactor.MARKER + " where no replacement is");
        }
    }

    /**
     * Refuses a start with a source or a directory that holds {@value Redactor#MARKER}: the record could not tell it
     * from a replacement of the redactor, and a resume makes those of a path real again ({@link #realPath}).
     *
     * @param what  what {@code value} is: {@code source}, {@code workspace}
     * @param value as the record would hold it; {@code null} for none
     * @throws RunRefusedException naming it
     */
    static void refuseAReplacement(String what, String value) {
        if (value != null && value.contains(Redactor.MARKER)) {
            // A source is not repeated: it may hold a login or a token, and no other message repeats one as given
            throw new RunRefusedException("The run is refused: its " + what + ("source".equals(what) ? "" : " " + value)
                    + " contains '" + Redactor.MARKER + "', which the record could not tell from a redacted secret");
        }
    }

    /** @throws RunRefusedException if the values cannot be read ({@code credentials.json} cannot be used) */
    private SecretValues values() {
        try {
            return secrets.get();
        } catch (IllegalArgumentException | UncheckedIOException e) {
            throw new RunRefusedException("The values to redact cannot be read: " + e.getMessage());
        }
    }

    /**
     * The run's directories as its containers see them: the workspace, the package, its scratch directory and, for
     * the run of an audit workflow, its register folder.
     *
     * @param workspace the real path of the directory the steps work in; {@code null} for none
     * @param register  the run's register folder; {@code null} for a run without one
     * @throws RunRefusedException if the run's scratch directory cannot be made or has no real path, or its
     *                             register folder has none
     */
    RunPaths paths(StoredRun run, String workspace, Prepared prepared, String register) {
        return new RunPaths(workspace, prepared.packageDirectory(), scratch(run), realPath(register),
                prepared.target() == RunTarget.CONTAINER);
    }

    /**
     * Writes the files the register handed the run into its register folder.
     *
     * @return the folder; {@code null} for a workflow that is no audit, which has none
     * @throws RunRefusedException if the folder cannot be written
     */
    String writeRegisterFolder(StoredRun run, Prepared prepared) {
        if (prepared.register() == null) {
            return null;
        }
        try {
            return run.writeRegisterFiles(prepared.register().files());
        } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            throw new RunRefusedException("The register folder of the run cannot be written, so the run is not"
                    + " started: " + e.getMessage());
        }
    }

    /** The real path of a folder of the run; {@code null} for none. */
    private String realPath(String folder) {
        if (folder == null) {
            return null;
        }
        try {
            return containers.directories().realPath(Path.of(folder));
        } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            throw new RunRefusedException(e.getMessage());
        }
    }

    /**
     * The options the engine runs the run with.
     *
     * @param parkAtQuestions    whether a top-level question parks the run
     * @param workspaceSourceSha the commit of the run's checkout of a source; {@code null} for none
     * @param folder             the run's register folder with what the run recorded of it, which a host run
     *                           compares before each command that gets the folder; {@code null} for a run without one
     */
    RunOptions options(String runId, RunSession session, RunRecorder recorder, RunPaths paths, Prepared prepared,
                       TriggerSource trigger, boolean parkAtQuestions, String workspaceSourceSha,
                       RegisterFolder folder) {
        // run_if, step commands and the AI CLIs' commands alike are recorded, by one runner that counts the containers
        CommandRunner recorded = recorder.recording(runner(prepared, paths, runId, folder));
        return RunOptions.builder()
                .runId(runId)
                .events(recorder)
                .commandRunner(recorded)
                .aiProviders(recorder.recording(prepared.ai().providers(recorded)))
                .workflowResolver(resolver(prepared.pkg()))
                .runControl(session.control())
                .clock(clock)
                .workingDirectory(paths.workspace())
                .runPaths(paths)
                .triggerSource(trigger)
                .checkpoints(true)
                .parkAtQuestions(parkAtQuestions)
                .parkOnPause(true)   // this process may end while the run is paused: a pause parks it to disk
                .secrets(prepared.ai().keptWhole(prepared.secrets()))
                .workspaceSourceSha(workspaceSourceSha)
                .outputBudgetBytes(settings.runOutputBudgetBytes())
                .platformData(prepared.platformData())
                .allowedModels(settings.allowedModels())
                .build();
    }

    // ---- the start check ----

    /**
     * What the run needs of this machine: a container run needs a container runtime; with one, every image the run
     * needs must be there (the AI providers' of the workflows it reaches by a literal {@code workflow_id} too) (a host
     * run needs no {@code shell} image, and without a runtime its container steps fail when they run); and the
     * workspace and the package must be directories a container may mount, and a workspace that a step writes
     * ({@code workspace_writable: true}) must not lie inside the package, which every step only reads.
     *
     * @return the real paths the check judged, which are the ones the run mounts; of a refused directory {@code null}
     */
    private Mounted checkMachine(RunTarget target, ContainerRuntime runtime, LoadedPackage pkg, Path workspace,
                              WorkflowResolver children, WorkflowUses.Picks picks, Preflight preflight) {
        if (runtime == null && target == RunTarget.CONTAINER) {
            preflight.refuse("no container runtime found: " + (settings.containerRuntime() == null
                    ? "neither podman nor docker answers 'version'"
                    : "container_runtime " + settings.containerRuntime() + " in traceworks.json does not answer"
                    + " 'version'") + "; install and start podman or docker, or run on the host with"
                    + " --allow-host-execution");
        }
        if (runtime != null) {
            preflight.refuse(images(runtime).missing(pkg, target == RunTarget.CONTAINER, children, picks));
        }
        // Each directory is resolved once: the path that is judged here is the path the run mounts
        HostDirectories.Checked checkedWorkspace = workspace == null ? null
                : containers.directories().check(workspace, HostDirectories.Role.WORKSPACE);
        if (checkedWorkspace != null && checkedWorkspace.isRefused()) {
            preflight.refuse("the workspace " + workspace + " cannot be mounted: " + checkedWorkspace.refusal());
        }
        HostDirectories.Checked checkedPackage = containers.directories().check(pkg.directory(),
                HostDirectories.Role.PACKAGE);
        if (checkedPackage.isRefused()) {
            preflight.refuse("the package " + pkg.directory() + " cannot be mounted: " + checkedPackage.refusal());
        }
        Mounted mounted = new Mounted(checkedWorkspace == null ? null : checkedWorkspace.realPath(),
                checkedPackage.realPath());
        if (mounted.workspace() != null && mounted.packageDirectory() != null
                && writesTheWorkspace(pkg.definition(), children)
                && RunPaths.within(mounted.workspace(), mounted.packageDirectory())) {
            preflight.refuse("the workspace " + workspace + " lies inside the package " + pkg.directory() + ", which"
                    + " every step sees read-only, and a step sets workspace_writable: true, so it would change the"
                    + " package: run from a directory outside the package, or pass --workspace");
        }
        return mounted;
    }

    /**
     * The real paths the start check judged the run's directories at.
     *
     * @param workspace        {@code null} for none, or for one the check refused
     * @param packageDirectory {@code null} for one the check refused
     */
    private record Mounted(String workspace, String packageDirectory) {}

    /**
     * A workflow the run reaches by a literal {@code workflow_id} that names Codex as a provider, its own or a block's:
     * its admission would refuse it only when its step runs, after the blocks before it ran. The run's own definition
     * is refused by its admission before its first block.
     */
    private static void checkChildProviders(WorkflowDefinition definition, WorkflowResolver children,
                                            Preflight preflight) {
        List<ChildWorkflows.Reached> reached = ChildWorkflows.reached(definition, children);
        for (ChildWorkflows.Reached child : reached.subList(1, reached.size())) {
            for (String reason : RunAdmission.codexReasons(child.definition())) {
                preflight.refuse("the child workflow " + child.definition().id() + ": " + reason);
            }
        }
    }

    /** Whether a step of the run, in a literal child workflow too, sets {@code workspace_writable: true}. */
    private static boolean writesTheWorkspace(WorkflowDefinition definition, WorkflowResolver children) {
        for (WorkflowDefinition workflow : ChildWorkflows.reachable(definition, children)) {
            for (BlockDefinition block : workflow.blocks()) {
                if (block == null || block.getConfig() == null) continue;
                if (Boolean.TRUE.equals(block.getConfig().get("workspace_writable"))) return true;
                boolean[] nested = {false};
                NestedBlocks.visit(block.getConfig(), inner -> nested[0] |= inner.config() != null
                        && Boolean.TRUE.equals(inner.config().get("workspace_writable")));
                if (nested[0]) return true;
            }
        }
        return false;
    }

    /** Every value the record redacts: the secrets by name, the credentials by name. */
    private static Map<String, String> redacted(Map<String, String> runSecrets,
                                                Map<String, Map<String, String>> credentials) {
        Map<String, String> all = new TreeMap<>(runSecrets);
        credentials.values().forEach(all::putAll);
        return all;
    }

    // ---- the run's directories, images, commands and child workflows ----

    /**
     * The run's scratch directory as its real path; created when the run folder has none (a folder from before
     * scratch directories).
     */
    private String scratch(StoredRun run) {
        try {
            return containers.directories().realPath(Path.of(run.prepareScratchDirectory()));
        } catch (IllegalArgumentException | IllegalStateException | UncheckedIOException e) {
            throw new RunRefusedException(e.getMessage());
        }
    }

    private Images images(ContainerRuntime runtime) {
        return new Images(runtime, containers.builtIns(), packages, List.of(), settings.aiImages(),
                settings.aiNetwork());
    }

    /**
     * The run's command runner: every command through the container policy; the image-less commands of a host run
     * to the host runner.
     */
    private CommandRunner runner(Prepared prepared, RunPaths paths, String runId, RegisterFolder folder) {
        return new ContainerCommandRunner(prepared.runtime(), prepared.target() == RunTarget.HOST ? hostRunner : null,
                paths, prepared.catalog(), settings.containerLimits(), runId,
                folder == null ? () -> null : folder::problem);
    }

    private WorkflowResolver resolver(LoadedPackage pkg) {
        return new PackageWorkflowResolver(pkg, settings.packagePath(), packages);
    }
}
