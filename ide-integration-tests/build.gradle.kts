import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// IDE integration bench: launches a REAL IDE (IntelliJ IDEA, CLion, Rider) via the
// IntelliJ Platform Starter framework, opens a committed fixture project so the real
// language backend (IntelliJ Java, CLion Nova/Radler, Rider/ReSharper) starts, then
// drives the plugin's MCP HTTP server over localhost and asserts on tool results.
//
// This is a high-fidelity bench: unlike a headless BasePlatformTestCase, it actually
// exercises the out-of-process backends — the same boundary an agent (and the bug
// reporter) hits. The per-IDE results feed the IDE Compatibility Matrix (see the
// `report` job in .github/workflows/ide-integration-tests.yml).
//
// Run locally (defaults to CLion; pass -Pagentbridge.ide=IU|CL|RD to pick the product):
//   ./gradlew :plugin-core:buildPlugin
//   ./gradlew :ide-integration-tests:integrationTest \
//       -Ppath.to.build.plugin=$(ls -t plugin-core/build/distributions/*.zip | head -1)
//
// In CI the IDE is downloaded by the Starter framework and launched under xvfb.

plugins {
    id("java")
    kotlin("jvm") version "2.4.0"
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
        // ACTUAL product-under-test (IntelliJ/CLion/Rider) itself at runtime via IdeProductProvider,
        // independent of this dependency.
        intellijIdeaUltimate(providers.gradleProperty("intellijPlatformVersion").get())
        testFramework(TestFrameworkType.Starter)
    }
    testImplementation("org.junit.jupiter:junit-jupiter:${providers.gradleProperty("ideStarterJunitVersion").get()}")
    testImplementation("com.google.code.gson:gson:${providers.gradleProperty("gsonVersion").get()}")
    // Starter wires its dependency-injection container via Kodein.
    testImplementation("org.kodein.di:kodein-di-jvm:${providers.gradleProperty("kodeinVersion").get()}")
    // ideStarterJunitPlatformVersion (1.13.4) matches ide-starter-junit5 (brought in via
    // testFramework(Starter)). Using an older launcher causes engine/API version mismatch at
    // test-discovery time.
    testRuntimeOnly(
        "org.junit.platform:junit-platform-launcher:${
            providers.gradleProperty("ideStarterJunitPlatformVersion").get()
        }"
    )
    // ide-starter-squashed declares kotlinx-coroutines-core-jvm as a runtime dependency in
    // its POM (the squashed JAR does NOT bundle coroutines classes), but the IntelliJ Platform
    // Gradle Plugin substitutes the intellij.deps.kotlinx artifact with the platform-bundled
    // version, which ends up in intellijPlatformClasspath — NOT on the testIdeUi task classpath.
    // We must add it explicitly so CommonScope.<clinit> can load SupervisorKt at test runtime.
    testRuntimeOnly(
        "org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm:${
            providers.gradleProperty("ideStarterCoroutinesVersion").get()
        }"
    )
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

        // The plugin ZIP to install into the launched IDE.
        val pluginPath = providers.gradleProperty("path.to.build.plugin")
        if (pluginPath.isPresent) {
            // CI passes a prebuilt ZIP (assembled once by the `build-plugin` job, which has the
            // Node/npm toolchain for chat-ui). Use it directly and do NOT depend on
            // :plugin-core:buildPlugin here — rebuilding would re-run :plugin-core:buildChatUi
            // (npm run build), which the integration matrix jobs have no Node toolchain for.
            //
            // NOTE: "path.to.build.plugin" is an IPGP reserved system property — IPGP overrides it
            // with the current module's plugin ZIP. We use a distinct name to avoid the collision.
            systemProperty("agentbridge.plugin.zip", rootProject.rootDir.resolve(pluginPath.get()).absolutePath)
        } else {
            // Local run with no explicit ZIP: build the plugin from source so the bench has
            // something to install. Resolve the built ZIP path and set the system property so
            // IdeBench.run can find it without a CLI flag.
            dependsOn(":plugin-core:buildPlugin")
            val builtZip = rootProject.layout.projectDirectory
                .file("plugin-core/build/distributions/agentbridge-${rootProject.version}.zip")
            systemProperty("agentbridge.plugin.zip", builtZip.asFile.absolutePath)
        }

        // Absolute path to the committed fixtures/ directory (resolved from the repo root so
        // it does not depend on the test task's working directory).
        systemProperty(
            "agentbridge.fixtures.dir",
            rootProject.layout.projectDirectory.dir("fixtures").asFile.absolutePath
        )

        // Which product the bench launches (IU | CL | RD), one per CI matrix entry. The Starter
        // framework downloads the matching product at runtime via IdeProductProvider — see
        // IdeUnderTest.current(). Defaults to CL for the historical local CLion flow.
        systemProperty("agentbridge.ide", providers.gradleProperty("agentbridge.ide").getOrElse("CL"))

        // Per-IDE versions the Starter framework downloads. Each test only reads the one matching
        // its selected product, so passing all three is harmless and keeps the matrix declarative.
        systemProperty(
            "agentbridge.iu.version",
            providers.gradleProperty("intellijPlatformVersion").getOrElse("2026.1.3")
        )
        systemProperty(
            "agentbridge.cl.version",
            providers.gradleProperty("clionPlatformVersion").getOrElse("2026.1")
        )
        systemProperty(
            "agentbridge.rd.version",
            providers.gradleProperty("riderPlatformVersion").getOrElse("2026.1")
        )

        // Non-gating IDE-error log. The CIServer override (see IdeBench/LoggedIdeErrors) records
        // Starter's log-scraped errors here instead of failing the cell; the `report` job renders
        // them beneath the matrix. It lives under test-results/, so it rides the existing
        // test-xml-<IDE> artifact upload — no extra CI upload/download step needed.
        systemProperty(
            "agentbridge.logged-errors.file",
            layout.buildDirectory.file("test-results/integrationTest/logged-ide-errors.tsv")
                .get().asFile.absolutePath
        )
    }
}
