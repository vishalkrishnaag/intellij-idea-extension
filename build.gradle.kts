plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

group = "local.felidae"
version = "0.2.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

repositories {
    mavenCentral()

    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IntelliJ IDEA 2025.3 uses platform build 253.
        intellijIdea("2025.3")

        // Keep this only if the plugin uses Java-language APIs.
        bundledPlugin("com.intellij.java")
    }

    // Gson replaces the hand-rolled JSON readers this plugin used to carry for
    // builtin-docs.json and the ML model files. Those were written to avoid a
    // dependency, but the string-only one silently dropped 125 of 126 doc
    // entries the moment builtin-docs.json gained an array field - exactly the
    // failure a real parser cannot have. Bundled rather than compileOnly so
    // the plugin does not depend on which libraries a given IDE build exposes.
    implementation("com.google.code.gson:gson:2.11.0")

    // Plain JUnit for the plugin's pure-logic functions (no Project/IntelliJ platform dependency), matching
    // the version the Felidae compiler module pins (compiler/gradle/libs.versions.toml). Fixture tests that need
    // a Project (BasePlatformTestCase and friends) are a separate, larger effort, not wired here.
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // bundledPlugin("com.intellij.java") makes the IntelliJ Platform Gradle plugin register a JUnit 5
    // LauncherSessionListener (com.intellij.testFramework.junit5.impl.JUnit5TestEnvironmentInitializer) on every
    // test run in this project, platform fixtures or not; that listener's own init path needs JUnit 4's
    // org.junit.rules.TestRule on the runtime classpath, or the test JVM fails to start with a
    // NoClassDefFoundError before any test of ours even runs. None of our tests use JUnit 4 themselves.
    testRuntimeOnly("junit:junit:4.13.2")
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        name = "Felidae"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "253"
            untilBuild = "253.*"
        }
    }
}

// Keeps the bundled hover-doc and completion content in sync with docs/builtin-docs.json of the Felidae
// repository (this plugin is its submodule dependencies/intellij-idea-extension), the single source of truth
// shared with the VS Code extension (see ../vs-code-extension/scripts/generate-builtin-docs.js). FELIDAE_DOCS
// names another copy when the plugin is built outside the repository.
tasks.register("generateBuiltinDocs") {
    val source = file(System.getenv("FELIDAE_DOCS") ?: "$projectDir/../../docs/builtin-docs.json")
    val dest = file("$projectDir/src/main/resources/builtin-docs.json")
    inputs.file(source)
    outputs.file(dest)
    doLast {
        dest.parentFile.mkdirs()
        source.copyTo(dest, overwrite = true)
    }
}

tasks.named("processResources") {
    dependsOn("generateBuiltinDocs")
}
