package local.felidae.intellij.execution;

import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * The names written before a {@code .} or {@code :} that are libraries rather than values: {@code db} in
 * {@code db.location("...")} of init.fx, and the {@code console} and {@code system} builtins. Java classes
 * imported with {@code import "java:..."} are ordinary class names and need no entry.
 *
 * The syntax highlighter needs this synchronously on every keystroke, so it is a constant.
 */
public final class FelidaeStdlibIndex {

    private static final Set<String> LIBRARIES = Set.of("db", "console", "system");

    private FelidaeStdlibIndex() {
        throw new AssertionError("FelidaeStdlibIndex cannot be instantiated.");
    }

    public static @NotNull Set<String> getLibraries() {
        return LIBRARIES;
    }
}
