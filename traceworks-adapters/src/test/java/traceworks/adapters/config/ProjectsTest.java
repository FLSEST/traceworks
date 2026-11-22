package traceworks.adapters.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code projects} in {@code traceworks.json}: the operator binds a project key to a folder, and a folder that is
 * the declared one or lies below it, by real paths, is of that project.
 */
class ProjectsTest {

    @TempDir
    Path temp;

    private Path home() throws IOException {
        return Files.createDirectories(temp.resolve("home"));
    }

    private Path userHome() throws IOException {
        return Files.createDirectories(temp.resolve("user"));
    }

    private Path folder(String name) throws IOException {
        return Files.createDirectories(temp.resolve(name));
    }

    private Projects projects(String entries) throws IOException {
        Files.writeString(home().resolve("traceworks.json"), "{\"projects\": " + entries + "}");
        return Projects.read(home(), userHome());
    }

    private void refused(String entries, String... parts) throws IOException {
        Path home = home();
        assertThatThrownBy(() -> projects(entries)).as(entries).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'projects'").hasMessageContainingAll(parts)
                .hasMessageContaining(home.resolve("traceworks.json").toString());
    }

    // --- the setting ---

    @Test
    void withoutTheSetting_noProjectIsDeclared() throws IOException {
        assertThat(Projects.read(home(), userHome()).folders()).as("no file").isEmpty();
        assertThat(projects("null").folders()).isEmpty();
        assertThat(projects("{}").folders()).isEmpty();
        Files.writeString(home().resolve("traceworks.json"), "{\"actor\": \"operator\"}");
        assertThat(Projects.read(home(), userHome()).folders()).isEmpty();
    }

    @Test
    void aFolder_isAbsolute_orStartsAtTheOperatorsHome() throws IOException {
        Path shop = temp.resolve("srv/shop");

        Projects projects = projects("{\"shop\": \"" + shop + "\", \"traceworks\": \"~/Entwicklung/traceworks\","
                + " \"web\": \"" + temp.resolve("a/../web") + "\"}");

        assertThat(projects.folders()).containsOnlyKeys("shop", "traceworks", "web");
        assertThat(projects.folder("shop")).contains(shop);
        assertThat(projects.folder("traceworks")).contains(userHome().resolve("Entwicklung/traceworks"));
        assertThat(projects.folder("web")).contains(temp.resolve("web"));
        assertThat(projects.folder("other")).isEmpty();
        assertThat(projects.file()).isEqualTo(home().resolve("traceworks.json"));
        assertThat(Files.exists(shop)).as("reading the setting creates nothing").isFalse();
    }

    @Test
    void aSettingThatIsNoObject_isRefused() throws IOException {
        refused("[\"/srv/shop\"]", "an object of project key to folder");
        refused("\"/srv/shop\"", "an object of project key to folder");
        refused("7", "an object of project key to folder");
    }

    @Test
    void aKeyThatIsNoProjectKey_isRefused() throws IOException {
        for (String bad : new String[] {"Shop", "a/b", "..", "", " shop", "-shop"}) {
            refused("{\"" + bad + "\": \"/srv/shop\"}", "\"" + bad + "\"", "no project key",
                    "[a-z0-9][a-z0-9._-]{0,127}");
        }
    }

    @Test
    void aValueThatIsNoAbsoluteOrHomePath_isRefused() throws IOException {
        refused("{\"shop\": \"srv/shop\"}", "\"shop\"", "srv/shop", "absolute", "~/");
        refused("{\"shop\": \"./shop\"}", "absolute");
        refused("{\"shop\": \"~other/shop\"}", "~other/shop", "absolute");
        refused("{\"shop\": \"\"}", "absolute");
        refused("{\"shop\": \" /srv/shop\"}", "white space");
        refused("{\"shop\": \"/srv/shop \"}", "white space");
        refused("{\"shop\": 7}", "\"shop\"", "no string");
        refused("{\"shop\": null}", "\"shop\"", "no string");
        refused("{\"shop\": [\"/srv/shop\"]}", "\"shop\"", "no string");
    }

    @Test
    void theRootOfTheFileSystem_isRefused() throws IOException {
        refused("{\"shop\": \"/\"}", "\"shop\"", "\"/\"", "root of the file system");
        refused("{\"shop\": \"/usr/..\"}", "root of the file system");
    }

    @Test
    void aLinkToTheRootOfTheFileSystem_isRefused() throws IOException {
        Path link = Files.createSymbolicLink(temp.resolve("to-root"), Path.of("/"));

        refused("{\"shop\": \"" + link + "\"}", "\"shop\"", link.toString(), "root of the file system");
    }

    /** Reproduced 2026-11-21: a key that stood twice was accepted, and the last one won without a word. */
    @Test
    void aKeyThatStandsTwice_isRefused_alsoWithTheSameFolder() throws IOException {
        refused("{\"shop\": \"/srv/shop\", \"web\": \"/srv/web\", \"shop\": \"/srv/other\"}", "\"shop\"",
                "twice");
        refused("{\"shop\": \"/srv/shop\", \"shop\": \"/srv/shop\"}", "\"shop\"", "twice");
        Files.writeString(home().resolve("traceworks.json"), "{\"projects\": {\"shop\": \"/srv/shop\"},"
                + " \"projects\": {\"web\": \"/srv/web\"}}");
        Path home = home();
        assertThatThrownBy(() -> Projects.read(home, userHome())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\"projects\"").hasMessageContaining("twice");
        assertThat(projects("{\"shop\": \"/srv/shop\", \"web\": \"/srv/web\"}").folders())
                .as("each key once").hasSize(2);
    }

    @Test
    void twoKeysForOneFolder_areRefused() throws IOException {
        refused("{\"shop\": \"/srv/shop\", \"web\": \"/srv/shop\"}", "\"shop\"", "\"web\"", "/srv/shop",
                "the same folder");
        refused("{\"shop\": \"/srv/shop\", \"web\": \"/srv/web/../shop/\"}", "the same folder");
        refused("{\"shop\": \"~/shop\", \"web\": \"" + userHome().resolve("shop") + "\"}", "the same folder");
    }

    @Test
    void twoKeysForOneFolder_behindALink_areRefused() throws IOException {
        Path shop = folder("shop");
        Path link = Files.createSymbolicLink(temp.resolve("link-to-shop"), shop);

        refused("{\"shop\": \"" + shop + "\", \"web\": \"" + link + "\"}", "\"shop\"", "\"web\"",
                "the same folder");
    }

    @Test
    void aFolderInTheFolderOfAnotherKey_isRefused() throws IOException {
        refused("{\"shop\": \"/srv/shop\", \"web\": \"/srv/shop/web\"}", "\"web\"", "/srv/shop/web", "\"shop\"",
                "/srv/shop", "lies in the folder", "guess");
        refused("{\"web\": \"/srv/shop/web\", \"shop\": \"/srv/shop\"}", "lies in the folder");
        refused("{\"all\": \"~/\", \"shop\": \"~/work/shop\"}", "lies in the folder");
    }

    @Test
    void aFolderInTheFolderOfAnotherKey_behindALink_isRefused() throws IOException {
        Path shop = folder("shop");
        Files.createDirectories(shop.resolve("web"));
        Path link = Files.createSymbolicLink(temp.resolve("link-to-web"), shop.resolve("web"));

        refused("{\"shop\": \"" + shop + "\", \"web\": \"" + link + "\"}", "\"web\"", "\"shop\"",
                "lies in the folder");
        // One of the two is not there yet: it is compared as written with the real path of the other
        Path toShop = Files.createSymbolicLink(temp.resolve("link-to-shop"), shop);
        refused("{\"shop\": \"" + toShop + "\", \"new\": \"" + shop.resolve("new") + "\"}", "\"new\"",
                "\"shop\"", "lies in the folder");
    }

    @Test
    void aSiblingWhoseNameOnlyStartsTheSame_isAnotherFolder() throws IOException {
        Projects projects = projects("{\"shop\": \"/srv/shop\", \"shop2\": \"/srv/shop2\"}");

        assertThat(projects.folders()).containsOnlyKeys("shop", "shop2");
    }

    // --- which project a folder is of ---

    @Test
    void aFolder_isOfTheProjectWhoseFolderItIs_orLiesBelow() throws IOException {
        Path shop = folder("shop");
        Path deep = Files.createDirectories(shop.resolve("src/main"));
        Path web = folder("web");
        Projects projects = projects("{\"shop\": \"" + shop + "\", \"web\": \"" + web + "\"}");

        assertThat(projects.holding(shop)).contains(new Projects.Declared("shop", shop));
        assertThat(projects.holding(deep)).as("a subfolder").contains(new Projects.Declared("shop", shop));
        assertThat(projects.holding(shop.resolve("src/../src/main"))).contains(new Projects.Declared("shop", shop));
        assertThat(projects.holding(web)).contains(new Projects.Declared("web", web));
        assertThat(projects.holding(temp)).as("a folder that holds a declared one").isEmpty();
        assertThat(projects.holding(folder("other"))).isEmpty();
    }

    @Test
    void aSiblingWhoseNameOnlyStartsTheSame_isNotOfTheProject() throws IOException {
        Path shop = folder("shop");
        Path shop2 = folder("shop2");
        Projects projects = projects("{\"shop\": \"" + shop + "\"}");

        assertThat(projects.holding(shop2)).isEmpty();
        assertThat(projects.holding(shop)).isPresent();
    }

    @Test
    void aDeclaredFolderThatDoesNotExist_isNoError_andHoldsNoFolder() throws IOException {
        Path missing = temp.resolve("missing");
        Projects projects = projects("{\"shop\": \"" + missing + "\"}");

        assertThat(projects.holding(missing)).as("nothing is there").isEmpty();
        assertThat(projects.holding(missing.resolve("sub"))).isEmpty();
        assertThat(projects.holding(folder("other"))).isEmpty();

        Files.createDirectories(missing.resolve("sub"));
        assertThat(projects.holding(missing.resolve("sub"))).as("asked when the folder is there")
                .contains(new Projects.Declared("shop", missing));
    }

    @Test
    void aDeclaredLinkToAFolder_holdsWhatItsTargetHolds() throws IOException {
        Path target = folder("disk/shop");
        Path sub = Files.createDirectories(target.resolve("sub"));
        Path link = Files.createSymbolicLink(temp.resolve("shop-link"), target);
        Projects projects = projects("{\"shop\": \"" + link + "\"}");

        assertThat(projects.holding(target)).contains(new Projects.Declared("shop", link));
        assertThat(projects.holding(sub)).contains(new Projects.Declared("shop", link));
        assertThat(projects.holding(link)).contains(new Projects.Declared("shop", link));
        assertThat(projects.holding(link.resolve("sub"))).contains(new Projects.Declared("shop", link));
    }

    @Test
    void aLinkIntoADeclaredFolder_isOfItsProject() throws IOException {
        Path shop = folder("shop");
        Files.createDirectories(shop.resolve("sub"));
        Path link = Files.createSymbolicLink(temp.resolve("into-shop"), shop.resolve("sub"));
        Projects projects = projects("{\"shop\": \"" + shop + "\"}");

        assertThat(projects.holding(link)).contains(new Projects.Declared("shop", shop));
    }

    @Test
    void theOperatorsHomeDirectory_startsAFolderWrittenWithATilde() throws IOException {
        Path work = Files.createDirectories(userHome().resolve("work/shop/module"));
        Projects projects = projects("{\"shop\": \"~/work/shop\"}");

        assertThat(projects.holding(work)).contains(new Projects.Declared("shop", userHome().resolve("work/shop")));
    }
}
