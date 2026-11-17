package traceworks.core.seal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedSignersTest {

    static final String KEY = SshSignatureTest.PUBLIC_KEY;
    static final String OTHER = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOtherOtherOtherOtherOtherOtherOtherOtherOther";

    private static AllowedSigners parse(String text) {
        return AllowedSigners.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aKeyIsAllowed_forThePrincipalsOfItsLines() {
        AllowedSigners signers = parse("# release keys\n\noperator@example.org,ops@example.org " + KEY + " a comment\n"
                + "eve@example.org " + OTHER + "\n" + "second@example.org " + KEY + "\n");

        assertThat(signers.principals(KEY, "traceworks"))
                .containsExactly("operator@example.org", "ops@example.org", "second@example.org");
        assertThat(signers.principals(OTHER, "traceworks")).containsExactly("eve@example.org");
    }

    @Test
    void aKeyThatIsNotListed_isNotAllowed() {
        assertThat(parse("eve@example.org " + OTHER + "\n").principals(KEY, "traceworks")).isEmpty();
        assertThat(AllowedSigners.none().principals(KEY, "traceworks")).isEmpty();
        assertThat(parse("").principals(KEY, "traceworks")).isEmpty();
    }

    @Test
    void namespaces_limitWhatALineAllows() {
        AllowedSigners git = parse("operator@example.org namespaces=\"git\" " + KEY + "\n");
        AllowedSigners both = parse("operator@example.org namespaces=\"git,traceworks\" " + KEY + "\n");
        AllowedSigners glob = parse("operator@example.org namespaces=\"trace*\" " + KEY + "\n");

        assertThat(git.principals(KEY, "traceworks")).isEmpty();
        assertThat(git.notes(KEY, "traceworks")).singleElement().asString().contains("namespaces").contains("git");
        assertThat(both.principals(KEY, "traceworks")).containsExactly("operator@example.org");
        assertThat(glob.principals(KEY, "traceworks")).containsExactly("operator@example.org");
    }

    @Test
    void aLineWithAnOptionThatIsNotRead_allowsNothing_andIsNamed() {
        AllowedSigners dated = parse("operator@example.org valid-before=\"20300101\" " + KEY + "\n");
        AllowedSigners authority = parse("*@example.org cert-authority " + KEY + "\n");
        AllowedSigners mixed = parse("operator@example.org namespaces=\"traceworks\",valid-after=\"20200101\" " + KEY + "\n");

        assertThat(dated.principals(KEY, "traceworks")).isEmpty();
        assertThat(dated.notes(KEY, "traceworks")).singleElement().asString().contains("line 1").contains("valid-before");
        assertThat(authority.principals(KEY, "traceworks")).isEmpty();
        assertThat(authority.notes(KEY, "traceworks")).singleElement().asString().contains("cert-authority");
        assertThat(mixed.principals(KEY, "traceworks")).isEmpty();
    }

    @Test
    void aQuotedOptionWithASpace_staysOneOption() {
        AllowedSigners signers = parse("operator@example.org namespaces=\"git, traceworks\" " + KEY + "\n");

        assertThat(signers.principals(KEY, "traceworks")).containsExactly("operator@example.org");
    }

    @Test
    void aLineThatIsNoSigner_allowsNothing_andIsCounted() {
        AllowedSigners signers = parse("just some words\noperator@example.org " + KEY + "\noperator@example.org ssh-ed25519\n");

        assertThat(signers.principals(KEY, "traceworks")).containsExactly("operator@example.org");
        assertThat(signers.unreadable()).containsExactly(1, 3);
    }

    @Test
    void theCommentOfAKey_doesNotMatter_butTheKeyDoes() {
        AllowedSigners signers = parse("operator@example.org " + KEY + "\n");

        assertThat(signers.principals(KEY + " some comment", "traceworks")).containsExactly("operator@example.org");
        assertThat(signers.principals(KEY.replace("ssh-ed25519", "ssh-rsa"), "traceworks")).isEmpty();
    }

    /** OpenSSH reads a pattern that starts with ! as a denial; taking it for a name would allow what it denies. */
    @Test
    void aNegatedNamespacePattern_makesTheLineAllowNothing() {
        AllowedSigners negated = parse("operator@example.org namespaces=\"!traceworks,*\" " + KEY + "\n");
        AllowedSigners other = parse("operator@example.org namespaces=\"*,!git\" " + KEY + "\n");

        assertThat(negated.principals(KEY, "traceworks")).isEmpty();
        assertThat(negated.notes(KEY, "traceworks")).singleElement().asString().contains("line 1").contains("!");
        assertThat(other.principals(KEY, "traceworks")).isEmpty();
    }

    @Test
    void aRepeatedNamespacesOption_makesTheLineAllowNothing() {
        AllowedSigners repeated = parse("operator@example.org namespaces=\"git\",namespaces=\"traceworks\" " + KEY + "\n");

        assertThat(repeated.principals(KEY, "traceworks")).isEmpty();
        assertThat(repeated.notes(KEY, "traceworks")).singleElement().asString().contains("namespaces").contains("twice");
    }
}
