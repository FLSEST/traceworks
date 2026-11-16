package traceworks.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI calls of child runs behind the egress proxy (package {@code combo-ai-children}; docs/cli.md, "The network of
 * an AI step"): a {@code simple} step of the run itself, a {@code sub-workflow} whose child asks a model, and a
 * {@code foreach} with {@code workflow_id} of two such children. A child run has a run id of its own in the record,
 * but its containers and the two networks are the run's: they carry the id of the run that was started, which is
 * what {@code resume} and {@code cancel} look for.
 *
 * <p>Each call asks its proxy for an address of its own that nobody listed ({@code 192.0.2.x}, a documentation
 * range, never reached: the proxy refuses it before it connects), so the {@code egress} of each command shows
 * which call asked.
 */
class AiChildrenIT extends AiIntegrationTest {

    private static final Pattern PROXY = Pattern.compile("proxy: http://(traceworks-[0-9a-f-]{36}-\\d+-egress):3128");

    @Test
    void aChildThatAsksAModel_runsBehindTheParentsProxy() {
        String runtime = traceworks.environment().runtime();
        Result run;
        List<String> networks;
        try (Containers.NetworkWatch watch = runtime.equals("docker") ? new Containers.NetworkWatch(runtime) : null) {
            run = traceworks.run("combo-ai-children");
            // The run has ended, so its networks were made long ago: their events are in the stream by now
            networks = watch == null ? null : watch.created();
        }

        assertThat(run.code()).as(run.describe()).isZero();
        RunFolder folder = traceworks.folder(run);
        String parent = folder.id();
        String once = folder.metadata("once").path("subRunId").asText();
        List<String> perItem = folder.ofStep("foreach.item.finished", folder.stepIndex("each")).stream()
                .map(item -> item.path("sub_run").asText()).toList();
        assertThat(perItem).hasSize(2).doesNotHaveDuplicates().doesNotContain(once, parent, "", "null");
        assertThat(folder.runIds()).containsExactlyInAnyOrder(parent, once, perItem.get(0), perItem.get(1));

        // Which run made which call, by the address the call asked its proxy for
        Map<String, String> asked = new LinkedHashMap<>();
        asked.put(parent, "192.0.2.10:443");
        asked.put(once, "192.0.2.20:443");
        asked.put(perItem.get(0), "192.0.2.31:443");
        asked.put(perItem.get(1), "192.0.2.32:443");
        List<String> proxies = new ArrayList<>();
        asked.forEach((runId, target) -> {
            String step = runId.equals(parent) ? "parent_ask" : "child_ask";
            assertThat(folder.eventsOf(runId, "ai.request")).as("the one AI call of run %s", runId).hasSize(1);
            // One command per call, under the run id of the run that made the call, behind the proxy: a child whose
            // calls took the open network would say "open" here and have no egress at all
            List<JsonNode> commands = folder.eventsOf(runId, "step.command");
            assertThat(commands).hasSize(1);
            assertThat(commands.getFirst().path("network").asText()).isEqualTo("hosts");
            assertThat(commands.getFirst().path("network_hosts")).extracting(JsonNode::asText)
                    .containsExactly("api.anthropic.com:443");
            // The egress of the call, in the record of the run that asked: a proxy shared between calls, or one
            // whose log were read under the wrong run, would show another call's address or none
            List<JsonNode> finished = folder.eventsOf(runId, "step.command.finished");
            assertThat(finished).hasSize(1);
            assertThat(finished.getFirst().path("egress").toString())
                    .isEqualTo("[{\"target\":\"" + target + "\",\"verdict\":\"DENY\",\"count\":1}]");
            String answer = folder.outputOf(runId, step);
            assertThat(answer).contains("via proxy " + target + ": HTTP/1.1 403 Forbidden")
                    .as("no way out but the proxy").doesNotContain(": connected");
            Matcher proxy = PROXY.matcher(answer);
            assertThat(proxy.find()).as("the proxy the call was given, in:\n" + answer).isTrue();
            proxies.add(proxy.group(1));
        });
        // A proxy per call, each a container of the run that was started: named by its id, the children's calls too
        assertThat(proxies).doesNotHaveDuplicates().allMatch(name -> name.startsWith("traceworks-" + parent + "-"));

        // Two networks for the whole run, named by the run that was started; a child run makes none of its own
        if (networks != null) {
            assertThat(networks).filteredOn(name -> folder.runIds().stream().anyMatch(name::contains))
                    .containsExactlyInAnyOrder("traceworks-" + parent + "-net", "traceworks-" + parent + "-out");
        }
        // Nothing is left: no container (a step's or a proxy's) and no network, under any of the four run ids
        for (String runId : folder.runIds()) {
            assertThat(Containers.of(runtime, runId)).as("containers left of run %s", runId).isEmpty();
            assertThat(Containers.networksOf(runtime, runId)).as("networks left of run %s", runId).isEmpty();
        }
        Result show = traceworks.exec("show", parent);
        assertThat(show.code()).as(show.describe()).isZero();
        assertThat(show.out()).contains("AI net    restricted");
    }
}
