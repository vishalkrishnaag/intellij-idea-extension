package local.felidae.intellij.execution;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Caches the set of importable core library names that {@code felidae}
 * reports through {@code felidae --list-libraries}.
 *
 * The syntax highlighter needs this list synchronously on every keystroke, so
 * it never shells out directly. Instead, {@link #refreshFrom(Path)} is called
 * once per resolved executable (from {@link local.felidae.intellij.FelidaeExternalAnnotator},
 * which already runs on a background thread) and the result is cached here.
 * Until a refresh succeeds, {@link #getLibraries()} returns a small built-in
 * default so highlighting still works in a fresh project that has not run
 * felidae yet.
 */
public final class FelidaeStdlibIndex {

    /**
     * Built-in fallback, used only until {@code felidae --list-libraries}
     * has answered at least once. Kept intentionally small; the authoritative
     * list always comes from Felidae.
     */
    private static final Set<String> DEFAULT_LIBRARIES = Set.of(
            "array", "comparison", "console", "csv", "db", "exception", "fact",
            "fact_analysis", "file", "flibrary", "group", "gtk", "http", "json",
            "list", "logic", "math", "ml", "package", "plot", "prelude",
            "process", "qt", "set", "smoke", "str", "system",
            "thread", "wordnet"
    );

    private static volatile Set<String> libraries = DEFAULT_LIBRARIES;

    private static final AtomicBoolean REFRESH_IN_FLIGHT = new AtomicBoolean(false);
    private static volatile Path lastRefreshedExecutable;

    private FelidaeStdlibIndex() {
        throw new AssertionError("FelidaeStdlibIndex cannot be instantiated.");
    }

    public static @NotNull Set<String> getLibraries() {
        return libraries;
    }

    /**
     * Refreshes the cached library set from {@code felidaeExecutable
     * --list-libraries}, synchronously, on the calling thread. Callers must
     * invoke this from a background thread (e.g. an ExternalAnnotator's
     * doAnnotate). A no-op once a given executable has already been queried
     * successfully.
     */
    public static void refreshFrom(@NotNull Path felidaeExecutable) {
        if (felidaeExecutable.equals(lastRefreshedExecutable)) {
            return;
        }
        if (!REFRESH_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!Files.isRegularFile(felidaeExecutable)) {
                return;
            }
            Set<String> fetched = query(felidaeExecutable);
            if (fetched != null && !fetched.isEmpty()) {
                libraries = fetched;
                lastRefreshedExecutable = felidaeExecutable;
            }
        } finally {
            REFRESH_IN_FLIGHT.set(false);
        }
    }

    private static Set<String> query(@NotNull Path felidaeExecutable) {
        ProcessBuilder builder = new ProcessBuilder(
                felidaeExecutable.toString(),
                "--list-libraries"
        );
        builder.redirectErrorStream(true);

        Process process = null;
        try {
            process = builder.start();
            Process running = process;
            // Drain stdout concurrently so both large output and a silent
            // stalled interpreter remain subject to the timeout below.
            var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder text = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) text.append(line);
                    return text.toString();
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return parseJsonStringArray(output.get(2, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception exception) {
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    /**
     * Minimal parser for the flat JSON string array Felidae prints, e.g.
     * {@code ["array","console","math"]}. Avoids a JSON library dependency
     * for a single, well-controlled shape.
     */
    private static @NotNull Set<String> parseJsonStringArray(@NotNull String json) {
        Set<String> result = new LinkedHashSet<>();
        boolean inString = false;
        boolean escaped = false;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (inString) {
                if (escaped) {
                    current.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    result.add(current.toString());
                    current.setLength(0);
                    inString = false;
                } else {
                    current.append(c);
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            }
        }

        return result;
    }
}
