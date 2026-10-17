package local.felidae.intellij.debug;

import com.intellij.debugger.PositionManager;
import com.intellij.debugger.PositionManagerFactory;
import com.intellij.debugger.engine.DebugProcess;
import org.jetbrains.annotations.NotNull;

/** Registers {@link FelidaePositionManager} with every JVM debug session. */
public final class FelidaePositionManagerFactory extends PositionManagerFactory {

    @Override
    public @NotNull PositionManager createPositionManager(@NotNull DebugProcess process) {
        return new FelidaePositionManager(process);
    }
}
