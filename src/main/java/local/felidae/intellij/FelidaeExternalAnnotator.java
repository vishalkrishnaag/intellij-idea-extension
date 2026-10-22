package local.felidae.intellij;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import local.felidae.intellij.execution.FelidaeExecutableResolver;
import local.felidae.intellij.execution.FelidaeProcessRunner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FelidaeExternalAnnotator extends ExternalAnnotator<
        FelidaeExternalAnnotator.CheckRequest,
        List<FelidaeExternalAnnotator.CheckDiagnostic>
        > {

    private static final String FILE_EXTENSION = "fx";

    private static final Duration CHECK_TIMEOUT =
            Duration.ofSeconds(15);

    private static final Duration OUTPUT_READ_TIMEOUT =
            Duration.ofSeconds(2);

    private static final Gson GSON = new Gson();

    /*
     * Fallback runtime-error location formats:
     *
     * error: Something failed at 4:12
     * Something failed at 4:12
     */
    private static final Pattern LOCATION = Pattern.compile(
            "\\bat\\s+(\\d+):(\\d+)\\b",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public @Nullable CheckRequest collectInformation(
            @NotNull PsiFile file
    ) {
        VirtualFile virtualFile = file.getVirtualFile();

        if (!isSupportedFile(virtualFile)) {
            return null;
        }

        Path sourceFile;

        try {
            sourceFile = Path.of(virtualFile.getPath())
                    .toAbsolutePath()
                    .normalize();
        } catch (InvalidPathException exception) {
            return null;
        }

        Path debugHost = FelidaeExecutableResolver.resolveCompiler(file.getProject());

        return new CheckRequest(sourceFile, debugHost);
    }

    @Override
    public @NotNull List<CheckDiagnostic> doAnnotate(
            CheckRequest request
    ) {
        List<CheckDiagnostic> diagnostics = new ArrayList<>();

        Path debugHost = request.debugHost();

        if (debugHost == null || !Files.isRegularFile(debugHost)) {
            diagnostics.add(new CheckDiagnostic(
                    "warning",
                    "Felidae compiler executable was not found. " +
                            "Set " + FelidaeExecutableResolver.COMPILER_ENVIRONMENT_VARIABLE +
                            " or configure the Felidae compiler path.",
                    1,
                    1
            ));

            return diagnostics;
        }

        if (!Files.isRegularFile(request.sourceFile())) {
            diagnostics.add(new CheckDiagnostic(
                    "warning",
                    "Felidae source file was not found: " +
                            request.sourceFile(),
                    1,
                    1
            ));

            return diagnostics;
        }

        // felidae [options] program.fx: options after the program would be the program's own arguments.
        // launchCommand prepends `java -jar` when debugHost is a jar (felidae.jar has no launcher script).
        ProcessBuilder builder = new ProcessBuilder(
                FelidaeProcessRunner.launchCommand(
                        debugHost,
                        List.of("--check-json", request.sourceFile().toString())
                )
        );

        /*
         * Merge stderr into stdout so both normal diagnostics and runtime
         * errors can be read from a single stream.
         */
        builder.redirectErrorStream(true);

        Path workingDirectory = request.sourceFile().getParent();

        if (workingDirectory != null && Files.isDirectory(workingDirectory)) {
            builder.directory(workingDirectory.toFile());
        }

        Process process = null;
        CompletableFuture<String> outputFuture = null;

        try {
            process = builder.start();

            Process runningProcess = process;

            outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return readAll(runningProcess.getInputStream());
                } catch (IOException exception) {
                    throw new OutputReadException(exception);
                }
            });

            boolean finished = process.waitFor(
                    CHECK_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS
            );

            if (!finished) {
                terminateProcess(process);

                diagnostics.add(new CheckDiagnostic(
                        "warning",
                        "Felidae --check-json timed out after " +
                                CHECK_TIMEOUT.toSeconds() + " seconds.",
                        1,
                        1
                ));

                return diagnostics;
            }

            String output = readProcessOutput(outputFuture);

            parseJsonDiagnostics(output, diagnostics);

            /*
             * Avoid adding one large duplicate runtime error when the
             * executable already emitted structured diagnostics.
             */
            if (process.exitValue() != 0 && diagnostics.isEmpty()) {
                diagnostics.add(
                        parseRuntimeError(
                                output.isBlank()
                                        ? "Felidae check failed with exit code " +
                                        process.exitValue() + "."
                                        : output
                        )
                );
            }
        } catch (IOException exception) {
            diagnostics.add(new CheckDiagnostic(
                    "warning",
                    "Cannot run Felidae --check-json: " +
                            safeMessage(exception),
                    1,
                    1
            ));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            if (process != null) {
                terminateProcess(process);
            }

            diagnostics.add(new CheckDiagnostic(
                    "warning",
                    "Felidae --check-json was interrupted.",
                    1,
                    1
            ));
        } catch (ExecutionException exception) {
            diagnostics.add(new CheckDiagnostic(
                    "warning",
                    "Cannot collect Felidae AST diagnostics: " +
                            rootCauseMessage(exception),
                    1,
                    1
            ));
        } catch (TimeoutException exception) {
            diagnostics.add(new CheckDiagnostic(
                    "warning",
                    "felidae finished, but its output stream did not " +
                            "close within " + OUTPUT_READ_TIMEOUT.toSeconds() +
                            " seconds.",
                    1,
                    1
            ));
        } catch (RuntimeException exception) {
            diagnostics.add(new CheckDiagnostic(
                    "warning",
                    "Unexpected felidae checker failure: " +
                            safeMessage(exception),
                    1,
                    1
            ));
        } finally {
            if (outputFuture != null && !outputFuture.isDone()) {
                outputFuture.cancel(true);
            }

            if (process != null && process.isAlive()) {
                terminateProcess(process);
            }
        }

        return diagnostics;
    }

    @Override
    public void apply(
            @NotNull PsiFile file,
            @Nullable List<CheckDiagnostic> diagnostics,
            @NotNull AnnotationHolder holder
    ) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return;
        }

        String sourceText = file.getText();

        for (CheckDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null) {
                continue;
            }

            String message = diagnostic.message();

            if (message == null || message.isBlank()) {
                message = "Felidae diagnostic";
            }

            TextRange range = rangeFor(
                    sourceText,
                    diagnostic.line(),
                    diagnostic.column(),
                    diagnostic.endLine(),
                    diagnostic.endColumn()
            );

            HighlightSeverity severity = severityFor(
                    diagnostic.severity()
            );

            holder.newAnnotation(severity, message)
                    .range(range)
                    .create();
        }
    }

    private static boolean isSupportedFile(
            @Nullable VirtualFile virtualFile
    ) {
        if (virtualFile == null || virtualFile.isDirectory()) {
            return false;
        }

        String extension = virtualFile.getExtension();

        return extension != null &&
                FILE_EXTENSION.equalsIgnoreCase(extension);
    }


    private static String readProcessOutput(
            @NotNull CompletableFuture<String> outputFuture
    ) throws InterruptedException, ExecutionException, TimeoutException {
        return outputFuture.get(
                OUTPUT_READ_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private static String readAll(
            @NotNull InputStream stream
    ) throws IOException {
        StringBuilder output = new StringBuilder();

        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                stream,
                                StandardCharsets.UTF_8
                        )
                )
        ) {
            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        return output.toString();
    }

    /** The {@code --check-json} schema (session.CheckReport.java): {version, path, diagnostics[], symbols[]}. */
    private record CheckJson(List<JsonDiagnostic> diagnostics) {
    }

    private record JsonDiagnostic(String code, String severity, String message, Position start, Position end) {
    }

    private record Position(int line, int column) {
    }

    // felidae --check-json prints exactly one line of JSON; a real parser (Gson, already a dependency for the
    // same reason - see build.gradle.kts) reads it, rather than a regex that cannot see past the nested
    // start/end objects every diagnostic now has.
    private static void parseJsonDiagnostics(
            @NotNull String output,
            @NotNull List<CheckDiagnostic> diagnostics
    ) {
        String candidate = output.strip();
        if (!candidate.startsWith("{")) {
            candidate = output.lines().map(String::strip).filter(line -> line.startsWith("{")).findFirst().orElse(null);
        }
        if (candidate == null) {
            return;
        }
        final CheckJson parsed;
        try {
            parsed = GSON.fromJson(candidate, CheckJson.class);
        } catch (JsonSyntaxException exception) {
            return;
        }
        if (parsed == null || parsed.diagnostics() == null) {
            return;
        }
        for (JsonDiagnostic diagnostic : parsed.diagnostics()) {
            if (diagnostic == null || diagnostic.start() == null) {
                continue;
            }
            final Position end = diagnostic.end() != null ? diagnostic.end() : diagnostic.start();
            final String message = diagnostic.message() == null || diagnostic.message().isBlank()
                    ? "Felidae AST diagnostic" : diagnostic.message();
            diagnostics.add(new CheckDiagnostic(
                    diagnostic.severity(),
                    message,
                    diagnostic.start().line(),
                    diagnostic.start().column(),
                    end.line(),
                    end.column()
            ));
        }
    }

    private static CheckDiagnostic parseRuntimeError(
            @NotNull String output
    ) {
        String message = output.strip();

        if (message.isBlank()) {
            message = "felidae check failed.";
        }

        if (message.regionMatches(
                true,
                0,
                "error:",
                0,
                "error:".length()
        )) {
            message = message
                    .substring("error:".length())
                    .strip();
        }

        Matcher locationMatcher = LOCATION.matcher(message);

        int line = 1;
        int column = 1;

        if (locationMatcher.find()) {
            line = parsePositiveInt(
                    locationMatcher.group(1),
                    1
            );

            column = parsePositiveInt(
                    locationMatcher.group(2),
                    1
            );
        }

        return new CheckDiagnostic(
                "error",
                message,
                line,
                column
        );
    }

    private static HighlightSeverity severityFor(
            @Nullable String severity
    ) {
        if (severity == null) {
            return HighlightSeverity.WARNING;
        }

        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "error", "fatal" ->
                    HighlightSeverity.ERROR;

            case "info", "information" ->
                    HighlightSeverity.INFORMATION;

            case "hint" ->
                    HighlightSeverity.WEAK_WARNING;

            case "warning", "warn" ->
                    HighlightSeverity.WARNING;

            default ->
                    HighlightSeverity.WARNING;
        };
    }

    private static int parsePositiveInt(
            @Nullable String value,
            int fallback
    ) {
        if (value == null) {
            return fallback;
        }

        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /**
     * The range {@code --check-json}'s {@code start}/{@code end} describe, when they differ (most of the warnings
     * - an unused binding, a boxed number that could be primitive - carry a real span over the name); a single
     * character, or the closest preceding one at the end of a line, when they are the same point (most syntax and
     * analysis errors, which name one position, not a range).
     */
    private static TextRange rangeFor(
            @NotNull String text,
            int startLine,
            int startColumn,
            int endLine,
            int endColumn
    ) {
        if (text.isEmpty()) {
            return TextRange.EMPTY_RANGE;
        }
        int start = offsetFor(text, startLine, startColumn);
        int end = offsetFor(text, endLine, endColumn);
        if (end > start) {
            return TextRange.create(start, end);
        }
        int rowStart = lineStart(text, startLine);
        int rowEnd = findLineEnd(text, rowStart);
        if (start < rowEnd) {
            return TextRange.create(start, start + 1);
        }
        if (rowEnd > rowStart) {
            return TextRange.create(rowEnd - 1, rowEnd);
        }
        return TextRange.create(rowStart, rowStart);
    }

    // The offset of the start of a one-based line, found by counting line breaks; the length of the document
    // when the requested line is past its end.
    private static int lineStart(@NotNull String text, int oneBasedLine) {
        int targetLine = Math.max(1, oneBasedLine);
        int currentLine = 1;
        int lineStart = 0;
        for (int index = 0; index < text.length() && currentLine < targetLine; index++) {
            if (text.charAt(index) == '\n') {
                currentLine++;
                lineStart = index + 1;
            }
        }
        return lineStart;
    }

    // A one-based (line, column) clamped to the actual line, so a bad diagnostic cannot create an invalid
    // TextRange.
    private static int offsetFor(@NotNull String text, int oneBasedLine, int oneBasedColumn) {
        int start = lineStart(text, oneBasedLine);
        int end = findLineEnd(text, start);
        int requested = start + Math.max(1, oneBasedColumn) - 1;
        return Math.max(start, Math.min(requested, end));
    }

    private static int findLineEnd(
            @NotNull String text,
            int lineStart
    ) {
        int index = Math.max(0, Math.min(lineStart, text.length()));

        while (index < text.length()) {
            char current = text.charAt(index);

            if (current == '\n' || current == '\r') {
                break;
            }

            index++;
        }

        return index;
    }

    private static void terminateProcess(
            @NotNull Process process
    ) {
        process.destroy();

        try {
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(250, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static String rootCauseMessage(
            @NotNull Throwable throwable
    ) {
        Throwable current = throwable;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        return safeMessage(current);
    }

    private static String safeMessage(
            @NotNull Throwable throwable
    ) {
        String message = throwable.getMessage();

        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }

        return message;
    }

    public record CheckRequest(
            @NotNull Path sourceFile,
            @Nullable Path debugHost
    ) {
    }

    public record CheckDiagnostic(
            @NotNull String severity,
            @NotNull String message,
            int line,
            int column,
            int endLine,
            int endColumn
    ) {
        public CheckDiagnostic {
            severity = severity.isBlank()
                    ? "warning"
                    : severity;

            message = message.isBlank()
                    ? "Felidae diagnostic"
                    : message;

            line = Math.max(1, line);
            column = Math.max(1, column);
            endLine = Math.max(line, endLine);
            endColumn = endLine == line ? Math.max(column, endColumn) : Math.max(1, endColumn);
        }

        /** A point diagnostic (a runtime error, a plugin-side warning): its end is the same position as its start. */
        public CheckDiagnostic(@NotNull String severity, @NotNull String message, int line, int column) {
            this(severity, message, line, column, line, column);
        }
    }

    private static final class OutputReadException
            extends RuntimeException {

        private OutputReadException(
                @NotNull IOException cause
        ) {
            super(cause);
        }
    }
}
