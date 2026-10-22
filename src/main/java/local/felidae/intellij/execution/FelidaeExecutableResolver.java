package local.felidae.intellij.execution;

import com.intellij.openapi.project.Project;
import local.felidae.intellij.settings.FelidaeSettingsState;
import local.felidae.intellij.ui.FelidaeConsoleService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import com.intellij.openapi.util.SystemInfo;

public final class FelidaeExecutableResolver {

    public static final String COMPILER_ENVIRONMENT_VARIABLE =
            "FELIDAE_PATH";

    private static final String WINDOWS_COMPILER_EXECUTABLE =
            "felidae.bat";

    private static final String UNIX_COMPILER_EXECUTABLE =
            "felidae";

    /**
     * What every action shows when no felidae compiler can be found. The locations
     * are the ones {@link #resolve} searches, so the two cannot drift apart.
     */
    public static final String COMPILER_NOT_FOUND_MESSAGE =
            "Felidae compiler was not found.\n\n" +
                    "Looked for <project>/build/java/felidae.jar (built by ./gradlew felidaeJar in the Felidae " +
                    "repository), and on PATH.\n\n" +
                    "Set the compiler path in Settings | Tools | Felidae, or the " +
                    "FELIDAE_PATH environment variable.\n";

    /**
     * Where Gradle's felidaeJar task (on the merged root compiler module) writes the jar, relative to a project
     * root. Pure and project-independent on purpose, so it is unit-tested directly (FelidaeExecutableResolverTest)
     * without needing a Project or the IntelliJ platform test fixtures the rest of this class's resolution
     * requires.
     */
    static @NotNull String compilerJarRelativePath() {
        return "build/java/felidae.jar";
    }

    private FelidaeExecutableResolver() {
        throw new AssertionError(
                "FelidaeExecutableResolver cannot be instantiated."
        );
    }

    public static @Nullable Path resolveCompiler(
            @NotNull Project project
    ) {
        Path configured = FelidaeSettingsState.getInstance(project).resolvedCompilerPath();
        if (configured != null) {
            return configured;
        }
        return resolve(
                project,
                COMPILER_ENVIRONMENT_VARIABLE,
                WINDOWS_COMPILER_EXECUTABLE,
                UNIX_COMPILER_EXECUTABLE
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
        // felidae compiles to JVM bytecode; it is not an interpreter. Gradle's `felidaeJar` task on the merged
        // root `compiler` module writes build/java/felidae.jar - there is no launcher script, so the jar is the
        // one search candidate and is run with `java -jar` (see FelidaeProcessRunner.shellCommand). A native
        // wrapper of this platform's executable name is still honored if hand-placed on PATH.
        List<Path> candidates = List.of(
                projectRoot.resolve(compilerJarRelativePath()));

        for (Path candidate : candidates) {
            Path normalized = candidate
                    .toAbsolutePath()
                    .normalize();

            if (Files.isRegularFile(normalized) && (SystemInfo.isWindows || Files.isExecutable(normalized) || normalized.toString().endsWith(".jar"))) {
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

    /**
     * Resolves the felidae compiler, or reports {@link #COMPILER_NOT_FOUND_MESSAGE} to {@code console}
     * (shown and cleared first, matching every call site this replaces) and returns {@code null}.
     */
    public static @Nullable Path resolveOrReport(
            @NotNull Project project,
            @NotNull FelidaeConsoleService console
    ) {
        Path resolved = resolveCompiler(project);
        if (resolved == null) {
            console.show();
            console.clear();
            console.printError(COMPILER_NOT_FOUND_MESSAGE);
        }
        return resolved;
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
