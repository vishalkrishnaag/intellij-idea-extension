package local.felidae.intellij.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the one project-independent, pure piece of FelidaeExecutableResolver's candidate-path logic:
 * where Gradle's felidaeJar task writes the jar, relative to a project root. resolveCompiler/resolve
 * themselves need a Project and are left to a future BasePlatformTestCase fixture suite.
 */
class FelidaeExecutableResolverTest {

    @Test
    void theCompilerJarPathIsBuildJavaFelidaeJar() {
        assertEquals("build/java/felidae.jar", FelidaeExecutableResolver.compilerJarRelativePath());
    }
}
