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

    public static final String VISUALIZER_ENVIRONMENT_VARIABLE =
            "CELIDAE_PATH";

    public static final String INTERPRETER_ENVIRONMENT_VARIABLE =
            "FELIDAE_PATH";

    private static final String WINDOWS_VISUALIZER_EXECUTABLE =
            "celidae.exe";

    private static final String UNIX_VISUALIZER_EXECUTABLE =
            "celidae";

    private static final String WINDOWS_INTERPRETER_EXECUTABLE =
            "felidae.exe";

    private static final String UNIX_INTERPRETER_EXECUTABLE =
            "felidae";

    private FelidaeExecutableResolver() {
        throw new AssertionError(
                "FelidaeExecutableResolver cannot be instantiated."
        );
    }

    public static @Nullable Path resolveVisualizer(
            @NotNull Project project
    ) {
        Path configured = FelidaeSettingsState.getInstance(project).resolvedCelidaePath();
        if (configured != null) {
            return configured;
        }
        return resolve(
                project,
                VISUALIZER_ENVIRONMENT_VARIABLE,
                WINDOWS_VISUALIZER_EXECUTABLE,
                UNIX_VISUALIZER_EXECUTABLE
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
        String nativeStage = SystemInfo.isWindows ? "build/windows-x64/release/dist/bin" :
                SystemInfo.isMac ? "build/macos-" +
                        (System.getProperty("os.arch").equals("aarch64") ? "arm64" : "x86_64") +
                        "/release/dist/bin" : "build/release/dist/bin";
        List<Path> candidates = List.of(
                projectRoot.resolve("dist/bin").resolve(executable),
                projectRoot.resolve("release/bin").resolve(executable),
                projectRoot.resolve(nativeStage).resolve(executable),
                projectRoot.resolve("build/release/dist/bin").resolve(executable),
                projectRoot.resolve("build/release").resolve(executable),
                projectRoot.resolve("bin").resolve(executable),
                projectRoot.resolve(executable));

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
