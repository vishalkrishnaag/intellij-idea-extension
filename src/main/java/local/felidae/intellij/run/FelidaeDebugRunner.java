package local.felidae.intellij.run;

import com.intellij.debugger.impl.GenericDebuggerRunner;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.executors.DefaultDebugExecutor;
import org.jetbrains.annotations.NotNull;

/**
 * Debugs a Felidae run configuration with IntelliJ's own JVM debugger. {@code felidae --debug} starts the compiled
 * program's JVM suspended with a JDWP agent; {@link FelidaeCommandLineState} gives the debugger that connection
 * ({@link com.intellij.execution.configurations.RemoteConnectionCreator}), which polls until the port is open.
 */
public final class FelidaeDebugRunner extends GenericDebuggerRunner {

    public static final String ID = "FelidaeDebugRunner";

    @Override
    public @NotNull String getRunnerId() {
        return ID;
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof FelidaeRunConfiguration;
    }
}
