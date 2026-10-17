package local.felidae.intellij.debug;

import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.debugger.ui.breakpoints.LineBreakpoint;
import com.intellij.xdebugger.XSourcePosition;
import local.felidae.intellij.FelidaeFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Line breakpoints in {@code .fx} files. A Felidae program is compiled to JVM classes that carry the source file
 * and a line for every statement, so a Felidae breakpoint is an ordinary JVM line breakpoint: this type only makes
 * the editor accept it in a Felidae file, and {@link FelidaePositionManager} maps it to the class and line.
 *
 * <p>The Java type's extra features (lambda and conditional-return variants, enclosing method) read the Java PSI,
 * which a Felidae file does not have, so they are switched off.
 */
public final class FelidaeLineBreakpointType extends JavaLineBreakpointType implements DumbAware {

    public FelidaeLineBreakpointType() {
        super("felidae-line", "Felidae Line Breakpoints");
    }

    @Override
    public boolean canPutAt(@NotNull VirtualFile file, int line, @NotNull Project project) {
        return file.getFileType() == FelidaeFileType.INSTANCE;
    }

    @Override
    public @NotNull List<JavaBreakpointVariant> computeVariants(
            @NotNull Project project,
            @NotNull XSourcePosition position
    ) {
        return List.of();
    }

    @Override
    public @Nullable PsiElement getContainingMethod(@NotNull LineBreakpoint<?> breakpoint) {
        return null;
    }
}
