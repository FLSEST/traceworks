package traceworks.adapters.config;

import traceworks.core.app.OperatorSettings;
import traceworks.core.port.AiNetwork;
import traceworks.core.port.ContainerLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorConfigFileTest {

    @TempDir
    Path home;

    private void config(String json) throws IOException {
        Files.writeString(home.resolve("traceworks.json"), json);
    }

    @Test
    void missingFile_givesDefaults() {
        assertThat(OperatorConfigFile.read(home)).isEqualTo(OperatorSettings.defaults());
    }

    @Test
    void readsAllKeys() throws IOException {
        config("{\"allow_host_execution\": true, \"actor\": \"flo\", \"package_path\": [\"/abs/lib\", \"rel/lib\"]}");

        OperatorSettings settings = OperatorConfigFile.read(home);

        assertThat(settings.allowHostExecution()).isTrue();
        assertThat(settings.actor()).isEqualTo("flo");
        assertThat(settings.packagePath()).containsExactly(Path.of("/abs/lib"), home.resolve("rel/lib"));
    }

    @Test
    void allowHostExecution_false_keepsHostRunsRefused() throws IOException {
        config("{\"allow_host_execution\": false}");

        assertThat(OperatorConfigFile.read(home).allowHostExecution()).isFalse();
    }

    @Test
    void nullActor_andEmptyObject_areAccepted() throws IOException {
        config("{\"actor\": null}");
        assertThat(OperatorConfigFile.read(home).actor()).isNull();
        config("{}");
        assertThat(OperatorConfigFile.read(home)).isEqualTo(OperatorSettings.defaults());
    }

    @Test
    void secretNames_isNullByDefault_andAListSortedWithoutDuplicates() throws IOException {
        assertThat(OperatorConfigFile.read(home).secretNames()).isNull();
        config("{\"secret_names\": null}");
        assertThat(OperatorConfigFile.read(home).secretNames()).isNull();
        config("{\"secret_names\": []}");
        assertThat(OperatorConfigFile.read(home).secretNames()).isEmpty();
        config("{\"secret_names\": [\"DEPLOY_TOKEN\", \"API_TOKEN\", \"DEPLOY_TOKEN\"]}");

        OperatorSettings settings = OperatorConfigFile.read(home);

        assertThat(settings.secretNames()).containsExactly("API_TOKEN", "DEPLOY_TOKEN");
        assertThat(settings.secretFromEnvironmentAllowed("API_TOKEN")).isTrue();
        assertThat(settings.secretFromEnvironmentAllowed("OTHER")).isFalse();
        // Closed by default: without secret_names a run takes no secret from the environment
        assertThat(OperatorSettings.defaults().secretFromEnvironmentAllowed("OTHER")).isFalse();
    }

    @Test
    void secretNames_refusesANameNoStepCouldDeclare() throws IOException {
        for (String bad : List.of("{\"secret_names\": \"DEPLOY_TOKEN\"}", "{\"secret_names\": [1]}",
                "{\"secret_names\": [\"deploy_token\"]}", "{\"secret_names\": [\"1ABC\"]}")) {
            config(bad);
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("secret_names");
        }
    }

    @Test
    void hooks_areReadAsArgv_withATimeout_default60Seconds() throws IOException {
        assertThat(OperatorConfigFile.read(home).hooks()).isEqualTo(OperatorSettings.Hooks.NONE);
        config("{\"hooks\": {\"register_changed\": [\"/opt/hooks/todos.sh\", \"--project\", \"tw\"]}}");

        OperatorSettings.Hooks hooks = OperatorConfigFile.read(home).hooks();

        assertThat(hooks.registerChanged()).containsExactly("/opt/hooks/todos.sh", "--project", "tw");
        assertThat(hooks.timeoutSeconds()).isEqualTo(60);
        config("{\"hooks\": {\"register_changed\": [\"x\"], \"timeout_seconds\": 5}}");
        assertThat(OperatorConfigFile.read(home).hooks().timeoutSeconds()).isEqualTo(5);
        config("{\"hooks\": null}");
        assertThat(OperatorConfigFile.read(home).hooks()).isEqualTo(OperatorSettings.Hooks.NONE);
    }

    @Test
    void hooks_refuseAShellString_anUnknownKey_andABadTimeout() throws IOException {
        for (String bad : List.of("{\"hooks\": {\"register_changed\": \"todos.sh --x\"}}",
                "{\"hooks\": {\"register_changed\": []}}", "{\"hooks\": {\"register_changed\": [1]}}",
                "{\"hooks\": {\"on_run\": [\"x\"]}}", "{\"hooks\": {\"register_changed\": [\"x\"], \"timeout_seconds\": 0}}",
                "{\"hooks\": {\"register_changed\": [\"x\"], \"timeout_seconds\": \"5\"}}", "{\"hooks\": []}")) {
            config(bad);
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hooks");
        }
    }

    @Test
    void unknownKey_isRefused_andNamed() throws IOException {
        config("{\"allow_host_exec\": true}");

        assertThatThrownBy(() -> OperatorConfigFile.read(home)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allow_host_exec");
    }

    @Test
    void wrongType_isRefused() throws IOException {
        for (String bad : List.of("{\"allow_host_execution\": \"yes\"}", "{\"actor\": 5}",
                "{\"package_path\": \"/lib\"}", "{\"package_path\": [1]}")) {
            config(bad);
            String key = bad.split("\"")[1];
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
        }
    }

    @Test
    void operatorConfig_containerRuntimeAndLimits_areReadAndChecked() throws IOException {
        config("{\"container_runtime\": \"docker\", \"container_limits\": {\"memory\": \"512m\", \"pids\": 64,"
                + " \"cpus\": 0.5}}");
        OperatorSettings settings = OperatorConfigFile.read(home);
        assertThat(settings.containerRuntime()).isEqualTo("docker");
        assertThat(settings.containerLimits()).isEqualTo(new ContainerLimits("512m", 64, "0.5"));

        config("{\"container_runtime\": null, \"container_limits\": {\"pids\": 10, \"cpus\": \"3\"}}");
        settings = OperatorConfigFile.read(home);
        assertThat(settings.containerRuntime()).as("auto-detection").isNull();
        assertThat(settings.containerLimits()).as("the missing limit keeps its default")
                .isEqualTo(new ContainerLimits("4g", 10, "3"));

        config("{}");
        assertThat(OperatorConfigFile.read(home).containerRuntime()).isNull();
        assertThat(OperatorConfigFile.read(home).containerLimits()).isEqualTo(ContainerLimits.DEFAULTS);

        for (String bad : List.of("{\"container_runtime\": \"lxc\"}", "{\"container_runtime\": \"Docker\"}",
                "{\"container_runtime\": true}", "{\"container_limits\": \"4g\"}",
                "{\"container_limits\": {\"memory\": 4}}", "{\"container_limits\": {\"memory\": \"0\"}}",
                "{\"container_limits\": {\"pids\": \"10\"}}", "{\"container_limits\": {\"pids\": 1.5}}",
                "{\"container_limits\": {\"pids\": 0}}", "{\"container_limits\": {\"cpus\": true}}",
                "{\"container_limits\": {\"cpus\": -1}}", "{\"container_limits\": {\"swap\": \"1g\"}}")) {
            config(bad);
            String key = bad.split("\"")[1];
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
        }
        config("{\"container_limits\": {\"swap\": \"1g\"}}");
        assertThatThrownBy(() -> OperatorConfigFile.read(home)).hasMessageContaining("swap");
    }

    /** The output limit is an integer of MiB from 1 to 1024; missing or null is 16, anything else is refused. */
    @Test
    void outputLimitMib_isAnIntegerFrom1To1024_and16ByDefault() throws IOException {
        config("{}");
        assertThat(OperatorConfigFile.read(home).outputLimitMib()).isEqualTo(16);
        assertThat(OperatorConfigFile.read(home).outputLimitBytes()).isEqualTo(16 * 1024 * 1024);
        config("{\"output_limit_mib\": null}");
        assertThat(OperatorConfigFile.read(home).outputLimitMib()).isEqualTo(16);
        for (int valid : new int[] {1, 4, 16, 1024}) {
            config("{\"output_limit_mib\": " + valid + "}");
            assertThat(OperatorConfigFile.read(home).outputLimitMib()).isEqualTo(valid);
        }
        assertThat(OperatorConfigFile.read(home).outputLimitBytes()).isEqualTo(1024 * 1024 * 1024);

        for (String bad : List.of("0", "1025", "-1", "\"16\"", "1.5", "16.0", "true", "[16]", "99999999999")) {
            config("{\"output_limit_mib\": " + bad + "}");
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'output_limit_mib' in " + home.resolve("traceworks.json") + " must be an integer"
                            + " from 1 to 1024 (MiB), not " + bad);
        }
    }

    /** Missing or null, the budget is 128 MiB, or eight times the output limit when that is more. */
    @Test
    void runOutputBudgetMib_byDefault_is128_orEightTimesTheOutputLimit() throws IOException {
        config("{}");
        assertThat(OperatorConfigFile.read(home).runOutputBudgetMib()).isEqualTo(128);
        assertThat(OperatorConfigFile.read(home).runOutputBudgetBytes()).isEqualTo(128L * 1024 * 1024);
        config("{\"run_output_budget_mib\": null}");
        assertThat(OperatorConfigFile.read(home).runOutputBudgetMib()).isEqualTo(128);
        config("{\"output_limit_mib\": 64}");
        assertThat(OperatorConfigFile.read(home).runOutputBudgetMib()).isEqualTo(512);
        config("{\"output_limit_mib\": 1024}");
        assertThat(OperatorConfigFile.read(home).runOutputBudgetMib()).isEqualTo(8192);
    }

    @Test
    void runOutputBudgetMib_isAnIntegerFrom1To65536() throws IOException {
        for (String valid : List.of("32", "128", "65536")) {
            config("{\"run_output_budget_mib\": " + valid + "}");
            assertThat(OperatorConfigFile.read(home).runOutputBudgetMib()).isEqualTo(Integer.parseInt(valid));
        }
        config("{\"output_limit_mib\": 1, \"run_output_budget_mib\": 1}");
        assertThatThrownBy(() -> OperatorConfigFile.read(home)).as("1 is in the range, but below twice the limit")
                .hasMessageContaining("at least twice");

        for (String bad : List.of("0", "65537", "-1", "\"128\"", "1.5", "128.0", "true", "[128]", "99999999999")) {
            config("{\"output_limit_mib\": 1, \"run_output_budget_mib\": " + bad + "}");
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'run_output_budget_mib' in " + home.resolve("traceworks.json") + " must be an"
                            + " integer from 1 to 65536 (MiB), not " + bad);
        }
    }

    /** One step can hold its stdout and its stderr up to the output limit each: a smaller budget is refused. */
    @Test
    void runOutputBudgetMib_belowTwiceTheOutputLimit_isRefused_namingBothSettings() throws IOException {
        config("{\"run_output_budget_mib\": 31}");
        assertThatThrownBy(() -> OperatorConfigFile.read(home))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'run_output_budget_mib' in " + home.resolve("traceworks.json") + " must be at least"
                        + " twice 'output_limit_mib' (2 x 16 = 32 MiB), since one step can hold its stdout and its"
                        + " stderr up to the output limit each, not 31");

        config("{\"output_limit_mib\": 100, \"run_output_budget_mib\": 128}");
        assertThatThrownBy(() -> OperatorConfigFile.read(home)).hasMessageContaining("(2 x 100 = 200 MiB)");

        config("{\"output_limit_mib\": 1, \"run_output_budget_mib\": 2}");
        assertThat(OperatorConfigFile.read(home).runOutputBudgetMib()).isEqualTo(2);
        config("{\"run_output_budget_mib\": 32}");
        assertThat(OperatorConfigFile.read(home).runOutputBudgetMib()).isEqualTo(32);
    }

    /** Missing, null and an empty object keep the built-in images; entries are kept by CLI id. */
    @Test
    void aiImages_areEmptyByDefault_andKeepTheEntries() throws IOException {
        config("{}");
        assertThat(OperatorConfigFile.read(home).aiImages()).isEmpty();
        config("{\"ai_images\": null}");
        assertThat(OperatorConfigFile.read(home).aiImages()).isEmpty();
        config("{\"ai_images\": {}}");
        assertThat(OperatorConfigFile.read(home).aiImages()).isEmpty();
        config("{\"ai_images\": {\"claude\": \"registry.example/claude:7\"}}");
        assertThat(OperatorConfigFile.read(home).aiImages()).containsExactly(
                java.util.Map.entry("claude", "registry.example/claude:7"));
        config("{\"ai_images\": {\"claude\": \"c:1\", \"pi\": \"p:1\"}}");
        assertThat(OperatorConfigFile.read(home).aiImages()).containsOnly(java.util.Map.entry("claude", "c:1"),
                java.util.Map.entry("pi", "p:1"));
    }

    @Test
    void aiImages_anUnknownCli_isRefused_withTheValidOnes() throws IOException {
        for (String cli : List.of("codex", "Claude", "gemini")) {
            config("{\"ai_images\": {\"" + cli + "\": \"x:1\"}}");
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(cli)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Unknown key '" + cli + "' in ai_images of " + home.resolve("traceworks.json")
                            + "; the keys are claude, pi");
        }
    }

    @Test
    void aiImages_aValueThatIsNoImageReference_isRefused() throws IOException {
        for (String bad : List.of("\"\"", "\"  \"", "\"a b\"", "\"-x\"", "\"./images/x\"", "\"a\\tb\"")) {
            config("{\"ai_images\": {\"claude\": " + bad + "}}");
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'ai_images.claude' in " + home.resolve("traceworks.json") + " must");
        }
        for (String wrong : List.of("5", "true", "null", "[\"x\"]", "{}")) {
            config("{\"ai_images\": {\"pi\": " + wrong + "}}");
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(wrong)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'ai_images.pi' in " + home.resolve("traceworks.json")
                            + " must be an image reference, a string");
        }
        for (String wrong : List.of("\"claude:1\"", "[]", "5", "true")) {
            config("{\"ai_images\": " + wrong + "}");
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(wrong)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'ai_images' in " + home.resolve("traceworks.json")
                            + " must be an object {claude, pi} of image references");
        }
    }

    @Test
    void aiNetwork_isRestrictedByDefault_andOpenWhenTheOperatorSaysSo() throws IOException {
        config("{}");
        assertThat(OperatorConfigFile.read(home).aiNetwork()).isEqualTo(AiNetwork.RESTRICTED);
        config("{\"ai_network\": null, \"ai_hosts\": null}");
        assertThat(OperatorConfigFile.read(home).aiNetwork()).isEqualTo(AiNetwork.RESTRICTED);
        config("{\"ai_network\": \"restricted\", \"ai_hosts\": {}}");
        assertThat(OperatorConfigFile.read(home).aiNetwork()).isEqualTo(AiNetwork.RESTRICTED);
        config("{\"ai_network\": \"open\"}");
        assertThat(OperatorConfigFile.read(home).aiNetwork()).isEqualTo(new AiNetwork(true, java.util.Map.of()));
        for (String wrong : List.of("\"OPEN\"", "\"none\"", "true", "5", "[]", "{}", "\"\"")) {
            config("{\"ai_network\": " + wrong + "}");
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(wrong)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'ai_network' in " + home.resolve("traceworks.json")
                            + " must be \"restricted\" or \"open\"");
        }
    }

    @Test
    void aDecidedFindingWithAHigherSeverity_isReopenedByDefault_andKeptWhenTheOperatorSaysSo() throws IOException {
        config("{}");
        assertThat(OperatorConfigFile.read(home).decisionOnHigherSeverity())
                .isEqualTo(traceworks.core.register.DecisionOnHigherSeverity.REOPEN);
        config("{\"decision_on_higher_severity\": null}");
        assertThat(OperatorConfigFile.read(home).decisionOnHigherSeverity())
                .isEqualTo(traceworks.core.register.DecisionOnHigherSeverity.REOPEN);
        config("{\"decision_on_higher_severity\": \"reopen\"}");
        assertThat(OperatorConfigFile.read(home).decisionOnHigherSeverity())
                .isEqualTo(traceworks.core.register.DecisionOnHigherSeverity.REOPEN);
        config("{\"decision_on_higher_severity\": \"keep\"}");
        assertThat(OperatorConfigFile.read(home).decisionOnHigherSeverity())
                .isEqualTo(traceworks.core.register.DecisionOnHigherSeverity.KEEP);
        for (String wrong : List.of("\"KEEP\"", "\"unchecked\"", "true", "5", "[]", "{}", "\"\"")) {
            config("{\"decision_on_higher_severity\": " + wrong + "}");
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(wrong)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'decision_on_higher_severity' in " + home.resolve("traceworks.json")
                            + " must be \"reopen\" or \"keep\"");
        }
    }

    /** {@code allowed_models}: missing or null is no restriction; a list names what may run, an empty one nothing. */
    @Test
    void allowedModels_areNoRestrictionByDefault_andAListOfProviderAndModelPairs() throws IOException {
        config("{}");
        assertThat(OperatorConfigFile.read(home).allowedModels().restricted()).isFalse();
        config("{\"allowed_models\": null}");
        assertThat(OperatorConfigFile.read(home).allowedModels().restricted()).isFalse();
        config("{\"allowed_models\": []}");
        assertThat(OperatorConfigFile.read(home).allowedModels().restricted()).isTrue();
        assertThat(OperatorConfigFile.read(home).allowedModels().allows("CLAUDE", "m")).isFalse();
        config("{\"allowed_models\": [{\"provider\": \"claude\", \"model\": \"claude-fable-*\"}, {\"provider\": \"PI\"},"
                + " {\"provider\": \"CLAUDE\", \"model\": null}]}");

        traceworks.core.engine.AllowedModels allowed = OperatorConfigFile.read(home).allowedModels();

        assertThat(allowed.entries()).containsExactly(
                new traceworks.core.engine.AllowedModels.Entry("CLAUDE", "claude-fable-*"),
                new traceworks.core.engine.AllowedModels.Entry("PI", null),
                new traceworks.core.engine.AllowedModels.Entry("CLAUDE", null));
    }

    @Test
    void allowedModels_aWrongType_anUnknownProvider_anUnknownKey_andAModelThatIsNoText_areRefused()
            throws IOException {
        Path file = home.resolve("traceworks.json");
        for (String wrong : List.of("{}", "\"CLAUDE\"", "5", "true", "[\"CLAUDE\"]", "[[]]")) {
            config("{\"allowed_models\": " + wrong + "}");
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(wrong)
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("'allowed_models' in " + file
                            + " must be a list of {\"provider\": ..., \"model\": ...} objects");
        }
        config("{\"allowed_models\": [{\"provider\": \"codex\"}]}");
        assertThatThrownBy(() -> OperatorConfigFile.read(home)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'allowed_models' in " + file + " holds an entry whose provider is 'codex': a provider is"
                        + " one of CLAUDE, PI");
        config("{\"allowed_models\": [{\"model\": \"m\"}]}");
        assertThatThrownBy(() -> OperatorConfigFile.read(home)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whose provider is no string");
        config("{\"allowed_models\": [{\"provider\": \"PI\", \"models\": \"m\"}]}");
        assertThatThrownBy(() -> OperatorConfigFile.read(home)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown key 'models' in an entry of allowed_models of " + file
                        + "; known keys are provider and model");
        for (String wrong : List.of("5", "\"\"", "[\"m\"]")) {
            config("{\"allowed_models\": [{\"provider\": \"PI\", \"model\": " + wrong + "}]}");
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(wrong)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whose model is no text");
        }
    }

    @Test
    void aiHosts_areMoreHostsByCli_inLowerCase() throws IOException {
        config("{\"ai_hosts\": {\"claude\": [\"Gateway.Corp.example:8443\", \"10.0.0.7:443\"], \"pi\": []}}");

        assertThat(OperatorConfigFile.read(home).aiNetwork()).isEqualTo(new AiNetwork(false,
                java.util.Map.of("claude", List.of("10.0.0.7:443", "gateway.corp.example:8443"))));
    }

    @Test
    void aiHosts_anUnknownCli_aWrongType_andWhatIsNoHost_areRefused() throws IOException {
        Path file = home.resolve("traceworks.json");
        config("{\"ai_hosts\": {\"codex\": [\"a.example:443\"]}}");
        assertThatThrownBy(() -> OperatorConfigFile.read(home)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown key 'codex' in ai_hosts of " + file + "; the keys are claude, pi");
        for (String wrong : List.of("[]", "\"a.example:443\"", "5", "true")) {
            config("{\"ai_hosts\": " + wrong + "}");
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(wrong)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'ai_hosts' in " + file + " must be an object {claude, pi} of lists of host:port");
        }
        for (String wrong : List.of("\"a.example:443\"", "{}", "5", "[5]", "[null]", "[[\"a.example:443\"]]")) {
            config("{\"ai_hosts\": {\"pi\": " + wrong + "}}");
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(wrong)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'ai_hosts.pi' in " + file + " must be a list of host:port, each a string");
        }
        for (String bad : List.of("a.example", "*.example:443", "*", "a.example:0", "a.example:70000",
                "https://a.example:443", "a.example:443/v1", "[::1]:443", " a.example:443", "")) {
            config("{\"ai_hosts\": {\"claude\": [\"" + bad + "\"]}}");
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("'ai_hosts.claude' in " + file + " holds \"" + bad + "\", which ");
        }
    }

    @Test
    void invalidJson_isRefused() throws IOException {
        config("{");

        assertThatThrownBy(() -> OperatorConfigFile.read(home)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceworks.json");
    }

    @Test
    void sealSettings_areTheDefaultsWithoutTheirKeys_andReadWhenSet() throws IOException {
        config("{}");
        assertThat(OperatorConfigFile.read(home).seals())
                .isEqualTo(traceworks.core.app.SealSettings.DEFAULTS);
        config("{\"require_seal\": null, \"seal_immutable\": false, \"signing_key\": null,"
                + " \"allowed_signers\": null}");
        assertThat(OperatorConfigFile.read(home).seals())
                .isEqualTo(traceworks.core.app.SealSettings.DEFAULTS);

        config("{\"require_seal\": \"unattended\", \"seal_immutable\": true, \"signing_key\": \"keys/seal\","
                + " \"allowed_signers\": \"/etc/traceworks/allowed_signers\"}");
        var seals = OperatorConfigFile.read(home).seals();

        assertThat(seals.require()).isEqualTo(traceworks.core.app.SealSettings.Require.UNATTENDED);
        assertThat(seals.immutable()).isTrue();
        assertThat(seals.signingKey()).isEqualTo(home.resolve("keys/seal"));
        assertThat(seals.allowedSigners()).isEqualTo(Path.of("/etc/traceworks/allowed_signers"));
        config("{\"require_seal\": \"always\"}");
        assertThat(OperatorConfigFile.read(home).seals().require())
                .isEqualTo(traceworks.core.app.SealSettings.Require.ALWAYS);
    }

    @Test
    void sealSettings_ofTheWrongTypeOrValue_areRefused() throws IOException {
        Path file = home.resolve("traceworks.json");
        for (String wrong : List.of("\"sometimes\"", "\"Always\"", "true", "1", "\"\"")) {
            config("{\"require_seal\": " + wrong + "}");
            assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(wrong)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("'require_seal' in " + file + " must be \"never\", \"unattended\" or \"always\"");
        }
        config("{\"seal_immutable\": \"yes\"}");
        assertThatThrownBy(() -> OperatorConfigFile.read(home)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("'seal_immutable' in " + file + " must be a boolean");
        for (String key : List.of("signing_key", "allowed_signers")) {
            for (String wrong : List.of("5", "[\"a\"]", "\"\"", "\" \"")) {
                config("{\"" + key + "\": " + wrong + "}");
                assertThatThrownBy(() -> OperatorConfigFile.read(home)).as(key + " " + wrong)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("'" + key + "' in " + file + " must be the path of a file");
            }
        }
    }
}
