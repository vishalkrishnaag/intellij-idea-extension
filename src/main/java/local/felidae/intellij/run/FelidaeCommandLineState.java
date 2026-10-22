package local.felidae.intellij.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.RemoteConnection;
import com.intellij.execution.configurations.RemoteConnectionCreator;
import java.io.IOException;
import java.net.ServerSocket;
import local.felidae.intellij.execution.FelidaeExecutableResolver;
import local.felidae.intellij.execution.FelidaeProcessRunner;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class FelidaeCommandLineState
        extends CommandLineState
        implements RemoteConnectionCreator {

    private final FelidaeRunConfiguration configuration;

    /** The JDWP port of a debug run; negative for a plain run. */
    private int debugPort = -1;

    public FelidaeCommandLineState(
            @NotNull ExecutionEnvironment environment,
            @NotNull FelidaeRunConfiguration configuration
    ) {
        super(environment);
        this.configuration = configuration;
    }

    @Override
    protected @NotNull ProcessHandler startProcess()
            throws ExecutionException {

        Path compilerPath = resolveCompiler();
        Path sourceFile = Path.of(
                configuration.getSourceFile()
        ).toAbsolutePath().normalize();

        java.util.ArrayList<String> parameters = new java.util.ArrayList<>();
        if (debugPort >= 0) {
            // felidae --debug --port N program.fx: compile, then start the program's JVM suspended on that port.
            parameters.add("--debug");
            parameters.add("--port");
            parameters.add(Integer.toString(debugPort));
        }
        parameters.add(sourceFile.toString());

        String arguments =
                configuration.getProgramArguments();

        if (!arguments.isBlank()) {
            parameters.addAll(
                    ParametersListUtil.parse(arguments)
            );
        }
        GeneralCommandLine commandLine = FelidaeProcessRunner.shellCommand(compilerPath, parameters);

        Path workingDirectory =
                resolveWorkingDirectory(sourceFile);

        commandLine.setWorkDirectory(
                workingDirectory.toFile()
        );

        OSProcessHandler processHandler =
                new OSProcessHandler(commandLine);

        ProcessTerminatedListener.attach(processHandler);

        return processHandler;
    }

    /** Debug runs: the debugger attaches to the JVM that {@code felidae --debug} starts, on a free local port. */
    @Override
    public RemoteConnection createRemoteConnection(@NotNull ExecutionEnvironment environment) {
        try (ServerSocket socket = new ServerSocket(0)) {
            debugPort = socket.getLocalPort();
        } catch (IOException exception) {
            return null;
        }
        return new RemoteConnection(true, "127.0.0.1", Integer.toString(debugPort), false);
    }

    /** The port opens only after the program is compiled, so the debugger keeps trying. */
    @Override
    public boolean isPollConnection() {
        return true;
    }

    private @NotNull Path resolveCompiler()
            throws ExecutionException {

        String configured =
                configuration.getCompilerPath();

        if (!configured.isBlank()) {
            return Path.of(configured)
                    .toAbsolutePath()
                    .normalize();
        }

        Path resolved =
                FelidaeExecutableResolver.resolveCompiler(
                        configuration.getProject()
                );

        if (resolved == null) {
            throw new ExecutionException(FelidaeExecutableResolver.COMPILER_NOT_FOUND_MESSAGE);
        }

        return resolved;
    }

    private @NotNull Path resolveWorkingDirectory(
            @NotNull Path sourceFile
    ) {
        String configured =
                configuration.getWorkingDirectory();

        if (!configured.isBlank()) {
            return Path.of(configured)
                    .toAbsolutePath()
                    .normalize();
        }

        Path parent = sourceFile.getParent();

        return parent != null
                ? parent
                : Path.of(".");
    }
}
