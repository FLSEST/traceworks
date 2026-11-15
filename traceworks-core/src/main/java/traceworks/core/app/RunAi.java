package traceworks.core.app;

import traceworks.core.ai.cli.CliAiProvider;
import traceworks.core.container.ImageCatalog;
import traceworks.core.context.BlockAiSettings;
import traceworks.core.engine.RunOptions;
import traceworks.core.port.AiCli;
import traceworks.core.port.AiNetwork;
import traceworks.core.port.AiProvider;
import traceworks.core.port.AiProviders;
import traceworks.core.port.CommandRunner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the run's AI steps run with: one provider per configured CLI, in the CLI's built-in image (the tag of the
 * run's image catalog) or in the operator's replacement of it ({@code ai_images}), with the credentials of that provider. The default provider is
 * {@value BlockAiSettings#DEFAULT_PROVIDER} when a CLI for it is configured; a block names its own, else the
 * workflow's ({@link BlockAiSettings#providerFor}), so a step without either runs on it, the provider whose image
 * and credentials the start check asks for.
 *
 * @param clis        the configured AI CLIs, one per provider id
 * @param images      the image each CLI runs in, by CLI id: its built-in image's tag, or the operator's reference
 * @param credentials the credentials with a value, by provider id and name ({@link AiCredentials})
 * @param network     where the containers of the calls can connect: the operator's {@code ai_network} and
 *                    {@code ai_hosts}
 */
record RunAi(List<AiCli> clis, Map<String, String> images, Map<String, Map<String, String>> credentials,
             AiNetwork network) {

    /**
     * The key under which a credential joins the run's secrets map ({@link RunOptions#secrets}): every cut of the
     * run's text keeps its value whole, as a secret's, but no step can declare it, since a secret's
     * name never has a colon. A credential reaches only its provider's containers.
     */
    static final String CREDENTIAL_KEY = "credential:";

    /**
     * The run's AI setup from the configured CLIs.
     *
     * @throws IllegalStateException when a CLI names an image that is not a built-in one: a build of Traceworks
     *                               whose CLIs and images do not match
     */
    static RunAi of(List<AiCli> clis, ImageCatalog catalog, Map<String, Map<String, String>> credentials) {
        return of(clis, catalog, credentials, Map.of(), AiNetwork.RESTRICTED);
    }

    /**
     * The run's AI setup from the configured CLIs, where the operator's {@code ai_images} replaces the built-in image
     * of a CLI: that CLI runs in the operator's reference, whatever the catalog holds for it.
     *
     * @param aiImages the operator's images by CLI id in lower case ({@link OperatorSettings#aiImages()})
     * @param network  where the containers of the calls can connect ({@link OperatorSettings#aiNetwork()})
     * @throws IllegalStateException when a CLI without a replacement names an image that is not a built-in one: a
     *                               build of Traceworks whose CLIs and images do not match
     */
    static RunAi of(List<AiCli> clis, ImageCatalog catalog, Map<String, Map<String, String>> credentials,
                    Map<String, String> aiImages, AiNetwork network) {
        Map<String, String> images = new LinkedHashMap<>();
        for (AiCli cli : clis) {
            String replacement = aiImages.get(cli.id().toLowerCase(Locale.ROOT));
            if (replacement != null) {
                images.put(cli.id(), replacement);
                continue;
            }
            String tag = catalog.builtInTags().get(cli.image());
            if (tag == null) {
                throw new IllegalStateException("The AI CLI " + cli.id() + " runs in the image " + cli.image()
                        + ", which is not a built-in image of this Traceworks");
            }
            images.put(cli.id(), tag);
        }
        return new RunAi(clis, images, credentials, network);
    }

    /** One {@link CliAiProvider} per CLI, each running its CLI through {@code runner}. */
    AiProviders providers(CommandRunner runner) {
        List<AiProvider> providers = new ArrayList<>();
        for (AiCli cli : clis) {
            providers.add(new CliAiProvider(cli, runner, images.get(cli.id()),
                    credentials.getOrDefault(cli.id(), Map.of()), network));
        }
        boolean defaultConfigured = clis.stream()
                .anyMatch(cli -> cli.id().equalsIgnoreCase(BlockAiSettings.DEFAULT_PROVIDER));
        return AiProviders.of(defaultConfigured ? BlockAiSettings.DEFAULT_PROVIDER : null, providers);
    }

    /**
     * The run's secrets with the credentials under {@link #CREDENTIAL_KEY} keys, which no step can declare. Each
     * key ends in {@code :<NAME>}, the name a replacement in a step's text shows ({@code SecretReplacer#ofKeys}).
     */
    Map<String, String> keptWhole(Map<String, String> runSecrets) {
        Map<String, String> all = new LinkedHashMap<>(runSecrets);
        credentials.forEach((provider, named) -> named.forEach((name, value) ->
                all.put(CREDENTIAL_KEY + provider + ":" + name, value)));
        return all;
    }
}
