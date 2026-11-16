package traceworks.selfaudit.areas;

import traceworks.selfaudit.areas.Finder.Call;
import traceworks.selfaudit.areas.Finder.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the finder knows of the calls between the members of the sources: every member with its lines, and who calls
 * whom by the type the compiler resolved. The incremental check takes its neighbours from that.
 */
class CallsTest {

    @TempDir
    Path root;

    private static final String NO_RULE = "rule unused\n  why Nothing.\n  call java.lang.Runtime#exec\n";

    private void write(String path, String content) throws IOException {
        Path file = root.resolve("mod/src/main/java").resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Result find() throws IOException {
        return Finder.find(root, List.of(root.resolve("mod/src/main/java")), RuleFile.parse(NO_RULE));
    }

    private static List<String> calls(Result result) {
        return result.calls().stream().map(CallsTest::shown).toList();
    }

    private static String shown(Call call) {
        return call.from().member().label() + " -> " + call.to().member().label();
    }

    @Test
    void everyMemberOfANamedClass_isListedWithItsFileAndLines_alsoOneWithoutAHit() throws IOException {
        write("p/A.java", """
                package p;
                class A {
                    private final int n = 1;
                    A() { }
                    void run() {
                        int x = n;
                    }
                    static class Inner { void in() { } }
                }
                """);

        Result result = find();

        assertThat(result.members()).extracting(m -> m.file() + " " + m.member().label() + " " + m.member().start()
                        + "-" + m.member().end())
                .containsExactly("mod/src/main/java/p/A.java A.n 3-3", "mod/src/main/java/p/A.java A() 4-4",
                        "mod/src/main/java/p/A.java A.run() 5-7", "mod/src/main/java/p/A.java A.Inner.in() 8-8");
    }

    @Test
    void aCall_aCreationAndAMethodReference_areCallsOfTheMemberTheyResolveTo_acrossFiles() throws IOException {
        write("p/A.java", """
                package p;
                class A {
                    void run(java.util.List<String> all) {
                        B b = new B("x");
                        b.say();
                        all.forEach(B::print);
                        "text".length();
                    }
                }
                """);
        write("p/B.java", """
                package p;
                class B {
                    B(String s) { }
                    void say() { }
                    void say(int other) { }
                    static void print(String s) { }
                }
                """);

        assertThat(calls(find())).containsExactly("A.run(java.util.List) -> B(String)", "A.run(java.util.List) -> B.say()",
                "A.run(java.util.List) -> B.print(String)");
    }

    @Test
    void aCallThroughAnInterface_countsEveryImplementationOfTheProject_andASupertypeItsOverrides() throws IOException {
        write("p/Port.java", "package p;\ninterface Port {\n    void send(String s);\n}\n");
        write("p/One.java", "package p;\nclass One implements Port {\n    public void send(String s) { }\n}\n");
        write("p/Two.java", "package p;\nclass Two extends One {\n    @Override public void send(String s) { }\n"
                + "    void other(String s) { }\n}\n");
        write("p/Lone.java", "package p;\nclass Lone {\n    public void send(String s) { }\n}\n");
        write("p/User.java", "package p;\nclass User {\n    void use(Port port) { port.send(\"x\"); }\n"
                + "    void direct(Two two) { two.send(\"y\"); }\n}\n");

        assertThat(calls(find())).containsExactlyInAnyOrder("User.use(Port) -> Port.send(String)",
                "User.use(Port) -> One.send(String)", "User.use(Port) -> Two.send(String)",
                "User.direct(Two) -> Two.send(String)");
    }

    @Test
    void aCallInALambdaOrAnAnonymousClass_belongsToTheMemberAroundIt_andACallOfItselfIsNone() throws IOException {
        write("p/A.java", """
                package p;
                class A {
                    Runnable field = () -> helper();
                    void outer() {
                        new Thread(new Runnable() {
                            public void run() { helper(); inner(); }
                            void inner() { }
                        }).start();
                        outer();
                    }
                    void helper() { }
                    A() { this(1); }
                    A(int n) { }
                }
                """);

        assertThat(calls(find())).containsExactly("A.field -> A.helper()", "A.outer() -> A.helper()",
                "A() -> A(int)");
    }
}
