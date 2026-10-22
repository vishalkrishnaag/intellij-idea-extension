package local.felidae.intellij.execution;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessHandlerFactory;
// ProcessAdapter is deprecated: ProcessListener has default methods now.
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import local.felidae.intellij.ui.FelidaeConsoleService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class FelidaeProcessRunner {

    /**
     * The full command line to run {@code executable}: felidae.jar has no launcher script, so when
     * {@code executable} ends in {@code .jar} the actual program is {@code java -jar <executable>
     * <arguments...>}; a native wrapper (hand-placed on PATH, or a configured override) is run directly.
     * The first element is the program, the rest its arguments - every felidae launch (shellCommand's
     * GeneralCommandLine, and FelidaeExternalAnnotator's own ProcessBuilder for live checking) goes
     * through this one place so the jar-vs-wrapper distinction cannot drift between them.
     */
    public static @NotNull List<String> launchCommand(@NotNull Path executable, @NotNull List<String> arguments) {
        boolean isJar = executable.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
        java.util.List<String> command = new java.util.ArrayList<>();
        if (isJar) {
            command.add("java");
            command.add("-jar");
        }
        command.add(executable.toString());
        command.addAll(arguments);
        return command;
    }

    /** Shell launch for user programs; tooling keeps its direct stdio protocol. See {@link #launchCommand}. */
    public static GeneralCommandLine shellCommand(Path executable, List<String> arguments) {
        GeneralCommandLine command = new GeneralCommandLine();
        command.setCharset(StandardCharsets.UTF_8);
        List<String> full = launchCommand(executable, arguments);
        String program = full.get(0);
        List<String> allArguments = full.subList(1, full.size());
        if (com.intellij.openapi.util.SystemInfo.isWindows) {
            command.setExePath(System.getenv().getOrDefault("ComSpec", "cmd.exe"));
            command.addParameters("/d", "/v:on", "/s", "/c");
            command.withEnvironment("FELIDAE_RUN_EXE", program);
            StringBuilder invocation = new StringBuilder("\"!FELIDAE_RUN_EXE!\"");
            for (int index = 0; index < allArguments.size(); ++index) {
                String key = "FELIDAE_RUN_ARG_" + index;
                String value = allArguments.get(index).replaceAll("(\\\\*)\"", "$1$1\\\\\"")
                        .replaceAll("(\\\\+)$", "$1$1");
                command.withEnvironment(key, value);
                invocation.append(" \"!").append(key).append("!\"");
            }
            command.addParameter("\"" + invocation + "\"");
        } else {
            command.setExePath("/bin/sh");
            command.addParameters("-c", "exec \"$@\"", "felidae-run", program);
            command.addParameters(allArguments);
        }
        return command;
    }

    private FelidaeProcessRunner() {
        throw new AssertionError(
                "FelidaeProcessRunner cannot be instantiated."
        );
    }

    public static void runCompiler(
            @NotNull Project project,
            @NotNull Path executable,
            @NotNull Path sourceFile
    ) {
        run(
                project,
                "Run Felidae",
                executable,
                sourceFile,
                List.of(),
                false,
                null
        );
    }

    /** The name of the generated program a query runs from, beside the file it queries. */
    public static final String QUERY_FILE = "cell_run.fx";

    /**
     * Runs {@code query} (an expression, ended with a period) against a file: a generated program beside it
     * imports the file and its {@code main()} evaluates the query, so the query sees the file's functions,
     * bindings and classes. The generated files are removed when the run ends.
     */
    public static void runQuery(
            @NotNull Project project,
            @NotNull Path executable,
            @NotNull Path sourceFile,
            @NotNull String query
    ) {
        Path directory = sourceFile.getParent();
        String expression = query.endsWith(".") ? query : query + ".";
        if (directory == null) {
            FelidaeConsoleService.getInstance(project).printError("Cannot resolve the folder of " + sourceFile + "\n");
            return;
        }
        Path queryFile = directory.resolve(QUERY_FILE);
        try {
            Files.writeString(queryFile, queryProgram(sourceFile.getFileName().toString(), expression), StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            FelidaeConsoleService.getInstance(project).printError("Cannot write " + queryFile + ": " + safeMessage(exception) + "\n");
            return;
        }
        run(
                project,
                "Run Felidae Query",
                executable,
                queryFile,
                List.of(),
                false,
                () -> removeQueryFiles(queryFile)
        );
    }

    /** The generated program: import the file, run the expression in {@code main()}. */
    public static @NotNull String queryProgram(@NotNull String fileName, @NotNull String expression) {
        return "import \"" + fileName.replace("\\", "/").replace("\"", "\\\"") + "\"\n\ndef main() =>\n    "
                + expression + "\nend\n";
    }

    private static void removeQueryFiles(@NotNull Path queryFile) {
        try {
            Files.deleteIfExists(queryFile);
            Files.deleteIfExists(queryFile.resolveSibling(QUERY_FILE.replaceAll("\\.fx$", ".class")));
        } catch (java.io.IOException ignored) {
            // a leftover generated file is harmless: the next query overwrites it
        }
    }

    public static void runChecker(
            @NotNull Project project,
            @NotNull Path debugger,
            @NotNull Path sourceFile
    ) {
        run(
                project,
                "Check Felidae",
                debugger,
                sourceFile,
                List.of("--check-json"),
                true,
                null
        );
    }

    private static void run(
            @NotNull Project project,
            @NotNull String taskTitle,
            @NotNull Path executable,
            @NotNull Path sourceFile,
            @NotNull List<String> options,
            boolean optionsFirst,
            @Nullable Runnable afterwards
    ) {
        FelidaeConsoleService consoleService =
                FelidaeConsoleService.getInstance(project);

        consoleService.show();
        consoleService.clear();

        if (!Files.isRegularFile(executable)) {
            consoleService.printError(
                    "Executable not found: " + executable + "\n"
            );
            return;
        }

        if (!Files.isRegularFile(sourceFile)) {
            consoleService.printError(
                    "Source file not found: " + sourceFile + "\n"
            );
            return;
        }

        new Task.Backgroundable(
                project,
                taskTitle,
                true
        ) {
            @Override
            public void run(
                    @NotNull ProgressIndicator indicator
            ) {
                indicator.setIndeterminate(true);
                indicator.setText(taskTitle);

                try {
                    execute(
                            project,
                            executable,
                            sourceFile,
                            options,
                            optionsFirst,
                            indicator,
                            consoleService
                    );
                } finally {
                    if (afterwards != null) afterwards.run();
                }
            }
        }.queue();
    }

    private static void execute(
            @NotNull Project project,
            @NotNull Path executable,
            @NotNull Path sourceFile,
            @NotNull List<String> options,
            boolean optionsFirst,
            @NotNull ProgressIndicator indicator,
            @NotNull FelidaeConsoleService consoleService
    ) {
        // felidae [options] program.fx [arguments]: everything after the program belongs to the program, so the
        // felidae options (--check-json) must come first.
        java.util.ArrayList<String> arguments = new java.util.ArrayList<>();
        if (optionsFirst) arguments.addAll(options);
        arguments.add(sourceFile.toString());
        if (!optionsFirst) arguments.addAll(options);
        GeneralCommandLine commandLine = shellCommand(executable, arguments);

        Path workingDirectory = sourceFile.getParent();

        if (workingDirectory != null) {
            commandLine.setWorkDirectory(
                    workingDirectory.toFile()
            );
        }

        /*
         * Run:
         *   felidae file.fx
         *
         * Tooling mode:
         *   felidae --check-json file.fx
         */

        ApplicationManager.getApplication().invokeLater(() -> {
            consoleService.printSystem(
                    "$ " + commandLine.getCommandLineString() + "\n\n"
            );
        });

        try {
            // ProcessHandlerFactory rather than `new OSProcessHandler(...)`:
            // that constructor is deprecated, and the factory additionally
            // gives the console ANSI colour handling for free.
            ProcessHandler processHandler =
                    ProcessHandlerFactory.getInstance()
                            .createColoredProcessHandler(commandLine);

            ConsoleView consoleView =
                    consoleService.getOrCreateConsole();

            ApplicationManager.getApplication().invokeLater(() -> {
                consoleView.attachToProcess(processHandler);
            });

            registerProcessListener(
                    processHandler,
                    consoleService
            );

            processHandler.startNotify();

            while (!processHandler.isProcessTerminated()) {
                if (indicator.isCanceled()) {
                    processHandler.destroyProcess();

                    ApplicationManager
                            .getApplication()
                            .invokeLater(() ->
                                    consoleService.printSystem(
                                            "\nFelidae process cancelled.\n"
                                    )
                            );

                    return;
                }

                try {
                    Thread.sleep(100);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    processHandler.destroyProcess();
                    return;
                }
            }
        } catch (Exception exception) {
            ApplicationManager.getApplication().invokeLater(() ->
                    consoleService.printError(
                            "Cannot start Felidae process: " +
                                    safeMessage(exception) +
                                    "\n"
                    )
            );
        }
    }

    private static void registerProcessListener(
            @NotNull ProcessHandler processHandler,
            @NotNull FelidaeConsoleService consoleService
    ) {
        processHandler.addProcessListener(
                new ProcessListener() {
                    @Override
                    public void processTerminated(
                            @NotNull ProcessEvent event
                    ) {
                        int exitCode = event.getExitCode();

                        ApplicationManager
                                .getApplication()
                                .invokeLater(() -> {
                                    if (exitCode == 0) {
                                        consoleService.printSystem(
                                                "\nProcess finished successfully.\n"
                                        );
                                    } else {
                                        consoleService.printError(
                                                "\nProcess finished with exit code " +
                                                        exitCode +
                                                        ".\n"
                                        );
                                    }
                                });
                    }

                    @Override
                    public void onTextAvailable(
                            @NotNull ProcessEvent event,
                            @NotNull Key outputType
                    ) {
                        /*
                         * ConsoleView.attachToProcess() already receives and
                         * displays output. Do not print event.getText() here,
                         * otherwise every output line appears twice.
                         */
                    }
                }
        );
    }

    private static @NotNull String safeMessage(
            @NotNull Throwable throwable
    ) {
        String message = throwable.getMessage();

        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }
}
