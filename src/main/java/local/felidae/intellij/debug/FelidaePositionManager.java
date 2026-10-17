package local.felidae.intellij.debug;

import com.intellij.debugger.NoDataException;
import com.intellij.debugger.PositionManager;
import com.intellij.debugger.SourcePosition;
import com.intellij.debugger.engine.DebugProcess;
import com.intellij.debugger.requests.ClassPrepareRequestor;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.request.ClassPrepareRequest;
import local.felidae.intellij.FelidaeFileType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Maps between the JVM and {@code .fx} sources for the debugger. The compiler writes the source file name and a line
 * number for every statement into the classes it generates ({@code SourceFile} and {@code LineNumberTable}), so the
 * mapping is: a location's {@code sourceName} names the file, its line number is the line; and a source position
 * finds its classes by their {@code sourceName}. Felidae classes are in the default package, which keeps the scan
 * over the loaded classes short.
 */
final class FelidaePositionManager implements PositionManager {

    private final DebugProcess process;

    FelidaePositionManager(@NotNull DebugProcess process) {
        this.process = process;
    }

    @Override
    public @NotNull Set<? extends FileType> getAcceptedFileTypes() {
        return Set.of(FelidaeFileType.INSTANCE);
    }

    @Override
    public @NotNull SourcePosition getSourcePosition(@Nullable Location location) throws NoDataException {
        if (location == null) throw NoDataException.INSTANCE;
        final String sourceName;
        try {
            sourceName = location.sourceName();
        } catch (AbsentInformationException exception) {
            throw NoDataException.INSTANCE;
        }
        int line = location.lineNumber() - 1;
        if (line < 0 || !isFelidaeSourceName(sourceName)) throw NoDataException.INSTANCE;
        PsiFile file = findFile(process.getProject(), sourceName);
        if (file == null) throw NoDataException.INSTANCE;
        return SourcePosition.createFromLine(file, line);
    }

    @Override
    public @NotNull List<ReferenceType> getAllClasses(@NotNull SourcePosition position) throws NoDataException {
        String sourceName = felidaeSourceName(position);
        List<ReferenceType> classes = new ArrayList<>();
        for (ReferenceType type : process.getVirtualMachineProxy().allClasses()) {
            if (inPackage(type.name())) continue; // a Felidae class is in the default package
            if (sourceName.equals(sourceNameOf(type))) classes.add(type);
        }
        return classes;
    }

    @Override
    public @NotNull List<Location> locationsOfLine(
            @NotNull ReferenceType type,
            @NotNull SourcePosition position
    ) throws NoDataException {
        String sourceName = felidaeSourceName(position);
        if (!sourceName.equals(sourceNameOf(type))) throw NoDataException.INSTANCE;
        int line = position.getLine() + 1;
        try {
            List<Location> exact = type.locationsOfLine(line);
            if (!exact.isEmpty()) return exact;
            return nextLineInMethod(type, line);
        } catch (AbsentInformationException exception) {
            throw NoDataException.INSTANCE;
        }
    }

    /**
     * A line with no code of its own (a blank line, {@code end}) that lies inside a method stops at the next line of
     * that method that has code; a line outside every method stays unresolved.
     */
    private static @NotNull List<Location> nextLineInMethod(
            @NotNull ReferenceType type,
            int line
    ) throws AbsentInformationException {
        List<Location> best = List.of();
        int bestLine = Integer.MAX_VALUE;
        for (Method method : type.methods()) {
            List<Location> locations = method.allLineLocations();
            if (locations.isEmpty()) continue;
            int first = Integer.MAX_VALUE;
            int last = 0;
            for (Location location : locations) {
                first = Math.min(first, location.lineNumber());
                last = Math.max(last, location.lineNumber());
            }
            if (line <= first || line >= last) continue;
            int next = Integer.MAX_VALUE;
            for (Location location : locations) {
                if (location.lineNumber() > line) next = Math.min(next, location.lineNumber());
            }
            if (next < bestLine) {
                bestLine = next;
                best = method.locationsOfLine(next);
            }
        }
        return best;
    }

    @Override
    public @NotNull ClassPrepareRequest createPrepareRequest(
            @NotNull ClassPrepareRequestor requestor,
            @NotNull SourcePosition position
    ) throws NoDataException {
        String sourceName = felidaeSourceName(position);
        ClassPrepareRequest request = process.getRequestsManager().createClassPrepareRequest(requestor, "*");
        if (request == null) throw NoDataException.INSTANCE;
        try {
            // Only classes compiled from this file; without it every class the JVM loads is reported.
            request.addSourceNameFilter(sourceName);
        } catch (RuntimeException unsupported) {
            // the target VM cannot filter by source name: the requestor still checks each class
        }
        return request;
    }

    /** {@code java.lang.String} is in a package; {@code main$School.Student} (a dotted Felidae class name) is not. */
    private static boolean inPackage(@NotNull String className) {
        int dot = className.indexOf('.');
        int dollar = className.indexOf('$');
        return dot >= 0 && (dollar < 0 || dot < dollar);
    }

    private static boolean isFelidaeSourceName(@Nullable String sourceName) {
        return sourceName != null && sourceName.endsWith("." + FelidaeFileType.DEFAULT_EXTENSION);
    }

    private static @NotNull String felidaeSourceName(@NotNull SourcePosition position) throws NoDataException {
        PsiFile file = position.getFile();
        if (file.getFileType() != FelidaeFileType.INSTANCE) throw NoDataException.INSTANCE;
        return file.getName();
    }

    private static @Nullable String sourceNameOf(@NotNull ReferenceType type) {
        try {
            return type.sourceName();
        } catch (AbsentInformationException exception) {
            return null;
        }
    }

    private static @Nullable PsiFile findFile(@NotNull Project project, @NotNull String sourceName) {
        return ReadAction.compute(() -> {
            Collection<VirtualFile> files = FilenameIndex.getVirtualFilesByName(sourceName, GlobalSearchScope.projectScope(project));
            for (VirtualFile file : files) {
                PsiFile psi = PsiManager.getInstance(project).findFile(file);
                if (psi != null) return psi;
            }
            return null;
        });
    }
}
