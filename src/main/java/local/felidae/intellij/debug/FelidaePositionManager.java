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
 * finds its classes by their {@code sourceName}; the scan skips the JDK and library packages.
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
        PsiFile file = findFile(process.getProject(), sourceName, packageOf(location));
        if (file == null) throw NoDataException.INSTANCE;
        return SourcePosition.createFromLine(file, line);
    }

    @Override
    public @NotNull List<ReferenceType> getAllClasses(@NotNull SourcePosition position) throws NoDataException {
        String sourceName = felidaeSourceName(position);
        List<ReferenceType> classes = new ArrayList<>();
        for (ReferenceType type : process.getVirtualMachineProxy().allClasses()) {
            if (isLibraryClass(type.name())) continue; // asking a JDK or driver class for its source name is wasted work
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

    /**
     * The JDK and the libraries a program uses (the TidesDB driver, Jackson). A Felidae class is in the default package
     * or in the package its file declares ({@code com.acme.app$Box}), so a dot alone does not tell the two apart.
     */
    private static boolean isLibraryClass(@NotNull String className) {
        for (String prefix : LIBRARY_PACKAGES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    private static final String[] LIBRARY_PACKAGES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "com.tidesdb.", "com.fasterxml.", "org.xml.", "org.w3c."
    };

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

    /**
     * The declaring class's package (as used for package/directory matching below, see AGENTS.md's output
     * model: {@code package com.acme} puts a file's classes under {@code com/acme}), or {@code null} for a
     * nested class's outer boundary not being present or a default-package class - either way, {@link #findFile}
     * then falls back to its pre-existing first-match behavior.
     */
    private static @Nullable String packageOf(@NotNull Location location) {
        ReferenceType declaringType;
        try {
            declaringType = location.declaringType();
        } catch (RuntimeException exception) {
            // the VM may already be gone by the time a stale location is resolved
            return null;
        }
        if (declaringType == null) return null;
        String name = declaringType.name();
        // JDI names a nested class with '$' (Outer$Inner); only the part before it carries the package.
        int dollar = name.indexOf('$');
        String outer = dollar >= 0 ? name.substring(0, dollar) : name;
        int lastDot = outer.lastIndexOf('.');
        return lastDot >= 0 ? outer.substring(0, lastDot) : null;
    }

    /** Whether {@code file}'s parent directory chain ends with {@code expectedPackage}'s dot-separated segments. */
    private static boolean matchesPackage(@NotNull VirtualFile file, @NotNull String expectedPackage) {
        VirtualFile parent = file.getParent();
        if (parent == null) return false;
        String expectedRelativeDir = expectedPackage.replace('.', '/');
        String parentPath = parent.getPath();
        return parentPath.equals(expectedRelativeDir) || parentPath.endsWith("/" + expectedRelativeDir);
    }

    /**
     * Several {@code .fx} files with the same name in different packages is ambiguous from the bare file name a
     * compiled class's {@code SourceFile} attribute carries (it has no path) - {@code expectedPackage}, when
     * known, disambiguates by preferring the candidate whose directory matches the stack frame's declaring
     * class's package; any other case (no package known, or none of the candidates match) falls back to the
     * first candidate the index returns, exactly as before this method took a package hint.
     */
    private static @Nullable PsiFile findFile(@NotNull Project project, @NotNull String sourceName, @Nullable String expectedPackage) {
        return ReadAction.compute(() -> {
            Collection<VirtualFile> files = FilenameIndex.getVirtualFilesByName(sourceName, GlobalSearchScope.projectScope(project));
            VirtualFile fallback = null;
            for (VirtualFile file : files) {
                if (fallback == null) fallback = file;
                if (expectedPackage != null && matchesPackage(file, expectedPackage)) {
                    PsiFile psi = PsiManager.getInstance(project).findFile(file);
                    if (psi != null) return psi;
                }
            }
            return fallback == null ? null : PsiManager.getInstance(project).findFile(fallback);
        });
    }
}
