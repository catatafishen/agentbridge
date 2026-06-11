import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

val testPlatformType: String = providers.gradleProperty("testPlatformType").getOrElse("IU")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        when (testPlatformType) {
            "CL" -> clion(providers.gradleProperty("clionPlatformVersion").get())
            else -> intellijIdeaUltimate(providers.gradleProperty("intellijPlatformVersion").get())
        }
        testFramework(TestFrameworkType.Platform)
        if (testPlatformType == "IU") {
            bundledPlugin("com.intellij.java")
        }
    }
    testImplementation(project(":plugin-core"))
    testImplementation("junit:junit:${providers.gradleProperty("junit4Version").get()}")
    testImplementation("com.google.code.gson:gson:${providers.gradleProperty("gsonVersion").get()}")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.13.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.1")
}

configurations.all {
    // IntelliJ 2026.1 bundles a junit-jupiter-engine newer than 5.13.1 that calls
    // ReflectionUtils.isNestedClassPresent(Class, Predicate, CycleErrorHandling) — an overload
    // added after junit-platform-commons:1.13.1. Maven-resolved commons loads before IntelliJ's
    // sandbox JARs on the classpath and lacks this method, causing NoSuchMethodError.
    // Exclude from Maven so IntelliJ's bundled (newer) commons is the sole source.
    // junit-platform-engine is NOT excluded: Gradle's JUnitPlatformTestDefinitionProcessor
    // needs ConfigurationParameters at bootstrap and IntelliJ's sandbox doesn't provide it.
    exclude(group = "org.junit.platform", module = "junit-platform-commons")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.catatafishen.agentbridge.ide-compat-tests"
        name = "AgentBridge IDE Compat Tests"
        version = "0.0.1"
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

tasks.test {
    systemProperty("testPlatformType", testPlatformType)
    useJUnitPlatform()
}
