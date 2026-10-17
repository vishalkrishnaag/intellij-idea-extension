package local.felidae.intellij.actions;

import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import local.felidae.intellij.run.FelidaeConfigurationType;
import local.felidae.intellij.run.FelidaeRunConfiguration;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Debugs the current file: a Felidae run configuration for it (the one made earlier for the same file, else a new
 * temporary one) is started with the Debug executor, which compiles the program with {@code felidae --debug} and
 * attaches IntelliJ's JVM debugger (see {@link local.felidae.intellij.run.FelidaeDebugRunner}).
 */
public final class FelidaeDebugAction extends AnAction implements DumbAware {

    @Override
    public void update(@NotNull AnActionEvent event) {
        FelidaeActionSupport.updateAvailability(event);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = FelidaeActionSupport.getProject(event);
        VirtualFile file = FelidaeActionSupport.getSelectedFile(event);
        if (project == null || file == null || !FelidaeActionSupport.isFelidaeFile(file)) return;
        Path sourceFile = FelidaeActionSupport.prepareSourceFile(file);
        if (sourceFile == null) return;
        debug(project, sourceFile);
    }

    /** Also used by the gutter icon of {@code main()}. */
    public static void debug(@NotNull Project project, @NotNull Path sourceFile) {
        RunManager manager = RunManager.getInstance(project);
        RunnerAndConfigurationSettings settings = null;
        for (RunnerAndConfigurationSettings existing : manager.getAllSettings()) {
            if (existing.getConfiguration() instanceof FelidaeRunConfiguration felidae
                    && sourceFile.toString().equals(felidae.getSourceFile())) {
                settings = existing;
                break;
            }
        }
        if (settings == null) {
            String name = sourceFile.getFileName().toString().replaceFirst("\\.fx$", "");
            settings = manager.createConfiguration(name, FelidaeConfigurationType.getInstance().getConfigurationFactories()[0]);
            ((FelidaeRunConfiguration) settings.getConfiguration()).setSourceFile(sourceFile.toString());
            settings.setTemporary(true);
            manager.addConfiguration(settings);
        }
        manager.setSelectedConfiguration(settings);
        ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance());
    }
}
