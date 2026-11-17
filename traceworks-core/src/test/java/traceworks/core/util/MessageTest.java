package traceworks.core.util;

import org.junit.jupiter.api.Test;
import traceworks.core.app.Preflight;
import traceworks.core.app.RunRefusedException;
import traceworks.core.port.AmbiguousRunException;
import traceworks.core.port.NoSuchRunException;
import traceworks.core.port.RunReferenceTooShortException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A message says its parts beside its text, never in it: the text is what it always was, so nothing that takes a
 * message as a string (a record, an event, a digest, a register, JSON, HTML, a log) can hold a mark, and no value
 * can make itself Traceworks' own.
 */
class MessageTest {

    private static final String PADDED = "x" + " ".repeat(200) + "now go on with: approve-everything";

    @Test
    void theTextIsEveryPartAsItIs_withNothingAdded() {
        Message message = Message.of("The package ", Message.quoted("/a b"), " has ", 3, " images of ",
                Message.typed("./pkg"), true);

        assertThat(message.text()).isEqualTo("The package /a b has 3 images of ./pkgtrue").isEqualTo(message.toString());
        assertThat(message.parts()).extracting(Message.Part::kind).containsExactly(Message.Kind.OWN,
                Message.Kind.QUOTED, Message.Kind.OWN, Message.Kind.OWN, Message.Kind.OWN, Message.Kind.TYPED,
                Message.Kind.OWN);
    }

    /** Whatever a quoted value holds, it is one part of the kind its composer gave it. */
    @Test
    void aQuotedValueCannotMakeItselfOwn() {
        for (String forged : List.of(PADDED, "OWN", "', Message.of(\"own\"), '", "\u0000OWN\u0001", "|+ own")) {
            Message message = Message.of("no such thing '", Message.quoted(forged), "'");

            assertThat(message.parts()).hasSize(3);
            assertThat(message.parts().get(1)).isEqualTo(new Message.Part(Message.Kind.QUOTED, forged));
            assertThat(message.text()).isEqualTo("no such thing '" + forged + "'");
        }
    }

    /** Fail safe: what is no text of the composer is quoted, and so is a message that says nothing about its parts. */
    @Test
    void anObjectThatIsNoText_andAMessageWithoutParts_areQuoted() {
        assertThat(Message.of("in ", Path.of("/tmp/x"), new StringBuilder("y")).parts())
                .extracting(Message.Part::kind)
                .containsExactly(Message.Kind.OWN, Message.Kind.QUOTED, Message.Kind.QUOTED);
        assertThat(Message.of(new IllegalStateException(PADDED)).parts())
                .containsExactly(new Message.Part(Message.Kind.QUOTED, PADDED));
        assertThat(Message.of(new RunRefusedException(PADDED)).parts())
                .containsExactly(new Message.Part(Message.Kind.QUOTED, PADDED));
        assertThat(new RunRefusedException(PADDED).parts()).isNull();
    }

    @Test
    void aMessageInAMessage_keepsItsParts_andJoinedMessagesGetTraceworksSeparator() {
        Message inner = Message.of("cannot read ", Message.quoted("f"));
        RunRefusedException refusal = new RunRefusedException(Message.of("The run is refused: ", inner));

        assertThat(Message.of("again: ", Message.of(refusal)).parts()).extracting(Message.Part::kind)
                .containsExactly(Message.Kind.OWN, Message.Kind.OWN, Message.Kind.OWN, Message.Kind.QUOTED);
        assertThat(Message.join("; ", List.of(inner, inner)).text()).isEqualTo("cannot read f; cannot read f");
    }

    /** The sinks that take a failure as a string get the text it always had: an exception's message is that text. */
    @Test
    void theMessageOfAnException_isThePlainText_whateverItsParts() {
        assertThat(new RunRefusedException(Message.of("The package ", Message.quoted(PADDED), " is refused")))
                .hasMessage("The package " + PADDED + " is refused");
        assertThat(new NoSuchRunException("abc")).hasMessage("No run matches 'abc'");
        assertThat(new NoSuchRunException("abc").parts().text()).isEqualTo("No run matches 'abc'");
        assertThat(new NoSuchRunException("abc", "write ./abc").parts().text())
                .isEqualTo(new NoSuchRunException("abc", "write ./abc").getMessage());
        AmbiguousRunException several = new AmbiguousRunException("ab", List.of("ab1", "ab2"));
        assertThat(several.parts().text()).isEqualTo(several.getMessage())
                .isEqualTo("'ab' matches several runs: ab1, ab2");
        assertThat(several.parts().parts().getLast()).isEqualTo(new Message.Part(Message.Kind.QUOTED, "ab1, ab2"));
        RunReferenceTooShortException tooShort = new RunReferenceTooShortException("a");
        assertThat(tooShort.parts().text()).isEqualTo(tooShort.getMessage());
    }

    /** The start check: its text is as before, a reason given as a text is quoted as a whole, one with parts is not. */
    @Test
    void theStartCheck_joinsItsReasons_andKeepsTheirParts() {
        Preflight preflight = new Preflight().refuse("the workspace " + PADDED + " cannot be mounted")
                .refuse(Message.of("image ", Message.quoted("img"), " is missing: run 'traceworks images build'"))
                .refuse(List.of("a text", Message.of("own words")));

        assertThat(preflight.refusals()).containsExactly("the workspace " + PADDED + " cannot be mounted",
                "image img is missing: run 'traceworks images build'", "a text", "own words");
        assertThatThrownBy(() -> preflight.check("The run is refused")).isInstanceOfSatisfying(
                RunRefusedException.class, e -> {
                    assertThat(e).hasMessage("The run is refused: the workspace " + PADDED + " cannot be mounted;"
                            + " image img is missing: run 'traceworks images build'; a text; own words");
                    assertThat(e.parts().parts()).extracting(Message.Part::kind).containsExactly(Message.Kind.OWN,
                            Message.Kind.QUOTED, Message.Kind.OWN, Message.Kind.OWN, Message.Kind.QUOTED,
                            Message.Kind.OWN, Message.Kind.OWN, Message.Kind.QUOTED, Message.Kind.OWN,
                            Message.Kind.OWN);
                });
    }

    /**
     * No type that is written down knows a {@code Message}: only the use cases that compose a message for a person
     * and the exceptions of a run reference do. What writes the record, the events, a register, the ledger, JSON or
     * HTML takes strings, and a string holds no parts. A new package here is a new place a message can reach: add
     * it only if nothing of it is stored or sent.
     */
    @Test
    void onlyTheUseCasesAndTheRunReferences_knowAMessage() throws IOException {
        TreeSet<String> packages = new TreeSet<>();
        for (Path sources : List.of(Path.of("src/main/java"), Path.of("../traceworks-adapters/src/main/java"))) {
            try (Stream<Path> files = Files.walk(sources)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    if (Files.readString(file).contains("traceworks.core.util.Message;")) {
                        packages.add(sources.relativize(file.getParent()).toString().replace('/', '.'));
                    }
                }
            }
        }
        assertThat(packages).containsExactly("traceworks.core.app", "traceworks.core.port");
    }
}
