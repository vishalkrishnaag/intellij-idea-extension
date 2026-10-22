package local.felidae.intellij.execution;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * launchCommand is a pure static function (no Project, no IntelliJ platform dependency), so it is tested
 * directly here rather than through a BasePlatformTestCase fixture.
 */
class FelidaeProcessRunnerTest {

    @Test
    void aJarIsLaunchedWithJavaJar() {
        List<String> command = FelidaeProcessRunner.launchCommand(
                Path.of("build", "java", "felidae.jar"),
                List.of("program.fx")
        );
        assertEquals(List.of("java", "-jar", Path.of("build", "java", "felidae.jar").toString(), "program.fx"), command);
    }

    @Test
    void aJarPathIsMatchedCaseInsensitively() {
        List<String> command = FelidaeProcessRunner.launchCommand(Path.of("felidae.JAR"), List.of());
        assertEquals(List.of("java", "-jar", "felidae.JAR"), command);
    }

    @Test
    void aNativeExecutableIsRunDirectly() {
        List<String> command = FelidaeProcessRunner.launchCommand(
                Path.of("/opt/felidae/bin/felidae"),
                List.of("--check-json", "program.fx")
        );
        assertEquals(List.of("/opt/felidae/bin/felidae", "--check-json", "program.fx"), command);
    }

    @Test
    void noArgumentsIsHandled() {
        List<String> command = FelidaeProcessRunner.launchCommand(Path.of("felidae"), List.of());
        assertEquals(List.of("felidae"), command);
    }
}
