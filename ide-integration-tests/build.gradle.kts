import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// IDE integration bench: launches a REAL IDE (CLion, Rider, …) via the IntelliJ
// Platform Starter framework, opens a committed fixture project so the real
// language backend (CLion Nova/Radler, Rider/ReSharper) starts, then drives the
// plugin's MCP HTTP server over localhost and asserts on tool results.
//
// This is the high-fidelity counterpart to :ide-compat-tests (which runs headless
// BasePlatformTestCase and therefore CANNOT exercise the out-of-process backends).
//
// Run locally:
//   ./gradlew :plugin-core:buildPlugin
//   ./gradlew :ide-integration-tests:integrationTest \
//       -Ppath.to.build.plugin=$(ls -t plugin-core/build/distributions/*.zip | head -1)
//
// In CI the IDE is downloaded by the Starter framework and launched under xvfb.

plugins {
    id("java")
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Base platform required by the Gradle plugin. The Starter framework downloads the
        // ACTUAL product-under-test (CLion/Rider) itself at runtime via IdeProductProvider,
        // independent of this dependency. Matches the repo convention (see ide-compat-tests).
        intellijIdeaUltimate(providers.gradleProperty("intellijPlatformVersion").get())
        testFramework(TestFrameworkType.Starter)
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("com.google.code.gson:gson:${providers.gradleProperty("gsonVersion").get()}")
    // Starter wires its dependency-injection container via Kodein.
    testImplementation("org.kodein.di:kodein-di-jvm:7.20.2")
    // 5.13.4 matches what ide-starter-junit5 (brought in via testFramework(Starter)) requires.
    // Using an older launcher causes engine/API version mismatch at test-discovery time.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")
    // ide-starter-squashed declares kotlinx-coroutines-core-jvm as a runtime dependency in
    // its POM (the squashed JAR does NOT bundle coroutines classes), but the IntelliJ Platform
    // Gradle Plugin substitutes the intellij.deps.kotlinx artifact with the platform-bundled
    // version, which ends up in intellijPlatformClasspath — NOT on the testIdeUi task classpath.
    // We must add it explicitly so CommonScope.<clinit> can load SupervisorKt at test runtime.
    testRuntimeOnly("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm:1.10.2-intellij-1")
}

// Dedicated task that launches a real IDE and runs the integration tests against it.
val integrationTest by intellijPlatformTesting.testIdeUi.registering {
    task {
        testClassesDirs = sourceSets.test.get().output.classesDirs
        // testFramework(TestFrameworkType.Starter) adds Starter/driver-client JARs to the
        // intellijPlatformTestDependencies configuration, which the plugin wires into
        // testCompileClasspath (so compilation works) but NOT into testRuntimeClasspath.
        // For testIdeUi tasks, the plugin does not auto-wire a classpath like it does for
        // testIde tasks. We reference intellijPlatformTestDependencies directly here to make
        // the Starter/driver-client JARs available at test discovery time.
        classpath = project.configurations["intellijPlatformTestDependencies"] +
            sourceSets.test.get().runtimeClasspath
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "standardOut", "standardError")
            showStandardStreams = true
        }

        // The plugin ZIP to install into the launched IDE. Built by :plugin-core:buildPlugin
        // and passed explicitly by CI (-Ppath.to.build.plugin=...). Fail loudly if absent
        // rather than silently launching a stock IDE with no plugin.
        dependsOn(":plugin-core:buildPlugin")
        val pluginPath = providers.gradleProperty("path.to.build.plugin")
        if (pluginPath.isPresent) {
            // Resolve to an absolute path so the test JVM can locate the file regardless of its
            // working directory. File.resolve() returns the argument unchanged if it's already absolute.
            systemProperty("path.to.build.plugin", rootProject.rootDir.resolve(pluginPath.get()).absolutePath)
        }

        // Absolute path to the committed fixtures/ directory (resolved from the repo root so
        // it does not depend on the test task's working directory).
        systemProperty(
            "agentbridge.fixtures.dir",
            rootProject.layout.projectDirectory.dir("fixtures").asFile.absolutePath
        )
        // Product version of the IDE-under-test (overridable per CI matrix entry).
        systemProperty(
            "agentbridge.clion.version",
            providers.gradleProperty("clionPlatformVersion").getOrElse("2026.1")
        )
    }
}
