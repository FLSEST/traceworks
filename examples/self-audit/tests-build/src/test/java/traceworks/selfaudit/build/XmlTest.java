package traceworks.selfaudit.build;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XmlTest {

    @Test
    void parse_givesElementsByLocalNameWithTheirLines() {
        Xml.Element root = Xml.parse("""
                <?xml version="1.0"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <dependencies>
                    <dependency><groupId> g </groupId><artifactId>a</artifactId></dependency>
                  </dependencies>
                </project>
                """);

        assertThat(root.name()).isEqualTo("project");
        Xml.Element dep = root.path("dependencies/dependency").getFirst();
        assertThat(dep.text("groupId")).isEqualTo("g");
        assertThat(dep.line()).isEqualTo(4);
        assertThat(root.text("missing")).isEmpty();
    }

    @Test
    void aDocumentType_isRefused_soNoEntityIsExpanded() {
        assertThatThrownBy(() -> Xml.parse("""
                <?xml version="1.0"?>
                <!DOCTYPE project [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <project>&x;</project>
                """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not well-formed XML");
    }

    @Test
    void deepNesting_isRefused() {
        String deep = "<a>".repeat(Xml.MAX_DEPTH + 1) + "</a>".repeat(Xml.MAX_DEPTH + 1);

        assertThatThrownBy(() -> Xml.parse(deep)).hasMessageContaining("nested deeper than");
    }

    @Test
    void brokenXml_isRefused() {
        assertThatThrownBy(() -> Xml.parse("<project><x></project>")).hasMessageContaining("not well-formed XML");
    }
}
