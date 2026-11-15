package traceworks.core.view;

import traceworks.core.describe.Described;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DescribeHtmlTest {

    private static final String SAMPLE = """
            {"id":"wf-1","name":"Deploy","description":"Builds and ships.","default_context":{"target":"staging"},
             "requires":["container.network"],
             "metadata":{"audit":{"enabled":true,"key":"deploys","category":"ops","report_block":"report"}},
             "blocks":[
              {"type":"shell","name":"build","config":{"command":"make build","network":true,
               "secrets":["DEPLOY_TOKEN"]}},
              {"type":"simple","name":"judge","config":{"command":"Judge it.","provider":"claude","model":"opus"}},
              {"type":"switch","name":"route","config":{"expression":"{{ target }}","cases":[
                {"value":"prod","blocks":[{"type":"shell","name":"careful","config":{"command":"echo careful"}}]}]}},
              {"type":"approval","name":"publish","config":{"question":"Publish?"}},
              {"type":"sub-workflow","name":"after","config":{"workflow_id":"../after"}},
              {"type":"shell","name":"report","config":{"command":"cat report.json"}}]}""";

    private static String render(String json) {
        return DescribeHtml.render(Described.of(json, Map.of("../after", """
                {"id":"after-1","name":"After","blocks":[{"type":"shell","name":"notify","config":{"command":"notify"}}]}""")),
                "1.2.3");
    }

    @Test
    void thePage_holdsWhatTheTextHolds() {
        String html = render(SAMPLE);

        assertThat(html).startsWith("<!DOCTYPE html>").endsWith("</body></html>\n");
        assertThat(html).contains("<title>Workflow Deploy</title>", "Builds and ships.", "wf-1", "d1g35t",
                "/packages/sample", "<td>target</td>", "staging", "container.network", "deploys", "report",
                "<td>build</td>", "<code>make build</code>", "network open", "secrets DEPLOY_TOKEN",
                "provider claude", "model opus", "case &quot;prod&quot;", "careful", "a person is asked",
                "workflow After (after-1)", "notify", "1.2.3",
                "8 blocks: 1 AI step, 1 shell step with network, 1 step that asks a person",
                "The operator&#39;s settings decide the rest", "traceworks check");
    }

    /** As {@code run.html}: one file, the style inside it, nothing loaded and nothing run. */
    @Test
    void thePage_isSelfContained() {
        String html = render(SAMPLE);

        assertThat(html).contains("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none';"
                + " style-src 'unsafe-inline'\">");
        assertThat(html).contains("<style>" + RunHtml.CSS + "</style>");
        assertThat(html).doesNotContain("<script", "<link", "<img", "<iframe", "http://", "https://", "src=",
                "href=", "@import", "url(");
    }

    @Test
    void everyTextOfThePackage_isEscaped() {
        String evil = "<script>alert(1)</script>";
        String json = """
                {"id":"wf<script>alert(1)</script>","name":"N<script>alert(1)</script>",
                 "description":"D<script>alert(1)</script>",
                 "default_context":{"k<script>alert(1)</script>":"v<script>alert(1)</script>"},
                 "requires":["r<script>alert(1)</script>"],
                 "metadata":{"audit":{"enabled":true,"key":"k","category":"c<script>alert(1)</script>",
                  "report_block":"b<script>alert(1)</script>"}},
                 "blocks":[
                  {"type":"shell<script>alert(1)</script>","name":"<script>alert(1)</script>",
                   "config":{"command":"echo <script>alert(1)</script>","secrets":["<script>alert(1)</script>"],
                    "container_image":"<script>alert(1)</script>","mounts":["<script>alert(1)</script>"]}},
                  {"type":"shell","name":"s","config":{"command":"x","container_image":"i<script>alert(1)</script>",
                   "secrets":["S<script>alert(1)</script>"],"mounts":["m<script>alert(1)</script>"]}},
                  {"type":"simple","name":"a","config":{"command":"x","provider":"p<script>alert(1)</script>",
                   "model":"m<script>alert(1)</script>","tools":"<script>alert(1)</script>",
                   "web":"<script>alert(1)</script>"}},
                  {"type":"approval","name":"q","config":{"question":"?","actor":"<script>alert(1)</script>"}},
                  {"type":"switch","name":"sw","config":{"expression":"x","cases":[
                   {"value":"<script>alert(1)</script>","blocks":[{"type":"shell",
                    "name":"n<script>alert(1)</script>","config":{"command":"<script>alert(1)</script>"}}]}]}},
                  {"type":"sub-workflow","name":"c","config":{"workflow_id":"<script>alert(1)</script>"}}]}""";

        String html = render(json);

        assertThat(html).doesNotContain("<script").doesNotContain(evil);
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void aCharacterThatChangesHowThePageReads_isShownByItsCodePoint() {
        String html = render("{\"id\":\"wf\",\"name\":\"a\\u202Eb\",\"blocks\":[{\"type\":\"shell\",\"name\":\"x\\u202Ey\","
                + "\"config\":{\"command\":\"echo \\u202E\"}}]}");

        assertThat(html).doesNotContain("\u202E").contains("a[U+202E]b", "x[U+202E]y");
    }

    @Test
    void theSamePackage_givesTheSameBytes() {
        assertThat(render(SAMPLE)).isEqualTo(render(SAMPLE));
    }
}
