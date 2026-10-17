package local.felidae.intellij.execution;

import com.intellij.openapi.project.Project;
import local.felidae.intellij.settings.FelidaeSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import com.intellij.openapi.util.SystemInfo;

public final class FelidaeExecutableResolver {

    public static final String INTERPRETER_ENVIRONMENT_VARIABLE =
            "FELIDAE_PATH";

    private static final String WINDOWS_INTERPRETER_EXECUTABLE =
            "felidae.bat";

    private static final String UNIX_INTERPRETER_EXECUTABLE =
            "felidae";

    /**
     * What every action shows when no interpreter can be found. The locations
     * are the ones {@link #resolve} searches, so the two cannot drift apart.
     */
    public static final String INTERPRETER_NOT_FOUND_MESSAGE =
            "Felidae interpreter was not found.\n\n" +
                    "Looked in <project>/build/java/cli/install/felidae/bin (./gradlew installDist in the Felidae " +
                    "repository), dist/bin and release/bin, and on PATH.\n\n" +
                    "Set the interpreter path in Settings | Tools | Felidae, or the " +
                    "FELIDAE_PATH environment variable.\n";

    private FelidaeExecutableResolver() {
        throw new AssertionError(
                "FelidaeExecutableResolver cannot be instantiated."
        );
    }

    public static @Nullable Path resolveInterpreter(
            @NotNull Project project
    ) {
        Path configured = FelidaeSettingsState.getInstance(project).resolvedInterpreterPath();
        if (configured != null) {
            return configured;
        }
        return resolve(
                project,
                INTERPRETER_ENVIRONMENT_VARIABLE,
                WINDOWS_INTERPRETER_EXECUTABLE,
                UNIX_INTERPRETER_EXECUTABLE
        );
    }

    private static @Nullable Path resolve(
            @NotNull Project project,
            @NotNull String environmentVariable,
            @NotNull String windowsExecutable,
            @NotNull String unixExecutable
    ) {
        Path configured = resolveFromEnvironment(environmentVariable);

        if (configured != null) {
            return configured;
        }

        String basePath = project.getBasePath();

        if (basePath == null || basePath.isBlank()) {
            return null;
        }

        final Path projectRoot;

        try {
            projectRoot = Path.of(basePath)
                    .toAbsolutePath()
                    .normalize();
        } catch (InvalidPathException exception) {
            return null;
        }

        String executable = SystemInfo.isWindows ? windowsExecutable : unixExecutable;
        // felidae is a launcher script written by Gradle's installDist; a packaged one has the same bin/ layout.
        List<Path> candidates = List.of(
                projectRoot.resolve("build/java/cli/install/felidae/bin").resolve(executable),
                projectRoot.resolve("dist/bin").resolve(executable),
                projectRoot.resolve("release/bin").resolve(executable),
                projectRoot.resolve("bin").resolve(executable));

        for (Path candidate : candidates) {
            Path normalized = candidate
                    .toAbsolutePath()
                    .normalize();

            if (Files.isRegularFile(normalized) && (SystemInfo.isWindows || Files.isExecutable(normalized))) {
                return normalized;
            }
        }

        String searchPath = System.getenv("PATH");
        if (searchPath != null) {
            for (String directory : searchPath.split(java.io.File.pathSeparator)) {
                if (directory.isBlank()) continue;
                Path candidate = Path.of(directory).resolve(executable);
                if (Files.isRegularFile(candidate) && (SystemInfo.isWindows || Files.isExecutable(candidate)))
                    return candidate.toAbsolutePath().normalize();
            }
        }

        return null;
    }

    private static @Nullable Path resolveFromEnvironment(
            @NotNull String variableName
    ) {
        String configured = System.getenv(variableName);

        if (configured == null || configured.isBlank()) {
            return null;
        }

        try {
            Path path = Path.of(configured)
                    .toAbsolutePath()
                    .normalize();

            return Files.isRegularFile(path)
                    ? path
                    : null;
        } catch (InvalidPathException exception) {
            return null;
        }
    }
}
